package com.touvay.engine.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestProcessorTest {

    // -- test doubles -------------------------------------------------------------------

    /** Streams the payload back in [chunks] deltas with [delayMillis] between them. */
    private class ChunkingPipeline(
        id: String = "test.chunk",
        private val chunks: Int = 1,
        private val delayMillis: Long = 0,
    ) : CapabilityPipeline {
        override val descriptor = CapabilityDescriptor(id, schemaVersion = 1)

        override suspend fun execute(
            payload: ByteArray,
            emit: suspend (ByteArray) -> Unit,
        ): ByteArray {
            repeat(chunks) {
                if (delayMillis > 0) delay(delayMillis)
                coroutineContext.ensureActive()
                emit(payload)
            }
            return payload
        }
    }

    private class ThrowingPipeline : CapabilityPipeline {
        override val descriptor = CapabilityDescriptor("test.throws", schemaVersion = 1)
        override suspend fun execute(
            payload: ByteArray,
            emit: suspend (ByteArray) -> Unit,
        ): ByteArray = throw IllegalStateException("pipeline exploded")
    }

    private sealed class Event {
        data class Accepted(val requestId: String) : Event()
        data class Delta(val requestId: String, val sequence: Int) : Event()
        data class Completed(val requestId: String, val stats: ExecutionStats) : Event()
        data class Failed(val requestId: String, val failure: RequestFailure) : Event()
    }

    private class RecordingListener : RequestListener {
        val events = mutableListOf<Event>()
        override fun onAccepted(requestId: String) {
            events += Event.Accepted(requestId)
        }
        override suspend fun onDelta(requestId: String, sequence: Int, payload: ByteArray) {
            events += Event.Delta(requestId, sequence)
        }
        override fun onCompleted(requestId: String, payload: ByteArray, stats: ExecutionStats) {
            events += Event.Completed(requestId, stats)
        }
        override fun onFailed(requestId: String, failure: RequestFailure) {
            events += Event.Failed(requestId, failure)
        }
        fun terminalEvents() = events.filter { it is Event.Completed || it is Event.Failed }
        fun deltas() = events.filterIsInstance<Event.Delta>()
    }

    private fun job(
        requestId: String = "req-1",
        capabilityId: String = "test.chunk",
        schemaVersion: Int = 1,
        clientId: String = "client-a",
        coalesceKey: String? = null,
    ) = RequestJob(requestId, clientId, capabilityId, schemaVersion, byteArrayOf(1), coalesceKey)

    // -- happy paths ---------------------------------------------------------------------

    @Test
    fun unaryRequest_completes_withAcceptedFirst() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 1)),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(), listener)
        advanceUntilIdle()

        assertIs<Event.Accepted>(listener.events.first())
        val completed = assertIs<Event.Completed>(listener.terminalEvents().single())
        assertEquals(1, completed.stats.deltaCount)
        assertEquals(1, listener.deltas().size)
    }

    @Test
    fun streamingRequest_deltasArriveInSequenceOrder() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 5)),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(), listener)
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2, 3, 4), listener.deltas().map { it.sequence })
        val completed = assertIs<Event.Completed>(listener.terminalEvents().single())
        assertEquals(5, completed.stats.deltaCount)
    }

    @Test
    fun statsAreSane_whenNoDeltas() = runTest {
        val zeroDeltas = object : CapabilityPipeline {
            override val descriptor = CapabilityDescriptor("test.unary", 1)
            override suspend fun execute(
                payload: ByteArray,
                emit: suspend (ByteArray) -> Unit,
            ): ByteArray = payload
        }
        val processor = RequestProcessor(
            CapabilityRegistry.of(zeroDeltas),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(capabilityId = "test.unary"), listener)
        advanceUntilIdle()

        val completed = assertIs<Event.Completed>(listener.terminalEvents().single())
        assertEquals(0, completed.stats.deltaCount)
        assertEquals(completed.stats.totalMillis, completed.stats.ttftMillis)
    }

    // -- validation failures --------------------------------------------------------------

    @Test
    fun unknownCapability_failsSynchronously() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline()),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(capabilityId = "no.such"), listener)

        val failed = assertIs<Event.Failed>(listener.events.single())
        assertEquals(RequestFailure.UnknownCapability("no.such"), failed.failure)
    }

    @Test
    fun schemaVersionMismatch_fails() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline()),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(schemaVersion = 2), listener)

        val failed = assertIs<Event.Failed>(listener.events.single())
        assertEquals(
            RequestFailure.SchemaVersionMismatch("test.chunk", requested = 2, supported = 1),
            failed.failure,
        )
    }

    @Test
    fun duplicateRequestId_failsSecondSubmit_withoutDisturbingFirst() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 1, delayMillis = 100)),
            StandardTestDispatcher(testScheduler),
        )
        val first = RecordingListener()
        val second = RecordingListener()

        processor.submit(job(requestId = "dup"), first)
        processor.submit(job(requestId = "dup"), second)
        advanceUntilIdle()

        val failed = assertIs<Event.Failed>(second.events.single())
        assertIs<RequestFailure.Internal>(failed.failure)
        assertIs<Event.Completed>(first.terminalEvents().single())
    }

    @Test
    fun pipelineException_mapsToInternal_withoutPayloadContent() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ThrowingPipeline()),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(capabilityId = "test.throws"), listener)
        advanceUntilIdle()

        val failed = assertIs<Event.Failed>(listener.terminalEvents().single())
        val internal = assertIs<RequestFailure.Internal>(failed.failure)
        assertEquals("internal execution failure", internal.message)
    }

    // -- cancellation ----------------------------------------------------------------------

    @Test
    fun cancel_stopsStreaming_andFailsWithCancelled() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 100, delayMillis = 10)),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(), listener)
        advanceTimeBy(35)
        runCurrent()
        assertTrue(listener.deltas().isNotEmpty())

        assertTrue(processor.cancel("client-a", "req-1"))
        advanceUntilIdle()

        val failed = assertIs<Event.Failed>(listener.terminalEvents().single())
        assertEquals(RequestFailure.Cancelled(superseded = false), failed.failure)
        assertTrue(listener.deltas().size < 100)
    }

    @Test
    fun cancel_beforeCoroutineRuns_stillDeliversExactlyOneTerminal() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 1)),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(), listener)
        // No scheduler advance: the coroutine has not started when cancel arrives.
        assertTrue(processor.cancel("client-a", "req-1"))
        advanceUntilIdle()

        val failed = assertIs<Event.Failed>(listener.terminalEvents().single())
        assertEquals(RequestFailure.Cancelled(superseded = false), failed.failure)
    }

    @Test
    fun cancel_unknownRequest_returnsFalse() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline()),
            StandardTestDispatcher(testScheduler),
        )
        assertFalse(processor.cancel("client-a", "never-submitted"))
    }

    @Test
    fun cancel_afterCompletion_returnsFalse() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 1)),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(), listener)
        advanceUntilIdle()

        assertFalse(processor.cancel("client-a", "req-1"))
        assertEquals(1, listener.terminalEvents().size)
    }

    // -- coalescing -------------------------------------------------------------------------

    @Test
    fun sameCoalesceKey_supersedesOlderRequest() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 100, delayMillis = 10)),
            StandardTestDispatcher(testScheduler),
        )
        val first = RecordingListener()
        val second = RecordingListener()

        processor.submit(job(requestId = "old", coalesceKey = "field-1"), first)
        advanceTimeBy(15)
        runCurrent()
        processor.submit(job(requestId = "new", coalesceKey = "field-1"), second)
        advanceUntilIdle()

        val failed = assertIs<Event.Failed>(first.terminalEvents().single())
        assertEquals(RequestFailure.Cancelled(superseded = true), failed.failure)
        assertIs<Event.Completed>(second.terminalEvents().single())
    }

    @Test
    fun sameCoalesceKey_differentClients_doNotInterfere() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 2, delayMillis = 10)),
            StandardTestDispatcher(testScheduler),
        )
        val a = RecordingListener()
        val b = RecordingListener()

        processor.submit(job(requestId = "a", clientId = "client-a", coalesceKey = "k"), a)
        processor.submit(job(requestId = "b", clientId = "client-b", coalesceKey = "k"), b)
        advanceUntilIdle()

        assertIs<Event.Completed>(a.terminalEvents().single())
        assertIs<Event.Completed>(b.terminalEvents().single())
    }

    @Test
    fun coalesceKey_isReusableAfterCompletion() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 1)),
            StandardTestDispatcher(testScheduler),
        )
        val first = RecordingListener()
        val second = RecordingListener()

        processor.submit(job(requestId = "r1", coalesceKey = "k"), first)
        advanceUntilIdle()
        processor.submit(job(requestId = "r2", coalesceKey = "k"), second)
        advanceUntilIdle()

        assertIs<Event.Completed>(first.terminalEvents().single())
        assertIs<Event.Completed>(second.terminalEvents().single())
    }

    // -- shutdown ------------------------------------------------------------------------------

    @Test
    fun shutdown_cancelsInFlightRequests_withTerminalCallbacks() = runTest {
        val processor = RequestProcessor(
            CapabilityRegistry.of(ChunkingPipeline(chunks = 100, delayMillis = 10)),
            StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()

        processor.submit(job(), listener)
        advanceTimeBy(15)
        runCurrent()

        processor.shutdown()
        advanceUntilIdle()

        val failed = assertIs<Event.Failed>(listener.terminalEvents().single())
        assertIs<RequestFailure.Cancelled>(failed.failure)
    }
}

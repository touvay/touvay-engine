package com.touvay.engine.core

import com.touvay.runtime.api.CancelSignal
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.InferenceSession
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.PrefillResult
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.api.TokenSequence
import com.touvay.runtime.api.TokenSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionCancellationIntegrationTest {
    @Test
    fun executingCancellation_flipsRuntimeSignal_andCleansUpInOrder() = runBlocking<Unit> {
        val decodeStarted = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val sawCancellation = AtomicBoolean(false)
        val cleanup = mutableListOf<String>()
        val inference = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val session = BlockingSession(decodeStarted, sawCancellation, cleanup)
        val coordinator = ExecutionCoordinator(
            programs = ExecutionProgramRegistry.of(factory()),
            router = ExecutionRouter { _, _ -> ExecutionPlan(listOf(candidate())) },
            scheduler = PriorityExecutionScheduler(SchedulerLimits(4, 4, 1)),
            models = provider(inference, session, cleanup),
        )
        val observer = object : ExecutionObserver {
            var failure: ExecutionFailure? = null
            override fun onAccepted(context: ExecutionContext) = Unit
            override fun onDelta(context: ExecutionContext, sequence: Int, payload: ByteArray) = Unit
            override fun onCompleted(
                context: ExecutionContext,
                payload: ByteArray,
                stats: ExecutionStats,
            ) {
                terminal.countDown()
            }
            override fun onFailed(requestKey: RequestKey, failure: ExecutionFailure) {
                this.failure = failure
                terminal.countDown()
            }
        }

        coordinator.submit(request(), observer)
        assertTrue(decodeStarted.await(5, TimeUnit.SECONDS), "decode did not start")
        assertTrue(coordinator.cancel("principal", "cancel-running"))
        assertTrue(terminal.await(5, TimeUnit.SECONDS), "request did not terminate")
        coordinator.shutdownAndAwait()
        inference.close()

        assertTrue(sawCancellation.get(), "Runtime never observed CancelSignal")
        assertEquals(ExecutionFailureCode.CANCELLED, observer.failure?.code)
        assertEquals(listOf("session.close", "lease.close"), cleanup)
    }

    private fun factory() = object : ExecutionProgramFactory {
        override val descriptor = CapabilityDescriptor("test.cancel", 1)
        override suspend fun prepare(
            context: ExecutionContextCandidate,
            payload: ByteArray,
        ): PreparedExecution = object : PreparedExecution {
            override val demand = ExecutionDemand(0, 0, 0)
            override fun newAttempt(candidate: ExecutionCandidate): AttemptProgram =
                object : AttemptProgram {
                    override fun prompt(): String = "prompt"
                    override fun sessionConfig(model: ModelInstanceInfo) = SessionConfig(8)
                    override fun decodeParams(maxTokens: Int) = DecodeParams(1)
                    override suspend fun consume(tokens: List<GeneratedToken>) = emptyList<ByteArray>()
                    override suspend fun finish(): ByteArray = ByteArray(0)
                    override fun close() = Unit
                }
            override fun close() = Unit
        }
    }

    private fun provider(
        dispatcher: CoroutineDispatcher,
        session: InferenceSession,
        cleanup: MutableList<String>,
    ) = object : ExecutionModelProvider {
        override suspend fun acquire(
            context: ExecutionContext,
            candidate: ExecutionCandidate,
        ): ExecutionModelLease = object : ExecutionModelLease {
                override val inferenceDispatcher = dispatcher
                override val instance = object : ModelInstance {
                    override val info = ModelInstanceInfo(1, 8)
                    override fun tokenize(text: String) = TokenSequence(intArrayOf(1))
                    override fun createSession(config: SessionConfig) = session
                    override fun close() = Unit
                }
                override fun close() {
                    synchronized(cleanup) { cleanup += "lease.close" }
                }
            }
    }

    private class BlockingSession(
        private val started: CountDownLatch,
        private val sawCancellation: AtomicBoolean,
        private val cleanup: MutableList<String>,
    ) : InferenceSession {
        override fun prefill(tokens: TokenSequence, cancel: CancelSignal) =
            PrefillResult(tokens.ids.size)

        override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!cancel.isCancelled && System.nanoTime() < deadline) Thread.yield()
            sawCancellation.set(cancel.isCancelled)
        }

        override fun close() {
            synchronized(cleanup) { cleanup += "session.close" }
        }
    }

    private companion object {
        fun candidate() = ExecutionCandidate(
            id = "only",
            modelRevision = ModelRevisionRef("pack", "1.0.0", "a".repeat(64)),
            profile = ExecutionProfileSpec(1),
            contextLength = 8,
            maxOutputTokens = 1,
            decodeQuantumTokens = 1,
            retryableFailures = emptySet(),
        )

        fun request() = ExecutionRequest(
            requestId = "cancel-running",
            principal = "principal",
            capabilityId = "test.cancel",
            schemaVersion = 1,
            payload = ByteArray(0),
            priority = ExecutionPriority.INTERACTIVE,
            coalesceKey = null,
            timeoutMillis = 10_000,
            contractVersion = 2,
            negotiatedFeatures = emptySet(),
            policyGeneration = 0,
            streamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
        )
    }
}

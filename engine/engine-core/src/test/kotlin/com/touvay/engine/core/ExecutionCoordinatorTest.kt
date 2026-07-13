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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionCoordinatorTest {
    @Test
    fun successfulAttempt_streamsAndClosesSessionBeforeModelLease() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(events = events)
        val observer = RecordingObserver {
            assertEquals(0, fixture.scheduler.snapshot().liveRequests)
            events += "observer.complete"
        }

        fixture.coordinator.submit(request(), observer)
        advanceUntilIdle()

        assertEquals(1, observer.accepted)
        assertEquals(listOf(0, 1), observer.deltas.map { it.first })
        assertEquals("final", observer.completed?.decodeToString())
        assertEquals(
            listOf("session.close", "lease.close", "prepared.close", "observer.complete"),
            events,
        )
        assertEquals(1, observer.terminals)
    }

    @Test
    fun retryUsesFrozenFallback_withoutReroutingOrSecondAcceptance() = runTest {
        val fixture = fixture(
            candidates = listOf(candidate("primary"), candidate("fallback")),
            failingCandidates = setOf("primary"),
        )
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(), observer)
        advanceUntilIdle()

        assertEquals(listOf("primary", "fallback"), fixture.provider.acquired)
        assertEquals(1, fixture.router.routeCount)
        assertEquals(1, observer.accepted)
        assertEquals("final", observer.completed?.decodeToString())
        assertEquals(1, observer.terminals)
    }

    @Test
    fun retryIsForbiddenAfterFirstPublishedDelta() = runTest {
        val fixture = fixture(
            candidates = listOf(candidate("primary"), candidate("fallback")),
            finishFailures = setOf("primary"),
        )
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(), observer)
        advanceUntilIdle()

        assertEquals(listOf("primary"), fixture.provider.acquired)
        assertTrue(observer.deltas.isNotEmpty())
        assertEquals(ExecutionFailureCode.INVALID_OUTPUT, observer.failure?.code)
        assertEquals(1, observer.terminals)
    }

    @Test
    fun boundedCreditsSuspendSecondDeltaUntilSequencedGrant() = runTest {
        val fixture = fixture(
            streamLimits = StreamLimits(1, 16, 1, 16),
        )
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(streamLimits = fixture.streamLimits), observer)
        runCurrent()
        assertEquals(1, observer.deltas.size)
        assertEquals(null, observer.completed)

        assertEquals(
            CreditGrantResult.STALE_OR_OUT_OF_ORDER,
            fixture.coordinator.grantCredits("principal", "request", 2, 1, 1),
        )
        runCurrent()
        assertEquals(1, observer.deltas.size)

        assertEquals(
            CreditGrantResult.ACCEPTED,
            fixture.coordinator.grantCredits("principal", "request", 1, 1, 1),
        )
        advanceUntilIdle()
        assertEquals(2, observer.deltas.size)
        assertEquals("final", observer.completed?.decodeToString())
    }

    @Test
    fun cancelBeforeDispatchStillDeliversExactlyOneTerminal() = runTest {
        val fixture = fixture()
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(), observer)
        assertTrue(fixture.coordinator.cancel("principal", "request"))
        advanceUntilIdle()

        assertEquals(ExecutionFailureCode.CANCELLED, observer.failure?.code)
        assertEquals(1, observer.terminals)
        assertFalse(fixture.coordinator.cancel("principal", "request"))
    }

    @Test
    fun cancellationRecordedBeforeSuccessClaimWinsTerminalRace() = runTest {
        val fixture = fixture(cancelDuringFinish = true)
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(), observer)
        advanceUntilIdle()

        assertEquals(ExecutionFailureCode.CANCELLED, observer.failure?.code)
        assertEquals(null, observer.completed)
        assertEquals(1, observer.terminals)
    }

    @Test
    fun ingressCapacityRejectsBeforeStartingUnboundedPreparation() = runTest {
        val fixture = fixture(schedulerLimits = SchedulerLimits(1, 1, 1))
        val first = RecordingObserver()
        val rejected = RecordingObserver()

        assertTrue(fixture.coordinator.submit(request(requestId = "first"), first))
        assertFalse(fixture.coordinator.submit(request(requestId = "second"), rejected))

        assertEquals(ExecutionFailureCode.BUSY, rejected.failure?.code)
        assertEquals(0, first.accepted)
        fixture.coordinator.cancel("principal", "first")
        advanceUntilIdle()
    }

    @Test
    fun coalescedSuccessorUsesBoundedReplacementSlotAtIngressCapacity() = runTest {
        val limits = StreamLimits(1, 16, 1, 16)
        val fixture = fixture(
            schedulerLimits = SchedulerLimits(1, 1, 1),
            streamLimits = limits,
        )
        val first = RecordingObserver()
        val successor = RecordingObserver()

        assertTrue(
            fixture.coordinator.submit(
                request(requestId = "first", coalesceKey = "same", streamLimits = limits),
                first,
            ),
        )
        runCurrent()
        assertEquals(1, first.deltas.size)

        assertTrue(
            fixture.coordinator.submit(
                request(requestId = "second", coalesceKey = "same", streamLimits = limits),
                successor,
            ),
        )
        runCurrent()
        assertEquals(ExecutionFailureCode.SUPERSEDED, first.failure?.code)

        assertEquals(
            CreditGrantResult.ACCEPTED,
            fixture.coordinator.grantCredits("principal", "second", 1, 1, 1),
        )
        advanceUntilIdle()
        assertEquals("final", successor.completed?.decodeToString())
    }

    @Test
    fun deadlineCoversModelAcquisition_andMapsToTypedFailure() = runTest {
        val fixture = fixture(acquireDelayMillis = 100)
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(timeoutMillis = 10), observer)
        advanceUntilIdle()

        assertEquals(ExecutionFailureCode.DEADLINE_EXCEEDED, observer.failure?.code)
        assertEquals(1, observer.terminals)
    }

    @Test
    fun hostileExceptionTextNeverReachesTerminalFailure() = runTest {
        val fixture = fixture(routerFailure = IllegalStateException("private user content"))
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(), observer)
        advanceUntilIdle()

        assertEquals(ExecutionFailureCode.INTERNAL, observer.failure?.code)
        assertEquals(null, observer.failure?.incidentId)
    }

    @Test
    fun promptTokensCannotConsumeReservedOutputBudget() = runTest {
        val fixture = fixture(candidates = listOf(candidate("primary", contextLength = 16, maxOutputTokens = 16)))
        val observer = RecordingObserver()

        fixture.coordinator.submit(request(), observer)
        advanceUntilIdle()

        assertEquals(ExecutionFailureCode.INVALID_REQUEST, observer.failure?.code)
        assertEquals(1, observer.terminals)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        candidates: List<ExecutionCandidate> = listOf(candidate("primary")),
        failingCandidates: Set<String> = emptySet(),
        finishFailures: Set<String> = emptySet(),
        events: MutableList<String> = mutableListOf(),
        acquireDelayMillis: Long = 0,
        streamLimits: StreamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
        routerFailure: RuntimeException? = null,
        cancelDuringFinish: Boolean = false,
        schedulerLimits: SchedulerLimits = SchedulerLimits(8, 8, 1),
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = FakeModelProvider(
            dispatcher,
            failingCandidates,
            events,
            acquireDelayMillis,
        )
        lateinit var coordinator: ExecutionCoordinator
        val factory = FakeProgramFactory(finishFailures, events) {
            if (cancelDuringFinish) coordinator.cancel("principal", "request")
        }
        val router = FakeRouter(candidates, routerFailure)
        val scheduler = PriorityExecutionScheduler(
            schedulerLimits,
            clockNanos = { testScheduler.currentTime * 1_000_000 },
        )
        coordinator = ExecutionCoordinator(
            programs = ExecutionProgramRegistry.of(factory),
            router = router,
            scheduler = scheduler,
            models = provider,
            dispatcher = dispatcher,
            clockNanos = { testScheduler.currentTime * 1_000_000 },
        )
        return Fixture(coordinator, provider, router, scheduler, streamLimits)
    }

    private class Fixture(
        val coordinator: ExecutionCoordinator,
        val provider: FakeModelProvider,
        val router: FakeRouter,
        val scheduler: PriorityExecutionScheduler,
        val streamLimits: StreamLimits,
    )

    private class FakeProgramFactory(
        private val finishFailures: Set<String>,
        private val events: MutableList<String>,
        private val finishAction: () -> Unit,
    ) : ExecutionProgramFactory {
        override val descriptor = CapabilityDescriptor("test.execute", 1)

        override suspend fun prepare(
            context: ExecutionContextCandidate,
            payload: ByteArray,
        ): PreparedExecution = object : PreparedExecution {
            override val demand = ExecutionDemand(1, 1, 0)

            override fun newAttempt(candidate: ExecutionCandidate): AttemptProgram =
                FakeAttemptProgram(candidate.id in finishFailures, finishAction)

            override fun close() {
                events += "prepared.close"
            }
        }
    }

    private class FakeAttemptProgram(
        private val failFinish: Boolean,
        private val finishAction: () -> Unit,
    ) : AttemptProgram {
        override fun prompt(): String = "prompt"
        override fun sessionConfig(model: ModelInstanceInfo): SessionConfig = SessionConfig(16)
        override fun decodeParams(maxTokens: Int): DecodeParams = DecodeParams(maxTokens)
        override suspend fun consume(tokens: List<GeneratedToken>): List<ByteArray> =
            tokens.map { it.piece.encodeToByteArray() }

        override suspend fun finish(): ByteArray {
            finishAction()
            if (failFinish) throw ExecutionException(ExecutionFailureCode.INVALID_OUTPUT)
            return "final".encodeToByteArray()
        }

        override fun close() = Unit
    }

    private class FakeRouter(
        private val candidates: List<ExecutionCandidate>,
        private val failure: RuntimeException?,
    ) : ExecutionRouter {
        var routeCount = 0

        override suspend fun route(
            context: ExecutionContext,
            prepared: PreparedExecution,
        ): ExecutionPlan {
            routeCount += 1
            failure?.let { throw it }
            return ExecutionPlan(candidates)
        }
    }

    private class FakeModelProvider(
        private val dispatcher: CoroutineDispatcher,
        private val failingCandidates: Set<String>,
        private val events: MutableList<String>,
        private val acquireDelayMillis: Long,
    ) : ExecutionModelProvider {
        val acquired = mutableListOf<String>()

        override suspend fun acquire(
            context: ExecutionContext,
            candidate: ExecutionCandidate,
        ): ExecutionModelLease {
            acquired += candidate.id
            if (acquireDelayMillis > 0) delay(acquireDelayMillis)
            if (candidate.id in failingCandidates) {
                throw ExecutionException(ExecutionFailureCode.MODEL_UNAVAILABLE)
            }
            return object : ExecutionModelLease {
                override val instance: ModelInstance = FakeModelInstance(events)
                override val inferenceDispatcher: CoroutineDispatcher = dispatcher
                override fun close() {
                    events += "lease.close"
                }
            }
        }
    }

    private class FakeModelInstance(
        private val events: MutableList<String>,
    ) : ModelInstance {
        override val info = ModelInstanceInfo(1, 16)
        override fun tokenize(text: String): TokenSequence = TokenSequence(intArrayOf(1))
        override fun createSession(config: SessionConfig): InferenceSession = FakeSession(events)
        override fun close() = Unit
    }

    private class FakeSession(
        private val events: MutableList<String>,
    ) : InferenceSession {
        private val pieces = listOf("a", "b")
        private var cursor = 0

        override fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult =
            PrefillResult(tokens.ids.size)

        override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
            repeat(params.maxTokens) {
                if (cancel.isCancelled || cursor >= pieces.size) return
                sink.onToken(cursor, pieces[cursor])
                cursor += 1
            }
        }

        override fun close() {
            events += "session.close"
        }
    }

    private class RecordingObserver(
        private val onTerminal: () -> Unit = {},
    ) : ExecutionObserver {
        var accepted = 0
        val deltas = mutableListOf<Pair<Int, String>>()
        var completed: ByteArray? = null
        var failure: ExecutionFailure? = null
        var terminals = 0

        override fun onAccepted(context: ExecutionContext) {
            accepted += 1
        }

        override fun onDelta(context: ExecutionContext, sequence: Int, payload: ByteArray) {
            deltas += sequence to payload.decodeToString()
        }

        override fun onCompleted(
            context: ExecutionContext,
            payload: ByteArray,
            stats: ExecutionStats,
        ) {
            onTerminal()
            completed = payload
            terminals += 1
        }

        override fun onFailed(requestKey: RequestKey, failure: ExecutionFailure) {
            onTerminal()
            this.failure = failure
            terminals += 1
        }
    }

    private companion object {
        fun candidate(
            id: String,
            contextLength: Int = 16,
            maxOutputTokens: Int = 2,
        ) = ExecutionCandidate(
            id = id,
            modelRevision = ModelRevisionRef("pack", "1.0.0", "a".repeat(64)),
            profile = ExecutionProfileSpec(1),
            contextLength = contextLength,
            maxOutputTokens = maxOutputTokens,
            decodeQuantumTokens = minOf(2, maxOutputTokens),
            retryableFailures = setOf(
                ExecutionFailureCode.MODEL_UNAVAILABLE,
                ExecutionFailureCode.RUNTIME_FAILURE,
                ExecutionFailureCode.INVALID_OUTPUT,
            ),
        )

        fun request(
            requestId: String = "request",
            coalesceKey: String? = null,
            timeoutMillis: Long = 1_000,
            streamLimits: StreamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
        ) = ExecutionRequest(
            requestId = requestId,
            principal = "principal",
            capabilityId = "test.execute",
            schemaVersion = 1,
            payload = byteArrayOf(1),
            priority = ExecutionPriority.INTERACTIVE,
            coalesceKey = coalesceKey,
            timeoutMillis = timeoutMillis,
            contractVersion = 2,
            negotiatedFeatures = setOf("transport.stream-credits.v1"),
            policyGeneration = 0,
            streamLimits = streamLimits,
        )
    }
}

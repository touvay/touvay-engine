package com.touvay.capability.rewrite

import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.engine.core.CapabilityExecutionProgramFactory
import com.touvay.engine.core.ExecutionContext
import com.touvay.engine.core.ExecutionCoordinator
import com.touvay.engine.core.ExecutionFailure
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.ExecutionModelLease
import com.touvay.engine.core.ExecutionModelProvider
import com.touvay.engine.core.ExecutionObserver
import com.touvay.engine.core.ExecutionPlan
import com.touvay.engine.core.ExecutionPriority
import com.touvay.engine.core.ExecutionProgramRegistry
import com.touvay.engine.core.ExecutionRequest
import com.touvay.engine.core.ExecutionRouter
import com.touvay.engine.core.ExecutionStats
import com.touvay.engine.core.PriorityExecutionScheduler
import com.touvay.engine.core.PromptAssetSource
import com.touvay.engine.core.RequestKey
import com.touvay.engine.core.SchedulerLimits
import com.touvay.engine.core.StreamLimits
import com.touvay.runtime.api.CancelSignal
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.InferenceSession
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.PrefillResult
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.api.TokenSequence
import com.touvay.runtime.api.TokenSink
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewriteExecutionIntegrationTest {
    @Test
    fun coordinatorExecutesRewriteWithStructuredStreamingAndFinal(): Unit = runBlocking<Unit> {
        val observer = RecordingObserver()
        val provider = FakeProvider { StreamingSession(listOf("Please review ", "this.")) }
        val coordinator = coordinator(provider)
        try {
            assertTrue(coordinator.submit(request("rewrite-success"), observer))
            assertTrue(observer.terminal.await(5, TimeUnit.SECONDS), "rewrite did not terminate")
            assertEquals(null, observer.failure)
            assertEquals(1, observer.accepted)
            assertEquals(RewriteTestFixtures.RESULT, RewriteResponse.parseFrom(observer.final).text)
            assertEquals(
                RewriteTestFixtures.RESULT,
                observer.deltas.joinToString("") { RewriteDelta.parseFrom(it).text },
            )
        } finally {
            coordinator.shutdownAndAwait()
            provider.close()
        }
    }

    @Test
    fun coordinatorCancellationReachesRuntimeAndClosesAttempt(): Unit = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val sawCancellation = AtomicBoolean()
        val observer = RecordingObserver()
        val provider = FakeProvider { BlockingSession(started, sawCancellation) }
        val coordinator = coordinator(provider)
        try {
            assertTrue(coordinator.submit(request("rewrite-cancel"), observer))
            assertTrue(started.await(5, TimeUnit.SECONDS), "decode did not start")
            assertTrue(coordinator.cancel("rewrite-client", "rewrite-cancel"))
            assertTrue(observer.terminal.await(5, TimeUnit.SECONDS), "cancel did not terminate")
            assertEquals(ExecutionFailureCode.CANCELLED, observer.failure?.code)
            assertTrue(sawCancellation.get(), "runtime did not observe CancelSignal")
            assertEquals(1, provider.sessionCloseCount)
            assertEquals(1, provider.leaseCloseCount)
        } finally {
            coordinator.shutdownAndAwait()
            provider.close()
        }
    }

    private fun coordinator(provider: FakeProvider): ExecutionCoordinator = ExecutionCoordinator(
        programs = ExecutionProgramRegistry.of(
            CapabilityExecutionProgramFactory(RewriteCapabilityDefinition()),
        ),
        router = ExecutionRouter { _, _ -> ExecutionPlan(listOf(RewriteTestFixtures.candidate)) },
        scheduler = PriorityExecutionScheduler(SchedulerLimits.DEFAULT),
        models = provider,
    )

    private fun request(id: String): ExecutionRequest = ExecutionRequest(
        requestId = id,
        principal = "rewrite-client",
        capabilityId = RewriteCapabilityDefinition.KEY.id,
        schemaVersion = RewriteCapabilityDefinition.KEY.schemaVersion,
        payload = RewriteTestFixtures.request().toByteArray(),
        priority = ExecutionPriority.INTERACTIVE,
        coalesceKey = "rewrite-active-field",
        timeoutMillis = 10_000,
        contractVersion = 2,
        negotiatedFeatures = setOf("stream-credits-v1"),
        policyGeneration = 1,
        streamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
    )

    private class RecordingObserver : ExecutionObserver {
        val terminal = CountDownLatch(1)
        val deltas: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        @Volatile var accepted: Int = 0
        @Volatile var final: ByteArray = ByteArray(0)
        @Volatile var failure: ExecutionFailure? = null

        override fun onAccepted(context: ExecutionContext) {
            accepted += 1
        }

        override fun onDelta(context: ExecutionContext, sequence: Int, payload: ByteArray) {
            deltas += payload.copyOf()
        }

        override fun onCompleted(
            context: ExecutionContext,
            payload: ByteArray,
            stats: ExecutionStats,
        ) {
            final = payload.copyOf()
            terminal.countDown()
        }

        override fun onFailed(requestKey: RequestKey, failure: ExecutionFailure) {
            this.failure = failure
            terminal.countDown()
        }
    }

    private class FakeProvider(
        private val sessionFactory: () -> InferenceSession,
    ) : ExecutionModelProvider, AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher = executor.asCoroutineDispatcher()
        @Volatile var sessionCloseCount: Int = 0
        @Volatile var leaseCloseCount: Int = 0

        override suspend fun acquire(
            context: ExecutionContext,
            candidate: com.touvay.engine.core.ExecutionCandidate,
        ): ExecutionModelLease = object : ExecutionModelLease {
            override val inferenceDispatcher = dispatcher
            override val promptAssets: PromptAssetSource = PromptAssetSource { ref ->
                require(ref == RewriteTestFixtures.assetRef)
                RewriteTestFixtures.assetBytes
            }
            override val instance: ModelInstance = object : ModelInstance {
                override val info = ModelInstanceInfo(estimatedRamBytes = 1, maxContextLength = 4_096)

                override fun tokenize(text: String): TokenSequence = TokenSequence(
                    text.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }.toIntArray(),
                )

                override fun createSession(config: SessionConfig): InferenceSession {
                    val delegate = sessionFactory()
                    return object : InferenceSession by delegate {
                        override fun close() {
                            delegate.close()
                            sessionCloseCount += 1
                        }
                    }
                }

                override fun close() = Unit
            }

            override fun close() {
                leaseCloseCount += 1
            }
        }

        override fun close() {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private class StreamingSession(private val pieces: List<String>) : InferenceSession {
        private var cursor = 0

        override fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult =
            PrefillResult(tokens.ids.size)

        override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
            var count = 0
            while (!cancel.isCancelled && cursor < pieces.size && count < params.maxTokens) {
                sink.onToken(cursor, pieces[cursor])
                cursor += 1
                count += 1
            }
        }

        override fun close() = Unit
    }

    private class BlockingSession(
        private val started: CountDownLatch,
        private val sawCancellation: AtomicBoolean,
    ) : InferenceSession {
        override fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult =
            PrefillResult(tokens.ids.size)

        override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!cancel.isCancelled && System.nanoTime() < deadline) Thread.yield()
            sawCancellation.set(cancel.isCancelled)
        }

        override fun close() = Unit
    }
}

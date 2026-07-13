package com.touvay.sdk

import android.os.DeadObjectException
import com.touvay.contract.CapabilityInfo
import com.touvay.contract.CapabilityStatusCodes
import com.touvay.contract.ClientHello
import com.touvay.contract.EngineError
import com.touvay.contract.EngineErrorCodes
import com.touvay.contract.EngineHello
import com.touvay.contract.ITouvayEngine
import com.touvay.contract.ITouvayResponseCallback
import com.touvay.contract.RequestEnvelope
import com.touvay.contract.RequestStats
import com.touvay.contract.ResponseDelta
import com.touvay.contract.ResponseFinal
import com.touvay.contract.StreamCreditWindow
import com.touvay.contract.TouvayContract
import com.touvay.contract.proto.EchoDelta
import com.touvay.contract.proto.EchoRequest
import com.touvay.contract.proto.EchoResponse
import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteDisposition
import com.touvay.contract.rewrite.v1.RewriteRequest as ContractRewriteRequest
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.sdk.internal.TouvayClientImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SDK behavior against an in-process engine stub: request plumbing, streaming,
 * cancellation propagation, error mapping, disconnection. The stub runs callbacks on its
 * own threads, mimicking binder-pool delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouvayClientImplTest {

    /** Implements dev.echo semantics; configurable for failure scenarios. */
    private class FakeEngine : ITouvayEngine.Stub() {
        val cancelledRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val cancelReceived = CountDownLatch(1)
        val creditGrantSequences = CopyOnWriteArrayList<Long>()
        var legacySubmits = 0
        var creditSubmits = 0
        var failWith: ((String) -> EngineError)? = null
        var throwOnSubmit: Boolean = false
        var capabilities: List<CapabilityInfo> = listOf(
            CapabilityInfo(TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO, 1, CapabilityStatusCodes.READY),
        )

        override fun negotiate(hello: ClientHello?): EngineHello = EngineHello(1, 1, "fake")

        override fun listCapabilities(): List<CapabilityInfo> = capabilities

        override fun listTransportFeatures(): List<String> =
            listOf(TouvayContract.FEATURE_STREAM_CREDITS_V1)

        override fun submit(request: RequestEnvelope?, callback: ITouvayResponseCallback?) {
            legacySubmits += 1
            if (throwOnSubmit) throw DeadObjectException()
            request!!
            callback!!
            val fail = failWith
            if (fail != null) {
                callback.onFailed(fail(request.requestId))
                return
            }
            if (request.capabilityId == TouvayContract.CAPABILITY_TEXT_REWRITE) {
                val rewrite = ContractRewriteRequest.parseFrom(request.payload)
                thread(name = "fake-rewrite-${request.requestId}") {
                    callback.onAccepted(request.requestId)
                    val rewritten = "Rewritten: ${rewrite.text}"
                    val pieces = listOf("Rewritten: ", rewrite.text)
                    pieces.forEachIndexed { sequence, piece ->
                        callback.onDelta(
                            ResponseDelta(
                                request.requestId,
                                sequence,
                                RewriteDelta.newBuilder()
                                    .setText(piece)
                                    .setProvisional(true)
                                    .build()
                                    .toByteArray(),
                            ),
                        )
                    }
                    callback.onCompleted(
                        ResponseFinal(
                            request.requestId,
                            RewriteResponse.newBuilder()
                                .setText(rewritten)
                                .setDisposition(
                                    RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN,
                                )
                                .build()
                                .toByteArray(),
                            RequestStats(12, 34, pieces.size),
                        ),
                    )
                }
                return
            }
            val echo = EchoRequest.parseFrom(request.payload)
            thread(name = "fake-engine-${request.requestId}") {
                callback.onAccepted(request.requestId)
                val chunks = echo.chunkCount.coerceAtLeast(1)
                var sequence = 0
                repeat(chunks) {
                    if (request.requestId in cancelledRequests) {
                        callback.onFailed(
                            EngineError(request.requestId, EngineErrorCodes.CANCELLED, false, "cancelled"),
                        )
                        return@thread
                    }
                    if (echo.interChunkDelayMillis > 0) {
                        Thread.sleep(echo.interChunkDelayMillis.toLong())
                    }
                    callback.onDelta(
                        ResponseDelta(
                            request.requestId,
                            sequence,
                            EchoDelta.newBuilder().setText(echo.text).build().toByteArray(),
                        ),
                    )
                    sequence++
                }
                callback.onCompleted(
                    ResponseFinal(
                        request.requestId,
                        EchoResponse.newBuilder().setText(echo.text).build().toByteArray(),
                        RequestStats(0, 0, sequence),
                    ),
                )
            }
        }

        override fun submitWithCredits(
            request: RequestEnvelope?,
            window: StreamCreditWindow?,
            callback: ITouvayResponseCallback?,
        ) {
            creditSubmits += 1
            submit(request, callback)
        }

        override fun grantCredits(
            requestId: String?,
            grantSequence: Long,
            deltaCredits: Int,
            byteCredits: Long,
        ) {
            creditGrantSequences += grantSequence
        }

        override fun cancel(requestId: String?) {
            if (requestId != null) {
                cancelledRequests += requestId
                cancelReceived.countDown()
            }
        }
    }

    private fun client(engine: ITouvayEngine = FakeEngine()) = TouvayClientImpl(
        engine = engine,
        onClose = {},
    )

    // -- capabilities ---------------------------------------------------------------------

    @Test
    fun capabilities_mapStatusCodes_includingUnknownForForwardCompat() = runBlocking<Unit> {
        val fake = FakeEngine()
        fake.capabilities = listOf(
            CapabilityInfo("dev.echo", 1, CapabilityStatusCodes.READY),
            CapabilityInfo("text.rewrite", 1, CapabilityStatusCodes.DOWNLOAD_REQUIRED),
            CapabilityInfo("future.cap", 1, statusCode = 99),
        )

        val caps = client(fake).capabilities()

        assertEquals(CapabilityStatus.Ready, caps[CapabilityId("dev.echo")])
        assertEquals(CapabilityStatus.DownloadRequired(null), caps[CapabilityId("text.rewrite")])
        assertEquals(CapabilityStatus.Unknown(99), caps[CapabilityId("future.cap")])
    }

    // -- unary + streaming -----------------------------------------------------------------

    @Test
    fun echo_returnsEngineText() = runBlocking<Unit> {
        assertEquals("hello touvay", client().diagnostics().echo("hello touvay"))
    }

    @Test
    fun echoStream_deliversAllChunksInOrder() = runBlocking<Unit> {
        val chunks = client().diagnostics().echoStream("chunky", chunks = 3).toList()
        assertEquals(listOf("chunky", "chunky", "chunky"), chunks)
    }

    @Test
    fun boundedStream_replenishesCreditsInConsumptionOrder() = runBlocking<Unit> {
        val fake = FakeEngine()

        client(fake).diagnostics().echoStream("credit", chunks = 3).toList()

        assertEquals(listOf(1L, 2L, 3L), fake.creditGrantSequences)
    }

    @Test
    fun rewrite_streamsTypedDeltasAndAuthoritativeStructuredResult() = runBlocking<Unit> {
        val events = client().rewrite().stream(
            RewriteRequest("make this clearer", RewriteTone.FORMAL, RewriteLength.SHORTER),
        ).toList()

        assertEquals("Rewritten: ", (events[0] as RewriteEvent.Delta).text)
        assertEquals("make this clearer", (events[1] as RewriteEvent.Delta).text)
        val result = (events[2] as RewriteEvent.Completed).result
        assertEquals("Rewritten: make this clearer", result.text)
        assertEquals(com.touvay.sdk.RewriteDisposition.REWRITTEN, result.disposition)
        assertEquals(12, result.timing.timeToFirstTokenMillis)
        assertEquals(34, result.timing.totalMillis)
        assertEquals(2, result.timing.deltaCount)
    }

    @Test
    fun rewrite_executeReturnsFinalAndValidatesBeforeBinder() = runBlocking<Unit> {
        val fake = FakeEngine()
        val result = client(fake).rewrite().execute(RewriteRequest("hello"))
        assertEquals("Rewritten: hello", result.text)

        val submits = fake.creditSubmits
        assertFailsWith<TouvayException.InvalidRequest> {
            client(fake).rewrite().execute(RewriteRequest(" "))
        }
        assertEquals(submits, fake.creditSubmits)
    }

    @Test
    fun v1Engine_usesLegacySubmitWithoutCallingCreditMethods() = runBlocking<Unit> {
        val fake = FakeEngine()
        val client = TouvayClientImpl(fake, onClose = {}, supportsStreamingCredits = false)

        assertEquals("legacy", client.diagnostics().echo("legacy"))

        assertEquals(1, fake.legacySubmits)
        assertEquals(0, fake.creditSubmits)
        assertTrue(fake.creditGrantSequences.isEmpty())
    }

    // -- error mapping ------------------------------------------------------------------------

    @Test
    fun unknownCapabilityError_mapsToCapabilityUnavailable() = runBlocking<Unit> {
        val fake = FakeEngine()
        fake.failWith = { requestId ->
            EngineError(requestId, EngineErrorCodes.UNKNOWN_CAPABILITY, false, "unknown capability")
        }

        val e = assertFailsWith<TouvayException.CapabilityUnavailable> {
            client(fake).diagnostics().echo("x")
        }
        assertEquals(TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO, e.capabilityId)
    }

    @Test
    fun supersededError_mapsToRequestSuperseded() = runBlocking<Unit> {
        val fake = FakeEngine()
        fake.failWith = { requestId ->
            EngineError(requestId, EngineErrorCodes.SUPERSEDED, false, "superseded")
        }

        assertFailsWith<TouvayException.RequestSuperseded> {
            client(fake).diagnostics().echo("x")
        }
        return@runBlocking
    }

    @Test
    fun invalidRequestError_mapsToTypedSdkFailure() = runBlocking<Unit> {
        val fake = FakeEngine()
        fake.failWith = { requestId ->
            EngineError(requestId, EngineErrorCodes.INVALID_REQUEST, false, "invalid request")
        }

        assertFailsWith<TouvayException.InvalidRequest> {
            client(fake).rewrite().execute(RewriteRequest("valid locally"))
        }
        return@runBlocking
    }

    @Test
    fun otherEngineErrors_mapToEngineFailure_preservingCodeAndRetryability() = runBlocking<Unit> {
        val fake = FakeEngine()
        fake.failWith = { requestId ->
            EngineError(requestId, EngineErrorCodes.BUSY, true, "queue full")
        }

        val e = assertFailsWith<TouvayException.EngineFailure> {
            client(fake).diagnostics().echo("x")
        }
        assertEquals(EngineErrorCodes.BUSY, e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun deadEngineOnSubmit_mapsToEngineDisconnected() = runBlocking<Unit> {
        val fake = FakeEngine()
        fake.throwOnSubmit = true

        assertFailsWith<TouvayException.EngineDisconnected> {
            client(fake).diagnostics().echo("x")
        }
        return@runBlocking
    }

    // -- cancellation ------------------------------------------------------------------------

    @Test
    fun abandoningTheStream_sendsCancelToTheEngine() = runBlocking<Unit> {
        val fake = FakeEngine()

        val firstChunk = client(fake).diagnostics()
            .echoStream("slow", chunks = 50, interChunkDelayMillis = 20)
            .take(1)
            .toList()

        assertEquals(listOf("slow"), firstChunk)
        assertTrue(
            fake.cancelReceived.await(5, TimeUnit.SECONDS),
            "engine never received cancel() after the collector stopped",
        )
    }

    // -- lifecycle ----------------------------------------------------------------------------

    @Test
    fun close_isIdempotent_andFailsSubsequentCalls() = runBlocking<Unit> {
        val impl = client()
        impl.close()
        impl.close()

        assertFailsWith<TouvayException.ClientClosed> { impl.capabilities() }
        return@runBlocking
    }

    @Test
    fun binderDeath_failsInFlightRequests_withEngineDisconnected() = runBlocking<Unit> {
        val impl = client()
        val firstChunk = CountDownLatch(1)
        // Collect on a real dispatcher: runBlocking's own thread must stay free to
        // simulate the death while the stream is verifiably in flight.
        val collector = async(Dispatchers.Default) {
            runCatching {
                impl.diagnostics()
                    .echoStream("doomed", chunks = 200, interChunkDelayMillis = 20)
                    .collect { firstChunk.countDown() }
            }.exceptionOrNull()
        }
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "stream never started")
        impl.onBinderDied()

        assertIs<TouvayException.EngineDisconnected>(collector.await())
    }

    // -- negotiation --------------------------------------------------------------------------

    @Test
    fun compatibilityCheck_acceptsOverlappingWindows() {
        assertNull(Touvay.checkCompatibility(EngineHello(1, 1, "e")))
    }

    @Test
    fun compatibilityCheck_rejectsEngineThatRequiresNewerContract() {
        val e = Touvay.checkCompatibility(EngineHello(contractVersion = 9, minContractVersion = 9, "e"))
        assertIs<TouvayException.EngineIncompatible>(e)
    }

    @Test
    fun compatibilityCheck_rejectsEngineOlderThanSdkSupports() {
        // Only meaningful once MIN_SUPPORTED_CONTRACT_VERSION > 1; guards the logic today.
        val e = Touvay.checkCompatibility(EngineHello(contractVersion = 0, minContractVersion = 0, "e"))
        assertIs<TouvayException.EngineIncompatible>(e)
    }
}

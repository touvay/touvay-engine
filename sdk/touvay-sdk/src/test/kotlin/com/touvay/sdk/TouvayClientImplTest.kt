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
import com.touvay.contract.TouvayContract
import com.touvay.contract.proto.EchoDelta
import com.touvay.contract.proto.EchoRequest
import com.touvay.contract.proto.EchoResponse
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
        var failWith: ((String) -> EngineError)? = null
        var throwOnSubmit: Boolean = false
        var capabilities: List<CapabilityInfo> = listOf(
            CapabilityInfo(TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO, 1, CapabilityStatusCodes.READY),
        )

        override fun negotiate(hello: ClientHello?): EngineHello = EngineHello(1, 1, "fake")

        override fun listCapabilities(): List<CapabilityInfo> = capabilities

        override fun submit(request: RequestEnvelope?, callback: ITouvayResponseCallback?) {
            if (throwOnSubmit) throw DeadObjectException()
            request!!
            callback!!
            val fail = failWith
            if (fail != null) {
                callback.onFailed(fail(request.requestId))
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

        override fun cancel(requestId: String?) {
            if (requestId != null) {
                cancelledRequests += requestId
                cancelReceived.countDown()
            }
        }
    }

    private fun client(engine: ITouvayEngine = FakeEngine()) = TouvayClientImpl(engine) {}

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

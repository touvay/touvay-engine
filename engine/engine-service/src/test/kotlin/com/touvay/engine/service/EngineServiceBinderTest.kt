package com.touvay.engine.service

import android.content.Intent
import android.os.Process
import com.touvay.contract.CapabilityStatusCodes
import com.touvay.contract.ClientHello
import com.touvay.contract.EngineError
import com.touvay.contract.EngineErrorCodes
import com.touvay.contract.ITouvayEngine
import com.touvay.contract.ITouvayResponseCallback
import com.touvay.contract.RequestEnvelope
import com.touvay.contract.RequestPriorities
import com.touvay.contract.ResponseDelta
import com.touvay.contract.ResponseFinal
import com.touvay.contract.TouvayContract
import com.touvay.contract.proto.EchoDelta
import com.touvay.contract.proto.EchoRequest
import com.touvay.contract.proto.EchoResponse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exercises the engine through its real binder surface. Robolectric runs both sides in
 * one process, so this validates contract semantics; true cross-process transport is
 * covered by the demo app's instrumented test on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineServiceBinderTest {

    private lateinit var controller: ServiceController<TouvayEngineService>
    private lateinit var engine: ITouvayEngine

    @Before
    fun setUp() {
        ShadowBinder.setCallingUid(Process.myUid())
        controller = Robolectric.buildService(TouvayEngineService::class.java).create()
        val binder = controller.get().onBind(Intent(TouvayContract.ACTION_BIND_ENGINE))
        engine = ITouvayEngine.Stub.asInterface(assertNotNull(binder))
    }

    @After
    fun tearDown() {
        controller.destroy()
        ShadowBinder.reset()
    }

    // -- handshake & discovery ---------------------------------------------------------

    @Test
    fun negotiate_reportsContractVersionWindow() {
        val hello = engine.negotiate(ClientHello(1, 1, "test"))

        assertEquals(TouvayContract.CONTRACT_VERSION, hello.contractVersion)
        assertEquals(TouvayContract.MIN_SUPPORTED_CONTRACT_VERSION, hello.minContractVersion)
    }

    @Test
    fun listCapabilities_includesEchoAsReady() {
        val echo = engine.listCapabilities()
            .single { it.id == TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO }

        assertEquals(1, echo.schemaVersion)
        assertEquals(CapabilityStatusCodes.READY, echo.statusCode)
    }

    // -- request execution ---------------------------------------------------------------

    @Test
    fun echo_unary_returnsTextWithStats() {
        val callback = RecordingCallback()
        engine.submit(echoEnvelope("req-1", "hello engine", chunks = 1), callback)

        callback.awaitTerminal()

        val final = assertNotNull(callback.completed, "expected completion, got ${callback.error}")
        assertEquals("hello engine", EchoResponse.parseFrom(final.payload).text)
        assertEquals(1, final.stats.deltaCount)
        assertTrue(final.stats.totalMillis >= 0)
        assertTrue(callback.accepted.contains("req-1"))
    }

    @Test
    fun echo_streaming_deliversOrderedChunksThatReassemble() {
        val callback = RecordingCallback()
        engine.submit(echoEnvelope("req-2", "abcdefghij", chunks = 3), callback)

        callback.awaitTerminal()

        assertNotNull(callback.completed)
        assertEquals(callback.deltas.map { it.sequence }, callback.deltas.indices.toList())
        val reassembled = callback.deltas.joinToString("") { EchoDelta.parseFrom(it.payload).text }
        assertEquals("abcdefghij", reassembled)
    }

    @Test
    fun cancel_midStream_terminatesWithCancelledError() {
        val callback = RecordingCallback()
        engine.submit(
            echoEnvelope("req-3", "x".repeat(200), chunks = 100, delayMillis = 20),
            callback,
        )

        assertTrue(callback.firstDelta.await(5, TimeUnit.SECONDS), "no delta before cancel")
        engine.cancel("req-3")
        callback.awaitTerminal()

        val error = assertNotNull(callback.error, "expected cancellation, got completion")
        assertEquals(EngineErrorCodes.CANCELLED, error.code)
        assertTrue(callback.deltas.size < 100)
    }

    @Test
    fun unknownCapability_failsThroughCallback() {
        val callback = RecordingCallback()
        engine.submit(
            RequestEnvelope("req-4", "no.such", 1, ByteArray(0), RequestPriorities.INTERACTIVE, null),
            callback,
        )

        callback.awaitTerminal()

        assertEquals(EngineErrorCodes.UNKNOWN_CAPABILITY, assertNotNull(callback.error).code)
    }

    @Test
    fun malformedPayload_failsAsInternal_withoutEchoingContent() {
        val callback = RecordingCallback()
        engine.submit(
            RequestEnvelope(
                "req-5",
                TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO,
                1,
                byteArrayOf(-1, -1, -1, -1, -1),
                RequestPriorities.INTERACTIVE,
                null,
            ),
            callback,
        )

        callback.awaitTerminal()

        val error = assertNotNull(callback.error)
        assertEquals(EngineErrorCodes.INTERNAL, error.code)
        assertTrue("malformed dev.echo payload" in error.message)
    }

    @Test
    fun serviceDestroy_cancelsInFlightRequests() {
        val callback = RecordingCallback()
        engine.submit(
            echoEnvelope("req-6", "y".repeat(200), chunks = 100, delayMillis = 20),
            callback,
        )
        assertTrue(callback.firstDelta.await(5, TimeUnit.SECONDS))

        controller.destroy()
        callback.awaitTerminal()

        assertEquals(EngineErrorCodes.CANCELLED, assertNotNull(callback.error).code)
    }

    // -- access control ------------------------------------------------------------------

    @Test
    fun foreignUid_isRejected() {
        ShadowBinder.setCallingUid(Process.myUid() + 12345)

        assertFailsWith<SecurityException> {
            engine.negotiate(ClientHello(1, 1, "attacker"))
        }
    }

    // -- helpers -----------------------------------------------------------------------------

    private fun echoEnvelope(
        requestId: String,
        text: String,
        chunks: Int,
        delayMillis: Int = 0,
    ): RequestEnvelope {
        val payload = EchoRequest.newBuilder()
            .setText(text)
            .setChunkCount(chunks)
            .setInterChunkDelayMillis(delayMillis)
            .build()
            .toByteArray()
        return RequestEnvelope(
            requestId,
            TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO,
            1,
            payload,
            RequestPriorities.INTERACTIVE,
            null,
        )
    }

    private class RecordingCallback : ITouvayResponseCallback.Stub() {
        val accepted = CopyOnWriteArrayList<String>()
        val deltas = CopyOnWriteArrayList<ResponseDelta>()
        @Volatile var completed: ResponseFinal? = null
        @Volatile var error: EngineError? = null
        val firstDelta = CountDownLatch(1)
        private val terminal = CountDownLatch(1)

        override fun onAccepted(requestId: String) {
            accepted += requestId
        }

        override fun onDelta(delta: ResponseDelta) {
            deltas += delta
            firstDelta.countDown()
        }

        override fun onCompleted(result: ResponseFinal) {
            completed = result
            terminal.countDown()
        }

        override fun onFailed(e: EngineError) {
            error = e
            terminal.countDown()
        }

        fun awaitTerminal() {
            if (!terminal.await(10, TimeUnit.SECONDS)) {
                fail("request did not reach a terminal state within 10s")
            }
        }
    }
}

package com.touvay.sdk.internal

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.touvay.contract.CapabilityInfo
import com.touvay.contract.CapabilityStatusCodes
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
import com.touvay.sdk.CapabilityId
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.TouvayClient
import com.touvay.sdk.TouvayDiagnostics
import com.touvay.sdk.TouvayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class TouvayClientImpl(
    private val engine: ITouvayEngine,
    private val onClose: () -> Unit,
) : TouvayClient {

    private val closed = AtomicBoolean(false)

    /** requestId → failer that tears the request's flow down with the given exception. */
    private val inFlight = ConcurrentHashMap<String, (TouvayException) -> Unit>()

    private val deathRecipient = IBinder.DeathRecipient { onBinderDied() }

    init {
        // linkToDeath never fires for a same-process binder; that case (Robolectric,
        // future in-process fakes) is covered by DeadObjectException mapping instead.
        runCatching { engine.asBinder().linkToDeath(deathRecipient, 0) }
    }

    override suspend fun capabilities(): Map<CapabilityId, CapabilityStatus> {
        ensureOpen()
        val infos = withContext(Dispatchers.IO) {
            translateBinderFailures { engine.listCapabilities() }
        }
        return infos.associate { CapabilityId(it.id) to it.toStatus() }
    }

    override fun diagnostics(): TouvayDiagnostics = Diagnostics()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { engine.asBinder().unlinkToDeath(deathRecipient, 0) }
            failAllInFlight(TouvayException.ClientClosed())
            onClose()
        }
    }

    /** Fails all in-flight requests; also invoked by tests to simulate engine death. */
    internal fun onBinderDied() {
        failAllInFlight(TouvayException.EngineDisconnected())
    }

    // -- request plumbing ------------------------------------------------------------------

    internal sealed class ResponseEvent {
        class Delta(val sequence: Int, val payload: ByteArray) : ResponseEvent()
        class Final(val payload: ByteArray) : ResponseEvent()
    }

    /**
     * Submits one request and streams its events. Flow cancellation propagates to the
     * engine via cancel(); engine-side failures surface as [TouvayException].
     */
    internal fun execute(
        capabilityId: String,
        schemaVersion: Int,
        payload: ByteArray,
        coalesceKey: String? = null,
    ): Flow<ResponseEvent> = callbackFlow {
        ensureOpen()
        val requestId = UUID.randomUUID().toString()

        val callback = object : ITouvayResponseCallback.Stub() {
            override fun onAccepted(acceptedRequestId: String) = Unit

            override fun onDelta(delta: ResponseDelta) {
                trySendBlocking(ResponseEvent.Delta(delta.sequence, delta.payload))
            }

            override fun onCompleted(result: ResponseFinal) {
                inFlight.remove(requestId)
                trySendBlocking(ResponseEvent.Final(result.payload))
                close()
            }

            override fun onFailed(error: EngineError) {
                inFlight.remove(requestId)
                close(error.toException(capabilityId))
            }
        }

        inFlight[requestId] = { failure -> close(failure) }
        try {
            translateBinderFailures {
                engine.submit(
                    RequestEnvelope(
                        requestId = requestId,
                        capabilityId = capabilityId,
                        schemaVersion = schemaVersion,
                        payload = payload,
                        priority = RequestPriorities.INTERACTIVE,
                        coalesceKey = coalesceKey,
                    ),
                    callback,
                )
            }
        } catch (e: TouvayException) {
            inFlight.remove(requestId)
            throw e
        }

        awaitClose {
            inFlight.remove(requestId)
            // No-op for already-terminal requests; cancels abandoned ones.
            runCatching { engine.cancel(requestId) }
        }
    }
        .buffer(Channel.UNLIMITED) // oneway binder callbacks must never block on the collector
        .flowOn(Dispatchers.IO)

    private fun ensureOpen() {
        if (closed.get()) throw TouvayException.ClientClosed()
    }

    private fun failAllInFlight(cause: TouvayException) {
        val failers = inFlight.values.toList()
        inFlight.clear()
        failers.forEach { fail -> fail(cause) }
    }

    private inline fun <T> translateBinderFailures(block: () -> T): T = try {
        block()
    } catch (e: DeadObjectException) {
        throw TouvayException.EngineDisconnected(e)
    } catch (e: RemoteException) {
        throw TouvayException.EngineFailure(
            code = -1,
            retryable = true,
            message = "binder transaction failed: ${e.javaClass.simpleName}",
        )
    }

    // -- diagnostics -------------------------------------------------------------------------

    private inner class Diagnostics : TouvayDiagnostics {

        override suspend fun echo(text: String): String {
            val payload = EchoRequest.newBuilder()
                .setText(text)
                .setChunkCount(1)
                .build()
                .toByteArray()

            val final = execute(TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO, ECHO_SCHEMA_VERSION, payload)
                .filterIsInstance<ResponseEvent.Final>()
                .first()
            return EchoResponse.parseFrom(final.payload).text
        }

        override fun echoStream(
            text: String,
            chunks: Int,
            interChunkDelayMillis: Long,
        ): Flow<String> {
            require(chunks >= 1) { "chunks must be >= 1, was $chunks" }
            require(interChunkDelayMillis in 0..MAX_DELAY_MILLIS) {
                "interChunkDelayMillis must be in 0..$MAX_DELAY_MILLIS, was $interChunkDelayMillis"
            }
            val payload = EchoRequest.newBuilder()
                .setText(text)
                .setChunkCount(chunks)
                .setInterChunkDelayMillis(interChunkDelayMillis.toInt())
                .build()
                .toByteArray()

            return execute(TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO, ECHO_SCHEMA_VERSION, payload)
                .mapNotNull { event ->
                    (event as? ResponseEvent.Delta)?.let { EchoDelta.parseFrom(it.payload).text }
                }
        }
    }

    private companion object {
        const val ECHO_SCHEMA_VERSION = 1
        const val MAX_DELAY_MILLIS = 10_000L
    }
}

private fun CapabilityInfo.toStatus(): CapabilityStatus = when (statusCode) {
    CapabilityStatusCodes.READY -> CapabilityStatus.Ready
    CapabilityStatusCodes.DOWNLOAD_REQUIRED -> CapabilityStatus.DownloadRequired(approxBytes = null)
    CapabilityStatusCodes.DEVICE_NOT_SUPPORTED -> CapabilityStatus.DeviceNotSupported(reason = null)
    CapabilityStatusCodes.DISABLED_BY_POLICY -> CapabilityStatus.DisabledByPolicy
    else -> CapabilityStatus.Unknown(statusCode)
}

private fun EngineError.toException(capabilityId: String): TouvayException = when (code) {
    EngineErrorCodes.UNKNOWN_CAPABILITY ->
        TouvayException.CapabilityUnavailable(capabilityId, message)
    EngineErrorCodes.CANCELLED -> TouvayException.RequestCancelled()
    EngineErrorCodes.SUPERSEDED -> TouvayException.RequestSuperseded()
    else -> TouvayException.EngineFailure(code, retryable, message)
}

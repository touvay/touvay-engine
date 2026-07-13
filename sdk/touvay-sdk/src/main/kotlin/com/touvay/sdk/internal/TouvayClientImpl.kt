package com.touvay.sdk.internal

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.google.protobuf.InvalidProtocolBufferException
import com.touvay.contract.CapabilityInfo
import com.touvay.contract.CapabilityStatusCodes
import com.touvay.contract.EngineError
import com.touvay.contract.EngineErrorCodes
import com.touvay.contract.ITouvayEngine
import com.touvay.contract.ITouvayResponseCallback
import com.touvay.contract.RequestEnvelope
import com.touvay.contract.RequestPriorities
import com.touvay.contract.RequestStats
import com.touvay.contract.ResponseDelta
import com.touvay.contract.ResponseFinal
import com.touvay.contract.StreamCreditWindow
import com.touvay.contract.TouvayContract
import com.touvay.contract.proto.EchoDelta
import com.touvay.contract.proto.EchoRequest
import com.touvay.contract.proto.EchoResponse
import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteDisposition as ContractRewriteDisposition
import com.touvay.contract.rewrite.v1.RewriteLength as ContractRewriteLength
import com.touvay.contract.rewrite.v1.RewriteRequest as ContractRewriteRequest
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.contract.rewrite.v1.RewriteTone as ContractRewriteTone
import com.touvay.sdk.CapabilityId
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.TouvayClient
import com.touvay.sdk.TouvayDiagnostics
import com.touvay.sdk.TouvayException
import com.touvay.sdk.TouvayRewrite
import com.touvay.sdk.RewriteCapability
import com.touvay.sdk.RewriteDisposition
import com.touvay.sdk.RewriteEvent
import com.touvay.sdk.RewriteLength
import com.touvay.sdk.RewriteRequest
import com.touvay.sdk.RewriteResult
import com.touvay.sdk.RewriteTiming
import com.touvay.sdk.RewriteTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class TouvayClientImpl(
    private val engine: ITouvayEngine,
    private val onClose: () -> Unit,
    private val supportsStreamingCredits: Boolean = true,
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

    override fun rewrite(): TouvayRewrite = RewriteOperations()

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
        class Final(val payload: ByteArray, val stats: RequestStats) : ResponseEvent()
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
    ): Flow<ResponseEvent> = flow {
        ensureOpen()
        val requestId = UUID.randomUUID().toString()
        val wireEvents = Channel<ResponseEvent>(STREAM_DELTA_CREDITS + 1)
        val terminal = AtomicBoolean(false)
        val outstandingDeltas = AtomicInteger(0)
        val outstandingBytes = AtomicLong(0)
        var expectedSequence = 0

        val callback = object : ITouvayResponseCallback.Stub() {
            override fun onAccepted(acceptedRequestId: String) = Unit

            override fun onDelta(delta: ResponseDelta) {
                if (terminal.get()) return
                val queued = synchronized(this) {
                    if (terminal.get() || delta.requestId != requestId ||
                        delta.sequence != expectedSequence ||
                        delta.payload.size > STREAM_BYTE_CREDITS
                    ) {
                        false
                    } else {
                        val pendingCount = outstandingDeltas.incrementAndGet()
                        val pendingBytes = outstandingBytes.addAndGet(delta.payload.size.toLong())
                        val accepted = pendingCount <= STREAM_DELTA_CREDITS &&
                            pendingBytes <= STREAM_BYTE_CREDITS &&
                            wireEvents.trySend(
                                ResponseEvent.Delta(delta.sequence, delta.payload.copyOf()),
                            ).isSuccess
                        if (accepted) {
                            expectedSequence += 1
                        } else {
                            outstandingDeltas.decrementAndGet()
                            outstandingBytes.addAndGet(-delta.payload.size.toLong())
                        }
                        accepted
                    }
                }
                if (!queued) {
                    terminal.set(true)
                    wireEvents.close(
                        TouvayException.EngineFailure(
                            EngineErrorCodes.BACKPRESSURE_EXCEEDED,
                            false,
                            "invalid bounded response stream",
                        ),
                    )
                }
            }

            override fun onCompleted(result: ResponseFinal) {
                synchronized(this) {
                    if (terminal.compareAndSet(false, true)) {
                        inFlight.remove(requestId)
                        if (result.requestId != requestId ||
                            wireEvents.trySend(
                                ResponseEvent.Final(result.payload.copyOf(), result.stats),
                            ).isFailure
                        ) {
                            wireEvents.close(
                                TouvayException.EngineFailure(
                                    EngineErrorCodes.BACKPRESSURE_EXCEEDED,
                                    false,
                                    "bounded response stream overflow",
                                ),
                            )
                        } else {
                            wireEvents.close()
                        }
                    }
                }
            }

            override fun onFailed(error: EngineError) {
                synchronized(this) {
                    if (terminal.compareAndSet(false, true)) {
                        inFlight.remove(requestId)
                        val failure = if (error.requestId == requestId) {
                            error.toException(capabilityId)
                        } else {
                            TouvayException.EngineFailure(
                                EngineErrorCodes.BACKPRESSURE_EXCEEDED,
                                false,
                                "invalid bounded response stream",
                            )
                        }
                        wireEvents.close(failure)
                    }
                }
            }
        }

        inFlight[requestId] = { failure ->
            terminal.set(true)
            wireEvents.close(failure)
        }
        try {
            translateBinderFailures {
                val envelope = RequestEnvelope(
                    requestId = requestId,
                    capabilityId = capabilityId,
                    schemaVersion = schemaVersion,
                    payload = payload,
                    priority = RequestPriorities.INTERACTIVE,
                    coalesceKey = coalesceKey,
                )
                if (supportsStreamingCredits) {
                    engine.submitWithCredits(
                        envelope,
                        StreamCreditWindow(
                            STREAM_DELTA_CREDITS,
                            STREAM_BYTE_CREDITS.toLong(),
                            STREAM_DELTA_CREDITS,
                            STREAM_BYTE_CREDITS.toLong(),
                        ),
                        callback,
                    )
                } else {
                    engine.submit(envelope, callback)
                }
            }
        } catch (e: TouvayException) {
            inFlight.remove(requestId)
            wireEvents.close(e)
            throw e
        }

        var grantSequence = 1L
        try {
            coroutineScope {
                for (event in wireEvents) {
                    emit(event)
                    if (event is ResponseEvent.Delta && supportsStreamingCredits) {
                        translateBinderFailures {
                            engine.grantCredits(
                                requestId,
                                grantSequence++,
                                1,
                                event.payload.size.toLong(),
                            )
                        }
                    }
                    if (event is ResponseEvent.Delta) {
                        outstandingDeltas.decrementAndGet()
                        outstandingBytes.addAndGet(-event.payload.size.toLong())
                    }
                }
            }
        } finally {
            inFlight.remove(requestId)
            wireEvents.close()
            // No-op for already-terminal requests; cancels abandoned ones.
            runCatching { engine.cancel(requestId) }
        }
    }
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

    // -- Rewrite -----------------------------------------------------------------------------

    private inner class RewriteOperations : TouvayRewrite {
        override suspend fun execute(request: RewriteRequest): RewriteResult =
            stream(request)
                .filterIsInstance<RewriteEvent.Completed>()
                .first()
                .result

        override fun stream(request: RewriteRequest): Flow<RewriteEvent> {
            val payload = encodeRequest(request)
            return this@TouvayClientImpl.execute(
                capabilityId = RewriteCapability.id.value,
                schemaVersion = RewriteCapability.schemaVersion,
                payload = payload,
            ).transform { event ->
                when (event) {
                    is ResponseEvent.Delta -> {
                        val delta = parseDelta(event.payload)
                        emit(RewriteEvent.Delta(event.sequence, delta.text))
                    }
                    is ResponseEvent.Final -> {
                        val response = parseFinal(event.payload)
                        emit(
                            RewriteEvent.Completed(
                                RewriteResult(
                                    text = response.text,
                                    disposition = when (response.disposition) {
                                        ContractRewriteDisposition.REWRITE_DISPOSITION_REWRITTEN ->
                                            RewriteDisposition.REWRITTEN
                                        ContractRewriteDisposition.REWRITE_DISPOSITION_UNCHANGED ->
                                            RewriteDisposition.UNCHANGED
                                        else -> invalidOutput()
                                    },
                                    timing = RewriteTiming(
                                        timeToFirstTokenMillis = event.stats.ttftMillis,
                                        totalMillis = event.stats.totalMillis,
                                        deltaCount = event.stats.deltaCount,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        private fun encodeRequest(request: RewriteRequest): ByteArray {
            val textBytes = request.text.toByteArray(Charsets.UTF_8).size
            if (request.text.isBlank() || textBytes !in 1..MAX_REWRITE_SOURCE_BYTES) {
                throw TouvayException.InvalidRequest("rewrite text is blank or too large")
            }
            val builder = ContractRewriteRequest.newBuilder()
                .setText(request.text)
                .setTone(
                    when (request.tone) {
                        RewriteTone.NEUTRAL -> ContractRewriteTone.REWRITE_TONE_NEUTRAL
                        RewriteTone.FORMAL -> ContractRewriteTone.REWRITE_TONE_FORMAL
                        RewriteTone.CASUAL -> ContractRewriteTone.REWRITE_TONE_CASUAL
                    },
                )
                .setLength(
                    when (request.length) {
                        RewriteLength.PRESERVE -> ContractRewriteLength.REWRITE_LENGTH_PRESERVE
                        RewriteLength.SHORTER -> ContractRewriteLength.REWRITE_LENGTH_SHORTER
                        RewriteLength.LONGER -> ContractRewriteLength.REWRITE_LENGTH_LONGER
                    },
                )
            request.outputLocaleBcp47?.let { locale ->
                val canonical = try {
                    Locale.Builder().setLanguageTag(locale).build().toLanguageTag()
                } catch (_: IllformedLocaleException) {
                    throw TouvayException.InvalidRequest("invalid output locale")
                }
                if (canonical == "und" || canonical.length > MAX_LOCALE_CHARS) {
                    throw TouvayException.InvalidRequest("invalid output locale")
                }
                builder.outputLocaleBcp47 = canonical
            }
            return builder.build().toByteArray().also { bytes ->
                if (bytes.size > MAX_REWRITE_PAYLOAD_BYTES) {
                    throw TouvayException.InvalidRequest("rewrite request is too large")
                }
            }
        }

        private fun parseDelta(bytes: ByteArray): RewriteDelta {
            val delta = try {
                RewriteDelta.parseFrom(bytes)
            } catch (_: InvalidProtocolBufferException) {
                invalidOutput()
            }
            if (!delta.provisional || delta.text.isEmpty()) invalidOutput()
            return delta
        }

        private fun parseFinal(bytes: ByteArray): RewriteResponse {
            val response = try {
                RewriteResponse.parseFrom(bytes)
            } catch (_: InvalidProtocolBufferException) {
                invalidOutput()
            }
            if (response.text.isEmpty()) invalidOutput()
            return response
        }

        private fun invalidOutput(): Nothing = throw TouvayException.EngineFailure(
            EngineErrorCodes.INVALID_OUTPUT,
            retryable = false,
            message = "invalid Rewrite response",
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
        const val STREAM_DELTA_CREDITS = 8
        const val STREAM_BYTE_CREDITS = 256 * 1024
        const val MAX_REWRITE_SOURCE_BYTES = 8 * 1024
        const val MAX_REWRITE_PAYLOAD_BYTES = 12 * 1024
        const val MAX_LOCALE_CHARS = 35
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
    EngineErrorCodes.INVALID_REQUEST -> TouvayException.InvalidRequest(message)
    else -> TouvayException.EngineFailure(code, retryable, message)
}

package com.touvay.engine.service

import android.os.Binder
import android.os.Process
import android.os.RemoteException
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
import com.touvay.contract.StreamCreditWindow as ContractStreamCreditWindow
import com.touvay.contract.TouvayContract
import com.touvay.engine.core.ExecutionStats
import com.touvay.engine.core.RequestFailure
import com.touvay.engine.core.RequestJob
import com.touvay.engine.core.RequestListener
import com.touvay.engine.core.RequestProcessor
import com.touvay.engine.core.RequestKey
import com.touvay.engine.core.StreamCreditWindow
import com.touvay.engine.core.StreamLimits
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's side of the binder contract. Binder threads only enqueue here; inference
 * work runs on the engine's own dispatchers, and results travel back through the client's
 * oneway callback (never blocking on the client).
 */
internal class EngineBinder(
    private val component: EngineComponent,
) : ITouvayEngine.Stub() {
    private val creditWindows = ConcurrentHashMap<RequestKey, StreamCreditWindow>()

    override fun negotiate(hello: ClientHello?): EngineHello {
        enforceCallerAllowed()
        // The engine never rejects a hello: it reports its window and the client decides,
        // so an old client gets a deterministic error instead of a binder exception.
        return EngineHello(
            contractVersion = TouvayContract.CONTRACT_VERSION,
            minContractVersion = TouvayContract.MIN_SUPPORTED_CONTRACT_VERSION,
            engineVersionName = EngineComponent.ENGINE_VERSION_NAME,
        )
    }

    override fun listCapabilities(): List<CapabilityInfo> {
        enforceCallerAllowed()
        return component.capabilities()
    }

    override fun listTransportFeatures(): List<String> {
        enforceCallerAllowed()
        return listOf(TouvayContract.FEATURE_STREAM_CREDITS_V1)
    }

    override fun submit(request: RequestEnvelope?, callback: ITouvayResponseCallback?) {
        enforceCallerAllowed()
        submitInternal(request, callback, null)
    }

    override fun submitWithCredits(
        request: RequestEnvelope?,
        window: ContractStreamCreditWindow?,
        callback: ITouvayResponseCallback?,
    ) {
        enforceCallerAllowed()
        if (callback == null) return
        val limits = try {
            requireNotNull(window)
            StreamLimits(
                initialDeltaCredits = window.initialDeltaCredits,
                initialByteCredits = window.initialByteCredits,
                maxDeltaCredits = window.maxDeltaCredits,
                maxByteCredits = window.maxByteCredits,
            )
        } catch (_: IllegalArgumentException) {
            runCatching {
                callback.onFailed(
                    EngineError(
                        request?.requestId.orEmpty(),
                        EngineErrorCodes.BACKPRESSURE_EXCEEDED,
                        false,
                        "invalid stream credit window",
                    ),
                )
            }
            return
        }
        submitInternal(request, callback, limits)
    }

    override fun grantCredits(
        requestId: String?,
        grantSequence: Long,
        deltaCredits: Int,
        byteCredits: Long,
    ) {
        enforceCallerAllowed()
        if (requestId == null) return
        val principal = Binder.getCallingUid().toString()
        if (component.production?.grantCredits(
                principal,
                requestId,
                grantSequence,
                deltaCredits,
                byteCredits,
            ) == true
        ) {
            return
        }
        val key = RequestKey(principal, requestId)
        creditWindows[key]?.grant(grantSequence, deltaCredits, byteCredits)
    }

    private fun submitInternal(
        request: RequestEnvelope?,
        callback: ITouvayResponseCallback?,
        limits: StreamLimits?,
    ) {
        if (callback == null) return
        if (request == null) {
            // Defensive: only reachable from hand-written clients, but the failure must
            // still arrive through the callback, not as a binder exception.
            runCatching {
                callback.onFailed(
                    EngineError("", EngineErrorCodes.INTERNAL, false, "null request envelope"),
                )
            }
            return
        }

        val clientId = Binder.getCallingUid().toString()
        if (component.isProductionCapabilityId(request.capabilityId)) {
            if (!component.isProductionCapability(request.capabilityId, request.schemaVersion)) {
                runCatching {
                    callback.onFailed(
                        EngineError(
                            request.requestId,
                            EngineErrorCodes.SCHEMA_VERSION_MISMATCH,
                            false,
                            "unsupported capability schema",
                        ),
                    )
                }
                return
            }
            val production = component.production
            if (production == null) {
                runCatching {
                    callback.onFailed(
                        EngineError(
                            request.requestId,
                            EngineErrorCodes.MODEL_UNAVAILABLE,
                            true,
                            "rewrite model unavailable",
                        ),
                    )
                }
                return
            }
            production.submit(
                request = request,
                principal = clientId,
                limits = limits ?: StreamLimits.DIAGNOSTIC_DEFAULT,
                callback = callback,
            )
            return
        }

        val credits = limits?.let(::StreamCreditWindow)
        val key = RequestKey(clientId, request.requestId)
        if (credits != null && creditWindows.putIfAbsent(key, credits) != null) {
            credits.close()
            runCatching {
                callback.onFailed(
                    EngineError(
                        request.requestId,
                        EngineErrorCodes.INTERNAL,
                        false,
                        "duplicate live request",
                    ),
                )
            }
            return
        }

        val job = RequestJob(
            requestId = request.requestId,
            clientId = clientId,
            capabilityId = request.capabilityId,
            schemaVersion = request.schemaVersion,
            payload = request.payload,
            coalesceKey = request.coalesceKey,
        )
        component.processor.submit(
            job,
            BinderRequestListener(callback, component.processor, key, credits) {
                if (credits != null) creditWindows.remove(key, credits)
                credits?.close()
            },
        )
    }

    override fun cancel(requestId: String?) {
        enforceCallerAllowed()
        if (requestId != null) {
            val principal = Binder.getCallingUid().toString()
            component.production?.cancel(principal, requestId)
            component.processor.cancel(principal, requestId)
        }
    }

    /**
     * v1 access policy (ARCHITECTURE.md §16): same-app callers only. The cross-app phase
     * replaces this with the consent registry — an explicit, reviewed change.
     */
    private fun enforceCallerAllowed() {
        val caller = Binder.getCallingUid()
        if (caller != Process.myUid()) {
            throw SecurityException("Touvay Engine v1 accepts same-app callers only")
        }
    }
}

/** Bridges core callbacks onto the client's oneway binder callback. */
private class BinderRequestListener(
    private val callback: ITouvayResponseCallback,
    private val processor: RequestProcessor,
    private val requestKey: RequestKey,
    private val credits: StreamCreditWindow?,
    private val onTerminal: () -> Unit,
) : RequestListener {

    override fun onAccepted(requestId: String) = deliver(requestId) {
        callback.onAccepted(requestId)
    }

    override suspend fun onDelta(requestId: String, sequence: Int, payload: ByteArray) {
        credits?.awaitAndConsume(payload.size)
        deliver(requestId) {
            callback.onDelta(ResponseDelta(requestId, sequence, payload))
        }
    }

    override fun onCompleted(requestId: String, payload: ByteArray, stats: ExecutionStats) {
        deliver(requestId) {
            callback.onCompleted(
                ResponseFinal(
                    requestId = requestId,
                    payload = payload,
                    stats = RequestStats(stats.ttftMillis, stats.totalMillis, stats.deltaCount),
                ),
            )
        }
        onTerminal()
    }

    override fun onFailed(requestId: String, failure: RequestFailure) {
        deliver(requestId) {
            callback.onFailed(failure.toEngineError(requestId))
        }
        onTerminal()
    }

    private inline fun deliver(requestId: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            // The client process is gone (§11.4): stop doing work on its behalf.
            processor.cancel(requestKey.principal, requestId)
        }
    }
}

private fun RequestFailure.toEngineError(requestId: String): EngineError = when (this) {
    is RequestFailure.UnknownCapability -> EngineError(
        requestId, EngineErrorCodes.UNKNOWN_CAPABILITY, false, "unknown capability",
    )
    is RequestFailure.SchemaVersionMismatch -> EngineError(
        requestId,
        EngineErrorCodes.SCHEMA_VERSION_MISMATCH,
        false,
        "unsupported capability schema",
    )
    is RequestFailure.Cancelled -> if (superseded) {
        EngineError(requestId, EngineErrorCodes.SUPERSEDED, false, "superseded by a newer request")
    } else {
        EngineError(requestId, EngineErrorCodes.CANCELLED, false, "cancelled")
    }
    is RequestFailure.Internal -> EngineError(requestId, EngineErrorCodes.INTERNAL, false, message)
}

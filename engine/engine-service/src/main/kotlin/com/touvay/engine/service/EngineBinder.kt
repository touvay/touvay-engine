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
import com.touvay.contract.TouvayContract
import com.touvay.engine.core.ExecutionStats
import com.touvay.engine.core.RequestFailure
import com.touvay.engine.core.RequestJob
import com.touvay.engine.core.RequestListener
import com.touvay.engine.core.RequestProcessor

/**
 * The engine's side of the binder contract. Binder threads only enqueue here; inference
 * work runs on the engine's own dispatchers, and results travel back through the client's
 * oneway callback (never blocking on the client).
 */
internal class EngineBinder(
    private val component: EngineComponent,
) : ITouvayEngine.Stub() {

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
        return component.registry.all().map { pipeline ->
            CapabilityInfo(
                id = pipeline.descriptor.id,
                schemaVersion = pipeline.descriptor.schemaVersion,
                statusCode = CapabilityStatusCodes.READY,
            )
        }
    }

    override fun submit(request: RequestEnvelope?, callback: ITouvayResponseCallback?) {
        enforceCallerAllowed()
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

        val job = RequestJob(
            requestId = request.requestId,
            clientId = Binder.getCallingUid().toString(),
            capabilityId = request.capabilityId,
            schemaVersion = request.schemaVersion,
            payload = request.payload,
            coalesceKey = request.coalesceKey,
        )
        component.processor.submit(job, BinderRequestListener(callback, component.processor))
    }

    override fun cancel(requestId: String?) {
        enforceCallerAllowed()
        if (requestId != null) {
            component.processor.cancel(requestId)
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
) : RequestListener {

    override fun onAccepted(requestId: String) = deliver(requestId) {
        callback.onAccepted(requestId)
    }

    override fun onDelta(requestId: String, sequence: Int, payload: ByteArray) =
        deliver(requestId) {
            callback.onDelta(ResponseDelta(requestId, sequence, payload))
        }

    override fun onCompleted(requestId: String, payload: ByteArray, stats: ExecutionStats) =
        deliver(requestId) {
            callback.onCompleted(
                ResponseFinal(
                    requestId = requestId,
                    payload = payload,
                    stats = RequestStats(stats.ttftMillis, stats.totalMillis, stats.deltaCount),
                ),
            )
        }

    override fun onFailed(requestId: String, failure: RequestFailure) = deliver(requestId) {
        callback.onFailed(failure.toEngineError(requestId))
    }

    private inline fun deliver(requestId: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            // The client process is gone (§11.4): stop doing work on its behalf.
            processor.cancel(requestId)
        }
    }
}

private fun RequestFailure.toEngineError(requestId: String): EngineError = when (this) {
    is RequestFailure.UnknownCapability -> EngineError(
        requestId, EngineErrorCodes.UNKNOWN_CAPABILITY, false, "unknown capability: $capabilityId",
    )
    is RequestFailure.SchemaVersionMismatch -> EngineError(
        requestId,
        EngineErrorCodes.SCHEMA_VERSION_MISMATCH,
        false,
        "capability $capabilityId speaks schema $supported; request used $requested",
    )
    is RequestFailure.Cancelled -> if (superseded) {
        EngineError(requestId, EngineErrorCodes.SUPERSEDED, false, "superseded by a newer request")
    } else {
        EngineError(requestId, EngineErrorCodes.CANCELLED, false, "cancelled")
    }
    is RequestFailure.Internal -> EngineError(requestId, EngineErrorCodes.INTERNAL, false, message)
}

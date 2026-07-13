package com.touvay.engine.service

import android.os.RemoteException
import com.touvay.contract.CapabilityStatusCodes
import com.touvay.contract.EngineError
import com.touvay.contract.EngineErrorCodes
import com.touvay.contract.ITouvayResponseCallback
import com.touvay.contract.RequestEnvelope
import com.touvay.contract.RequestPriorities
import com.touvay.contract.RequestStats
import com.touvay.contract.ResponseDelta
import com.touvay.contract.ResponseFinal
import com.touvay.contract.TouvayContract
import com.touvay.engine.core.ExecutionCancellationReason
import com.touvay.engine.core.ExecutionContext
import com.touvay.engine.core.ExecutionFailure
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.ExecutionObserver
import com.touvay.engine.core.ExecutionPriority
import com.touvay.engine.core.ExecutionRequest
import com.touvay.engine.core.RequestKey
import com.touvay.engine.core.StreamLimits
import com.touvay.engine.models.ModelManagerPlatform
import com.touvay.engine.models.ModelManagerPlatformConfiguration
import com.touvay.engine.models.ModelManagerRuntimeRegistration
import com.touvay.engine.models.ModelManagerTrustedKey
import com.touvay.runtime.llamacpp.LlamaCppRuntime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Android composition and Binder-facing lifecycle for the single Rewrite execution plan. */
internal class ProductionExecutionController(
    private val configuration: OfflineModelConfiguration,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stack = AtomicReference<Stack?>(null)
    private val status = AtomicInteger(CapabilityStatusCodes.DOWNLOAD_REQUIRED)
    private val initialization: Job

    init {
        initialization = scope.launch { initialize() }
    }

    fun capabilityStatus(): Int = status.get()

    fun submit(
        request: RequestEnvelope,
        principal: String,
        limits: StreamLimits,
        callback: ITouvayResponseCallback,
    ) {
        val current = stack.get()
        if (current == null) {
            fail(
                callback,
                request.requestId,
                EngineErrorCodes.MODEL_UNAVAILABLE,
                retryable = true,
                message = "rewrite model unavailable",
            )
            return
        }
        val executionRequest = try {
            ExecutionRequest(
                requestId = request.requestId,
                principal = principal,
                capabilityId = request.capabilityId,
                schemaVersion = request.schemaVersion,
                payload = request.payload,
                priority = if (request.priority == RequestPriorities.BACKGROUND) {
                    ExecutionPriority.BACKGROUND
                } else {
                    ExecutionPriority.INTERACTIVE
                },
                coalesceKey = request.coalesceKey,
                timeoutMillis = REQUEST_TIMEOUT_MILLIS,
                contractVersion = TouvayContract.CONTRACT_VERSION,
                negotiatedFeatures = setOf(TouvayContract.FEATURE_STREAM_CREDITS_V1),
                policyGeneration = POLICY_GENERATION,
                streamLimits = limits,
            )
        } catch (_: IllegalArgumentException) {
            fail(
                callback,
                request.requestId,
                EngineErrorCodes.INVALID_REQUEST,
                retryable = false,
                message = "invalid request",
            )
            return
        }
        current.execution.coordinator.submit(
            executionRequest,
            BinderExecutionObserver(current.execution, callback, executionRequest),
        )
    }

    fun cancel(principal: String, requestId: String): Boolean =
        stack.get()?.execution?.coordinator?.cancel(
            principal,
            requestId,
            ExecutionCancellationReason.CLIENT_CANCELLED,
        ) ?: false

    fun grantCredits(
        principal: String,
        requestId: String,
        sequence: Long,
        deltaCredits: Int,
        byteCredits: Long,
    ): Boolean {
        val current = stack.get() ?: return false
        current.execution.coordinator.grantCredits(
            principal,
            requestId,
            sequence,
            deltaCredits,
            byteCredits,
        )
        return true
    }

    fun shutdown() {
        initialization.cancel()
        val current = stack.getAndSet(null)
        if (current != null) {
            current.execution.coordinator.shutdown()
            runBlocking(Dispatchers.IO) {
                runCatching { current.execution.shutdown() }
                runCatching { current.models.shutdown() }
            }
        }
        scope.cancel()
    }

    private suspend fun initialize() {
        if (configuration.tier == com.touvay.engine.models.ModelManagerDeviceTier.T0) {
            status.set(CapabilityStatusCodes.DEVICE_NOT_SUPPORTED)
            return
        }
        var models: ModelManagerPlatform? = null
        try {
            val created = ModelManagerPlatform(
                root = configuration.storeRoot,
                configuration = ModelManagerPlatformConfiguration(
                    engineVersion = EngineComponent.ENGINE_VERSION_NAME,
                    androidApi = android.os.Build.VERSION.SDK_INT,
                    deviceTier = configuration.tier,
                    totalRamBytes = configuration.device.totalRamBytes,
                    isLowRamDevice = configuration.device.isLowRamDevice,
                    supportedAbis = configuration.device.supportedAbis.toSet(),
                    trustedKeys = listOf(
                        ModelManagerTrustedKey(configuration.keyId, configuration.publicKey),
                    ),
                ),
                runtimeRegistrations = listOf(
                    ModelManagerRuntimeRegistration(
                        bindingIdentity = LLAMACPP_BINDING_IDENTITY,
                        runtime = LlamaCppRuntime(),
                        adapterVersion = LLAMACPP_ADAPTER_VERSION,
                    ),
                ),
            )
            models = created
            created.installAndActivate(configuration.sourceRoot)
            val route = created.activeRoutes(
                RewriteExecutionRouter.CAPABILITY_ID,
                RewriteExecutionRouter.SCHEMA_VERSION,
            ).singleOrNull { it.revision.packId == configuration.packId }
                ?: return
            if (route.runtimeId != LLAMACPP_RUNTIME_ID) return
            val execution = ExecutionEngineComponent(
                modelManager = created.runtimeInstances,
                deviceProfile = { configuration.device },
                router = RewriteExecutionRouter(
                    route = route,
                    threads = minOf(Runtime.getRuntime().availableProcessors(), MAX_THREADS)
                        .coerceAtLeast(1),
                ),
            )
            if (stack.compareAndSet(null, Stack(created, execution))) {
                models = null
                status.set(CapabilityStatusCodes.READY)
            }
        } catch (_: Exception) {
            status.set(CapabilityStatusCodes.DOWNLOAD_REQUIRED)
        } catch (_: LinkageError) {
            status.set(CapabilityStatusCodes.DEVICE_NOT_SUPPORTED)
        } finally {
            models?.shutdown()
        }
    }

    private class Stack(
        val models: ModelManagerPlatform,
        val execution: ExecutionEngineComponent,
    )

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 2L * 60L * 1000L
        const val POLICY_GENERATION = 1L
        const val MAX_THREADS = 4
        const val LLAMACPP_RUNTIME_ID = "llamacpp"
        const val LLAMACPP_BINDING_IDENTITY = "llamacpp.b5199"
        const val LLAMACPP_ADAPTER_VERSION = "1.0.0"

        fun fail(
            callback: ITouvayResponseCallback,
            requestId: String,
            code: Int,
            retryable: Boolean,
            message: String,
        ) {
            runCatching { callback.onFailed(EngineError(requestId, code, retryable, message)) }
        }
    }
}

private class BinderExecutionObserver(
    private val execution: ExecutionEngineComponent,
    private val callback: ITouvayResponseCallback,
    request: ExecutionRequest,
) : ExecutionObserver {
    private val requestKey = RequestKey(request.principal, request.requestId)

    override fun onAccepted(context: ExecutionContext) = deliver {
        callback.onAccepted(context.requestKey.requestId)
    }

    override fun onDelta(context: ExecutionContext, sequence: Int, payload: ByteArray) = deliver {
        callback.onDelta(ResponseDelta(context.requestKey.requestId, sequence, payload))
    }

    override fun onCompleted(
        context: ExecutionContext,
        payload: ByteArray,
        stats: com.touvay.engine.core.ExecutionStats,
    ) = deliver {
        callback.onCompleted(
            ResponseFinal(
                requestId = context.requestKey.requestId,
                payload = payload,
                stats = RequestStats(stats.ttftMillis, stats.totalMillis, stats.deltaCount),
            ),
        )
    }

    override fun onFailed(requestKey: RequestKey, failure: ExecutionFailure) = deliver {
        callback.onFailed(failure.toEngineError(requestKey.requestId))
    }

    private inline fun deliver(block: () -> Unit) {
        try {
            block()
        } catch (_: RemoteException) {
            execution.coordinator.cancel(
                requestKey.principal,
                requestKey.requestId,
                ExecutionCancellationReason.CLIENT_GONE,
            )
        }
    }
}

private fun ExecutionFailure.toEngineError(requestId: String): EngineError {
    val code = when (code) {
        ExecutionFailureCode.UNKNOWN_CAPABILITY -> EngineErrorCodes.UNKNOWN_CAPABILITY
        ExecutionFailureCode.SCHEMA_VERSION_MISMATCH -> EngineErrorCodes.SCHEMA_VERSION_MISMATCH
        ExecutionFailureCode.INVALID_REQUEST -> EngineErrorCodes.INVALID_REQUEST
        ExecutionFailureCode.DUPLICATE_REQUEST -> EngineErrorCodes.INTERNAL
        ExecutionFailureCode.BUSY -> EngineErrorCodes.BUSY
        ExecutionFailureCode.DEADLINE_EXCEEDED -> EngineErrorCodes.DEADLINE_EXCEEDED
        ExecutionFailureCode.CANCELLED -> EngineErrorCodes.CANCELLED
        ExecutionFailureCode.SUPERSEDED -> EngineErrorCodes.SUPERSEDED
        ExecutionFailureCode.PREEMPTED -> EngineErrorCodes.PREEMPTED
        ExecutionFailureCode.MODEL_UNAVAILABLE -> EngineErrorCodes.MODEL_UNAVAILABLE
        ExecutionFailureCode.RUNTIME_FAILURE -> EngineErrorCodes.RUNTIME_FAILURE
        ExecutionFailureCode.INVALID_OUTPUT -> EngineErrorCodes.INVALID_OUTPUT
        ExecutionFailureCode.BACKPRESSURE_EXCEEDED -> EngineErrorCodes.BACKPRESSURE_EXCEEDED
        ExecutionFailureCode.INTERNAL -> EngineErrorCodes.INTERNAL
    }
    return EngineError(requestId, code, this.code.retryable, safeMessage(code))
}

private fun safeMessage(code: Int): String = when (code) {
    EngineErrorCodes.UNKNOWN_CAPABILITY -> "unknown capability"
    EngineErrorCodes.SCHEMA_VERSION_MISMATCH -> "unsupported capability schema"
    EngineErrorCodes.INVALID_REQUEST -> "invalid request"
    EngineErrorCodes.BUSY -> "engine busy"
    EngineErrorCodes.DEADLINE_EXCEEDED -> "deadline exceeded"
    EngineErrorCodes.CANCELLED -> "cancelled"
    EngineErrorCodes.SUPERSEDED -> "superseded"
    EngineErrorCodes.PREEMPTED -> "preempted"
    EngineErrorCodes.MODEL_UNAVAILABLE -> "model unavailable"
    EngineErrorCodes.RUNTIME_FAILURE -> "runtime failure"
    EngineErrorCodes.INVALID_OUTPUT -> "invalid model output"
    EngineErrorCodes.BACKPRESSURE_EXCEEDED -> "stream backpressure exceeded"
    else -> "internal failure"
}

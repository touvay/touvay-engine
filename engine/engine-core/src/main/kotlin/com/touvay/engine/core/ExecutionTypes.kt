package com.touvay.engine.core

import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.SessionConfig
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher

/** Authenticated identity of one live request. */
public data class RequestKey(
    public val principal: String,
    public val requestId: String,
)

/** Scheduling urgency after engine policy clamps the client hint. */
public enum class ExecutionPriority {
    INTERACTIVE,
    BACKGROUND,
}

/** Immutable transport limits negotiated for one response stream. */
public data class StreamLimits(
    public val initialDeltaCredits: Int,
    public val initialByteCredits: Long,
    public val maxDeltaCredits: Int,
    public val maxByteCredits: Long,
) {
    init {
        require(initialDeltaCredits in 1..maxDeltaCredits)
        require(initialByteCredits in 1..maxByteCredits)
        require(maxDeltaCredits in 1..MAX_DELTA_CREDITS)
        require(maxByteCredits in 1..MAX_BYTE_CREDITS)
    }

    public companion object {
        public const val MAX_DELTA_CREDITS: Int = 256
        public const val MAX_BYTE_CREDITS: Long = 4L * 1024L * 1024L

        public val DIAGNOSTIC_DEFAULT: StreamLimits = StreamLimits(
            initialDeltaCredits = 8,
            initialByteCredits = 256L * 1024L,
            maxDeltaCredits = 8,
            maxByteCredits = 256L * 1024L,
        )
    }
}

/** Validated metadata before Scheduler admission is committed. */
public class ExecutionContextCandidate(
    public val requestKey: RequestKey,
    public val capabilityId: String,
    public val schemaVersion: Int,
    public val priority: ExecutionPriority,
    public val coalesceIdentity: String?,
    public val receivedAtNanos: Long,
    public val deadlineAtNanos: Long,
    public val contractVersion: Int,
    negotiatedFeatures: Set<String>,
    public val payloadBytes: Int,
    public val bulkInputBytes: Long,
    public val policyGeneration: Long,
    public val correlationId: String,
    public val streamLimits: StreamLimits,
    public val ingressSequence: Long = 0,
) {
    public val negotiatedFeatures: Set<String> =
        Collections.unmodifiableSet(negotiatedFeatures.toSet())

    init {
        require(requestKey.principal.isNotBlank())
        require(requestKey.requestId.isNotBlank())
        require(capabilityId.isNotBlank())
        require(schemaVersion > 0)
        require(receivedAtNanos >= 0)
        require(deadlineAtNanos > receivedAtNanos)
        require(contractVersion > 0)
        require(payloadBytes >= 0)
        require(bulkInputBytes >= 0)
        require(policyGeneration >= 0)
        require(correlationId.isNotBlank())
        require(ingressSequence >= 0)
    }

    /** Creates the final immutable context after admission succeeds. */
    public fun admitted(admittedAtNanos: Long): ExecutionContext {
        require(admittedAtNanos in receivedAtNanos until deadlineAtNanos)
        return ExecutionContext(this, admittedAtNanos)
    }
}

/**
 * Immutable, content-free metadata shared by every layer for one admitted request.
 * Mutable lifecycle state and user content deliberately live elsewhere (ADR-017).
 */
public class ExecutionContext internal constructor(
    candidate: ExecutionContextCandidate,
    public val admittedAtNanos: Long,
) {
    public val requestKey: RequestKey = candidate.requestKey
    public val capabilityId: String = candidate.capabilityId
    public val schemaVersion: Int = candidate.schemaVersion
    public val priority: ExecutionPriority = candidate.priority
    public val coalesceIdentity: String? = candidate.coalesceIdentity
    public val receivedAtNanos: Long = candidate.receivedAtNanos
    public val deadlineAtNanos: Long = candidate.deadlineAtNanos
    public val contractVersion: Int = candidate.contractVersion
    public val negotiatedFeatures: Set<String> = candidate.negotiatedFeatures
    public val payloadBytes: Int = candidate.payloadBytes
    public val bulkInputBytes: Long = candidate.bulkInputBytes
    public val policyGeneration: Long = candidate.policyGeneration
    public val correlationId: String = candidate.correlationId
    public val streamLimits: StreamLimits = candidate.streamLimits
}

/** One transport-neutral submission. Payload bytes are defensively snapshotted. */
public class ExecutionRequest(
    public val requestId: String,
    public val principal: String,
    public val capabilityId: String,
    public val schemaVersion: Int,
    payload: ByteArray,
    public val priority: ExecutionPriority,
    public val coalesceKey: String?,
    public val timeoutMillis: Long,
    public val contractVersion: Int,
    negotiatedFeatures: Set<String>,
    public val policyGeneration: Long,
    public val streamLimits: StreamLimits,
) {
    internal val payloadSnapshot: ByteArray = payload.copyOf()
    public val negotiatedFeatures: Set<String> =
        Collections.unmodifiableSet(negotiatedFeatures.toSet())

    init {
        require(requestId.isNotBlank() && requestId.length <= 128)
        require(principal.isNotBlank() && principal.length <= 128)
        require(capabilityId.isNotBlank() && capabilityId.length <= 128)
        require(schemaVersion > 0)
        require(coalesceKey == null || coalesceKey.length <= 128)
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(contractVersion > 0)
        require(policyGeneration >= 0)
        require(payloadSnapshot.size <= MAX_INLINE_PAYLOAD_BYTES)
    }

    public companion object {
        public const val MAX_INLINE_PAYLOAD_BYTES: Int = 512 * 1024
        public const val MAX_TIMEOUT_MILLIS: Long = 10L * 60L * 1000L
    }
}

/** Conservative payload-free facts used for admission. */
public data class ExecutionDemand(
    public val fixedRamBytes: Long,
    public val kvBytes: Long,
    public val loadCost: Int,
) {
    init {
        require(fixedRamBytes >= 0)
        require(kvBytes >= 0)
        require(loadCost >= 0)
    }
}

/** Exact immutable model revision selected by routing. */
public data class ModelRevisionRef(
    public val packId: String,
    public val packVersion: String,
    public val manifestSha256: String,
)

/** Requested load-affecting profile; Model Manager canonicalizes it through the Registry. */
public data class ExecutionProfileSpec(
    public val threads: Int,
    public val useMmap: Boolean = true,
) {
    init {
        require(threads > 0)
    }
}

/** One immutable candidate inside an [ExecutionPlan]. */
public class ExecutionCandidate(
    public val id: String,
    public val modelRevision: ModelRevisionRef,
    public val profile: ExecutionProfileSpec,
    public val contextLength: Int,
    public val maxOutputTokens: Int,
    public val decodeQuantumTokens: Int,
    retryableFailures: Set<ExecutionFailureCode>,
) {
    public val retryableFailures: Set<ExecutionFailureCode> =
        Collections.unmodifiableSet(retryableFailures.toSet())

    init {
        require(id.isNotBlank() && id.length <= 128)
        require(contextLength > 0)
        require(maxOutputTokens > 0)
        require(decodeQuantumTokens in 1..maxOutputTokens)
    }
}

/** Immutable dispatch-time route. Attempt 2 consumes candidate 2 without rerouting. */
public class ExecutionPlan(candidates: List<ExecutionCandidate>) {
    public val candidates: List<ExecutionCandidate> =
        Collections.unmodifiableList(candidates.toList())

    init {
        require(this.candidates.size in 1..2)
        require(this.candidates.map { it.id }.distinct().size == this.candidates.size)
    }
}

/** Capability-specific preparation retained for the request lifetime. */
public interface PreparedExecution : AutoCloseable {
    public val demand: ExecutionDemand
    public fun newAttempt(candidate: ExecutionCandidate): AttemptProgram
}

/** Creates bounded prepared state without acquiring Runtime resources. */
public interface ExecutionProgramFactory {
    public val descriptor: CapabilityDescriptor
    public suspend fun prepare(
        context: ExecutionContextCandidate,
        payload: ByteArray,
    ): PreparedExecution
}

/** Routes prepared facts once, immediately before attempt 1. */
public fun interface ExecutionRouter {
    public suspend fun route(
        context: ExecutionContext,
        prepared: PreparedExecution,
    ): ExecutionPlan
}

/** Attempt-local capability semantics. A retry receives a new instance. */
public interface AttemptProgram : AutoCloseable {
    public fun prompt(): String
    public fun sessionConfig(model: ModelInstanceInfo): SessionConfig
    public fun decodeParams(maxTokens: Int): DecodeParams
    public suspend fun consume(tokens: List<GeneratedToken>): List<ByteArray>
    public suspend fun finish(): ByteArray
}

/** One generated token copied out of the non-blocking Runtime sink. */
public data class GeneratedToken(
    public val tokenId: Int,
    public val piece: String,
)

/** Runtime/model boundary owned by the composition adapter. */
public interface ExecutionModelProvider {
    public suspend fun acquire(
        context: ExecutionContext,
        candidate: ExecutionCandidate,
    ): ExecutionModelLease
}

/** A borrowed Model Manager instance plus its per-instance inference lane. */
public interface ExecutionModelLease : AutoCloseable {
    public val instance: ModelInstance
    public val inferenceDispatcher: CoroutineDispatcher
}

/** Publicly safe reason for an execution terminal failure. */
public enum class ExecutionFailureCode(public val retryable: Boolean) {
    UNKNOWN_CAPABILITY(false),
    SCHEMA_VERSION_MISMATCH(false),
    INVALID_REQUEST(false),
    DUPLICATE_REQUEST(false),
    BUSY(true),
    DEADLINE_EXCEEDED(true),
    CANCELLED(false),
    SUPERSEDED(false),
    PREEMPTED(true),
    MODEL_UNAVAILABLE(true),
    RUNTIME_FAILURE(true),
    INVALID_OUTPUT(false),
    BACKPRESSURE_EXCEEDED(true),
    INTERNAL(false),
}

public data class ExecutionFailure(
    public val code: ExecutionFailureCode,
    public val incidentId: String? = null,
)

/** First cancellation source wins and never changes. */
public enum class ExecutionCancellationReason {
    CLIENT_CANCELLED,
    SUPERSEDED,
    PREEMPTED,
    DEADLINE_EXCEEDED,
    CLIENT_GONE,
    ENGINE_SHUTDOWN,
    RESOURCE_PRESSURE,
    BACKPRESSURE_EXCEEDED,
}

/** Serialized observer for one request. Implementations must remain content-safe. */
public interface ExecutionObserver {
    public fun onAccepted(context: ExecutionContext)
    public fun onDelta(context: ExecutionContext, sequence: Int, payload: ByteArray)
    public fun onCompleted(context: ExecutionContext, payload: ByteArray, stats: ExecutionStats)
    public fun onFailed(requestKey: RequestKey, failure: ExecutionFailure)
}

/** Typed failure thrown by ports and attempt programs without leaking downstream text. */
public class ExecutionException(
    public val failureCode: ExecutionFailureCode,
) : RuntimeException(failureCode.name)

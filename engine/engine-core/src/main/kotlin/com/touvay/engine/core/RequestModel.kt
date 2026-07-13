package com.touvay.engine.core

/**
 * One request as seen by engine-core: transport-agnostic — the binder layer maps
 * envelopes to this, tests construct it directly.
 *
 * Not a `data` class: byte-array payloads make value semantics misleading.
 *
 * @property clientId opaque identity of the submitting client (the binder layer uses the
 *   calling uid); scopes coalescing so clients can never cancel each other's work.
 * @property coalesceKey optional; a newer job with the same (clientId, key) supersedes an
 *   older in-flight one (ARCHITECTURE.md §14.1).
 */
public class RequestJob(
    public val requestId: String,
    public val clientId: String,
    public val capabilityId: String,
    public val schemaVersion: Int,
    payload: ByteArray,
    public val coalesceKey: String? = null,
) {
    public val payload: ByteArray = payload.copyOf()
}

/**
 * Receives the results of one request. Exactly one terminal callback ([onCompleted] or
 * [onFailed]) fires per submitted job — guaranteed even when the job is cancelled before
 * it starts. [onAccepted] fires only after validation succeeds; pre-validation failures
 * have no accepted callback. [onDelta] calls are sequential per request.
 *
 * Called on engine worker threads: implementations must be fast and must not block.
 */
public interface RequestListener {
    public fun onAccepted(requestId: String)
    public suspend fun onDelta(requestId: String, sequence: Int, payload: ByteArray)
    public fun onCompleted(requestId: String, payload: ByteArray, stats: ExecutionStats)
    public fun onFailed(requestId: String, failure: RequestFailure)
}

/** Why a request failed. Maps 1:1 onto contract error codes at the binder layer. */
public sealed class RequestFailure {
    public data class UnknownCapability(val capabilityId: String) : RequestFailure()

    public data class SchemaVersionMismatch(
        val capabilityId: String,
        val requested: Int,
        val supported: Int,
    ) : RequestFailure()

    /** @property superseded true when a newer request with the same coalesce key won. */
    public data class Cancelled(val superseded: Boolean) : RequestFailure()

    /** @property message static and content-free; never derived from downstream exceptions. */
    public data class Internal(val message: String) : RequestFailure()
}

/** Timing facts about a completed request (ARCHITECTURE.md §17 metrics). */
public data class ExecutionStats(
    /** Millis from acceptance to first delta; equals [totalMillis] when there were none. */
    public val ttftMillis: Long,
    public val totalMillis: Long,
    public val deltaCount: Int,
)

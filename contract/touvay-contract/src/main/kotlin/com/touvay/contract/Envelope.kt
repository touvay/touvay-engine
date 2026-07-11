package com.touvay.contract

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One capability request travelling client → engine.
 *
 * The envelope is stable AIDL surface; [payload] is a versioned protobuf message whose
 * schema is selected by ([capabilityId], [schemaVersion]) — ARCHITECTURE.md ADR-003.
 * Payload carriers are deliberately not `data` classes: byte-array payloads make value
 * semantics misleading and equality on them is never needed in production code.
 *
 * @property requestId client-generated, unique per connection; echoed on every callback.
 * @property priority one of [RequestPriorities].
 * @property coalesceKey optional; a new request with the same key from the same client
 *   supersedes an older in-flight one, which fails with [EngineErrorCodes.SUPERSEDED].
 */
@Parcelize
public class RequestEnvelope(
    public val requestId: String,
    public val capabilityId: String,
    public val schemaVersion: Int,
    public val payload: ByteArray,
    public val priority: Int,
    public val coalesceKey: String?,
) : Parcelable

/** One streamed chunk of a response. [sequence] starts at 0 and increments by 1. */
@Parcelize
public class ResponseDelta(
    public val requestId: String,
    public val sequence: Int,
    public val payload: ByteArray,
) : Parcelable

/** Terminal success message for a request. */
@Parcelize
public class ResponseFinal(
    public val requestId: String,
    public val payload: ByteArray,
    public val stats: RequestStats,
) : Parcelable

/**
 * Timing facts about a completed request. Deliberately excludes model identity
 * (ARCHITECTURE.md §9): clients get timings, never coupling to what ran.
 */
@Parcelize
public data class RequestStats(
    /** Milliseconds from acceptance to first delta (or to completion if no deltas). */
    public val ttftMillis: Long,
    /** Milliseconds from acceptance to the terminal callback. */
    public val totalMillis: Long,
    public val deltaCount: Int,
) : Parcelable

/**
 * Terminal failure message for a request.
 *
 * @property code one of [EngineErrorCodes].
 * @property retryable true when the same request may succeed if resubmitted (e.g. BUSY).
 * @property message developer-facing description. MUST never contain user content
 *   (ARCHITECTURE.md §16) — it may be logged by clients.
 */
@Parcelize
public data class EngineError(
    public val requestId: String,
    public val code: Int,
    public val retryable: Boolean,
    public val message: String,
) : Parcelable

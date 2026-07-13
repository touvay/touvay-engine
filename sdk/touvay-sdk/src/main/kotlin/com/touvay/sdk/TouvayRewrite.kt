package com.touvay.sdk

import kotlinx.coroutines.flow.Flow

/** Stable SDK identity of the structured Rewrite capability. */
public object RewriteCapability {
    public val id: CapabilityId = CapabilityId("text.rewrite")
    public const val schemaVersion: Int = 1
}

/** Requested Rewrite tone. */
public enum class RewriteTone {
    NEUTRAL,
    FORMAL,
    CASUAL,
}

/** Requested Rewrite length relationship to the source. */
public enum class RewriteLength {
    PRESERVE,
    SHORTER,
    LONGER,
}

/** Capability-owned meaning of the authoritative final text. */
public enum class RewriteDisposition {
    REWRITTEN,
    UNCHANGED,
}

/** Structured input to `text.rewrite@1`. */
public data class RewriteRequest(
    public val text: String,
    public val tone: RewriteTone = RewriteTone.NEUTRAL,
    public val length: RewriteLength = RewriteLength.PRESERVE,
    public val outputLocaleBcp47: String? = null,
)

/** Content-free timing facts returned by the engine. */
public data class RewriteTiming(
    public val timeToFirstTokenMillis: Long,
    public val totalMillis: Long,
    public val deltaCount: Int,
)

/** Authoritative structured Rewrite result. */
public data class RewriteResult(
    public val text: String,
    public val disposition: RewriteDisposition,
    public val timing: RewriteTiming,
)

/** Ordered Rewrite stream. The terminal [Completed] result replaces provisional text. */
public sealed interface RewriteEvent {
    /** Append-only provisional text. */
    public data class Delta(
        public val sequence: Int,
        public val text: String,
    ) : RewriteEvent

    /** Exactly one authoritative terminal result. */
    public data class Completed(public val result: RewriteResult) : RewriteEvent
}

/** Typed capability facade; model, prompt, Runtime, and routing details remain engine-owned. */
public interface TouvayRewrite {
    /** Executes Rewrite and returns the authoritative structured result. */
    public suspend fun execute(request: RewriteRequest): RewriteResult

    /** Streams provisional deltas followed by one authoritative [RewriteEvent.Completed]. */
    public fun stream(request: RewriteRequest): Flow<RewriteEvent>
}

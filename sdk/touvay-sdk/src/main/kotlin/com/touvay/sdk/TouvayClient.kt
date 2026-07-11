package com.touvay.sdk

import kotlinx.coroutines.flow.Flow

/**
 * A connection to the Touvay Engine. Obtain via [Touvay.connect]; release with [close].
 *
 * All operations are asynchronous and main-safe. The client does not auto-reconnect: when
 * the engine process dies, in-flight work fails with [TouvayException.EngineDisconnected]
 * (retryable) and the app reconnects with [Touvay.connect] — the SDK never silently
 * resubmits generative work (ARCHITECTURE.md §11.4).
 */
public interface TouvayClient : AutoCloseable {

    /**
     * The capabilities this engine build knows, with their availability on this device.
     * Feature-detect on this map — never on SDK or engine versions (ARCHITECTURE.md §9).
     */
    public suspend fun capabilities(): Map<CapabilityId, CapabilityStatus>

    /** Diagnostic operations; free (no model is loaded). */
    public fun diagnostics(): TouvayDiagnostics

    /** Idempotent. In-flight requests fail with [TouvayException.ClientClosed]. */
    override fun close()
}

/** Namespaced capability identifier, e.g. `CapabilityId("text.rewrite")`. */
@JvmInline
public value class CapabilityId(public val value: String)

/** Availability of one capability on this device (ARCHITECTURE.md §9). */
public sealed interface CapabilityStatus {
    public data object Ready : CapabilityStatus

    /** @property approxBytes download size when known; null when the engine didn't say. */
    public data class DownloadRequired(public val approxBytes: Long?) : CapabilityStatus

    /** @property reason developer-facing explanation when known. */
    public data class DeviceNotSupported(public val reason: String?) : CapabilityStatus

    public data object DisabledByPolicy : CapabilityStatus

    /**
     * A status code this SDK version doesn't know — reported by a newer engine. Treat as
     * unavailable; upgrading the SDK gives it a name.
     */
    public data class Unknown(public val statusCode: Int) : CapabilityStatus
}

/**
 * Permanent diagnostic surface backed by the engine's `dev.echo` capability. Exercises
 * the full request path — binding, routing, streaming, cancellation — without loading a
 * model. Intended for integration tests and health checks, not product features.
 */
public interface TouvayDiagnostics {

    /** Echoes [text] back through the engine. */
    public suspend fun echo(text: String): String

    /**
     * Echoes [text] back as a stream of [chunks] pieces, [interChunkDelayMillis] apart.
     * The artificial delay exists so cancellation behavior is deterministically testable.
     * Cancelling collection cancels the engine-side request.
     */
    public fun echoStream(
        text: String,
        chunks: Int = 4,
        interChunkDelayMillis: Long = 0L,
    ): Flow<String>
}

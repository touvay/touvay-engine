package com.touvay.sdk

/**
 * Failures surfaced by the Touvay SDK. Sealed so callers can handle exhaustively;
 * evolves additively (new subclasses may appear — always keep an `else`/default branch).
 *
 * Cancellation initiated by the caller surfaces as [kotlinx.coroutines.CancellationException],
 * following coroutine convention — not as a [TouvayException].
 */
public sealed class TouvayException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** No engine to bind: the host app doesn't embed one (and no standalone engine exists). */
    public class EngineUnavailable(
        message: String,
        cause: Throwable? = null,
    ) : TouvayException(message, cause)

    /** Contract version windows don't overlap; SDK or engine needs updating. */
    public class EngineIncompatible(message: String) : TouvayException(message)

    /** The engine process died. Retryable: reconnect with [Touvay.connect]. */
    public class EngineDisconnected(
        cause: Throwable? = null,
    ) : TouvayException("engine connection lost; reconnect with Touvay.connect()", cause)

    /** The capability is unknown to this engine or unavailable on this device. */
    public class CapabilityUnavailable(
        public val capabilityId: String,
        message: String,
    ) : TouvayException(message)

    /** Structured capability input failed validation and must be corrected before retrying. */
    public class InvalidRequest(message: String) : TouvayException(message)

    /** The engine cancelled the request (e.g. engine shutdown mid-request). */
    public class RequestCancelled : TouvayException("request cancelled by the engine")

    /** A newer request with the same coalesce key replaced this one. Expected in type-then-retry flows. */
    public class RequestSuperseded :
        TouvayException("request superseded by a newer request with the same coalesce key")

    /** This client was [TouvayClient.close]d. */
    public class ClientClosed : TouvayException("client is closed")

    /**
     * Any other engine-reported failure.
     *
     * @property code contract error code; stable across releases.
     * @property retryable whether resubmitting the same request may succeed.
     */
    public class EngineFailure(
        public val code: Int,
        public val retryable: Boolean,
        message: String,
    ) : TouvayException(message)
}

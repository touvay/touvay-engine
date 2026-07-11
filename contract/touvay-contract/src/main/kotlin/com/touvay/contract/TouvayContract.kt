package com.touvay.contract

/**
 * Constants of the Touvay Engine binder contract.
 *
 * The contract evolves additively only (ARCHITECTURE.md ADR-009): existing constants never
 * change meaning; new codes and capabilities are appended.
 */
public object TouvayContract {
    /** Contract version implemented by this artifact. */
    public const val CONTRACT_VERSION: Int = 1

    /** Oldest contract version this artifact can interoperate with. */
    public const val MIN_SUPPORTED_CONTRACT_VERSION: Int = 1

    /**
     * Intent action for binding the engine service. In the embedded phase the SDK binds
     * this action within its own package; engine-app discovery is a future, additive step.
     */
    public const val ACTION_BIND_ENGINE: String = "com.touvay.engine.action.BIND"

    /**
     * Permanent diagnostic capability: echoes text back, optionally chunked/delayed.
     * Exercises the unary and streaming request shapes without loading any model.
     * Payload schemas: [com.touvay.contract.proto.EchoRequest] et al., schema version 1.
     */
    public const val CAPABILITY_DIAGNOSTICS_ECHO: String = "dev.echo"
}

/** Status codes carried by [CapabilityInfo.statusCode]. */
public object CapabilityStatusCodes {
    public const val READY: Int = 0
    public const val DOWNLOAD_REQUIRED: Int = 1
    public const val DEVICE_NOT_SUPPORTED: Int = 2
    public const val DISABLED_BY_POLICY: Int = 3
}

/** Error codes carried by [EngineError.code]. */
public object EngineErrorCodes {
    public const val UNKNOWN_CAPABILITY: Int = 1
    public const val SCHEMA_VERSION_MISMATCH: Int = 2

    /** Cancelled at the client's request. */
    public const val CANCELLED: Int = 3

    /** Cancelled because a newer request with the same coalesce key superseded it. */
    public const val SUPERSEDED: Int = 4
    public const val BUSY: Int = 5
    public const val INPUT_TOO_LARGE: Int = 6
    public const val INTERNAL: Int = 7
}

/** Scheduling classes carried by [RequestEnvelope.priority] (ARCHITECTURE.md §14.1). */
public object RequestPriorities {
    /** A user is actively waiting on the result. */
    public const val INTERACTIVE: Int = 0

    /** Deferred work; shed first under load or thermal pressure. */
    public const val BACKGROUND: Int = 1
}

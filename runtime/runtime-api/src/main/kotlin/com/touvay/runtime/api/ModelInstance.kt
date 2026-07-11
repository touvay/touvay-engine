package com.touvay.runtime.api

import java.nio.file.Path

/**
 * A loaded model. Owned and refcounted by the model manager; capability pipelines only
 * ever borrow sessions from it. Closing releases all memory, including native allocations
 * — verified by the runtime conformance kit.
 */
public interface ModelInstance : AutoCloseable {
    public val info: ModelInstanceInfo

    /** Creates an inference session. Sessions are single-owner and must be closed. */
    public fun createSession(config: SessionConfig): InferenceSession
}

/** Resource facts the budget manager accounts for (ARCHITECTURE.md §14.3). */
public data class ModelInstanceInfo(
    /** Estimated resident footprint of weights + fixed buffers, excluding KV cache. */
    val estimatedRamBytes: Long,
    val maxContextLength: Int,
)

/**
 * A model pack already installed and integrity-verified by the model manager. Runtimes
 * receive resolved file paths only — pack discovery, signatures, and digests are the
 * model manager's job (ARCHITECTURE.md §13), never the runtime's.
 */
public data class ResolvedModelPack(
    val id: String,
    val version: String,
    /** Logical file name from the pack manifest → absolute path on disk. */
    val files: Map<String, Path>,
)

/** How to load a model. */
public data class LoadConfig(
    /** Decode thread count; the engine derives it from big-core topology (§14.2). */
    val threads: Int,
    /** Memory-map weights read-only where supported — evictable page cache (§14.3). */
    val useMmap: Boolean = true,
)

/** How to configure one session. */
public data class SessionConfig(
    /** Context length cap for this session; tier policy, enforced by the budget manager. */
    val contextLength: Int,
)

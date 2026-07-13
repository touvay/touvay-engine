package com.touvay.engine.models

import java.io.InputStream

internal enum class ModelPackSourceEntryKind {
    REGULAR_FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
    OTHER,
}

internal class ModelPackSourceEntry(
    val logicalPath: String,
    val kind: ModelPackSourceEntryKind,
    val linkCount: Int = 1,
)

/** A local, reopenable source. Implementations must not perform network I/O. */
internal interface ModelPackSource {
    fun openManifest(): InputStream

    fun openSignatureEnvelope(): InputStream

    fun entries(): List<ModelPackSourceEntry>

    fun open(logicalPath: String): InputStream
}

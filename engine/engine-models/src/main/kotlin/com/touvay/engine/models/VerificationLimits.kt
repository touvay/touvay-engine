package com.touvay.engine.models

internal data class VerificationLimits(
    val maxManifestBytes: Int = 1024 * 1024,
    val maxSignatureEnvelopeBytes: Int = 4 * 1024,
    val maxFiles: Int = 256,
    val maxCapabilities: Int = 128,
    val maxLogicalPathUtf8Bytes: Int = 240,
    val maxIdentifierUtf8Bytes: Int = 128,
    val maxTotalDeclaredFileBytes: Long = 8L * 1024 * 1024 * 1024,
    val protobufRecursionLimit: Int = 32,
) {
    init {
        require(maxManifestBytes in 1..(16 * 1024 * 1024))
        require(maxSignatureEnvelopeBytes >= SignatureEnvelopeCodec.MIN_ENVELOPE_BYTES)
        require(maxFiles > 0)
        require(maxCapabilities > 0)
        require(maxLogicalPathUtf8Bytes > 0)
        require(maxIdentifierUtf8Bytes in 1..1024)
        require(maxTotalDeclaredFileBytes >= 0)
        require(protobufRecursionLimit in 1..100)
    }
}

package com.touvay.engine.models

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class SignatureEnvelope(
    val keyId: String,
    val signature: ByteString,
)

internal class SignatureEnvelopeCodec(
    private val limits: VerificationLimits = VerificationLimits(),
) {
    fun decode(bytes: ByteArray): SignatureEnvelope {
        if (bytes.size > limits.maxSignatureEnvelopeBytes) {
            verificationFailure(VerificationFailure.SIGNATURE_ENVELOPE_TOO_LARGE)
        }
        if (bytes.size < MIN_ENVELOPE_BYTES) {
            verificationFailure(VerificationFailure.MALFORMED_SIGNATURE_ENVELOPE)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) {
            verificationFailure(VerificationFailure.MALFORMED_SIGNATURE_ENVELOPE)
        }
        val version = buffer.get().toInt() and 0xff
        val algorithm = buffer.get().toInt() and 0xff
        if (version != ENVELOPE_VERSION || algorithm != ALGORITHM_ED25519) {
            verificationFailure(VerificationFailure.UNSUPPORTED_SIGNATURE_ENVELOPE)
        }
        val keyIdLength = buffer.short.toInt() and 0xffff
        if (keyIdLength !in 1..MAX_KEY_ID_BYTES ||
            buffer.remaining() != keyIdLength + ED25519_SIGNATURE_BYTES
        ) {
            verificationFailure(VerificationFailure.MALFORMED_SIGNATURE_ENVELOPE)
        }
        val keyIdBytes = ByteArray(keyIdLength)
        buffer.get(keyIdBytes)
        val keyId = decodeUtf8(keyIdBytes)
        if (!Identifiers.isSigningKey(keyId)) {
            verificationFailure(VerificationFailure.MALFORMED_SIGNATURE_ENVELOPE)
        }
        val signature = ByteArray(ED25519_SIGNATURE_BYTES)
        buffer.get(signature)
        return SignatureEnvelope(keyId, ByteString.copyFrom(signature))
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        verificationFailure(VerificationFailure.MALFORMED_SIGNATURE_ENVELOPE)
    }

    companion object {
        private val MAGIC: ByteArray = byteArrayOf(
            'T'.code.toByte(),
            'V'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            'S'.code.toByte(),
            'I'.code.toByte(),
            'G'.code.toByte(),
            0,
        )
        const val ENVELOPE_VERSION: Int = 1
        const val ALGORITHM_ED25519: Int = 1
        const val MAX_KEY_ID_BYTES: Int = 64
        const val ED25519_SIGNATURE_BYTES: Int = 64
        const val MIN_ENVELOPE_BYTES: Int = 8 + 1 + 1 + 2 + 1 + ED25519_SIGNATURE_BYTES
    }
}

package com.touvay.engine.models

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class SignatureEnvelopeCodecTest {
    private val codec = SignatureEnvelopeCodec()
    private val signature = ByteArray(64) { it.toByte() }

    @Test
    fun decodesV1EnvelopeExactly() {
        val decoded = codec.decode(TestFixtures.envelope(TestFixtures.KEY_ID, signature))

        assertEquals(TestFixtures.KEY_ID, decoded.keyId)
        assertContentEquals(signature, decoded.signature.toByteArray())
    }

    @Test
    fun rejectsBadMagicVersionAndAlgorithm() {
        val valid = TestFixtures.envelope(TestFixtures.KEY_ID, signature)
        assertEnvelopeFailure(valid.copyOf().also { it[0] = 0 })
        assertVerificationFailure(VerificationFailure.UNSUPPORTED_SIGNATURE_ENVELOPE) {
            codec.decode(valid.copyOf().also { it[8] = 2 })
        }
        assertVerificationFailure(VerificationFailure.UNSUPPORTED_SIGNATURE_ENVELOPE) {
            codec.decode(valid.copyOf().also { it[9] = 2 })
        }
    }

    @Test
    fun rejectsInvalidLengthTrailingBytesAndInvalidUtf8() {
        val valid = TestFixtures.envelope(TestFixtures.KEY_ID, signature)
        assertEnvelopeFailure(valid.copyOf(valid.size - 1))
        assertEnvelopeFailure(valid + 0)
        assertEnvelopeFailure(valid.copyOf().also {
            it[10] = 0
            it[11] = 65
        })
        assertEnvelopeFailure(valid.copyOf().also { it[12] = 0xff.toByte() })
    }

    @Test
    fun rejectsInvalidKeyIdAndOversizeEnvelope() {
        assertEnvelopeFailure(TestFixtures.envelope("Uppercase", signature))
        assertEnvelopeFailure(TestFixtures.envelope("bad/key", signature))
        assertVerificationFailure(VerificationFailure.SIGNATURE_ENVELOPE_TOO_LARGE) {
            codec.decode(ByteArray(4097))
        }
    }

    private fun assertEnvelopeFailure(bytes: ByteArray) {
        assertVerificationFailure(VerificationFailure.MALFORMED_SIGNATURE_ENVELOPE) {
            codec.decode(bytes)
        }
    }
}

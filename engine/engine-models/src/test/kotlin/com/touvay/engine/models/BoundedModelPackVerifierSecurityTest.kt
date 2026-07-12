package com.touvay.engine.models

import com.google.protobuf.ByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class BoundedModelPackVerifierSecurityTest {
    @Test
    fun rejectsManifestAndSignatureTampering() {
        val manifest = TestFixtures.manifest().toByteArray()
        val envelope = TestFixtures.envelope(TestFixtures.KEY_ID, TestFixtures.sign(manifest))
        val verifier = BoundedModelPackVerifier(TestFixtures.trustStore())

        val structurallyValidTamper = manifest + byteArrayOf(0xa0.toByte(), 0x06, 0x01)
        assertVerificationFailure(VerificationFailure.INVALID_SIGNATURE) {
            verifier.verify(structurallyValidTamper, envelope, TestFixtures.environment())
        }
        assertVerificationFailure(VerificationFailure.INVALID_SIGNATURE) {
            verifier.verify(
                manifest,
                envelope.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
                TestFixtures.environment(),
            )
        }
    }

    @Test
    fun rejectsWrongDomainSignature() {
        val manifest = TestFixtures.manifest().toByteArray()
        val signatureOverRawManifest = TestFixtures.signMessage(manifest)
        val envelope = TestFixtures.envelope(TestFixtures.KEY_ID, signatureOverRawManifest)

        assertVerificationFailure(VerificationFailure.INVALID_SIGNATURE) {
            BoundedModelPackVerifier(TestFixtures.trustStore()).verify(
                manifest,
                envelope,
                TestFixtures.environment(),
            )
        }
    }

    @Test
    fun rejectsMismatchedUnknownAndRevokedKeysInOrder() {
        val manifest = TestFixtures.manifest().toByteArray()
        val signature = TestFixtures.sign(manifest)
        assertVerificationFailure(VerificationFailure.KEY_ID_MISMATCH) {
            BoundedModelPackVerifier(TestFixtures.trustStore()).verify(
                manifest,
                TestFixtures.envelope("other.test", signature),
                TestFixtures.environment(),
            )
        }

        val unknownManifest = TestFixtures.manifest("unknown.test").toByteArray()
        assertVerificationFailure(VerificationFailure.UNKNOWN_SIGNING_KEY) {
            BoundedModelPackVerifier(TestFixtures.trustStore()).verify(
                unknownManifest,
                TestFixtures.envelope("unknown.test", TestFixtures.sign(unknownManifest)),
                TestFixtures.environment(),
            )
        }

        assertVerificationFailure(VerificationFailure.REVOKED_SIGNING_KEY) {
            BoundedModelPackVerifier(
                TestFixtures.trustStore(SigningKeyStatus.REVOKED),
            ).verify(
                manifest,
                TestFixtures.envelope(TestFixtures.KEY_ID, signature),
                TestFixtures.environment(),
            )
        }
    }

    @Test
    fun supportsExplicitUserApprovedRawKeyFingerprint() {
        val keyId = sha256Hex(TestFixtures.PUBLIC_KEY)
        val manifest = TestFixtures.manifest(keyId).toByteArray()
        val store = ImmutableModelPackTrustStore(
            listOf(
                TrustedSigningKey(
                    keyId,
                    ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
                    SigningKeyTrust.USER_APPROVED,
                ),
            ),
        )

        val verified = BoundedModelPackVerifier(store).verify(
            manifest,
            TestFixtures.envelope(keyId, TestFixtures.sign(manifest)),
            TestFixtures.environment(),
        )
        assertEquals(SigningKeyTrust.USER_APPROVED, verified.trust)
    }

    @Test
    fun signatureVerificationPrecedesCompatibilityAndReceivesExactDomainMessage() {
        val manifest = TestFixtures.manifest().toByteArray()
        var captured: ByteArray? = null
        val rejectingVerifier = PackSignatureVerifier { _, message, _ ->
            captured = message.copyOf()
            false
        }
        val failure = assertVerificationFailure(VerificationFailure.INVALID_SIGNATURE) {
            BoundedModelPackVerifier(
                TestFixtures.trustStore(),
                rejectingVerifier,
            ).verify(
                manifest,
                TestFixtures.envelope(TestFixtures.KEY_ID, ByteArray(64)),
                TestFixtures.environment(engineVersion = "9.0.0"),
            )
        }

        assertContentEquals(ModelPackSignature.message(manifest), captured)
        assertFalse(failure.message.orEmpty().contains("touvay.pack", ignoreCase = true))
    }

    @Test
    fun failureMessagesNeverEchoManifestContent() {
        val sentinel = "privatepromptsentinel"
        val manifest = TestFixtures.manifest().toBuilder().setPackId(sentinel).build().toByteArray()
        val failure = assertVerificationFailure(VerificationFailure.INCOMPATIBLE_ENGINE) {
            BoundedModelPackVerifier(TestFixtures.trustStore()).verify(
                manifest,
                TestFixtures.envelope(TestFixtures.KEY_ID, TestFixtures.sign(manifest)),
                TestFixtures.environment(engineVersion = "9.0.0"),
            )
        }
        assertFalse(failure.message.orEmpty().contains(sentinel))
        assertEquals(null, failure.cause)
    }

    @Test
    fun preservesUnknownOptionalFieldsBecauseExactBytesAreSigned() {
        val manifest = TestFixtures.manifest().toByteArray() +
            byteArrayOf(0xa0.toByte(), 0x06, 0x01)
        val verified = BoundedModelPackVerifier(TestFixtures.trustStore()).verify(
            manifest,
            TestFixtures.envelope(TestFixtures.KEY_ID, TestFixtures.sign(manifest)),
            TestFixtures.environment(),
        )
        assertEquals(TestFixtures.KEY_ID, verified.signingKeyId)
    }
}

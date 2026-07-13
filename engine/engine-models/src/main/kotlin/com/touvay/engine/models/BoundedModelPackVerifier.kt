package com.touvay.engine.models

import com.touvay.engine.models.proto.ModelPackManifest

internal class VerifiedManifest(
    val manifest: ModelPackManifest,
    val manifestSha256: String,
    val signingKeyId: String,
    val trust: SigningKeyTrust,
)

internal class BoundedModelPackVerifier(
    private val trustStore: ModelPackTrustStore,
    private val signatureVerifier: PackSignatureVerifier = TinkEd25519SignatureVerifier(),
    limits: VerificationLimits = VerificationLimits(),
    private val compatibilityVerifier: CompatibilityVerifier = CompatibilityVerifier(),
) {
    private val manifestParser = ManifestParser(limits)
    private val signatureEnvelopeCodec = SignatureEnvelopeCodec(limits)

    fun verify(
        manifestBytes: ByteArray,
        signatureEnvelopeBytes: ByteArray,
        environment: CompatibilityEnvironment,
    ): VerifiedManifest {
        val verified = verifyAuthenticity(manifestBytes, signatureEnvelopeBytes)
        compatibilityVerifier.verify(verified.manifest, environment)
        return verified
    }

    fun verifyAuthenticity(
        manifestBytes: ByteArray,
        signatureEnvelopeBytes: ByteArray,
    ): VerifiedManifest {
        val manifest = manifestParser.parse(manifestBytes)
        val envelope = signatureEnvelopeCodec.decode(signatureEnvelopeBytes)
        if (manifest.signingKeyId != envelope.keyId) {
            verificationFailure(VerificationFailure.KEY_ID_MISMATCH)
        }
        val signingKey = trustStore.resolve(envelope.keyId)
            ?: verificationFailure(VerificationFailure.UNKNOWN_SIGNING_KEY)
        if (signingKey.status == SigningKeyStatus.REVOKED) {
            verificationFailure(VerificationFailure.REVOKED_SIGNING_KEY)
        }
        val message = ModelPackSignature.message(manifestBytes)
        if (!signatureVerifier.verify(signingKey.publicKey, message, envelope.signature)) {
            verificationFailure(VerificationFailure.INVALID_SIGNATURE)
        }
        return VerifiedManifest(
            manifest = manifest,
            manifestSha256 = sha256Hex(manifestBytes),
            signingKeyId = signingKey.keyId,
            trust = signingKey.trust,
        )
    }
}

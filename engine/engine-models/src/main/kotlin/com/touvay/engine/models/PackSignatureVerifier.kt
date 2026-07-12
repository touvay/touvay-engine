package com.touvay.engine.models

import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.protobuf.ByteString
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

internal fun interface PackSignatureVerifier {
    fun verify(publicKey: ByteString, message: ByteArray, signature: ByteString): Boolean
}

/**
 * One pure-Java Tink path on every supported Android version.
 *
 * The raw-key constructor intentionally selects Tink's PureJavaImpl instead of probing
 * Android's API-33-only JCA Ed25519 provider. Keeping Tink behind this port contains its
 * subtle API and makes a pin upgrade a focused dependency/security review.
 */
internal class TinkEd25519SignatureVerifier : PackSignatureVerifier {
    override fun verify(
        publicKey: ByteString,
        message: ByteArray,
        signature: ByteString,
    ): Boolean = try {
        Ed25519Verify(publicKey.toByteArray()).verify(signature.toByteArray(), message)
        true
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

internal object ModelPackSignature {
    private val domainSeparator: ByteArray =
        "TOUVAY_MODEL_PACK_V1\u0000".toByteArray(StandardCharsets.UTF_8)

    fun message(manifestBytes: ByteArray): ByteArray =
        ByteArray(domainSeparator.size + manifestBytes.size).also { result ->
            domainSeparator.copyInto(result)
            manifestBytes.copyInto(result, destinationOffset = domainSeparator.size)
        }
}

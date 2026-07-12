package com.touvay.engine.models

import com.google.protobuf.ByteString
import java.security.MessageDigest

internal enum class SigningKeyTrust {
    ENGINE_PINNED,
    USER_APPROVED,
}

internal enum class SigningKeyStatus {
    ACTIVE,
    REVOKED,
}

internal class TrustedSigningKey(
    val keyId: String,
    val publicKey: ByteString,
    val trust: SigningKeyTrust,
    val status: SigningKeyStatus = SigningKeyStatus.ACTIVE,
) {
    init {
        require(Identifiers.isSigningKey(keyId)) { "invalid signing key configuration" }
        require(publicKey.size() == ED25519_PUBLIC_KEY_BYTES) {
            "invalid signing key configuration"
        }
        if (trust == SigningKeyTrust.USER_APPROVED) {
            require(keyId == sha256Hex(publicKey.toByteArray())) {
                "invalid user-approved signing key configuration"
            }
        }
    }

    companion object {
        const val ED25519_PUBLIC_KEY_BYTES: Int = 32
    }
}

internal fun interface ModelPackTrustStore {
    fun resolve(keyId: String): TrustedSigningKey?
}

internal class ImmutableModelPackTrustStore(keys: Collection<TrustedSigningKey>) :
    ModelPackTrustStore {
    private val byId: Map<String, TrustedSigningKey>

    init {
        val duplicates = keys.groupingBy { it.keyId }.eachCount().values.any { it > 1 }
        require(!duplicates) { "duplicate signing key configuration" }
        byId = keys.associateBy { it.keyId }
    }

    override fun resolve(keyId: String): TrustedSigningKey? = byId[keyId]
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

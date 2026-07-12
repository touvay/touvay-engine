package com.touvay.engine.models

import com.google.protobuf.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class TrustStoreTest {
    @Test
    fun resolvesPinnedAndRevokedKeysWithoutMutation() {
        val active = TrustedSigningKey(
            TestFixtures.KEY_ID,
            ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
            SigningKeyTrust.ENGINE_PINNED,
        )
        val revoked = TrustedSigningKey(
            "release.revoked",
            ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
            SigningKeyTrust.ENGINE_PINNED,
            SigningKeyStatus.REVOKED,
        )
        val store = ImmutableModelPackTrustStore(listOf(active, revoked))

        assertEquals(SigningKeyStatus.ACTIVE, store.resolve(TestFixtures.KEY_ID)?.status)
        assertEquals(SigningKeyStatus.REVOKED, store.resolve("release.revoked")?.status)
        assertNull(store.resolve("release.unknown"))
    }

    @Test
    fun userApprovedKeyIdMustBeRawKeyFingerprint() {
        val fingerprint = sha256Hex(TestFixtures.PUBLIC_KEY)
        val key = TrustedSigningKey(
            fingerprint,
            ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
            SigningKeyTrust.USER_APPROVED,
        )
        assertEquals(fingerprint, key.keyId)

        assertFailsWith<IllegalArgumentException> {
            TrustedSigningKey(
                TestFixtures.KEY_ID,
                ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
                SigningKeyTrust.USER_APPROVED,
            )
        }
    }

    @Test
    fun rejectsDuplicateIdsAndWrongKeyLength() {
        val key = TrustedSigningKey(
            TestFixtures.KEY_ID,
            ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
            SigningKeyTrust.ENGINE_PINNED,
        )
        assertFailsWith<IllegalArgumentException> {
            ImmutableModelPackTrustStore(listOf(key, key))
        }
        assertFailsWith<IllegalArgumentException> {
            TrustedSigningKey(
                TestFixtures.KEY_ID,
                ByteString.copyFrom(ByteArray(31)),
                SigningKeyTrust.ENGINE_PINNED,
            )
        }
    }
}

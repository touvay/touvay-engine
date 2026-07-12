package com.touvay.engine.models

import com.google.protobuf.ByteString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TinkEd25519SignatureVerifierTest {
    private val verifier = TinkEd25519SignatureVerifier()

    @Test
    fun verifiesRfc8032TestVectorOne() {
        val publicKey = ByteString.copyFrom(
            TestFixtures.hex(
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            ),
        )
        val signature = ByteString.copyFrom(
            TestFixtures.hex(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
        )

        assertTrue(verifier.verify(publicKey, ByteArray(0), signature))
        assertFalse(
            verifier.verify(
                publicKey,
                byteArrayOf(1),
                signature,
            ),
        )
    }

    @Test
    fun rejectsMalformedRawKeysAndSignaturesWithoutThrowing() {
        assertFalse(
            verifier.verify(
                ByteString.copyFrom(ByteArray(31)),
                byteArrayOf(1),
                ByteString.copyFrom(ByteArray(64)),
            ),
        )
        assertFalse(
            verifier.verify(
                ByteString.copyFrom(TestFixtures.PUBLIC_KEY),
                byteArrayOf(1),
                ByteString.copyFrom(ByteArray(63)),
            ),
        )
    }
}

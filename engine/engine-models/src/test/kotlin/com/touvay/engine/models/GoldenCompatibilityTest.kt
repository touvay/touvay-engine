package com.touvay.engine.models

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class GoldenCompatibilityTest {
    @Test
    fun v1GoldenEnvelopeVerifiesAndParses() {
        val manifestBytes = Base64.getDecoder().decode(GOLDEN_MANIFEST)
        val envelopeBytes = Base64.getDecoder().decode(GOLDEN_ENVELOPE)

        val verified = BoundedModelPackVerifier(TestFixtures.trustStore()).verify(
            manifestBytes,
            envelopeBytes,
            TestFixtures.environment(),
        )

        assertEquals("touvay.pack.compact-writer-q4", verified.manifest.packId)
        assertEquals("1.2.0", verified.manifest.packVersion)
        assertEquals(TestFixtures.KEY_ID, verified.signingKeyId)
        assertEquals(SigningKeyTrust.ENGINE_PINNED, verified.trust)
        assertEquals(GOLDEN_DIGEST, verified.manifestSha256)
    }

    @Test
    fun deterministicProducerStillMatchesV1GoldenBytes() {
        assertContentEquals(
            Base64.getDecoder().decode(GOLDEN_MANIFEST),
            TestFixtures.manifest().toByteArray(),
        )
    }

    companion object {
        private const val GOLDEN_MANIFEST: String =
            "CAESHXRvdXZheS5wYWNrLmNvbXBhY3Qtd3JpdGVyLXE0GgUxLjIuMCIFMS4wLjAqBTIuMC4wMIDLxsMGOgxyZWxlYXNlLnRlc3RCEQoIbGxhbWFjcHASBTEuMC4wSjwKDHRleHQucmV3cml0ZRABGD4iFXRlbXBsYXRlcy9yZXdyaXRlLnRwbCoRY29uZmlnL3Jld3JpdGUucGJSEQiAgIDCAxCACBiAYCIDY3B1WhcIAhIJYXJtNjQtdjhhEgZ4ODZfNjQYHWI4Cgx3ZWlnaHRzLmdndWYQ4Nao6gEaIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBIAFiPgoVdGVtcGxhdGVzL3Jld3JpdGUudHBsEIABGiACAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAiADYjkKEWNvbmZpZy9yZXdyaXRlLnBiEEAaIAMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDIARiMAoHTElDRU5TRRDeWBogBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQgBWoVCgpBcGFjaGUtMi4wEgdMSUNFTlNF"
        private const val GOLDEN_ENVELOPE: String =
            "VFZNUFNJRwABAQAMcmVsZWFzZS50ZXN0Eayw75b+cK6ZVsNsOm3UIs05gd6jBVOrRsJRh5pfMWV/zHWtK8dNZBqr3ihVoGLfaBMNNYSCdMeRrDuT1fD8DQ=="
        private const val GOLDEN_DIGEST: String =
            "6c419622486eacc6f65300aec8d7a55b8ab1e31cefe5c9bdf9bdf39b789d2cc1"
    }
}

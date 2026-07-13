package com.touvay.contract

import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteDisposition
import com.touvay.contract.rewrite.v1.RewriteLength
import com.touvay.contract.rewrite.v1.RewriteRequest
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.contract.rewrite.v1.RewriteTone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RewriteProtoContractTest {
    @Test
    fun requestRoundTrip_preservesStructuredOptions() {
        val request = RewriteRequest.newBuilder()
            .setText("Rewrite me")
            .setTone(RewriteTone.REWRITE_TONE_FORMAL)
            .setLength(RewriteLength.REWRITE_LENGTH_SHORTER)
            .setOutputLocaleBcp47("en-GB")
            .build()

        val decoded = RewriteRequest.parseFrom(request.toByteArray())

        assertEquals("Rewrite me", decoded.text)
        assertEquals(RewriteTone.REWRITE_TONE_FORMAL, decoded.tone)
        assertEquals(RewriteLength.REWRITE_LENGTH_SHORTER, decoded.length)
        assertTrue(decoded.hasOutputLocaleBcp47())
        assertEquals("en-GB", decoded.outputLocaleBcp47)
    }

    @Test
    fun omittedOptions_haveStableNeutralDefaults() {
        val decoded = RewriteRequest.parseFrom(
            RewriteRequest.newBuilder().setText("text").build().toByteArray(),
        )

        assertEquals(RewriteTone.REWRITE_TONE_UNSPECIFIED, decoded.tone)
        assertEquals(RewriteLength.REWRITE_LENGTH_UNSPECIFIED, decoded.length)
        assertFalse(decoded.hasOutputLocaleBcp47())
    }

    @Test
    fun streamingAndFinalMessages_areSeparateStructuredSchemas() {
        val delta = RewriteDelta.newBuilder()
            .setText("partial")
            .setProvisional(true)
            .build()
        val response = RewriteResponse.newBuilder()
            .setText("final")
            .setDisposition(RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN)
            .build()

        assertTrue(RewriteDelta.parseFrom(delta.toByteArray()).provisional)
        assertEquals(
            RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN,
            RewriteResponse.parseFrom(response.toByteArray()).disposition,
        )
    }

    @Test
    fun enumNumbers_areFrozenForWireCompatibility() {
        assertEquals(1, RewriteTone.REWRITE_TONE_NEUTRAL.number)
        assertEquals(2, RewriteTone.REWRITE_TONE_FORMAL.number)
        assertEquals(3, RewriteTone.REWRITE_TONE_CASUAL.number)
        assertEquals(1, RewriteLength.REWRITE_LENGTH_PRESERVE.number)
        assertEquals(2, RewriteLength.REWRITE_LENGTH_SHORTER.number)
        assertEquals(3, RewriteLength.REWRITE_LENGTH_LONGER.number)
        assertEquals(1, RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN.number)
        assertEquals(2, RewriteDisposition.REWRITE_DISPOSITION_UNCHANGED.number)
    }
}

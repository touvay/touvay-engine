package com.touvay.contract

import com.touvay.contract.proto.EchoRequest
import com.touvay.contract.proto.EchoResponse
import org.junit.Test
import kotlin.test.assertEquals

/** Serialization checks for the dev.echo payload schema (plain JVM, no Android). */
class DiagnosticsProtoTest {

    @Test
    fun echoRequest_roundTrips() {
        val request = EchoRequest.newBuilder()
            .setText("hello touvay")
            .setChunkCount(4)
            .setInterChunkDelayMillis(25)
            .build()

        val parsed = EchoRequest.parseFrom(request.toByteArray())

        assertEquals("hello touvay", parsed.text)
        assertEquals(4, parsed.chunkCount)
        assertEquals(25, parsed.interChunkDelayMillis)
    }

    @Test
    fun echoRequest_missingFields_defaultSafely() {
        // Wire compatibility: a minimal v1 producer that only sets text must parse with
        // usable defaults for everything else.
        val minimal = EchoRequest.newBuilder().setText("x").build().toByteArray()

        val parsed = EchoRequest.parseFrom(minimal)

        assertEquals("x", parsed.text)
        assertEquals(0, parsed.chunkCount)
        assertEquals(0, parsed.interChunkDelayMillis)
    }

    @Test
    fun echoResponse_roundTrips() {
        val bytes = EchoResponse.newBuilder().setText("echoed").build().toByteArray()
        assertEquals("echoed", EchoResponse.parseFrom(bytes).text)
    }
}

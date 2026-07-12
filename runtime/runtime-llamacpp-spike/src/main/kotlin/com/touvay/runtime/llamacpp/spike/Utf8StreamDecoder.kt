package com.touvay.runtime.llamacpp.spike

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Streaming UTF-8 reassembly for BPE token pieces: a single token's bytes may end in
 * the middle of a multi-byte code point. Incomplete trailing sequences are held back
 * for the next piece; genuinely malformed bytes are replaced, never thrown.
 */
internal class Utf8StreamDecoder {

    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 1)
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(input, output, /* endOfInput = */ false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }
}

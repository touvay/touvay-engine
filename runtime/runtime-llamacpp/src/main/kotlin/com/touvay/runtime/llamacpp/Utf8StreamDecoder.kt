package com.touvay.runtime.llamacpp

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Streaming UTF-8 reassembly for BPE token pieces (SPI-ST-2): a single token's bytes
 * may end mid-code-point. Incomplete trailing sequences are held for the next piece;
 * genuinely malformed bytes are replaced, never thrown. Proven in the Task 1 spike.
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

    /** Completes the stream, replacing an incomplete trailing sequence, then resets. */
    fun flush(): String {
        if (pending.isEmpty()) return ""
        val trailing = pending.toString(Charsets.UTF_8)
        pending = ByteArray(0)
        return trailing
    }
}

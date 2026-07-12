package com.touvay.runtime.llamacpp

import kotlin.system.measureNanoTime

/**
 * Thin JNI surface over upstream llama.cpp (pinned tag b5199; scripts/fetch-llamacpp.ps1).
 * Policy — threads, chunk sizes, state machines, UTF-8 reassembly — lives in Kotlin.
 */
internal object LlamaNative {

    /** Wall time of System.loadLibrary, measured once at first class use. */
    val libraryLoadNanos: Long = measureNanoTime { System.loadLibrary("touvay_llama") }

    init {
        nativeBackendInit()
    }

    private external fun nativeBackendInit()

    /** @return model handle, or 0 on rejection. Slow: seconds on device. */
    external fun nativeLoadModel(path: String, useMmap: Boolean): Long

    external fun nativeModelCtxTrain(model: Long): Int

    /** @return session handle (context + abort callback wired), or 0 on failure. */
    external fun nativeCreateContext(model: Long, nCtx: Int, nThreads: Int, nBatch: Int): Long

    external fun nativeTokenize(model: Long, text: String, addSpecial: Boolean): IntArray?

    /** Raw decoded bytes for exact TCK stream-parity verification. */
    external fun nativeDetokenize(model: Long, tokens: IntArray): ByteArray?

    /** @return 0 complete; -2 cancelled; other = llama_decode failure status. */
    external fun nativePrefill(session: Long, tokens: IntArray, chunk: Int): Int

    /**
     * @return tokens produced (>= 0), or -1000-status on backend failure. A pending
     * sink exception propagates from this call directly (SPI-ST-6).
     */
    external fun nativeDecode(session: Long, maxTokens: Int, callback: TokenBytesCallback): Int

    /** Thread-safe; the ggml abort callback observes it inside the current step. */
    external fun nativeCancel(session: Long)

    external fun nativeFreeContext(session: Long)

    external fun nativeFreeModel(model: Long)
}

/**
 * Receives one generated token as raw UTF-8 piece bytes (may end mid-code-point).
 * Return false to stop generation. Called on the decoding thread.
 */
internal fun interface TokenBytesCallback {
    fun onToken(tokenId: Int, bytes: ByteArray): Boolean
}

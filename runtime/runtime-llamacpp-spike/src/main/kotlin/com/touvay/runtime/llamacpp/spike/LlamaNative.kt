package com.touvay.runtime.llamacpp.spike

import kotlin.system.measureNanoTime

/**
 * Thin JNI surface over upstream llama.cpp (pinned tag b5199; see
 * `scripts/fetch-llamacpp.ps1`). All policy — threads, context sizes, cancellation
 * semantics, UTF-8 reassembly — lives in Kotlin; this object is calls only.
 */
internal object LlamaNative {

    /** Wall time of System.loadLibrary, measured once at first class use. */
    val libraryLoadNanos: Long = measureNanoTime { System.loadLibrary("touvay_llama_spike") }

    init {
        nativeBackendInit()
    }

    private external fun nativeBackendInit()

    /** @return model handle, or 0 on failure. Slow: seconds on device. */
    external fun nativeLoadModel(path: String, useMmap: Boolean): Long

    external fun nativeModelCtxTrain(model: Long): Int

    /** @return context handle, or 0 on failure (typically KV-cache allocation). */
    external fun nativeCreateContext(model: Long, nCtx: Int, nThreads: Int, nBatch: Int): Long

    external fun nativeTokenize(model: Long, text: String, addSpecial: Boolean): IntArray?

    /** @return 0 ok, -2 cancelled, other = llama_decode status. */
    external fun nativePrefill(ctx: Long, tokens: IntArray): Int

    /** @return number of tokens produced before EOG/cancel/stop. */
    external fun nativeDecode(ctx: Long, maxTokens: Int, callback: TokenBytesCallback): Int

    /** Thread-safe; unblocks a decode/prefill running on another thread within one token. */
    external fun nativeCancel(ctx: Long)

    external fun nativeFreeContext(ctx: Long)

    external fun nativeFreeModel(model: Long)
}

/**
 * Receives one generated token as raw UTF-8 piece bytes (may end mid-code-point).
 * Return false to stop generation. Called on the decoding thread.
 */
internal fun interface TokenBytesCallback {
    fun onToken(tokenId: Int, bytes: ByteArray): Boolean
}

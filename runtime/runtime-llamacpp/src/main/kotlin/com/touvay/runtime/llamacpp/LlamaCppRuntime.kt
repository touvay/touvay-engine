package com.touvay.runtime.llamacpp

import com.touvay.runtime.api.CancelSignal
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.InferenceRuntime
import com.touvay.runtime.api.InferenceSession
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.PrefillResult
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.RuntimeAvailability
import com.touvay.runtime.api.RuntimeId
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.api.TokenSequence
import com.touvay.runtime.api.TokenSink
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Backend failures that are not contract violations (SPI-ER-3). */
public class LlamaCppException internal constructor(message: String) : RuntimeException(message)

/**
 * Production llama.cpp adapter behind the runtime SPI.
 * Design: docs/runtime/runtime-llamacpp-design.md. Conformance: LlamaCppTck.
 *
 * Documented constants (verified by the TCK):
 * - prefill chunk: [PREFILL_CHUNK_TOKENS] tokens
 * - cancellation bound: one decode step (ggml abort callback typically interrupts
 *   inside the step; declared conservatively until device calibration)
 */
public class LlamaCppRuntime : InferenceRuntime {

    override val id: RuntimeId = RuntimeId("llamacpp")

    override fun probe(device: DeviceProfile): RuntimeAvailability =
        if (device.supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }) {
            RuntimeAvailability.Available()
        } else {
            RuntimeAvailability.Unavailable("requires arm64-v8a (or x86_64 emulator)")
        }

    override fun loadModel(pack: ResolvedModelPack, config: LoadConfig): ModelInstance {
        require(config.threads > 0) { "threads must be positive, was ${config.threads}" }
        val weights = requireNotNull(pack.files[WEIGHTS_FILE]) {
            "pack ${pack.id} has no '$WEIGHTS_FILE' entry"
        }
        // Fail-fast gate for hostile/absent files (SPI-ER-1/5): existence + GGUF magic,
        // before any native allocation.
        require(Files.exists(weights)) { "model file not found: $weights" }
        require(Files.size(weights) >= 8) { "model file too small: $weights" }
        val magic = ByteArray(4)
        RandomAccessFile(weights.toFile(), "r").use { it.readFully(magic) }
        require(magic.contentEquals(GGUF_MAGIC)) { "not a GGUF file: $weights" }

        val handle = LlamaNative.nativeLoadModel(weights.toString(), config.useMmap)
        require(handle != 0L) { "model rejected by llama.cpp: $weights" }
        return LlamaCppModelInstance(handle, config.threads, Files.size(weights))
    }

    public companion object {
        public const val WEIGHTS_FILE: String = "weights.gguf"
        public const val PREFILL_CHUNK_TOKENS: Int = 256
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

        /** First-touch native library load cost; constant after the first call. */
        public fun libraryLoadNanos(): Long = LlamaNative.libraryLoadNanos
    }
}

public class LlamaCppModelInstance internal constructor(
    modelHandle: Long,
    private val threads: Int,
    weightsBytes: Long,
) : ModelInstance {

    private val nativeHandle = modelHandle

    @Volatile
    private var closed = false

    /** SPI-LC-6: sessions still open when the instance closes are released defensively. */
    private val liveSessions = ConcurrentHashMap<LlamaCppSession, Unit>()

    private val guard = NativeGuardRegistry.register(
        this,
        "model",
        modelHandle,
        LlamaNative::nativeFreeModel,
    )

    override val info: ModelInstanceInfo = ModelInstanceInfo(
        // mmap'd weights dominate; per-session KV is measured separately (SPI-ME-1).
        estimatedRamBytes = weightsBytes,
        maxContextLength = LlamaNative.nativeModelCtxTrain(nativeHandle),
    )

    // Covariant return: benchmark/engine callers holding the concrete type keep
    // cancelNow() reachable without casts.
    override fun createSession(config: SessionConfig): LlamaCppSession {
        check(!closed) { "instance is closed" }
        require(config.contextLength > 0) {
            "contextLength must be positive, was ${config.contextLength}"
        }
        val handle = LlamaNative.nativeCreateContext(
            nativeHandle, config.contextLength, threads, N_BATCH,
        )
        if (handle == 0L) {
            throw LlamaCppException(
                "context creation failed (n_ctx=${config.contextLength}, threads=$threads)",
            )
        }
        val session = LlamaCppSession(handle) { self -> liveSessions.remove(self) }
        liveSessions[session] = Unit
        return session
    }

    override fun tokenize(text: String): TokenSequence {
        check(!closed) { "instance is closed" }
        val ids = LlamaNative.nativeTokenize(nativeHandle, text, true)
            ?: throw LlamaCppException("tokenization failed")
        return TokenSequence(ids)
    }

    /** Exact backend detokenization used only by the conformance kit. */
    internal fun detokenize(tokenIds: IntArray): String {
        check(!closed) { "instance is closed" }
        val bytes = LlamaNative.nativeDetokenize(nativeHandle, tokenIds)
            ?: throw LlamaCppException("detokenization failed")
        return bytes.toString(Charsets.UTF_8)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Engine contract says sessions close first; survive it if they didn't.
        liveSessions.keys.forEach { it.close() }
        liveSessions.clear()
        NativeGuardRegistry.markClosed(guard)
        LlamaNative.nativeFreeModel(nativeHandle)
    }

    private companion object {
        const val N_BATCH = 512
    }
}

public class LlamaCppSession internal constructor(
    sessionHandle: Long,
    private val onClosed: (LlamaCppSession) -> Unit,
) : InferenceSession {

    private enum class State { READY, CANCELLED, CLOSED }

    @Volatile
    private var state = State.READY

    private val nativeAccess = Any()
    private var nativeHandle = sessionHandle

    private val utf8 = Utf8StreamDecoder()

    private val guard = NativeGuardRegistry.register(
        this,
        "session",
        sessionHandle,
        LlamaNative::nativeFreeContext,
    )

    override fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult {
        checkReady()
        // SPI-CX-6: a pre-set signal is an immediate no-op, not an observed cancellation.
        if (cancel.isCancelled) return PrefillResult(0)
        propagateCancelWhile(cancel) {
            when (val status = LlamaNative.nativePrefill(
                liveHandle(), tokens.ids, LlamaCppRuntime.PREFILL_CHUNK_TOKENS,
            )) {
                0 -> Unit
                -2 -> state = State.CANCELLED // SPI-LC-9
                else -> throw LlamaCppException("prefill failed with llama status $status")
            }
        }
        return PrefillResult(promptTokenCount = tokens.ids.size)
    }

    override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
        checkReady()
        require(params.maxTokens > 0) { "maxTokens must be positive, was ${params.maxTokens}" }
        if (cancel.isCancelled) return // SPI-CX-6
        var observedCancel = false
        var pendingTokenId: Int? = null
        var pendingPiece = ""

        fun emitPending(finalSuffix: String = "") {
            val tokenId = pendingTokenId ?: return
            val piece = pendingPiece + finalSuffix
            pendingTokenId = null
            pendingPiece = ""
            sink.onToken(tokenId, piece)
        }
        try {
            propagateCancelWhile(cancel) {
                val result = LlamaNative.nativeDecode(liveHandle(), params.maxTokens) { tokenId, bytes ->
                    // Hold one callback so a final incomplete UTF-8 sequence can be
                    // flushed into the last token without inventing an extra callback.
                    emitPending()
                    pendingTokenId = tokenId
                    pendingPiece = utf8.decode(bytes)
                    val keepGoing = !cancel.isCancelled
                    if (!keepGoing) observedCancel = true
                    keepGoing
                }
                emitPending(utf8.flush())
                if (result < 0) {
                    throw LlamaCppException("decode failed with llama status ${-1000 - result}")
                }
            }
        } catch (t: Throwable) {
            // SPI-ST-6: a throwing sink (or backend failure) invalidates generation state.
            state = State.CANCELLED
            throw t
        }
        if (observedCancel || cancel.isCancelled) {
            state = State.CANCELLED // SPI-LC-9
        }
    }

    /**
     * Immediate cooperative cancel from any thread: the ggml abort callback observes
     * the flag inside the current decode step (SPI-CX-4).
     */
    public fun cancelNow() {
        synchronized(nativeAccess) {
            val handle = nativeHandle
            if (handle != 0L) LlamaNative.nativeCancel(handle)
        }
    }

    override fun close() {
        val handle = synchronized(nativeAccess) {
            if (nativeHandle == 0L) return
            state = State.CLOSED
            nativeHandle.also { nativeHandle = 0L }
        }
        NativeGuardRegistry.markClosed(guard)
        LlamaNative.nativeFreeContext(handle)
        onClosed(this)
    }

    private fun checkReady() {
        when (state) {
            State.READY -> Unit
            State.CANCELLED ->
                throw IllegalStateException("session was cancelled; only close() is legal (SPI-LC-9)")
            State.CLOSED -> throw IllegalStateException("session is closed")
        }
    }

    /**
     * Bridges the engine's [CancelSignal] to the native flag for the duration of one
     * blocking native call: a watcher-free design would poll only at token boundaries;
     * this lets cancel() interrupt inside a step via the abort callback.
     */
    private inline fun propagateCancelWhile(cancel: CancelSignal, block: () -> Unit) {
        if (cancel === CancelSignal.NONE) {
            block()
            return
        }
        val watcher = Thread {
            while (state == State.READY && !Thread.currentThread().isInterrupted) {
                if (cancel.isCancelled) {
                    cancelNow()
                    return@Thread
                }
                try {
                    Thread.sleep(CANCEL_POLL_MILLIS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        watcher.name = "llamacpp-cancel-watcher"
        watcher.isDaemon = true
        watcher.start()
        try {
            block()
        } finally {
            watcher.interrupt()
            watcher.join()
        }
    }

    private companion object {
        const val CANCEL_POLL_MILLIS = 5L
    }

    private fun liveHandle(): Long = synchronized(nativeAccess) {
        check(nativeHandle != 0L) { "session is closed" }
        nativeHandle
    }
}

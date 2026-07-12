package com.touvay.runtime.llamacpp.spike

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
import com.touvay.runtime.api.TokenSequence
import com.touvay.runtime.api.TokenSink

/**
 * SPIKE (Task 1 Part B): llama.cpp behind the runtime-api SPI, for feasibility
 * measurement only. Not registered with the engine, not reachable from the SDK.
 * Converting this into the production adapter requires explicit approval and the
 * runtime-tck conformance suite.
 *
 * Deliberate spike simplifications, all documented in docs/spikes/llamacpp-feasibility.md:
 * greedy decoding only ([DecodeParams.temperature] ignored), single active decode per
 * session, CPU-only.
 */
public class LlamaSpikeRuntime : InferenceRuntime {

    override val id: RuntimeId = RuntimeId("llamacpp-spike")

    override fun probe(device: DeviceProfile): RuntimeAvailability =
        if (device.supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }) {
            RuntimeAvailability.Available()
        } else {
            RuntimeAvailability.Unavailable("requires arm64-v8a (or x86_64 emulator)")
        }

    override fun loadModel(pack: ResolvedModelPack, config: LoadConfig): ModelInstance {
        val weights = requireNotNull(pack.files[WEIGHTS_FILE]) {
            "pack ${pack.id} has no '$WEIGHTS_FILE' entry"
        }
        val handle = LlamaNative.nativeLoadModel(weights.toString(), config.useMmap)
        require(handle != 0L) { "llama.cpp failed to load model from $weights" }
        return LlamaSpikeModelInstance(handle, config.threads, weights.toFile().length())
    }

    public companion object {
        public const val WEIGHTS_FILE: String = "weights.gguf"

        /** First-touch native library load cost; constant after the first call. */
        public fun libraryLoadNanos(): Long = LlamaNative.libraryLoadNanos
    }
}

public class LlamaSpikeModelInstance internal constructor(
    private val modelHandle: Long,
    private val defaultThreads: Int,
    weightsBytes: Long,
) : ModelInstance {

    @Volatile
    private var closed = false

    override val info: ModelInstanceInfo = ModelInstanceInfo(
        // mmap'd weights dominate; KV cache is per-session and measured separately.
        estimatedRamBytes = weightsBytes,
        maxContextLength = LlamaNative.nativeModelCtxTrain(modelHandle),
    )

    // Covariant return: callers holding the concrete type keep cancelNow() reachable.
    override fun createSession(config: com.touvay.runtime.api.SessionConfig): LlamaSpikeSession =
        createSession(config, defaultThreads)

    /** Spike-only overload: thread-count override for the benchmark's thread sweep. */
    public fun createSession(
        config: com.touvay.runtime.api.SessionConfig,
        threads: Int,
    ): LlamaSpikeSession {
        check(!closed) { "model instance is closed" }
        val handle = LlamaNative.nativeCreateContext(
            modelHandle, config.contextLength, threads, N_BATCH,
        )
        check(handle != 0L) {
            "llama.cpp context creation failed (n_ctx=${config.contextLength}, threads=$threads)"
        }
        return LlamaSpikeSession(handle)
    }

    /** Spike helper: benchmark scenarios need token counts before prefill. */
    public fun tokenize(text: String): IntArray {
        check(!closed) { "model instance is closed" }
        return requireNotNull(LlamaNative.nativeTokenize(modelHandle, text, true)) {
            "tokenization failed"
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            LlamaNative.nativeFreeModel(modelHandle)
        }
    }

    private companion object {
        const val N_BATCH = 512
    }
}

public class LlamaSpikeSession internal constructor(
    private val contextHandle: Long,
) : InferenceSession {

    @Volatile
    private var closed = false
    private val utf8 = Utf8StreamDecoder()

    override fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult {
        check(!closed) { "session is closed" }
        if (cancel.isCancelled) return PrefillResult(0)
        val status = LlamaNative.nativePrefill(contextHandle, tokens.ids)
        check(status == 0 || status == STATUS_CANCELLED) {
            "prefill failed with llama status $status"
        }
        return PrefillResult(promptTokenCount = tokens.ids.size)
    }

    override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
        check(!closed) { "session is closed" }
        LlamaNative.nativeDecode(contextHandle, params.maxTokens) { tokenId, bytes ->
            sink.onToken(tokenId, utf8.decode(bytes))
            !cancel.isCancelled
        }
    }

    /**
     * Immediate cooperative cancel from any thread: flips the native flag so an
     * in-flight decode or prefill on another thread returns within one token.
     */
    public fun cancelNow() {
        LlamaNative.nativeCancel(contextHandle)
    }

    override fun close() {
        if (!closed) {
            closed = true
            LlamaNative.nativeFreeContext(contextHandle)
        }
    }

    private companion object {
        const val STATUS_CANCELLED = -2
    }
}

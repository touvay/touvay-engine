package com.touvay.runtime.tck

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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/**
 * Test fixture for the TCK's self-test: a fully conformant in-JVM runtime whose
 * [FakeBehavior] knobs introduce specific SPI violations. Each knob exists to prove
 * that a specific TCK check catches the violation it was written for.
 */
internal class FakeBehavior(
    var stepDelayMillis: Long = 2,
    var eogAtToken: Int = 24,
    var leakOnClose: Boolean = false,
    var ignoreCancel: Boolean = false,
    var emitEogMarker: Boolean = false,
    var nondeterministic: Boolean = false,
    var throwErrorOnMalformed: Boolean = false,
    var skipConfigValidation: Boolean = false,
    var reorderStreaming: Boolean = false,
    var leakUserContentToLog: Boolean = false,
)

/** Simulated native allocator; the fake's [FakeProbe] reads it. */
internal class FakeAllocTracker {
    val allocatedBytes = AtomicLong(0)
    val threads = AtomicInteger(20)
    val logs = mutableListOf<String>()
}

internal class FakeProbe(private val tracker: FakeAllocTracker) : TckProbe {
    override fun residentBytes(): Long = 100L * 1024 * 1024 + tracker.allocatedBytes.get()
    override fun nativeBytes(): Long = tracker.allocatedBytes.get()
    override fun threadCount(): Int = tracker.threads.get()
    override fun beginLogCapture() {
        synchronized(tracker.logs) { tracker.logs.clear() }
    }

    override fun endLogCapture(): List<String> =
        synchronized(tracker.logs) { tracker.logs.toList() }
}

internal class FakeRuntime(
    private val behavior: FakeBehavior,
    private val tracker: FakeAllocTracker,
    private val prefillChunkTokens: Int,
) : InferenceRuntime {

    override val id: RuntimeId = RuntimeId("tck-fake")

    override fun probe(device: DeviceProfile): RuntimeAvailability =
        RuntimeAvailability.Available()

    override fun loadModel(pack: ResolvedModelPack, config: LoadConfig): ModelInstance {
        if (!behavior.skipConfigValidation) {
            require(config.threads > 0) { "threads must be positive, was ${config.threads}" }
        }
        val file = requireNotNull(pack.files["weights.gguf"]) { "pack has no weights.gguf" }
        require(Files.exists(file)) { "no such file: $file" }
        val header = Files.newInputStream(file).use { it.readNBytes(8) }
        if (!header.toString(Charsets.US_ASCII).startsWith(MAGIC)) {
            if (behavior.throwErrorOnMalformed) {
                throw OutOfMemoryError("simulated native abort on malformed file")
            }
            throw IllegalArgumentException("bad magic in $file")
        }
        tracker.allocatedBytes.addAndGet(MODEL_BYTES)
        return FakeModelInstance(behavior, tracker, prefillChunkTokens)
    }

    companion object {
        const val MAGIC = "FAKEMODL"
        const val MODEL_BYTES = 50L * 1024 * 1024
        const val KV_BYTES = 5L * 1024 * 1024
    }
}

private class FakeModelInstance(
    private val behavior: FakeBehavior,
    private val tracker: FakeAllocTracker,
    private val prefillChunkTokens: Int,
) : ModelInstance {

    @Volatile
    private var closed = false
    private val sessions = ConcurrentHashMap<FakeSession, Unit>()

    override val info = ModelInstanceInfo(
        estimatedRamBytes = FakeRuntime.MODEL_BYTES,
        maxContextLength = 4096,
    )

    override fun createSession(config: SessionConfig): InferenceSession {
        check(!closed) { "instance is closed" }
        if (!behavior.skipConfigValidation) {
            require(config.contextLength > 0) {
                "contextLength must be positive, was ${config.contextLength}"
            }
        }
        tracker.allocatedBytes.addAndGet(FakeRuntime.KV_BYTES)
        val session = FakeSession(behavior, tracker, prefillChunkTokens) { self ->
            sessions.remove(self)
        }
        sessions[session] = Unit
        return session
    }

    override fun tokenize(text: String): TokenSequence {
        check(!closed) { "instance is closed" }
        if (behavior.leakUserContentToLog && "TCK-SENTINEL" in text) {
            synchronized(tracker.logs) { tracker.logs += text }
        }
        return TokenSequence(IntArray(text.length) { text[it].code })
    }

    override fun close() {
        if (!closed) {
            closed = true
            sessions.keys.forEach { it.close() }
            sessions.clear()
            if (!behavior.leakOnClose) {
                tracker.allocatedBytes.addAndGet(-FakeRuntime.MODEL_BYTES)
            }
        }
    }
}

private class FakeSession(
    private val behavior: FakeBehavior,
    private val tracker: FakeAllocTracker,
    private val prefillChunkTokens: Int,
    private val onClosed: (FakeSession) -> Unit,
) : InferenceSession {

    @Volatile
    private var closed = false

    @Volatile
    private var cancelled = false
    private var promptSeed = 0L

    override fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult {
        check(!closed) { "session is closed" }
        check(!cancelled) { "session was cancelled; only close() is legal (SPI-LC-9)" }
        // SPI-CX-6: a pre-set signal is an immediate no-op — NOT an observed
        // cancellation, so the session stays READY.
        if (cancel.isCancelled && !behavior.ignoreCancel) return PrefillResult(0)
        promptSeed = tokens.ids.fold(7L) { acc, id -> acc * 31 + id }
        var processed = 0
        while (processed < tokens.ids.size) {
            Thread.sleep(behavior.stepDelayMillis)
            processed += prefillChunkTokens
            if (processed < tokens.ids.size && cancel.isCancelled && !behavior.ignoreCancel) {
                cancelled = true // observed mid-work: SPI-LC-9 applies
                return PrefillResult(processed)
            }
        }
        return PrefillResult(tokens.ids.size)
    }

    override fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink) {
        check(!closed) { "session is closed" }
        check(!cancelled) { "session was cancelled; only close() is legal (SPI-LC-9)" }
        if (cancel.isCancelled && !behavior.ignoreCancel) return // SPI-CX-6
        val total = minOf(params.maxTokens, behavior.eogAtToken)
        for (i in 0 until total) {
            Thread.sleep(behavior.stepDelayMillis)
            val noise = if (behavior.nondeterministic) (Math.random() * 1000).toInt() else 0
            val tokenId = TOKEN_BASE + i + noise
            val pieceIndex = if (behavior.reorderStreaming && i == 1) 2 else i
            val piece = if (behavior.emitEogMarker && i == total - 1) "<|eog|>" else "w$pieceIndex "
            sink.onToken(tokenId, piece)
            if (cancel.isCancelled && !behavior.ignoreCancel) {
                cancelled = true // observed mid-generation: SPI-LC-9 applies
                return
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (!behavior.leakOnClose) {
                tracker.allocatedBytes.addAndGet(-FakeRuntime.KV_BYTES)
            }
            onClosed(this)
        }
    }

    companion object {
        const val TOKEN_BASE = 1_000_000
    }
}

internal fun fakeDetokenize(tokenIds: IntArray): String = buildString {
    tokenIds.forEach { tokenId ->
        if (tokenId >= FakeSession.TOKEN_BASE) {
            append("w${tokenId - FakeSession.TOKEN_BASE} ")
        } else {
            append(tokenId.toChar())
        }
    }
}

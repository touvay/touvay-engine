package com.touvay.runtime.tck

import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.InferenceRuntime
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.SessionConfig
import java.io.File
import java.nio.file.Path

/**
 * Everything an adapter hands the TCK to make its conformance claim
 * (docs/runtime/runtime-tck.md §2). The kit verifies the *documented constants*
 * against actual behavior — documentation that lies fails tests.
 */
public class RuntimeTckSubject(
    /** Fresh runtime per call; the kit never reuses runtime objects across tests. */
    public val runtimeFactory: () -> InferenceRuntime,
    /** Smallest real pack exercising the full path. Files must exist locally. */
    public val conformancePack: ResolvedModelPack,
    public val loadConfig: LoadConfig,
    public val sessionConfig: SessionConfig,
    /** SPI-CX-2: the adapter's documented prefill chunk size, in tokens. */
    public val documentedPrefillChunkTokens: Int,
    /** SPI-CX-3/4: the adapter's declared cancellation bound. */
    public val cancelBound: CancelBound,
    /** Environment measurement; use [ProcStatusTckProbe] on Linux/Android. */
    public val probe: TckProbe,
    public val deviceProfile: DeviceProfile,
    /** Scratch space for generated malformed-corpus files. */
    public val workDir: Path,
    /** SPI-ME-2: documented KV bytes/token for the conformance model. */
    public val declaredKvBytesPerToken: Long,
    /** Backend detokenization used to prove streaming parity (SPI-ST-3). */
    public val detokenize: (ModelInstance, IntArray) -> String,
    /** Multi-byte content is deliberate: exercises UTF-8 reassembly (SPI-ST-2). */
    public val shortPrompt: String = "Rewrite this politely: send the report tonight. 你好 🌍",
    /** Marker fragments that must never appear in sink output (SPI-ST-4 heuristic). */
    public val eogMarkers: List<String> = listOf("<|", "</s>", "<eos>", "<end_of_turn>"),
    /** Greedy output from a clean prompt must not contain U+FFFD; opt out only with cause. */
    public val expectNoReplacementChars: Boolean = true,
    /** Where TCK-PF writes its informative JSON report. */
    public val reportDir: Path,
)

/** Declared cancellation bound (SPI-CX-3/4). */
public sealed interface CancelBound {
    /** Portable guarantee: returns within one decode step. */
    public data object OneStep : CancelBound

    /** Tighter bound for adapters hooking backend abort callbacks. */
    public data class TighterMillis(public val millis: Long) : CancelBound
}

/**
 * Environment measurements. Any probe method may return null = unavailable; the
 * corresponding tests are skipped (JUnit assumption), never silently passed.
 */
public interface TckProbe {
    /** Resident set size, bytes. */
    public fun residentBytes(): Long?

    /** Native-allocator bytes (Android: Debug.getNativeHeapAllocatedSize). */
    public fun nativeBytes(): Long?

    /** OS thread count of this process. */
    public fun threadCount(): Int?

    /** Clears or snapshots the adapter's local diagnostic log before a privacy check. */
    public fun beginLogCapture(): Unit = Unit

    /** Returns adapter log lines emitted since [beginLogCapture]. */
    public fun endLogCapture(): List<String> = emptyList()
}

/** Works on Android and Linux JVMs via /proc/self/status; nativeBytes unavailable. */
public open class ProcStatusTckProbe : TckProbe {
    private fun field(key: String): Long? {
        val status = runCatching { File("/proc/self/status").readText() }.getOrNull()
            ?: return null
        return Regex("$key:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLong()
    }

    override fun residentBytes(): Long? = field("VmRSS")?.let { it * 1024 }
    override fun nativeBytes(): Long? = null
    override fun threadCount(): Int? = field("Threads")?.toInt()
}

package com.touvay.runtime.llamacpp

import android.os.Build
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.tck.AbstractRuntimeTck
import com.touvay.runtime.tck.CancelBound
import com.touvay.runtime.tck.ProcStatusTckProbe
import com.touvay.runtime.tck.RuntimeTckSubject
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileInputStream

/**
 * The llama.cpp adapter's conformance claim (docs/runtime/runtime-tck.md §1).
 *
 * Requires the conformance model pushed first (skips cleanly otherwise):
 *   adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf \
 *     /sdcard/Android/data/com.touvay.runtime.llamacpp.test/files/model.gguf
 * Run with `adb shell am instrument` (AGP's connected task uninstalls afterwards,
 * deleting the pushed model — see AGENTS.md gotchas).
 */
class LlamaCppTck : AbstractRuntimeTck() {

    override fun subject(): RuntimeTckSubject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model.gguf")
        assumeTrue("model.gguf not pushed; skipping llama.cpp TCK", model.exists())
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val additionalOutput = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")

        return RuntimeTckSubject(
            runtimeFactory = { LlamaCppRuntime() },
            conformancePack = ResolvedModelPack(
                id = "tck.qwen2.5-0.5b-instruct-q4km",
                version = "1",
                files = mapOf(LlamaCppRuntime.WEIGHTS_FILE to model.toPath()),
            ),
            loadConfig = LoadConfig(threads = 4),
            sessionConfig = SessionConfig(contextLength = 1024),
            documentedPrefillChunkTokens = LlamaCppRuntime.PREFILL_CHUNK_TOKENS,
            // Conservative declaration until physical-device calibration; the abort
            // callback typically interrupts inside a step (design doc §6).
            cancelBound = CancelBound.OneStep,
            probe = AndroidTckProbe(),
            deviceProfile = DeviceProfile(
                totalRamBytes = memory.totalMem,
                isLowRamDevice = activityManager.isLowRamDevice,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            ),
            workDir = context.cacheDir.toPath().resolve("tck"),
            // Qwen2.5-0.5B: 24 layers × 2 KV heads × 64 head dim × K/V × fp16.
            declaredKvBytesPerToken = 12_288,
            detokenize = { instance, tokenIds ->
                (instance as LlamaCppModelInstance).detokenize(tokenIds)
            },
            reportDir = File(
                additionalOutput ?: context.getExternalFilesDir(null)?.absolutePath
                    ?: context.cacheDir.absolutePath,
            ).toPath(),
        )
    }

    private class AndroidTckProbe : ProcStatusTckProbe() {
        override fun nativeBytes(): Long = Debug.getNativeHeapAllocatedSize()

        override fun beginLogCapture() {
            shell("logcat -c")
        }

        override fun endLogCapture(): List<String> =
            shell("logcat -d -v brief -s TouvayLlamaCpp:*").lineSequence().toList()

        private fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation()
                .uiAutomation.executeShellCommand(command)
            return try {
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            } finally {
                descriptor.close()
            }
        }
    }
}

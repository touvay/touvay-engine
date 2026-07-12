package com.touvay.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.touvay.runtime.api.CancelSignal
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.api.TokenSink
import com.touvay.runtime.llamacpp.LlamaCppModelInstance
import com.touvay.runtime.llamacpp.LlamaCppRuntime
import com.touvay.runtime.llamacpp.LlamaCppSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Production-adapter measurement scenarios. Runs inside the legacy-named :spike process; produces one
 * JSON document per run. Baseline-first by design: greedy decoding, mmap on, default
 * batch — configuration sweeps are limited to thread count and context size, the two
 * knobs the architecture's budget manager will own (ARCHITECTURE.md §14).
 */
class BenchmarkSuite(
    private val context: Context,
    private val modelPath: String,
    private val progress: (String) -> Unit,
) {
    private val runtime = LlamaCppRuntime()

    @Volatile
    private var activeSession: LlamaCppSession? = null

    @Volatile
    private var aborted = false

    /** Cooperative abort from any thread (binder). */
    fun abort() {
        aborted = true
        activeSession?.cancelNow()
    }

    fun run(quick: Boolean): JSONObject {
        val result = JSONObject()
        result.put("schema", 1)
        result.put("quick", quick)
        result.put("timestamp", System.currentTimeMillis())
        result.put("device", deviceInfo())
        result.put("modelFile", JSONObject().apply {
            put("path", modelPath)
            put("bytes", File(modelPath).length())
        })
        result.put("memBaseline", memSnapshot())

        step("native library load")
        result.put("libLoadMs", LlamaCppRuntime.libraryLoadNanos() / 1e6)

        step("cold model load")
        val threads = defaultThreads()
        val pack = ResolvedModelPack(
            id = "bench.qwen2.5-0.5b-instruct-q4km",
            version = "1",
            files = mapOf(LlamaCppRuntime.WEIGHTS_FILE to Paths.get(modelPath)),
        )
        var model: LlamaCppModelInstance? = null
        val coldLoadMs = measureMs {
            model = runtime.loadModel(pack, LoadConfig(threads = threads, useMmap = true))
                as LlamaCppModelInstance
        }
        val loaded = requireNotNull(model)
        result.put("modelLoad", JSONObject().apply {
            put("coldMs", coldLoadMs)
            put("threadsDefault", threads)
            put("maxCtxTrain", loaded.info.maxContextLength)
            put("mem", memSnapshot())
        })

        try {
            step("short prompt (rewrite-style)")
            loaded.createSession(SessionConfig(CTX_DEFAULT)).use { session ->
                activeSession = session
                result.put("shortPrompt", generation(loaded, session, SHORT_PROMPT, 48))
                // KV reuse is outside Runtime v1, so use a
                // fresh session for warmth-of-caches comparison instead.
            }
            step("short prompt (warm process)")
            loaded.createSession(SessionConfig(CTX_DEFAULT)).use { session ->
                activeSession = session
                result.put("shortPromptWarm", generation(loaded, session, SHORT_PROMPT, 48))
            }

            step("cancellation latency (3 runs)")
            result.put("cancellation", cancellationScenario(loaded))

            if (!quick && !aborted) {
                step("medium prompt (summarize-style)")
                loaded.createSession(SessionConfig(CTX_DEFAULT)).use { session ->
                    activeSession = session
                    result.put("mediumPrompt", generation(loaded, session, MEDIUM_PROMPT, 96))
                }

                step("burst: 8 short requests")
                result.put("burst", burstScenario(loaded))

                step("thread sweep")
                result.put("threadSweep", threadSweep(pack))

                step("context probe")
                result.put("contextProbe", contextProbe(loaded))
            }
        } finally {
            activeSession = null
        }

        step("unload")
        val memBeforeUnload = memSnapshot()
        loaded.close()
        val memAfterUnload = memSnapshot()
        step("warm reload")
        var reload: LlamaCppModelInstance? = null
        val warmLoadMs = measureMs {
            reload = runtime.loadModel(pack, LoadConfig(threads = threads, useMmap = true))
                as LlamaCppModelInstance
        }
        reload?.close()
        result.put("unload", JSONObject().apply {
            put("memBefore", memBeforeUnload)
            put("memAfter", memAfterUnload)
            put("memAfterWarmReloadFree", memSnapshot())
            put("warmReloadMs", warmLoadMs)
        })

        result.put("aborted", aborted)
        return result
    }

    // -- scenarios -------------------------------------------------------------------

    private fun generation(
        model: LlamaCppModelInstance,
        session: LlamaCppSession,
        userPrompt: String,
        maxTokens: Int,
    ): JSONObject {
        val tokens = model.tokenize(chatWrap(userPrompt))
        val prefillStart = now()
        session.prefill(tokens, CancelSignal.NONE)
        val prefillEnd = now()

        val firstTokenAt = AtomicLong(-1)
        val count = AtomicInteger(0)
        session.decode(
            DecodeParams(maxTokens = maxTokens),
            CancelSignal.NONE,
            TokenSink { _, _ ->
                if (count.getAndIncrement() == 0) firstTokenAt.set(now())
            },
        )
        val decodeEnd = now()

        val prefillMs = ms(prefillStart, prefillEnd)
        val decoded = count.get()
        val firstMs = if (firstTokenAt.get() > 0) ms(prefillEnd, firstTokenAt.get()) else -1.0
        val decodeMs = ms(prefillEnd, decodeEnd)
        return JSONObject().apply {
            put("promptTokens", tokens.ids.size)
            put("prefillMs", prefillMs)
            put("prefillTokPerS", rate(tokens.ids.size, prefillMs))
            put("decodedTokens", decoded)
            put("firstTokenMs", firstMs)
            put("ttftTotalMs", prefillMs + firstMs)
            put("decodeMs", decodeMs)
            put("decodeTokPerS", rate(decoded, decodeMs))
            put("thermal", thermalSnapshot())
        }
    }

    private fun cancellationScenario(model: LlamaCppModelInstance): JSONObject {
        val runs = JSONArray()
        var maxLatency = -1.0
        repeat(3) { attempt ->
            if (aborted) return@repeat
            model.createSession(SessionConfig(CTX_DEFAULT)).use { session ->
                activeSession = session
                val tokens = model.tokenize(chatWrap(MEDIUM_PROMPT))
                session.prefill(tokens, CancelSignal.NONE)

                val seen = AtomicInteger(0)
                val cancelRequestedAt = AtomicLong(0)
                val decodeReturnedAt = AtomicLong(0)
                val done = CountDownLatch(1)
                val decoder = thread(name = "benchmark-cancel-$attempt") {
                    session.decode(
                        DecodeParams(maxTokens = 256),
                        CancelSignal.NONE,
                        TokenSink { _, _ -> seen.incrementAndGet() },
                    )
                    decodeReturnedAt.set(now())
                    done.countDown()
                }
                // Cancel once generation is demonstrably mid-flight.
                while (seen.get() < 12 && done.count > 0) SystemClock.sleep(1)
                cancelRequestedAt.set(now())
                session.cancelNow()
                check(done.await(30, TimeUnit.SECONDS)) { "decode did not return after cancel" }
                decoder.join()

                val latency = ms(cancelRequestedAt.get(), decodeReturnedAt.get())
                maxLatency = maxOf(maxLatency, latency)
                runs.put(
                    JSONObject()
                        .put("tokensBeforeCancel", seen.get())
                        .put("cancelToReturnMs", latency),
                )
            }
        }
        activeSession = null
        return JSONObject().put("runs", runs).put("maxCancelToReturnMs", maxLatency)
    }

    private fun burstScenario(model: LlamaCppModelInstance): JSONObject {
        val iterations = JSONArray()
        repeat(8) { i ->
            if (aborted) return@repeat
            model.createSession(SessionConfig(CTX_DEFAULT)).use { session ->
                activeSession = session
                val stats = generation(model, session, SHORT_PROMPT, 32)
                iterations.put(JSONObject().put("i", i).put("stats", stats))
            }
            SystemClock.sleep(1_500)
        }
        activeSession = null
        return JSONObject().put("iterations", iterations)
    }

    /**
     * Threads are per-instance in the production adapter (LoadConfig), so the sweep
     * reloads the model per thread count — cheap while the page cache is warm.
     */
    private fun threadSweep(pack: ResolvedModelPack): JSONArray {
        val cores = Runtime.getRuntime().availableProcessors()
        val counts = sortedSetOf(2, 4, cores.coerceAtLeast(1))
        val sweep = JSONArray()
        counts.forEach { threadCount ->
            if (aborted) return@forEach
            val model = runtime.loadModel(pack, LoadConfig(threads = threadCount, useMmap = true))
                as LlamaCppModelInstance
            try {
                model.createSession(SessionConfig(CTX_DEFAULT)).use { session ->
                    activeSession = session
                    val stats = generation(model, session, SHORT_PROMPT, 32)
                    sweep.put(JSONObject().put("threads", threadCount).put("stats", stats))
                }
            } finally {
                model.close()
            }
        }
        activeSession = null
        return sweep
    }

    private fun contextProbe(model: LlamaCppModelInstance): JSONArray {
        val probe = JSONArray()
        intArrayOf(512, 1024, 2048, 4096).forEach { ctx ->
            if (aborted) return@forEach
            val before = memSnapshot()
            val entry = JSONObject().put("nCtx", ctx).put("memBefore", before)
            try {
                model.createSession(SessionConfig(ctx)).use {
                    entry.put("ok", true)
                    entry.put("memWithContext", memSnapshot())
                }
            } catch (t: Throwable) {
                entry.put("ok", false)
                entry.put("error", "${t.javaClass.simpleName}: ${t.message}")
            }
            probe.put(entry)
        }
        return probe
    }

    // -- measurement helpers ------------------------------------------------------------

    private fun deviceInfo(): JSONObject = JSONObject().apply {
        put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("sdk", Build.VERSION.SDK_INT)
        put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("cores", Runtime.getRuntime().availableProcessors())
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        put("totalRamMb", mem.totalMem / (1024 * 1024))
        put("isLowRamDevice", am.isLowRamDevice)
    }

    private fun memSnapshot(): JSONObject {
        val status = runCatching { File("/proc/self/status").readText() }.getOrDefault("")
        fun kb(key: String): Long =
            Regex("$key:\\s+(\\d+) kB").find(status)?.groupValues?.get(1)?.toLong() ?: -1
        return JSONObject().apply {
            put("vmRssKb", kb("VmRSS"))
            put("vmHwmKb", kb("VmHWM")) // peak RSS
            put("nativeHeapAllocatedKb", Debug.getNativeHeapAllocatedSize() / 1024)
        }
    }

    private fun thermalSnapshot(): JSONObject = JSONObject().apply {
        runCatching {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tenthsC > 0) put("batteryTempC", tenthsC / 10.0)
        }
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            put("thermalStatus", pm.currentThermalStatus)
            if (Build.VERSION.SDK_INT >= 30) {
                put("thermalHeadroom10s", pm.getThermalHeadroom(10))
            }
        }
    }

    /**
     * Benchmark heuristic: leave one core for the OS, cap at 6. The production engine derives
     * this from big-core topology (§14.2); the thread sweep measures whether that matters.
     */
    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)

    private fun chatWrap(userPrompt: String): String =
        "<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"

    private fun step(name: String) {
        progress("▶ $name")
    }

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()
    private fun ms(from: Long, to: Long): Double = (to - from) / 1e6
    private fun rate(count: Int, millis: Double): Double =
        if (millis > 0) count / (millis / 1000.0) else -1.0

    private inline fun measureMs(block: () -> Unit): Double {
        val t0 = now()
        block()
        return ms(t0, now())
    }

    private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private companion object {
        const val CTX_DEFAULT = 1024

        val SHORT_PROMPT = "Rewrite this message so it sounds professional: " +
            "hey can u send me the report by tonight? thx"

        val MEDIUM_PROMPT = "Summarize the following paragraph in two sentences: " +
            "On-device machine learning has moved from a research curiosity to a product " +
            "requirement in only a few years. Users increasingly expect their phones to " +
            "draft replies, correct grammar, translate conversations, and describe images " +
            "without shipping their words to a data center. The engineering reality is " +
            "less romantic: mobile processors juggle strict power envelopes, memory is " +
            "shared with an operating system that reclaims it aggressively, and thermal " +
            "limits arrive within seconds of sustained computation. A runtime that wants " +
            "to serve real applications must therefore treat latency, memory, and heat as " +
            "budgets to be negotiated rather than constants to be assumed. It must load " +
            "expensive resources lazily, stream results as they are produced, cancel work " +
            "the moment it becomes irrelevant, and degrade gracefully on the billions of " +
            "devices that will never own a neural accelerator."
    }
}

package com.touvay.demo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.touvay.demo.benchmark.BenchmarkLength
import com.touvay.demo.benchmark.BenchmarkTone
import com.touvay.demo.benchmark.RewriteAcceptancePolicy
import com.touvay.demo.benchmark.RewriteBenchmarkCase
import com.touvay.demo.benchmark.RewriteBenchmarkCategory
import com.touvay.demo.benchmark.RewriteBenchmarkCorpus
import com.touvay.demo.benchmark.RewriteQualityScore
import com.touvay.demo.benchmark.RewriteQualityScorer
import com.touvay.demo.benchmark.percentile
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.RewriteCapability
import com.touvay.sdk.RewriteEvent
import com.touvay.sdk.RewriteLength
import com.touvay.sdk.RewriteRequest
import com.touvay.sdk.RewriteResult
import com.touvay.sdk.RewriteTone
import com.touvay.sdk.Touvay
import com.touvay.sdk.TouvayClient
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Production SDK/Binder Rewrite corpus benchmark. Uses synthetic, versioned inputs only. */
@RunWith(AndroidJUnit4::class)
class RewriteBenchmarkTest {
    @Test
    fun productionRewriteCorpusBenchmark(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val quick = arguments.getString("quick", "false").toBoolean()
        val enforce = arguments.getString("enforceThresholds", "false").toBoolean()
        val corpusText = context.assets.open(RewriteBenchmarkCorpus.ASSET_NAME)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val fullCorpus = RewriteBenchmarkCorpus.parse(corpusText)
        val corpus = if (quick) balancedQuickCorpus(fullCorpus) else fullCorpus

        val pack = DemoPackProvisioner.prepare(context)
        assertTrue(pack.weightsPresent, "signed Rewrite benchmark weights are missing")
        assertEquals(DemoPackProvisioner.EXPECTED_WEIGHTS_BYTES, pack.weightsBytes)

        val report = JSONObject()
            .put("schemaVersion", REPORT_SCHEMA_VERSION)
            .put("suite", "production-rewrite-corpus")
            .put("quick", quick)
            .put("enforceThresholds", enforce)
            .put("timestampEpochMillis", System.currentTimeMillis())
            .put("corpus", JSONObject()
                .put("version", RewriteBenchmarkCorpus.VERSION)
                .put("sha256", RewriteBenchmarkCorpus.sha256(corpusText))
                .put("fullCaseCount", fullCorpus.size)
                .put("executedCaseCount", corpus.size))
            .put("model", JSONObject()
                .put("id", arguments.getString("modelId", DEFAULT_MODEL_ID))
                .put("version", arguments.getString("modelVersion", DEFAULT_MODEL_VERSION))
                .put("sha256", arguments.getString("modelSha256", DEFAULT_MODEL_SHA256)))
            .put("device", deviceInfo(context))
            .put("acceptancePolicy", acceptancePolicyJson())

        val resourcesBefore = resourceSnapshot(context)
        val coldResults = JSONArray()
        corpus.distinctBy { it.category }.forEach { case ->
            val lifecycleStarted = now()
            val client = connectReady(context)
            try {
                coldResults.put(measureCase(context, client, case, "cold", lifecycleStarted))
            } finally {
                client.close()
                delay(COLD_RELEASE_DELAY_MS)
            }
        }

        val warmResults = JSONArray()
        val thermalSentinelBefore = JSONArray()
        val thermalSentinelAfter = JSONArray()
        val warmClient = connectReady(context)
        val cancellation: JSONArray
        val warmResourcesBefore: JSONObject
        val warmResourcesAfter: JSONObject
        try {
            warmResourcesBefore = resourceSnapshot(context)
            val sentinel = corpus.first { it.category == RewriteBenchmarkCategory.GRAMMAR }
            repeat(if (quick) QUICK_SENTINEL_RUNS else FULL_SENTINEL_RUNS) {
                thermalSentinelBefore.put(
                    measureCase(context, warmClient, sentinel, "thermal-sentinel-before", now()),
                )
            }
            corpus.forEach { case ->
                warmResults.put(measureCase(context, warmClient, case, "warm", now()))
            }
            repeat(if (quick) QUICK_SENTINEL_RUNS else FULL_SENTINEL_RUNS) {
                thermalSentinelAfter.put(
                    measureCase(context, warmClient, sentinel, "thermal-sentinel-after", now()),
                )
            }
            warmResourcesAfter = resourceSnapshot(context)
            cancellation = cancellationBenchmark(
                client = warmClient,
                case = corpus.first { it.category == RewriteBenchmarkCategory.EXPANSION },
                runs = if (quick) QUICK_CANCELLATION_RUNS else FULL_CANCELLATION_RUNS,
            )
        } finally {
            warmClient.close()
        }

        val resourcesAfter = resourceSnapshot(context)
        val summary = summarize(
            coldResults,
            warmResults,
            thermalSentinelBefore,
            thermalSentinelAfter,
            cancellation,
        )
        val warmOperationCount = corpus.size + thermalSentinelBefore.length() + thermalSentinelAfter.length()
        val energyPerWarmCase = energyPerCaseMWh(
            warmResourcesBefore,
            warmResourcesAfter,
            warmOperationCount,
        )
        val gate = evaluateAcceptance(summary, energyPerWarmCase)
        report
            .put("cold", coldResults)
            .put("warm", warmResults)
            .put("thermalSentinel", JSONObject()
                .put("before", thermalSentinelBefore)
                .put("after", thermalSentinelAfter))
            .put("cancellation", cancellation)
            .put("resources", JSONObject()
                .put("before", resourcesBefore)
                .put("after", resourcesAfter)
                .put("energyConsumedMWh", energyConsumedMWh(resourcesBefore, resourcesAfter))
                .put("warmWindowBefore", warmResourcesBefore)
                .put("warmWindowAfter", warmResourcesAfter)
                .put("energyPerWarmCaseMWh", energyPerWarmCase))
            .put("summary", summary)
            .put("acceptance", gate)

        val outputDirectory = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.let(::File)
            ?: context.getExternalFilesDir(null)
            ?: context.cacheDir
        outputDirectory.mkdirs()
        File(outputDirectory, RESULT_FILE).writeText(report.toString(2))

        val violations = gate.getJSONArray("violations")
        if (enforce) {
            assertEquals(0, violations.length(), "Rewrite acceptance violations: $violations")
        }
    }

    private suspend fun connectReady(context: Context): TouvayClient {
        val client = Touvay.connect(context)
        try {
            withTimeout(CAPABILITY_TIMEOUT_MS) {
                while (client.capabilities()[RewriteCapability.id] != CapabilityStatus.Ready) {
                    delay(CAPABILITY_POLL_MS)
                }
            }
            return client
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
    }

    private suspend fun measureCase(
        context: Context,
        client: TouvayClient,
        case: RewriteBenchmarkCase,
        executionClass: String,
        endToEndStarted: Long,
    ): JSONObject = coroutineScope {
        val memoryBefore = enginePssKb(context)
        val peakPss = AtomicLong(memoryBefore.coerceAtLeast(0L))
        val sampler = launch(Dispatchers.Default) {
            while (true) {
                peakPss.updateAndGet { max(it, enginePssKb(context).coerceAtLeast(0L)) }
                delay(MEMORY_SAMPLE_MS)
            }
        }
        val requestStarted = now()
        var firstDeltaAt = -1L
        var completed: RewriteResult? = null
        val preview = StringBuilder()
        try {
            client.rewrite().stream(case.toRequest()).collect { event ->
                when (event) {
                    is RewriteEvent.Delta -> {
                        if (firstDeltaAt < 0) firstDeltaAt = now()
                        preview.append(event.text)
                    }
                    is RewriteEvent.Completed -> completed = event.result
                }
            }
        } finally {
            sampler.cancelAndJoin()
        }
        val finished = now()
        val result = requireNotNull(completed) { "Rewrite completed without an authoritative result" }
        val firstAt = firstDeltaAt.takeIf { it >= 0 } ?: finished
        val quality = RewriteQualityScorer.score(case, result.text)
        JSONObject()
            .put("id", case.id)
            .put("category", case.category.wireName)
            .put("locale", case.locale)
            .put("executionClass", executionClass)
            .put("wallTtftMs", ms(endToEndStarted, firstAt))
            .put("requestTtftMs", ms(requestStarted, firstAt))
            .put("endToEndMs", ms(endToEndStarted, finished))
            .put("requestWallMs", ms(requestStarted, finished))
            .put("engineTtftMs", result.timing.timeToFirstTokenMillis)
            .put("engineTotalMs", result.timing.totalMillis)
            .put("deltaCount", result.timing.deltaCount)
            .put("outputChars", result.text.length)
            .put("previewChars", preview.length)
            .put("enginePssBeforeKb", memoryBefore)
            .put("enginePssPeakKb", peakPss.get())
            .put("enginePssAfterKb", enginePssKb(context))
            .put("thermalAfter", thermalSnapshot(context))
            .put("quality", quality.toJson())
            .put("output", result.text)
    }

    private suspend fun cancellationBenchmark(
        client: TouvayClient,
        case: RewriteBenchmarkCase,
        runs: Int,
    ): JSONArray = coroutineScope {
        val results = JSONArray()
        repeat(runs) { run ->
            val firstDelta = CompletableDeferred<Unit>()
            val collector: Job = launch(Dispatchers.Default) {
                client.rewrite().stream(case.toRequest()).collect { event ->
                    if (event is RewriteEvent.Delta && !firstDelta.isCompleted) {
                        firstDelta.complete(Unit)
                    }
                }
            }
            withTimeout(CANCELLATION_START_TIMEOUT_MS) { firstDelta.await() }
            val cancelledAt = now()
            collector.cancelAndJoin()
            results.put(JSONObject()
                .put("run", run)
                .put("cancelToCollectorReturnMs", ms(cancelledAt, now())))
        }
        results
    }

    private fun summarize(
        cold: JSONArray,
        warm: JSONArray,
        thermalBefore: JSONArray,
        thermalAfter: JSONArray,
        cancellation: JSONArray,
    ): JSONObject {
        val coldRows = cold.objects()
        val warmRows = warm.objects()
        val quality = warmRows.map { it.getJSONObject("quality") }
        val categoryMeans = JSONObject()
        RewriteBenchmarkCategory.entries.forEach { category ->
            val scores = warmRows
                .filter { it.getString("category") == category.wireName }
                .map { it.getJSONObject("quality").getDouble("composite") }
            categoryMeans.put(category.wireName, scores.average())
        }
        val cancellationValues = cancellation.objects()
            .map { it.getDouble("cancelToCollectorReturnMs") }
        val firstWindow = percentile(
            thermalBefore.objects().map { it.getDouble("wallTtftMs") },
            0.50,
        )
        val lastWindow = percentile(
            thermalAfter.objects().map { it.getDouble("wallTtftMs") },
            0.50,
        )
        val sustainedDegradation = if (firstWindow > 0) {
            ((lastWindow / firstWindow) - 1.0).coerceAtLeast(0.0)
        } else {
            0.0
        }
        return JSONObject()
            .put("qualityCompositeMean", quality.map { it.getDouble("composite") }.average())
            .put("qualityCompositeMin", quality.minOf { it.getDouble("composite") })
            .put("requiredTermRecallMean", quality.map { it.getDouble("requiredTermRecall") }.average())
            .put("forbiddenTermComplianceMean", quality.map { it.getDouble("forbiddenTermCompliance") }.average())
            .put("categoryQualityMeans", categoryMeans)
            .put("warmTtftP50Ms", percentile(warmRows.map { it.getDouble("wallTtftMs") }, 0.50))
            .put("warmTtftP95Ms", percentile(warmRows.map { it.getDouble("wallTtftMs") }, 0.95))
            .put("coldTtftP95Ms", percentile(coldRows.map { it.getDouble("wallTtftMs") }, 0.95))
            .put("warmEndToEndP95Ms", percentile(warmRows.map { it.getDouble("endToEndMs") }, 0.95))
            .put("sustainedTtftDegradation", sustainedDegradation)
            .put("peakEnginePssKb", (coldRows + warmRows).maxOf { it.getLong("enginePssPeakKb") })
            .put("maxThermalStatus", (coldRows + warmRows).maxOf {
                it.getJSONObject("thermalAfter").getInt("status")
            })
            .put("cancellationP95Ms", percentile(cancellationValues, 0.95))
            .put("cancellationMaxMs", cancellationValues.max())
    }

    private fun evaluateAcceptance(summary: JSONObject, energyPerWarmCase: Any): JSONObject {
        val violations = JSONArray()
        fun maximum(metric: String, threshold: Double) {
            val actual = summary.getDouble(metric)
            if (actual > threshold) violations.put("$metric=$actual > $threshold")
        }
        fun minimum(metric: String, threshold: Double) {
            val actual = summary.getDouble(metric)
            if (actual < threshold) violations.put("$metric=$actual < $threshold")
        }
        minimum("qualityCompositeMean", RewriteAcceptancePolicy.QUALITY_COMPOSITE_MEAN_MIN)
        minimum("qualityCompositeMin", RewriteAcceptancePolicy.QUALITY_SINGLE_CASE_MIN)
        minimum("requiredTermRecallMean", RewriteAcceptancePolicy.REQUIRED_TERM_RECALL_MIN)
        minimum("forbiddenTermComplianceMean", RewriteAcceptancePolicy.FORBIDDEN_TERM_COMPLIANCE_MIN)
        val categoryMeans = summary.getJSONObject("categoryQualityMeans")
        RewriteBenchmarkCategory.entries.forEach { category ->
            val actual = categoryMeans.getDouble(category.wireName)
            if (actual < RewriteAcceptancePolicy.QUALITY_CATEGORY_MEAN_MIN) {
                violations.put("category.${category.wireName}=$actual < " +
                    RewriteAcceptancePolicy.QUALITY_CATEGORY_MEAN_MIN)
            }
        }
        maximum("warmTtftP95Ms", RewriteAcceptancePolicy.WARM_TTFT_P95_MS_MAX)
        maximum("coldTtftP95Ms", RewriteAcceptancePolicy.COLD_TTFT_P95_MS_MAX)
        maximum("warmEndToEndP95Ms", RewriteAcceptancePolicy.WARM_END_TO_END_P95_MS_MAX)
        maximum(
            "sustainedTtftDegradation",
            RewriteAcceptancePolicy.SUSTAINED_TTFT_DEGRADATION_MAX,
        )
        maximum("cancellationP95Ms", RewriteAcceptancePolicy.CANCELLATION_P95_MS_MAX)
        maximum("cancellationMaxMs", RewriteAcceptancePolicy.CANCELLATION_MAX_MS_MAX)
        maximum("peakEnginePssKb", RewriteAcceptancePolicy.PEAK_ENGINE_PSS_KB_MAX.toDouble())
        maximum("maxThermalStatus", RewriteAcceptancePolicy.THERMAL_STATUS_MAX.toDouble())
        if (summary.getLong("peakEnginePssKb") <= 0) {
            violations.put("peakEnginePssKb unavailable")
        }
        if (energyPerWarmCase is Double &&
            energyPerWarmCase > RewriteAcceptancePolicy.ENERGY_PER_CASE_MWH_MAX
        ) {
            violations.put("energyPerWarmCaseMWh=$energyPerWarmCase > " +
                RewriteAcceptancePolicy.ENERGY_PER_CASE_MWH_MAX)
        } else if (energyPerWarmCase !is Double) {
            violations.put("energyPerWarmCaseMWh unavailable")
        }
        return JSONObject()
            .put("passed", violations.length() == 0)
            .put("violations", violations)
    }

    private fun balancedQuickCorpus(cases: List<RewriteBenchmarkCase>): List<RewriteBenchmarkCase> =
        RewriteBenchmarkCategory.entries.flatMap { category ->
            cases.filter { it.category == category }.take(QUICK_CASES_PER_CATEGORY)
        }

    private fun RewriteBenchmarkCase.toRequest() = RewriteRequest(
        text = source,
        tone = when (tone) {
            BenchmarkTone.NEUTRAL -> RewriteTone.NEUTRAL
            BenchmarkTone.FORMAL -> RewriteTone.FORMAL
            BenchmarkTone.CASUAL -> RewriteTone.CASUAL
        },
        length = when (length) {
            BenchmarkLength.PRESERVE -> RewriteLength.PRESERVE
            BenchmarkLength.SHORTER -> RewriteLength.SHORTER
            BenchmarkLength.LONGER -> RewriteLength.LONGER
        },
        outputLocaleBcp47 = locale,
    )

    private fun RewriteQualityScore.toJson() = JSONObject()
        .put("characterFScore", characterFScore)
        .put("tokenFScore", tokenFScore)
        .put("requiredTermRecall", requiredTermRecall)
        .put("forbiddenTermCompliance", forbiddenTermCompliance)
        .put("lengthCompliance", lengthCompliance)
        .put("composite", composite)

    private fun acceptancePolicyJson() = JSONObject()
        .put("version", RewriteAcceptancePolicy.VERSION)
        .put("qualityCompositeMeanMin", RewriteAcceptancePolicy.QUALITY_COMPOSITE_MEAN_MIN)
        .put("qualityCategoryMeanMin", RewriteAcceptancePolicy.QUALITY_CATEGORY_MEAN_MIN)
        .put("qualitySingleCaseMin", RewriteAcceptancePolicy.QUALITY_SINGLE_CASE_MIN)
        .put("requiredTermRecallMin", RewriteAcceptancePolicy.REQUIRED_TERM_RECALL_MIN)
        .put("forbiddenTermComplianceMin", RewriteAcceptancePolicy.FORBIDDEN_TERM_COMPLIANCE_MIN)
        .put("warmTtftP95MsMax", RewriteAcceptancePolicy.WARM_TTFT_P95_MS_MAX)
        .put("coldTtftP95MsMax", RewriteAcceptancePolicy.COLD_TTFT_P95_MS_MAX)
        .put("warmEndToEndP95MsMax", RewriteAcceptancePolicy.WARM_END_TO_END_P95_MS_MAX)
        .put("cancellationP95MsMax", RewriteAcceptancePolicy.CANCELLATION_P95_MS_MAX)
        .put("cancellationMaxMsMax", RewriteAcceptancePolicy.CANCELLATION_MAX_MS_MAX)
        .put("peakEnginePssKbMax", RewriteAcceptancePolicy.PEAK_ENGINE_PSS_KB_MAX)
        .put("thermalStatusMax", RewriteAcceptancePolicy.THERMAL_STATUS_MAX)
        .put("sustainedTtftDegradationMax", RewriteAcceptancePolicy.SUSTAINED_TTFT_DEGRADATION_MAX)
        .put("energyPerWarmCaseMWhMax", RewriteAcceptancePolicy.ENERGY_PER_CASE_MWH_MAX)

    private fun deviceInfo(context: Context): JSONObject {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("totalRamBytes", memory.totalMem)
            .put("lowRam", activity.isLowRamDevice)
    }

    private fun resourceSnapshot(context: Context): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val energy = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val charge = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return JSONObject()
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("energyCounterNWh", energy.takeUnless { it == Long.MIN_VALUE } ?: JSONObject.NULL)
            .put("chargeCounterUAh", charge.takeUnless { it == Long.MIN_VALUE } ?: JSONObject.NULL)
            .put("currentAverageUa", battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
            .put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
            .put("batteryTempC", (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0)
            .put("thermal", thermalSnapshot(context))
    }

    private fun thermalSnapshot(context: Context): JSONObject {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("status", power.currentThermalStatus)
            .put("headroom10s", runCatching { power.getThermalHeadroom(10) }.getOrNull() ?: JSONObject.NULL)
    }

    @Suppress("DEPRECATION")
    private fun enginePssKb(context: Context): Long {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = activity.runningAppProcesses
            ?.firstOrNull { it.processName == "${context.packageName}:touvay" }
            ?.pid
            ?: return -1L
        val info: Debug.MemoryInfo = activity.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()
            ?: return -1L
        return info.totalPss.toLong()
    }

    private fun energyConsumedMWh(before: JSONObject, after: JSONObject): Any {
        if (before.isNull("energyCounterNWh") || after.isNull("energyCounterNWh")) return JSONObject.NULL
        val deltaNWh = before.getLong("energyCounterNWh") - after.getLong("energyCounterNWh")
        return if (deltaNWh >= 0) deltaNWh / 1_000_000.0 else JSONObject.NULL
    }

    private fun energyPerCaseMWh(before: JSONObject, after: JSONObject, cases: Int): Any {
        val total = energyConsumedMWh(before, after)
        return if (total is Double && cases > 0) total / cases else JSONObject.NULL
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map(::getJSONObject)

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()
    private fun ms(from: Long, to: Long): Double = (to - from) / 1e6

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val RESULT_FILE = "rewrite-benchmark-result.json"
        const val DEFAULT_MODEL_ID = "touvay.demo.qwen2.5-0.5b-rewrite"
        const val DEFAULT_MODEL_VERSION = "1.0.0"
        const val DEFAULT_MODEL_SHA256 =
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        const val QUICK_CASES_PER_CATEGORY = 2
        const val QUICK_CANCELLATION_RUNS = 3
        const val FULL_CANCELLATION_RUNS = 10
        const val QUICK_SENTINEL_RUNS = 1
        const val FULL_SENTINEL_RUNS = 3
        const val COLD_RELEASE_DELAY_MS = 750L
        const val CAPABILITY_TIMEOUT_MS = 180_000L
        const val CAPABILITY_POLL_MS = 500L
        const val CANCELLATION_START_TIMEOUT_MS = 30_000L
        const val MEMORY_SAMPLE_MS = 50L
    }
}

package com.touvay.demo.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.RewriteCapability
import com.touvay.sdk.RewriteEvent
import com.touvay.sdk.RewriteLength
import com.touvay.sdk.RewriteRequest
import com.touvay.sdk.RewriteTone
import com.touvay.sdk.Touvay
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class ConsoleBenchmarkSummary(
    val cases: Int,
    val corpusSha256: String,
    val meanQuality: Double,
    val warmTtftP95Millis: Long,
    val warmEndToEndP95Millis: Long,
    val coldTtftP95Millis: Long,
    val cancellationP95Millis: Long,
    val peakPssBytes: Long,
    val maximumThermalStatus: Int,
    val energyPerWarmCaseMilliwattHours: Double?,
    val resultFile: File,
)

/** UI entry point for the production-SDK half of Rewrite Benchmark Suite v1. */
internal class ConsoleBenchmarkRunner(private val context: Context) {
    suspend fun run(
        quick: Boolean,
        onProgress: (completed: Int, total: Int, phase: String) -> Unit,
    ): ConsoleBenchmarkSummary {
        val corpusText = context.assets.open(RewriteBenchmarkCorpus.ASSET_NAME)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val allCases = RewriteBenchmarkCorpus.parse(corpusText)
        val cases = if (quick) {
            RewriteBenchmarkCategory.entries.flatMap { category ->
                allCases.filter { it.category == category }.take(2)
            }
        } else {
            allCases
        }
        val coldCases = RewriteBenchmarkCategory.entries.map { category ->
            allCases.first { it.category == category }
        }
        val rows = mutableListOf<CaseMeasurement>()
        val totalWork = coldCases.size + cases.size
        coldCases.forEachIndexed { index, case ->
            val started = SystemClock.elapsedRealtime()
            val client = connectReady()
            try {
                rows += execute(client, case, cold = true, startedAtMillis = started)
            } finally {
                client.close()
            }
            onProgress(index + 1, totalWork, "Cold execution")
        }

        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val energyBefore = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val client = connectReady()
        try {
            cases.forEachIndexed { index, case ->
                rows += execute(client, case, cold = false)
                onProgress(coldCases.size + index + 1, totalWork, "Warm corpus")
            }
        } finally {
            client.close()
        }
        val energyAfter = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val cancellationRuns = if (quick) 3 else 10
        val cancellation = List(cancellationRuns) { index ->
            val case = cases[index % cases.size]
            cancellationLatency(case)
        }
        val warm = rows.filterNot(CaseMeasurement::cold)
        val cold = rows.filter(CaseMeasurement::cold)
        val energyPerCase = energyDeltaMilliwattHours(energyBefore, energyAfter)
            ?.div(warm.size.coerceAtLeast(1))
        val resultFile = writeResult(
            quick = quick,
            corpusSha256 = RewriteBenchmarkCorpus.sha256(corpusText),
            rows = rows,
            cancellation = cancellation,
            energyPerCase = energyPerCase,
        )
        return ConsoleBenchmarkSummary(
            cases = warm.size,
            corpusSha256 = RewriteBenchmarkCorpus.sha256(corpusText),
            meanQuality = warm.map { it.quality }.average(),
            warmTtftP95Millis = percentileMillis(warm.map { it.ttftMillis }),
            warmEndToEndP95Millis = percentileMillis(warm.map { it.endToEndMillis }),
            coldTtftP95Millis = percentileMillis(cold.map { it.ttftMillis }),
            cancellationP95Millis = percentileMillis(cancellation),
            peakPssBytes = rows.maxOfOrNull { it.pssBytes } ?: 0L,
            maximumThermalStatus = rows.maxOfOrNull { it.thermalStatus } ?: 0,
            energyPerWarmCaseMilliwattHours = energyPerCase,
            resultFile = resultFile,
        )
    }

    private suspend fun execute(
        client: com.touvay.sdk.TouvayClient,
        case: RewriteBenchmarkCase,
        cold: Boolean,
        startedAtMillis: Long = SystemClock.elapsedRealtime(),
    ): CaseMeasurement {
        val started = startedAtMillis
        var firstDelta: Long? = null
        var output = ""
        client.rewrite().stream(case.request()).collect { event ->
            when (event) {
                is RewriteEvent.Delta -> if (firstDelta == null) {
                    firstDelta = SystemClock.elapsedRealtime()
                }
                is RewriteEvent.Completed -> output = event.result.text
            }
        }
        val completed = SystemClock.elapsedRealtime()
        return CaseMeasurement(
            id = case.id,
            category = case.category.wireName,
            cold = cold,
            ttftMillis = (firstDelta ?: completed) - started,
            endToEndMillis = completed - started,
            quality = RewriteQualityScorer.score(case, output).composite,
            pssBytes = enginePssBytes(),
            thermalStatus = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .currentThermalStatus,
        )
    }

    private suspend fun cancellationLatency(case: RewriteBenchmarkCase): Long = coroutineScope {
        val client = connectReady()
        try {
            val firstDelta = CompletableDeferred<Unit>()
            lateinit var request: Job
            request = launch {
                client.rewrite().stream(case.request()).collect { event ->
                    if (event is RewriteEvent.Delta) firstDelta.complete(Unit)
                }
            }
            withTimeoutOrNull(CANCEL_FIRST_DELTA_TIMEOUT_MILLIS) { firstDelta.await() }
            val started = SystemClock.elapsedRealtime()
            request.cancel()
            request.join()
            SystemClock.elapsedRealtime() - started
        } finally {
            client.close()
        }
    }

    private fun enginePssBytes(): Long {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = activity.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == "${context.packageName}:touvay" }?.pid ?: return 0L
        return activity.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()?.totalPss
            ?.times(1024L) ?: 0L
    }

    private suspend fun connectReady(): com.touvay.sdk.TouvayClient {
        val client = Touvay.connect(context)
        repeat(CAPABILITY_POLL_ATTEMPTS) {
            if (client.capabilities()[RewriteCapability.id] == CapabilityStatus.Ready) return client
            delay(CAPABILITY_POLL_MILLIS)
        }
        client.close()
        error("Rewrite capability did not become ready")
    }

    private fun percentileMillis(values: List<Long>): Long =
        percentile(values.map(Long::toDouble), 0.95).toLong()

    private fun writeResult(
        quick: Boolean,
        corpusSha256: String,
        rows: List<CaseMeasurement>,
        cancellation: List<Long>,
        energyPerCase: Double?,
    ): File {
        val file = File(requireNotNull(context.getExternalFilesDir(null)), "rewrite-console-benchmark.json")
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("suite", "rewrite-benchmark-v1")
            .put("quick", quick)
            .put("corpusSha256", corpusSha256)
            .put("energyPerWarmCaseMilliwattHours", energyPerCase ?: JSONObject.NULL)
            .put("cancellationMillis", JSONArray(cancellation))
        root.put(
            "cases",
            JSONArray().apply {
                rows.forEach { row ->
                    put(
                        JSONObject()
                            .put("id", row.id)
                            .put("category", row.category)
                            .put("cold", row.cold)
                            .put("ttftMillis", row.ttftMillis)
                            .put("endToEndMillis", row.endToEndMillis)
                            .put("quality", row.quality)
                            .put("pssBytes", row.pssBytes)
                            .put("thermalStatus", row.thermalStatus),
                    )
                }
            },
        )
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    private fun RewriteBenchmarkCase.request(): RewriteRequest = RewriteRequest(
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

    private fun energyDeltaMilliwattHours(before: Long, after: Long): Double? {
        if (before == Long.MIN_VALUE || after == Long.MIN_VALUE || before <= after) return null
        return (before - after).toDouble() / 1_000_000.0
    }

    private data class CaseMeasurement(
        val id: String,
        val category: String,
        val cold: Boolean,
        val ttftMillis: Long,
        val endToEndMillis: Long,
        val quality: Double,
        val pssBytes: Long,
        val thermalStatus: Int,
    )

    private companion object {
        const val CANCEL_FIRST_DELTA_TIMEOUT_MILLIS = 30_000L
        const val CAPABILITY_POLL_ATTEMPTS = 180
        const val CAPABILITY_POLL_MILLIS = 1_000L
    }
}

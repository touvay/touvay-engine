package com.touvay.demo

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.touvay.demo.benchmark.ConsoleBenchmarkRunner
import com.touvay.engine.models.ModelManagerRevisionInfo
import com.touvay.engine.models.ModelManagerRevisionState
import com.touvay.engine.models.ModelRevisionIdentity
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.RewriteCapability
import com.touvay.sdk.RewriteEvent
import com.touvay.sdk.RewriteLength
import com.touvay.sdk.RewriteRequest
import com.touvay.sdk.RewriteTone
import com.touvay.sdk.Touvay
import com.touvay.sdk.TouvayClient
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Non-production diagnostics, model administration, capability testing, and benchmarks. */
class MainActivity : ComponentActivity() {
    private val consoleLog = ConsoleLog()
    private lateinit var models: DeveloperModelRepository
    private lateinit var optionsStore: ConsoleOptionsStore
    private var client: TouvayClient? = null
    private var connectionJob: Job? = null
    private var executionJob: Job? = null
    private var benchmarkJob: Job? = null
    private var modelJob: Job? = null
    private var importedSource: Path? = null
    private var verifiedImport: ModelRevisionIdentity? = null
    private var revisions: List<ModelManagerRevisionInfo> = emptyList()

    private lateinit var content: FrameLayout
    private lateinit var dashboardText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var logText: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var modelState: TextView
    private lateinit var testerInput: EditText
    private lateinit var testerLocale: EditText
    private lateinit var testerTone: Spinner
    private lateinit var testerLength: Spinner
    private lateinit var testerStreaming: CheckBox
    private lateinit var testerRun: Button
    private lateinit var testerCancel: Button
    private lateinit var testerOutput: TextView
    private lateinit var testerStructured: TextView
    private lateinit var benchmarkQuick: CheckBox
    private lateinit var benchmarkRun: Button
    private lateinit var benchmarkCancel: Button
    private lateinit var benchmarkText: TextView

    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                modelState.text = "Importing signed pack directory…"
                runCatching { models.importDirectory(uri) }
                    .onSuccess { source ->
                        importedSource = source
                        verifiedImport = null
                        modelState.text = "Imported to private staging. Verification required."
                        consoleLog.record("model_imported")
                    }
                    .onFailure {
                        modelState.text = "Model import failed. Check the selected directory layout."
                        consoleLog.record("model_import_failed")
                    }
                renderLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = DeveloperModelRepository(this)
        optionsStore = ConsoleOptionsStore(this)
        buildUi()
        show(Screen.DASHBOARD)
        lifecycleScope.launch {
            val pack = runCatching { models.prepareBuiltIn() }.getOrNull()
            consoleLog.record(
                "console_started",
                if (pack?.weightsPresent == true) "signed source ready" else "weights missing",
            )
            connectEngine(refreshModelsAfterConnect = true)
        }
    }

    override fun onDestroy() {
        executionJob?.cancel()
        benchmarkJob?.cancel()
        modelJob?.cancel()
        connectionJob?.cancel()
        disconnectEngine()
        super.onDestroy()
    }

    private fun buildUi() {
        content = FrameLayout(this)
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            Screen.entries.forEach { screen ->
                addView(Button(this@MainActivity).apply {
                    text = screen.label
                    setOnClickListener { show(screen) }
                })
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Touvay Engine Developer Console"
                textSize = 22f
                setPadding(dp(16), dp(16), dp(16), dp(8))
            }, MATCH_PARENT, WRAP_CONTENT)
            addView(HorizontalScrollView(this@MainActivity).apply { addView(navigation) })
            addView(
                content,
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun show(screen: Screen) {
        content.removeAllViews()
        content.addView(
            when (screen) {
                Screen.DASHBOARD -> dashboard()
                Screen.TESTER -> capabilityTester()
                Screen.MODELS -> modelManager()
                Screen.BENCHMARK -> benchmark()
                Screen.DIAGNOSTICS -> diagnostics()
                Screen.LOGS -> logs()
                Screen.SETTINGS -> settings()
            },
            MATCH_PARENT,
            MATCH_PARENT,
        )
    }

    private fun dashboard(): View = page("Dashboard") {
        dashboardText = body("Collecting Engine status…")
        addView(dashboardText)
        addView(action("Refresh") { connectEngine(refreshModelsAfterConnect = true) })
    }

    private fun capabilityTester(): View = page("Capability Tester — Rewrite") {
        testerInput = EditText(this@MainActivity).apply {
            setText("we need to make this message clear and professional")
            minLines = 4
            hint = "Text to rewrite"
        }
        testerTone = spinner(arrayOf("Neutral", "Formal", "Casual"))
        testerLength = spinner(arrayOf("Preserve", "Shorter", "Longer"))
        testerLocale = EditText(this@MainActivity).apply { hint = "Output locale, for example en-US" }
        testerStreaming = CheckBox(this@MainActivity).apply {
            text = "Streaming preview"
            isChecked = true
        }
        testerRun = action("Run Rewrite", ::runRewrite)
        testerCancel = action("Cancel", ::cancelRewrite).apply { isEnabled = false }
        testerOutput = body("No output")
        testerStructured = body("Structured result will appear here.")
        addView(testerInput)
        addView(testerTone)
        addView(testerLength)
        addView(testerLocale)
        addView(testerStreaming)
        addView(testerRun)
        addView(testerCancel)
        addView(heading("Streaming / final output"))
        addView(testerOutput)
        addView(heading("Structured output and timing"))
        addView(testerStructured)
    }

    private fun modelManager(): View = page("Model Manager") {
        addView(body("All operations use the signed pack verifier and transactional Model Manager."))
        modelSpinner = Spinner(this@MainActivity)
        modelState = body("No imported pack selected.")
        addView(modelSpinner)
        val sources = models.acquisitionSources()
        addView(action(sources.first { it.id == "local_directory" }.label) {
            directoryPicker.launch(null)
        })
        addView(action("Verify imported pack") { verifyImportedPack() })
        addView(action("Install verified pack") { installImportedPack() })
        addView(action("Activate selected") { mutateSelectedModel(ModelOperation.ACTIVATE) })
        addView(action("Rollback to selected") { mutateSelectedModel(ModelOperation.ROLLBACK) })
        addView(action("Delete selected inactive version") { mutateSelectedModel(ModelOperation.DELETE) })
        addView(Button(this@MainActivity).apply {
            val catalog = sources.first { it.id == "official_catalog" }
            text = catalog.label
            isEnabled = catalog.available
            contentDescription = "Official Model Catalog downloads are not implemented"
        })
        addView(modelState)
        updateModelSpinner()
    }

    private fun benchmark(): View = page("Rewrite Benchmark Suite") {
        addView(body("Runs the production SDK corpus path. Exact token throughput remains in the companion Runtime benchmark host."))
        benchmarkQuick = CheckBox(this@MainActivity).apply {
            text = "Quick run (14 warm + 7 cold cases)"
            isChecked = true
        }
        benchmarkRun = action("Run benchmark", ::runBenchmark)
        benchmarkCancel = action("Cancel benchmark", ::cancelBenchmark).apply { isEnabled = false }
        benchmarkText = body("No benchmark has run in this session.")
        addView(benchmarkQuick)
        addView(benchmarkRun)
        addView(benchmarkCancel)
        addView(benchmarkText)
    }

    private fun diagnostics(): View = page("Diagnostics") {
        diagnosticText = body(currentDiagnosticReport())
        addView(diagnosticText)
        addView(action("Run health check") { connectEngine(refreshModelsAfterConnect = false) })
    }

    private fun logs(): View = page("Developer Logs") {
        addView(body("Content-free lifecycle and operation events only. Inputs and model outputs are never logged."))
        logText = body(consoleLog.render())
        addView(action("Refresh logs", ::renderLogs))
        addView(action("Clear logs") {
            consoleLog.clear()
            renderLogs()
        })
        addView(logText)
    }

    private fun settings(): View = page("Developer Settings") {
        val initial = optionsStore.read()
        val verbose = CheckBox(this@MainActivity).apply {
            text = "Verbose content-free logging"
            isChecked = initial.verboseLogs
        }
        val retain = CheckBox(this@MainActivity).apply {
            text = "Retain benchmark result files"
            isChecked = initial.retainBenchmarkResults
        }
        val experimental = CheckBox(this@MainActivity).apply {
            text = "Experimental developer features"
            isChecked = initial.experimentalFeatures
        }
        val save = action("Save developer options") {
            optionsStore.update(ConsoleOptions(verbose.isChecked, retain.isChecked, experimental.isChecked))
            consoleLog.record("settings_saved")
        }
        addView(verbose)
        addView(retain)
        addView(experimental)
        addView(save)
        addView(heading("Trusted keys"))
        addView(body(models.trustedKeySummary()))
        addView(heading("Installed models"))
        addView(body(renderRevisionSummary()))
    }

    private fun connectEngine(refreshModelsAfterConnect: Boolean) {
        if (
            connectionJob?.isActive == true ||
            benchmarkJob?.isActive == true ||
            modelJob?.isActive == true
        ) return
        connectionJob = lifecycleScope.launch {
            disconnectEngine()
            setDiagnostic(ConsoleDiagnosticCode.ENGINE_NOT_RUNNING, "Connecting")
            try {
                val connected = Touvay.connect(this@MainActivity)
                client = connected
                var status: CapabilityStatus? = null
                for (attempt in 0 until CAPABILITY_POLL_ATTEMPTS) {
                    status = connected.capabilities()[RewriteCapability.id]
                    if (status == CapabilityStatus.Ready) break
                    delay(CAPABILITY_POLL_MILLIS)
                }
                val diagnostic = ConsoleDiagnostics.fromCapability(status)
                setDiagnostic(diagnostic, statusLabel(status))
                consoleLog.record("engine_connected", diagnostic.name.lowercase())
                if (::testerRun.isInitialized) testerRun.isEnabled = status == CapabilityStatus.Ready
                if (refreshModelsAfterConnect) {
                    disconnectEngine()
                    refreshModels()
                    connectionJob = null
                    connectEngine(refreshModelsAfterConnect = false)
                    return@launch
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val diagnostic = ConsoleDiagnostics.fromFailure(failure)
                setDiagnostic(diagnostic, "Connection failed")
                consoleLog.record("engine_connection_failed", diagnostic.name.lowercase())
            }
            renderLogs()
        }
    }

    private fun disconnectEngine() {
        executionJob?.cancel()
        executionJob = null
        client?.close()
        client = null
    }

    private fun runRewrite() {
        val connected = client ?: run {
            setDiagnostic(ConsoleDiagnosticCode.ENGINE_NOT_RUNNING, "Connect before testing")
            return
        }
        executionJob?.cancel()
        executionJob = lifecycleScope.launch {
            testerRun.isEnabled = false
            testerCancel.isEnabled = true
            testerOutput.text = ""
            testerStructured.text = "Running…"
            val request = RewriteRequest(
                text = testerInput.text.toString(),
                tone = when (testerTone.selectedItemPosition) {
                    1 -> RewriteTone.FORMAL
                    2 -> RewriteTone.CASUAL
                    else -> RewriteTone.NEUTRAL
                },
                length = when (testerLength.selectedItemPosition) {
                    1 -> RewriteLength.SHORTER
                    2 -> RewriteLength.LONGER
                    else -> RewriteLength.PRESERVE
                },
                outputLocaleBcp47 = testerLocale.text.toString().trim().ifBlank { null },
            )
            val wallStarted = SystemClock.elapsedRealtime()
            try {
                if (testerStreaming.isChecked) {
                    val preview = StringBuilder()
                    connected.rewrite().stream(request).collect { event ->
                        when (event) {
                            is RewriteEvent.Delta -> {
                                preview.append(event.text)
                                testerOutput.text = preview.toString()
                            }
                            is RewriteEvent.Completed -> renderResult(event.result, wallStarted)
                        }
                    }
                } else {
                    renderResult(connected.rewrite().execute(request), wallStarted)
                }
                consoleLog.record("rewrite_completed")
            } catch (cancelled: CancellationException) {
                testerStructured.text = ConsoleDiagnosticCode.REQUEST_CANCELLED.message
                consoleLog.record("rewrite_cancelled")
            } catch (failure: Throwable) {
                val diagnostic = ConsoleDiagnostics.fromFailure(failure)
                testerStructured.text = diagnostic.message
                consoleLog.record("rewrite_failed", diagnostic.name.lowercase())
            } finally {
                testerRun.isEnabled = client != null
                testerCancel.isEnabled = false
                renderLogs()
            }
        }
    }

    private fun renderResult(result: com.touvay.sdk.RewriteResult, wallStarted: Long) {
        testerOutput.text = result.text
        testerStructured.text = buildString {
            appendLine("Disposition: ${result.disposition}")
            appendLine("TTFT: ${result.timing.timeToFirstTokenMillis} ms")
            appendLine("Engine total: ${result.timing.totalMillis} ms")
            appendLine("Wall total: ${SystemClock.elapsedRealtime() - wallStarted} ms")
            append("Deltas: ${result.timing.deltaCount}")
        }
    }

    private fun cancelRewrite() {
        executionJob?.cancel()
        executionJob = null
    }

    private fun verifyImportedPack() {
        val source = importedSource ?: run {
            modelState.text = "Import a signed model-pack directory first."
            return
        }
        withEngineOffline("model_verify") {
            val identity = models.verify(source)
            verifiedImport = identity
            modelState.text = "Verified ${identity.display()} using the signed-pack pipeline."
        }
    }

    private fun installImportedPack() {
        val source = importedSource ?: run {
            modelState.text = "Import and verify a signed model pack first."
            return
        }
        if (verifiedImport == null) {
            modelState.text = "Verification is required before installation."
            return
        }
        withEngineOffline("model_install") {
            val identity = models.install(source)
            modelState.text = "Installed ${identity.display()} as an inactive revision."
        }
    }

    private fun mutateSelectedModel(operation: ModelOperation) {
        val selected = revisions.getOrNull(modelSpinner.selectedItemPosition) ?: run {
            modelState.text = "Select an installed model revision first."
            return
        }
        withEngineOffline("model_${operation.name.lowercase()}") {
            when (operation) {
                ModelOperation.ACTIVATE -> {
                    models.activate(selected.identity)
                    modelState.text = "Activated ${selected.identity.display()}."
                }
                ModelOperation.ROLLBACK -> {
                    models.rollback(selected.identity)
                    modelState.text = "Rolled back to ${selected.identity.display()}."
                }
                ModelOperation.DELETE -> {
                    if (selected.state == ModelManagerRevisionState.ACTIVE) {
                        modelState.text = "Active model versions cannot be deleted."
                    } else {
                        val result = models.deleteInactive(selected.identity)
                        modelState.text = "Inactive model deletion: $result"
                    }
                }
            }
        }
    }

    private fun withEngineOffline(event: String, operation: suspend () -> Unit) {
        if (modelJob?.isActive == true) return
        modelJob = lifecycleScope.launch {
            disconnectEngine()
            modelState.text = "Working…"
            try {
                operation()
                consoleLog.record(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                modelState.text = when (event) {
                    "model_verify" -> ConsoleDiagnosticCode.MODEL_VERIFICATION_FAILED.message
                    "model_install" -> "Model installation failed"
                    else -> "Model operation failed"
                }
                consoleLog.record("${event}_failed")
            } finally {
                refreshModels()
                modelJob = null
                connectEngine(refreshModelsAfterConnect = false)
                renderLogs()
            }
        }
    }

    private suspend fun refreshModels() {
        revisions = runCatching { models.revisions() }.getOrElse { emptyList() }
        updateModelSpinner()
        renderDashboard(ConsoleDiagnosticCode.ENGINE_NOT_RUNNING, "Catalog refreshed")
    }

    private fun updateModelSpinner() {
        if (!::modelSpinner.isInitialized) return
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            revisions.map { revision ->
                "${revision.identity.packId}@${revision.identity.packVersion} • ${revision.state}"
            }.ifEmpty { listOf("No installed models") },
        )
    }

    private fun runBenchmark() {
        if (benchmarkJob?.isActive == true) return
        benchmarkJob = lifecycleScope.launch {
            disconnectEngine()
            benchmarkRun.isEnabled = false
            benchmarkCancel.isEnabled = true
            consoleLog.record("benchmark_started", if (benchmarkQuick.isChecked) "quick" else "full")
            try {
                val summary = ConsoleBenchmarkRunner(this@MainActivity).run(benchmarkQuick.isChecked) {
                        completed, total, phase ->
                    runOnUiThread { benchmarkText.text = "$phase: $completed / $total" }
                }
                benchmarkText.text = buildString {
                    appendLine("Warm cases: ${summary.cases}")
                    appendLine("Mean quality: ${"%.3f".format(Locale.US, summary.meanQuality)}")
                    appendLine("Warm TTFT p95: ${summary.warmTtftP95Millis} ms")
                    appendLine("Cold wall TTFT p95: ${summary.coldTtftP95Millis} ms")
                    appendLine("End-to-end p95: ${summary.warmEndToEndP95Millis} ms")
                    appendLine("Cancellation p95: ${summary.cancellationP95Millis} ms")
                    appendLine("Peak Engine PSS: ${summary.peakPssBytes / MIB} MiB")
                    appendLine("Max thermal status: ${summary.maximumThermalStatus}")
                    appendLine("Energy/warm case: ${summary.energyPerWarmCaseMilliwattHours ?: "unavailable"} mWh")
                    append("Result: ${summary.resultFile.absolutePath}")
                }
                consoleLog.record("benchmark_completed")
            } catch (cancelled: CancellationException) {
                benchmarkText.text = "Benchmark cancelled."
                consoleLog.record("benchmark_cancelled")
            } catch (failure: Throwable) {
                val diagnostic = ConsoleDiagnostics.fromFailure(failure)
                benchmarkText.text = diagnostic.message
                consoleLog.record("benchmark_failed", diagnostic.name.lowercase())
            } finally {
                benchmarkRun.isEnabled = true
                benchmarkCancel.isEnabled = false
                benchmarkJob = null
                connectEngine(refreshModelsAfterConnect = false)
                renderLogs()
            }
        }
    }

    private fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
    }

    private fun setDiagnostic(code: ConsoleDiagnosticCode, detail: String) {
        renderDashboard(code, detail)
        if (::diagnosticText.isInitialized) diagnosticText.text = currentDiagnosticReport(code, detail)
    }

    private fun renderDashboard(code: ConsoleDiagnosticCode, detail: String) {
        if (!::dashboardText.isInitialized) return
        val activity = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val enginePid = activity.runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == "$packageName:touvay" }?.pid
        val pss = enginePid?.let { activity.getProcessMemoryInfo(intArrayOf(it)).firstOrNull()?.totalPss }
        val thermal = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        val battery = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val active = revisions.singleOrNull { it.state == ModelManagerRevisionState.ACTIVE }
        dashboardText.text = buildString {
            appendLine("Engine: ${code.message}")
            appendLine("Connected: ${client != null}")
            appendLine("Runtime: llama.cpp b5199 / adapter 1.0.0")
            appendLine("Active model: ${active?.identity?.display() ?: "none"}")
            appendLine("Engine PSS: ${pss?.let { "$it KiB" } ?: "not running"}")
            appendLine("Thermal status: $thermal")
            appendLine("Battery: ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
            append("Detail: $detail")
        }
    }

    private fun currentDiagnosticReport(
        code: ConsoleDiagnosticCode = if (client == null) {
            ConsoleDiagnosticCode.ENGINE_NOT_RUNNING
        } else {
            ConsoleDiagnosticCode.READY
        },
        detail: String = "No additional action required",
    ): String = buildString {
        appendLine(code.message)
        appendLine("Recommended action: ${recommendation(code)}")
        appendLine("Detail: $detail")
        append("Internal exception text is intentionally hidden.")
    }

    private fun recommendation(code: ConsoleDiagnosticCode): String = when (code) {
        ConsoleDiagnosticCode.READY -> "Run a capability test or benchmark"
        ConsoleDiagnosticCode.ENGINE_NOT_INSTALLED -> "Install a build that packages Engine service"
        ConsoleDiagnosticCode.ENGINE_NOT_RUNNING -> "Reconnect the Engine"
        ConsoleDiagnosticCode.MODEL_MISSING -> "Import, verify, install, and activate a signed Rewrite pack"
        ConsoleDiagnosticCode.MODEL_VERIFICATION_FAILED -> "Check the pack signature and trusted key"
        ConsoleDiagnosticCode.ENGINE_BUSY -> "Wait for the current request or cancel it"
        ConsoleDiagnosticCode.INSUFFICIENT_MEMORY -> "Close memory-heavy apps and use a smaller compatible model"
        ConsoleDiagnosticCode.ENGINE_UPDATE_REQUIRED -> "Update the Engine and Developer Console together"
        ConsoleDiagnosticCode.REQUEST_CANCELLED -> "Start a new explicit request if needed"
        ConsoleDiagnosticCode.INVALID_INPUT -> "Correct the structured Rewrite request"
        ConsoleDiagnosticCode.RUNTIME_FAILURE,
        ConsoleDiagnosticCode.UNKNOWN,
        -> "Review content-free developer logs and retry"
    }

    private fun statusLabel(status: CapabilityStatus?): String = when (status) {
        CapabilityStatus.Ready -> "text.rewrite@1 ready"
        is CapabilityStatus.DownloadRequired -> "Signed Rewrite pack required"
        is CapabilityStatus.DeviceNotSupported -> "Device is not supported"
        CapabilityStatus.DisabledByPolicy -> "Capability disabled by policy"
        is CapabilityStatus.Unknown -> "Unknown capability status ${status.statusCode}"
        null -> "Rewrite capability absent"
    }

    private fun renderLogs() {
        if (::logText.isInitialized) logText.text = consoleLog.render()
    }

    private fun renderRevisionSummary(): String = if (revisions.isEmpty()) {
        "No installed model revisions."
    } else {
        revisions.joinToString("\n") { "${it.identity.display()} • ${it.state} • ${it.compatibility}" }
    }

    private fun page(title: String, populate: LinearLayout.() -> Unit): View =
        ScrollView(this).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(32))
                    addView(heading(title))
                    populate()
                },
                MATCH_PARENT,
                WRAP_CONTENT,
            )
        }

    private fun heading(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun body(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextIsSelectable(true)
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun spinner(values: Array<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            values,
        )
    }

    private fun ModelRevisionIdentity.display(): String = "$packId@$packVersion (${manifestSha256.take(12)})"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Screen(val label: String) {
        DASHBOARD("Dashboard"),
        TESTER("Tester"),
        MODELS("Models"),
        BENCHMARK("Benchmark"),
        DIAGNOSTICS("Diagnostics"),
        LOGS("Logs"),
        SETTINGS("Settings"),
    }

    private enum class ModelOperation { ACTIVATE, ROLLBACK, DELETE }

    private companion object {
        const val CAPABILITY_POLL_ATTEMPTS = 180
        const val CAPABILITY_POLL_MILLIS = 1_000L
        const val MIB = 1024L * 1024L
    }
}

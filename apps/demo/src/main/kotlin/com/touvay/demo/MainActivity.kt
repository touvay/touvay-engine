package com.touvay.demo

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.RewriteCapability
import com.touvay.sdk.RewriteEvent
import com.touvay.sdk.RewriteLength
import com.touvay.sdk.RewriteRequest
import com.touvay.sdk.RewriteTone
import com.touvay.sdk.Touvay
import com.touvay.sdk.TouvayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Engineering-only end-to-end validation surface for `text.rewrite@1`. */
class MainActivity : ComponentActivity() {
    private var client: TouvayClient? = null
    private var execution: Job? = null
    private var packState: DemoPackState? = null

    private lateinit var input: EditText
    private lateinit var locale: EditText
    private lateinit var tone: Spinner
    private lateinit var length: Spinner
    private lateinit var run: Button
    private lateinit var cancel: Button
    private lateinit var output: TextView
    private lateinit var timing: TextView
    private lateinit var metadata: TextView
    private lateinit var diagnostics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        lifecycleScope.launch { connectAndAwaitCapability() }
    }

    override fun onDestroy() {
        execution?.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }

    private suspend fun connectAndAwaitCapability() {
        metadata.text = getString(R.string.preparing_pack)
        packState = withContext(Dispatchers.IO) {
            DemoPackProvisioner.prepare(this@MainActivity)
        }
        updateDiagnostics()
        val connected = try {
            Touvay.connect(this)
        } catch (failure: Exception) {
            metadata.text = "Engine connection failed: ${failure.message}"
            return
        }
        client = connected
        repeat(CAPABILITY_POLL_ATTEMPTS) {
            val status = connected.capabilities()[RewriteCapability.id]
            metadata.text = "Capability: ${RewriteCapability.id.value}\n" +
                "Schema: ${RewriteCapability.schemaVersion}\nStatus: $status"
            if (status == CapabilityStatus.Ready) {
                run.isEnabled = true
                return
            }
            delay(CAPABILITY_POLL_MILLIS)
        }
        metadata.append("\nPack did not become ready. Check diagnostics and restart the app.")
    }

    private fun executeRewrite() {
        val connected = client ?: return
        execution?.cancel()
        execution = lifecycleScope.launch {
            run.isEnabled = false
            cancel.isEnabled = true
            output.text = ""
            timing.text = getString(R.string.executing)
            val preview = StringBuilder()
            val started = SystemClock.elapsedRealtime()
            try {
                connected.rewrite().stream(
                    RewriteRequest(
                        text = input.text.toString(),
                        tone = selectedTone(),
                        length = selectedLength(),
                        outputLocaleBcp47 = locale.text.toString().trim().ifEmpty { null },
                    ),
                ).collect { event ->
                    when (event) {
                        is RewriteEvent.Delta -> {
                            preview.append(event.text)
                            output.text = preview.toString()
                            timing.text = "Streaming delta ${event.sequence + 1}"
                        }
                        is RewriteEvent.Completed -> {
                            output.text = event.result.text
                            val stats = event.result.timing
                            timing.text = "Disposition: ${event.result.disposition}\n" +
                                "TTFT: ${stats.timeToFirstTokenMillis} ms\n" +
                                "Engine total: ${stats.totalMillis} ms\n" +
                                "Wall total: ${SystemClock.elapsedRealtime() - started} ms\n" +
                                "Deltas: ${stats.deltaCount}"
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                timing.text = getString(R.string.cancelled)
                throw cancelled
            } catch (failure: Exception) {
                timing.text = "Rewrite failed: ${failure.message}"
            } finally {
                cancel.isEnabled = false
                run.isEnabled = true
            }
        }
    }

    private fun selectedTone(): RewriteTone = when (tone.selectedItemPosition) {
        1 -> RewriteTone.FORMAL
        2 -> RewriteTone.CASUAL
        else -> RewriteTone.NEUTRAL
    }

    private fun selectedLength(): RewriteLength = when (length.selectedItemPosition) {
        1 -> RewriteLength.SHORTER
        2 -> RewriteLength.LONGER
        else -> RewriteLength.PRESERVE
    }

    private fun updateDiagnostics() {
        val state = packState
        diagnostics.text = buildString {
            appendLine("Runtime registration: llama.cpp b5199 / adapter 1.0.0")
            appendLine("Runtime/model identity remains hidden from the SDK API.")
            appendLine("Pack: touvay.demo.qwen2.5-0.5b-rewrite@1.0.0")
            appendLine("Trust: demo-only Ed25519 engine-pinned public key")
            appendLine("Offline source: ${state?.sourceRoot?.absolutePath ?: "unavailable"}")
            appendLine("Weights present: ${state?.weightsPresent == true}")
            append("Weights bytes: ${state?.weightsBytes ?: 0} / ${DemoPackProvisioner.EXPECTED_WEIGHTS_BYTES}")
        }
    }

    private fun buildUi() {
        input = EditText(this).apply {
            hint = getString(R.string.input_hint)
            setText(R.string.input_default)
            minLines = 4
        }
        locale = EditText(this).apply { hint = getString(R.string.locale_hint) }
        tone = spinner(arrayOf("Clear / neutral", "Formal", "Casual"))
        length = spinner(arrayOf("Preserve length", "Shorter", "Longer"))
        run = Button(this).apply {
            text = getString(R.string.rewrite_button)
            isEnabled = false
            setOnClickListener { executeRewrite() }
        }
        cancel = Button(this).apply {
            text = getString(R.string.cancel_button)
            isEnabled = false
            setOnClickListener { execution?.cancel() }
        }
        output = section(getString(R.string.output_heading))
        timing = section(getString(R.string.timing_heading))
        metadata = section(getString(R.string.capability_heading))
        diagnostics = section(getString(R.string.diagnostics_heading))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(section(getString(R.string.title)), MATCH_PARENT, WRAP_CONTENT)
            addView(input, MATCH_PARENT, WRAP_CONTENT)
            addView(tone, MATCH_PARENT, WRAP_CONTENT)
            addView(length, MATCH_PARENT, WRAP_CONTENT)
            addView(locale, MATCH_PARENT, WRAP_CONTENT)
            addView(run, MATCH_PARENT, WRAP_CONTENT)
            addView(cancel, MATCH_PARENT, WRAP_CONTENT)
            addView(output, MATCH_PARENT, WRAP_CONTENT)
            addView(timing, MATCH_PARENT, WRAP_CONTENT)
            addView(metadata, MATCH_PARENT, WRAP_CONTENT)
            addView(diagnostics, MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun spinner(values: Array<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            values,
        )
    }

    private fun section(initial: String): TextView = TextView(this).apply {
        setPadding(0, 24, 0, 8)
        text = initial
        setTextIsSelectable(true)
    }

    private companion object {
        const val CAPABILITY_POLL_ATTEMPTS = 180
        const val CAPABILITY_POLL_MILLIS = 1_000L
    }
}

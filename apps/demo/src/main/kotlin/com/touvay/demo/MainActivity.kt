package com.touvay.demo

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.touvay.sdk.Touvay
import com.touvay.sdk.TouvayClient
import kotlinx.coroutines.launch

/**
 * Minimal walking-skeleton client: connects to the embedded engine (running in the
 * `:touvay` process) and exercises the dev.echo capability, unary and streaming.
 */
class MainActivity : ComponentActivity() {

    private var client: TouvayClient? = null

    private lateinit var input: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        lifecycleScope.launch {
            output.text = try {
                val connected = Touvay.connect(this@MainActivity)
                client = connected
                val capabilities = connected.capabilities()
                    .entries.joinToString("\n") { (id, status) -> "  ${id.value}: $status" }
                "Connected to engine.\nCapabilities:\n$capabilities"
            } catch (e: Exception) {
                "Failed to connect: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }

    private fun runEcho() {
        val connected = client ?: run { output.text = getString(R.string.not_connected); return }
        lifecycleScope.launch {
            output.text = try {
                "Echo: " + connected.diagnostics().echo(input.text.toString())
            } catch (e: Exception) {
                "Echo failed: ${e.message}"
            }
        }
    }

    private fun runEchoStream() {
        val connected = client ?: run { output.text = getString(R.string.not_connected); return }
        lifecycleScope.launch {
            output.text = "Streaming:\n"
            try {
                connected.diagnostics()
                    .echoStream(input.text.toString(), chunks = 6, interChunkDelayMillis = 250)
                    .collect { chunk -> output.append("[$chunk]") }
                output.append("\n(done)")
            } catch (e: Exception) {
                output.append("\nStream failed: ${e.message}")
            }
        }
    }

    private fun buildUi() {
        input = EditText(this).apply {
            hint = getString(R.string.input_hint)
            setText(R.string.input_default)
        }
        output = TextView(this).apply {
            setPadding(0, 24, 0, 0)
            text = getString(R.string.connecting)
        }
        val echoButton = Button(this).apply {
            text = getString(R.string.echo_button)
            setOnClickListener { runEcho() }
        }
        val streamButton = Button(this).apply {
            text = getString(R.string.stream_button)
            setOnClickListener { runEchoStream() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(input, MATCH_PARENT, WRAP_CONTENT)
            addView(echoButton, MATCH_PARENT, WRAP_CONTENT)
            addView(streamButton, MATCH_PARENT, WRAP_CONTENT)
            addView(output, MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
}

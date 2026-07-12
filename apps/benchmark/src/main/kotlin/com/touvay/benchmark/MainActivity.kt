package com.touvay.benchmark

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Manual driver for the production-adapter benchmark. Results stream into the on-screen log and the
 * final JSON is written to this app's external files dir for `adb pull`.
 */
class MainActivity : ComponentActivity() {

    private var runner: ISpikeRunner? = null
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            runner = ISpikeRunner.Stub.asInterface(service)
            appendLine("benchmark service connected (pid ${runner?.pid})")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runner = null
            appendLine("benchmark service disconnected (process died); rebinding…")
            // BIND_AUTO_CREATE restarts the service; onServiceConnected fires again.
        }
    }

    private val callback = object : ISpikeCallback.Stub() {
        override fun onProgress(line: String?) = appendLine(line ?: "")

        override fun onFinished(resultJson: String?) {
            val json = resultJson ?: return
            val file = File(getExternalFilesDir(null), "benchmark-results-${System.currentTimeMillis()}.json")
            file.writeText(json)
            appendLine("FINISHED — results written to ${file.absolutePath}")
            appendLine(json)
        }

        override fun onError(message: String?) = appendLine("ERROR: $message")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        bindService(
            Intent(this, SpikeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }

    private fun modelPath(): String? {
        val file = File(getExternalFilesDir(null), "model.gguf")
        if (!file.exists()) {
            appendLine("model.gguf missing. Push it first:")
            appendLine("  adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf " +
                "/sdcard/Android/data/com.touvay.benchmark/files/model.gguf")
            return null
        }
        return file.absolutePath
    }

    private fun run(quick: Boolean) {
        val path = modelPath() ?: return
        val active = runner ?: run { appendLine("service not connected yet"); return }
        appendLine(if (quick) "running QUICK suite…" else "running FULL suite…")
        active.runSuite(path, quick, callback)
    }

    /**
     * Scenario 14: forced engine-process death and recovery. Kills the :spike process
     * (same uid), waits for the auto-rebind, then reruns the quick suite — its cold
     * model-load time is the recovery cost.
     */
    private fun killAndRecover() {
        val active = runner ?: run { appendLine("service not connected yet"); return }
        val pid = active.pid
        appendLine("killing :spike (pid $pid) at t=0…")
        val killedAt = SystemClock.elapsedRealtime()
        Process.killProcess(pid)
        Thread {
            while (runner == null || runCatching { runner?.pid == pid }.getOrDefault(true)) {
                SystemClock.sleep(50)
                if (SystemClock.elapsedRealtime() - killedAt > 15_000) {
                    runOnUiThread { appendLine("recovery timed out after 15s") }
                    return@Thread
                }
            }
            val rebindMs = SystemClock.elapsedRealtime() - killedAt
            runOnUiThread {
                appendLine("spike process restarted in ${rebindMs}ms (new pid ${runner?.pid})")
                appendLine("rerunning quick suite; its modelLoad.coldMs is the reload cost")
                run(quick = true)
            }
        }.start()
    }

    @SuppressLint("SetTextI18n") // developer tool; not localized by design
    private fun buildUi() {
        log = TextView(this).apply { text = "Touvay llama.cpp production benchmark\n" }
        val quick = Button(this).apply {
            text = "Run quick"
            setOnClickListener { run(quick = true) }
        }
        val full = Button(this).apply {
            text = "Run full"
            setOnClickListener { run(quick = false) }
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { runner?.cancelActive(); appendLine("cancel requested") }
        }
        val killRecover = Button(this).apply {
            text = "Kill spike process + recover"
            setOnClickListener { killAndRecover() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(quick, MATCH_PARENT, WRAP_CONTENT)
            addView(full, MATCH_PARENT, WRAP_CONTENT)
            addView(cancel, MATCH_PARENT, WRAP_CONTENT)
            addView(killRecover, MATCH_PARENT, WRAP_CONTENT)
            addView(log, MATCH_PARENT, WRAP_CONTENT)
        }
        scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
    }

    private fun appendLine(line: String) {
        runOnUiThread {
            log.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}

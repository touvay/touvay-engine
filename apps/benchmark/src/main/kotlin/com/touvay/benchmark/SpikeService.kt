package com.touvay.benchmark

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.util.concurrent.Executors

/**
 * Hosts the benchmark in the `:spike` process, mirroring the production topology
 * (inference never runs in the client's process). Killed on purpose by the recovery
 * scenario; holds no state that death could lose.
 */
class SpikeService : Service() {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "spike-runner") }

    @Volatile
    private var activeSuite: BenchmarkSuite? = null

    private val binder = object : ISpikeRunner.Stub() {
        override fun getPid(): Int = Process.myPid()

        override fun runSuite(modelPath: String?, quick: Boolean, callback: ISpikeCallback?) {
            if (modelPath == null || callback == null) return
            executor.execute {
                try {
                    val suite = BenchmarkSuite(applicationContext, modelPath) { line ->
                        runCatching { callback.onProgress(line) }
                    }
                    activeSuite = suite
                    val result = suite.run(quick)
                    runCatching { callback.onFinished(result.toString(2)) }
                } catch (t: Throwable) {
                    runCatching { callback.onError("${t.javaClass.simpleName}: ${t.message}") }
                } finally {
                    activeSuite = null
                }
            }
        }

        override fun cancelActive() {
            activeSuite?.abort()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

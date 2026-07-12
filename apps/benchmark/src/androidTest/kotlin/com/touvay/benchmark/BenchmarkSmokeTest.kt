package com.touvay.benchmark

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Automated quick-suite run against the production adapter in the legacy-named :spike
 * process. Skips (does not fail) when the
 * model hasn't been pushed, so CI without model assets stays green:
 *
 *   adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf \
 *       /sdcard/Android/data/com.touvay.benchmark/files/model.gguf
 *   ./gradlew :apps:benchmark:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkSmokeTest {

    @Test
    fun quickSuite_producesSaneMeasurements() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), "model.gguf")
        assumeTrue("model.gguf not pushed; skipping benchmark smoke test", model.exists())
        // Full by default for parity evidence; manual instrumentation may pass quick=true.
        val quick = InstrumentationRegistry.getArguments()
            .getString("quick", "false").toBoolean()

        val connected = CountDownLatch(1)
        var runner: ISpikeRunner? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runner = ISpikeRunner.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        context.bindService(
            Intent(context, SpikeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS), "spike service never connected")

            val finished = CountDownLatch(1)
            var resultJson: String? = null
            var error: String? = null
            val callback = object : ISpikeCallback.Stub() {
                override fun onProgress(line: String?) = Unit
                override fun onFinished(json: String?) {
                    resultJson = json
                    finished.countDown()
                }

                override fun onError(message: String?) {
                    error = message
                    finished.countDown()
                }
            }

            assertNotNull(runner).runSuite(model.absolutePath, quick, callback)
            // Model load + short generations; generous bound for slow emulators.
            assertTrue(finished.await(15, TimeUnit.MINUTES), "quick suite did not finish")
            if (error != null) fail("suite failed: $error")

            val result = JSONObject(assertNotNull(resultJson))
            val additionalOutput = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir")
            val outFile = File(
                additionalOutput ?: context.getExternalFilesDir(null)?.absolutePath
                    ?: context.cacheDir.absolutePath,
                "production-benchmark-result.json",
            )
            outFile.writeText(result.toString(2))

            assertTrue(result.getDouble("libLoadMs") >= 0)
            assertTrue(result.getJSONObject("modelLoad").getDouble("coldMs") > 0)
            val short = result.getJSONObject("shortPrompt")
            assertTrue(short.getInt("decodedTokens") > 0, "no tokens decoded")
            assertTrue(short.getDouble("decodeTokPerS") > 0, "no decode rate measured")
            val cancel = result.getJSONObject("cancellation")
            assertTrue(
                cancel.getDouble("maxCancelToReturnMs") in 0.0..2_000.0,
                "cancellation latency out of bounds: ${cancel.getDouble("maxCancelToReturnMs")}ms",
            )
        } finally {
            context.unbindService(connection)
        }
    }
}

package com.touvay.demo

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.touvay.sdk.Touvay
import com.touvay.sdk.TouvayException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Failure-path validation across the real process boundary (Task 0 closure): prompt
 * cancellation of abandoned streams, engine-process death mid-stream, and reconnection.
 *
 * Same-uid *rejection* is deliberately not tested here — an instrumented test always runs
 * as the app's own uid; the foreign-uid rejection path is covered by
 * EngineServiceBinderTest with ShadowBinder.
 */
@RunWith(AndroidJUnit4::class)
class EngineResilienceCrossProcessTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun abandoningAStream_endsItPromptly() = runBlocking<Unit> {
        val client = Touvay.connect(context)
        try {
            val elapsed = measureTimeMillis {
                client.diagnostics()
                    .echoStream("cancel me", chunks = 100, interChunkDelayMillis = 100)
                    .take(2)
                    .toList()
            }
            // The full stream would take ~10s; early abandonment must cancel the
            // engine-side request and end the flow quickly.
            assertTrue(
                elapsed < 3_000,
                "stream did not end promptly after abandonment (took ${elapsed}ms)",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun engineProcessDeath_failsInFlight_andFreshConnectRecovers() = runBlocking<Unit> {
        val client = Touvay.connect(context)
        try {
            val firstChunk = CompletableDeferred<Unit>()
            val outcome = async(Dispatchers.Default) {
                runCatching {
                    client.diagnostics()
                        .echoStream("doomed", chunks = 100, interChunkDelayMillis = 100)
                        .collect { firstChunk.complete(Unit) }
                }.exceptionOrNull()
            }
            withTimeout(10_000) { firstChunk.await() }

            // Same uid, so the app may kill its own :touvay sibling — a faithful
            // simulation of the low-memory killer reclaiming the engine process.
            val enginePid = assertNotNull(findEngineProcessPid(), "no :touvay process found")
            Process.killProcess(enginePid)

            val failure = withTimeout(10_000) { outcome.await() }
            assertIs<TouvayException.EngineDisconnected>(
                failure,
                "expected EngineDisconnected, got $failure",
            )
        } finally {
            client.close()
        }

        // Design-for-death (ADR-012): a fresh connect must reach a restarted engine.
        val reconnected = Touvay.connect(context)
        try {
            assertEquals("alive again", reconnected.diagnostics().echo("alive again"))
        } finally {
            reconnected.close()
        }
    }

    private fun findEngineProcessPid(): Int? {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.processName.endsWith(":touvay") }
            ?.pid
    }
}

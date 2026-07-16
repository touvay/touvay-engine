package com.touvay.demo

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.RewriteCapability
import com.touvay.sdk.RewriteEvent
import com.touvay.sdk.RewriteLength
import com.touvay.sdk.RewriteRequest
import com.touvay.sdk.RewriteTone
import com.touvay.sdk.Touvay
import com.touvay.sdk.TouvayException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real process/Binder/Model Manager/llama.cpp validation; requires the documented GGUF push. */
@RunWith(AndroidJUnit4::class)
class RewriteEndToEndTest {
    @Test
    fun signedPack_rewriteStreamingCancellationDiscoveryAndStructuredOutput(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pack = DemoPackProvisioner.prepare(context)
        val weights = File(pack.sourceRoot, "files/weights.gguf")
        assertTrue(weights.isFile, "demo GGUF was not provisioned; see docs/demo/demo-pack.md")
        assertEquals(DemoPackProvisioner.EXPECTED_WEIGHTS_BYTES, weights.length())

        val client = Touvay.connect(context)
        try {
            val readinessStarted = SystemClock.elapsedRealtime()
            var previousStatus: CapabilityStatus? = null
            val status = try {
                withTimeout(30_000) {
                    while (true) {
                        val current = client.capabilities()[RewriteCapability.id]
                        previousStatus = current
                        if (current == CapabilityStatus.Ready) return@withTimeout current
                        delay(500)
                    }
                    error("unreachable")
                }
            } catch (failure: TimeoutCancellationException) {
                throw AssertionError(
                    "TCK_DIAGNOSTIC demo readiness lastStatus=$previousStatus " +
                        "elapsedMillis=${SystemClock.elapsedRealtime() - readinessStarted}",
                    failure,
                )
            }
            assertEquals(CapabilityStatus.Ready, status)

            val events = mutableListOf<RewriteEvent>()
            client.rewrite().stream(
                RewriteRequest(
                    text = "please make this sentence clearer for a project update",
                    tone = RewriteTone.FORMAL,
                    length = RewriteLength.SHORTER,
                ),
            ).collect(events::add)
            assertTrue(events.any { it is RewriteEvent.Delta })
            val completed = assertIs<RewriteEvent.Completed>(events.last()).result
            assertTrue(completed.text.isNotBlank())
            assertTrue(completed.timing.totalMillis >= completed.timing.timeToFirstTokenMillis)
            assertTrue(completed.timing.deltaCount > 0)

            assertFailsWith<TouvayException.InvalidRequest> {
                client.rewrite().execute(RewriteRequest(" "))
            }

            val cancelled = runCatching {
                client.rewrite().stream(
                    RewriteRequest("Write a substantially longer and polished version of this note."),
                ).first { it is RewriteEvent.Delta }
                throw CancellationException("collector cancelled after first delta")
            }.exceptionOrNull()
            assertIs<CancellationException>(cancelled)

            val afterCancellation = withTimeout(30_000) {
                while (true) {
                    try {
                        return@withTimeout client.rewrite().execute(
                            RewriteRequest("Make this clearer."),
                        )
                    } catch (failure: TouvayException.EngineFailure) {
                        if (!failure.retryable) throw failure
                        delay(100)
                    }
                }
                error("unreachable")
            }
            assertTrue(afterCancellation.text.isNotBlank())
        } finally {
            client.close()
        }
    }
}

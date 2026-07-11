package com.touvay.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.touvay.sdk.CapabilityId
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.Touvay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The one test Robolectric cannot provide: the echo round trip across a real process
 * boundary (app process ↔ :touvay process) with real binder marshalling. Device-only.
 */
@RunWith(AndroidJUnit4::class)
class EchoCrossProcessTest {

    @Test
    fun echo_roundTrips_acrossTheRealProcessBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = Touvay.connect(context)
        try {
            assertEquals(
                CapabilityStatus.Ready,
                client.capabilities()[CapabilityId("dev.echo")],
            )

            assertEquals("hello engine", client.diagnostics().echo("hello engine"))

            val chunks = client.diagnostics().echoStream("hello world", chunks = 3).toList()
            assertEquals("hello world", chunks.joinToString(""))
        } finally {
            client.close()
        }
    }
}

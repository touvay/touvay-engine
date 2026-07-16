package com.touvay.demo

import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.TouvayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConsoleDiagnosticsTest {
    @Test
    fun failures_mapToStableHumanMessages_withoutRawExceptionText() {
        val raw = "secret internal native exception"
        val failure = TouvayException.EngineFailure(code = 5, retryable = true, message = raw)
        val diagnostic = ConsoleDiagnostics.fromFailure(failure)

        assertEquals(ConsoleDiagnosticCode.ENGINE_BUSY, diagnostic)
        assertFalse(diagnostic.message.contains(raw))
    }

    @Test
    fun capabilityStatuses_mapWithoutInspectingInternalReasons() {
        assertEquals(
            ConsoleDiagnosticCode.READY,
            ConsoleDiagnostics.fromCapability(CapabilityStatus.Ready),
        )
        assertEquals(
            ConsoleDiagnosticCode.MODEL_MISSING,
            ConsoleDiagnostics.fromCapability(CapabilityStatus.DownloadRequired(null)),
        )
        assertEquals(
            ConsoleDiagnosticCode.ENGINE_UPDATE_REQUIRED,
            ConsoleDiagnostics.fromCapability(CapabilityStatus.Unknown(99)),
        )
    }
}

package com.touvay.capability.rewrite

import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteDisposition
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.engine.core.ExecutionException
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.GeneratedToken
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RewriteOutputTest {
    @Test
    fun streamingDeltas_areBoundedProvisionalAndUnicodeSafe(): Unit = runTest {
        val generated = "🙂".repeat(300)
        RewriteTestFixtures.prepare().use { prepared ->
            val attempt = prepared.newAttempt(RewriteTestFixtures.candidate)
            try {
                val deltas = attempt.consume(listOf(GeneratedToken(1, generated)))
                assertTrue(deltas.size > 1)
                assertTrue(deltas.all { it.size <= RewriteCapabilityDefinition.MAX_DELTA_BYTES })
                val rebuilt = deltas.joinToString("") { bytes ->
                    RewriteDelta.parseFrom(bytes).also { assertTrue(it.provisional) }.text
                }
                assertEquals(generated, rebuilt)
                assertEquals(generated, RewriteResponse.parseFrom(attempt.finish()).text)
            } finally {
                attempt.close()
            }
        }
    }

    @Test
    fun finalResponse_isAuthoritativeNormalizedAndStructured(): Unit = runTest {
        RewriteTestFixtures.prepare().use { prepared ->
            val attempt = prepared.newAttempt(RewriteTestFixtures.candidate)
            try {
                val provisional = attempt.consume(
                    listOf(GeneratedToken(1, "  Hello\r\n"), GeneratedToken(2, "world  ")),
                )
                assertEquals("  Hello\r\nworld  ", provisional.joinToString("") {
                    RewriteDelta.parseFrom(it).text
                })
                val response = RewriteResponse.parseFrom(attempt.finish())
                assertEquals("Hello\nworld", response.text)
                assertEquals(RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN, response.disposition)
            } finally {
                attempt.close()
            }
        }
    }

    @Test
    fun unchangedOutput_isRepresentedExplicitly(): Unit = runTest {
        RewriteTestFixtures.prepare().use { prepared ->
            val attempt = prepared.newAttempt(RewriteTestFixtures.candidate)
            try {
                attempt.consume(listOf(GeneratedToken(1, RewriteTestFixtures.SOURCE)))
                val response = RewriteResponse.parseFrom(attempt.finish())
                assertEquals(RewriteDisposition.REWRITE_DISPOSITION_UNCHANGED, response.disposition)
            } finally {
                attempt.close()
            }
        }
    }

    @Test
    fun invalidOrOversizedModelOutput_failsWithTypedInvalidOutput(): Unit = runTest {
        val values = listOf(
            "bad\u0000output",
            "x".repeat(RewriteCapabilityDefinition.MAX_RESULT_UTF8_BYTES + 1),
        )
        values.forEach { value ->
            RewriteTestFixtures.prepare().use { prepared ->
                val attempt = prepared.newAttempt(RewriteTestFixtures.candidate)
                try {
                    val failure = assertFailsWith<ExecutionException> {
                        attempt.consume(listOf(GeneratedToken(1, value)))
                    }
                    assertEquals(ExecutionFailureCode.INVALID_OUTPUT, failure.failureCode)
                } finally {
                    attempt.close()
                }
            }
        }
    }
}

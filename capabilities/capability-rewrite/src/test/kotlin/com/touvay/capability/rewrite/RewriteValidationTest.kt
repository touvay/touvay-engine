package com.touvay.capability.rewrite

import com.touvay.contract.rewrite.v1.RewriteRequest
import com.touvay.engine.core.CapabilityExecutionProgramFactory
import com.touvay.engine.core.ExecutionException
import com.touvay.engine.core.ExecutionFailureCode
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RewriteValidationTest {
    @Test
    fun invalidOptionsAndText_failAsInvalidRequestWithoutContent(): Unit = runTest {
        val sentinel = "REWRITE-PRIVATE-SENTINEL"
        val requests = listOf(
            RewriteTestFixtures.request(text = " "),
            RewriteTestFixtures.request(text = sentinel, locale = "not_a_locale"),
            RewriteTestFixtures.request(text = sentinel, locale = "en-a"),
            RewriteTestFixtures.request(text = "x".repeat(RewriteCapabilityDefinition.MAX_SOURCE_UTF8_BYTES + 1)),
            RewriteRequest.newBuilder().setText(sentinel).setToneValue(99).build(),
        )
        requests.forEachIndexed { index, request ->
            val failure = assertFailsWith<ExecutionException>("case $index") {
                prepare(request.toByteArray())
            }
            assertEquals(ExecutionFailureCode.INVALID_REQUEST, failure.failureCode)
            assertFalse(failure.message.orEmpty().contains(sentinel))
        }
    }

    @Test
    fun malformedCorpus_failsCleanlyWithTypedErrors(): Unit = runTest {
        val random = Random(23)
        val corpus = buildList {
            add(ByteArray(0))
            add(byteArrayOf(0x0f))
            add("REWRITE-FUZZ-SENTINEL".toByteArray())
            repeat(64) { length -> add(random.nextBytes(length + 1)) }
        }
        corpus.forEach { payload ->
            try {
                prepare(payload)
                // Empty/default protobuf is syntactically valid but semantically rejected;
                // random bytes should never produce a valid non-blank RewriteRequest.
                throw AssertionError("malformed payload succeeded")
            } catch (failure: ExecutionException) {
                assertEquals(ExecutionFailureCode.INVALID_REQUEST, failure.failureCode)
            }
        }
    }

    private suspend fun prepare(payload: ByteArray) {
        CapabilityExecutionProgramFactory(RewriteCapabilityDefinition())
            .prepare(RewriteTestFixtures.context(payload.size), payload)
            .close()
    }
}

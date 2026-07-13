package com.touvay.capability.tck

import com.touvay.engine.core.CapabilityAvailability
import com.touvay.engine.core.CapabilityDiscovery
import com.touvay.engine.core.CapabilityExecutionPlan
import com.touvay.engine.core.CapabilityFrameworkException
import com.touvay.engine.core.CapabilityFrameworkRegistry
import com.touvay.engine.core.CapabilityRoutingRequirements
import com.touvay.engine.core.CapabilityStepInput
import com.touvay.engine.core.CapabilityStepOutput
import com.touvay.engine.core.ExecutionDemand
import com.touvay.engine.core.ExecutionException
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.PromptAssetLoader
import com.touvay.engine.core.CapabilityAttemptEnvironment
import com.touvay.engine.core.PromptAssetRenderer
import com.touvay.engine.core.PromptValues
import com.touvay.engine.core.PromptAssetRef
import com.touvay.engine.core.PromptExecutionStep
import com.touvay.engine.core.PromptRecipe
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Executable mandatory Capability SPI v1 requirements. */
public class CapabilityTckChecks(private val subject: CapabilityTckSubject) {
    private val harness = CapabilityTestHarness(subject)

    /** CAP-ID-01: registrations resolve only by exact id and schema version. */
    public fun id01ExactKeyRegistration() {
        val registry = CapabilityFrameworkRegistry.of(subject.definition)
        assertTrue(registry.find(subject.definition.contract.key) === subject.definition)
        val different = subject.definition.contract.key.copy(
            schemaVersion = subject.definition.contract.key.schemaVersion + 1,
        )
        assertEquals(null, registry.find(different))
    }

    /** CAP-ID-02: duplicate exact keys fail registry construction. */
    public fun id02DuplicateRejected() {
        expectIllegalArgument { CapabilityFrameworkRegistry.of(subject.definition, subject.definition) }
    }

    /** CAP-ID-03: descriptor collections are immutable snapshots. */
    public fun id03DescriptorImmutable() {
        @Suppress("UNCHECKED_CAST")
        val modalities = subject.definition.contract.inputModalities as MutableSet<com.touvay.engine.core.CapabilityInputModality>
        assertThrows(UnsupportedOperationException::class.java) {
            modalities.add(com.touvay.engine.core.CapabilityInputModality.AUDIO)
        }
    }

    /** CAP-DS-01: discovery is stable and policy availability remains external. */
    public fun ds01DiscoveryStable() {
        val registry = CapabilityFrameworkRegistry.of(subject.definition)
        val records = CapabilityDiscovery(registry) { CapabilityAvailability.MODEL_NOT_INSTALLED }.discover()
        assertEquals(1, records.size)
        assertEquals(subject.definition.contract.key, records.single().contract.key)
        assertEquals(CapabilityAvailability.MODEL_NOT_INSTALLED, records.single().availability)
    }

    /** CAP-PL-01: valid preparation produces a bounded semantic plan. */
    public fun pl01PlanValid(): Unit = runBlocking<Unit> {
        harness.prepare().use { prepared ->
            val plan = (subject.definition.prepare(
                com.touvay.engine.core.CapabilityPreparationContext(subject.context),
                com.touvay.engine.core.CapabilityPayload.copyOf(
                    subject.validPayload(),
                    subject.definition.contract.maxInlinePayloadBytes,
                ),
                com.touvay.engine.core.CapabilityContext.EMPTY,
            )).use { it.executionPlan }
            assertEquals(1, plan.steps.size)
            assertTrue(plan.steps.single() is PromptExecutionStep)
            assertTrue(prepared.demand.fixedRamBytes >= 0)
        }
    }

    /** CAP-PL-02: repeated preparation is structurally deterministic. */
    public fun pl02PreparationDeterministic(): Unit = runBlocking<Unit> {
        val first = subject.definition.prepareDirectPlan(subject)
        val second = subject.definition.prepareDirectPlan(subject)
        assertEquals(fingerprint(first), fingerprint(second))
    }

    /** CAP-PL-03: a plan cannot consume a future step's output. */
    public fun pl03ForwardReferenceRejected() {
        val recipe = subject.recipe()
        val routing = CapabilityRoutingRequirements(minContextLength = 1, maxOutputTokens = 1)
        val demand = ExecutionDemand(0, 0, 0)
        expectIllegalArgument {
            CapabilityExecutionPlan(
                listOf(
                    PromptExecutionStep(
                        id = "first",
                        recipe = recipe,
                        inputs = listOf(CapabilityStepInput.PriorStepOutput("value", "second", "out")),
                        routing = routing,
                        demand = demand,
                        output = CapabilityStepOutput("out", 64),
                    ),
                    PromptExecutionStep(
                        "second",
                        recipe,
                        emptyList(),
                        routing,
                        demand,
                        CapabilityStepOutput("out", 64),
                    ),
                ),
            )
        }
    }

    /** CAP-PL-04: inline payload ceiling is enforced before capability parsing. */
    public fun pl04PayloadBounded(): Unit = runBlocking<Unit> {
        val oversized = ByteArray(subject.definition.contract.maxInlinePayloadBytes + 1)
        expectExecutionFailure(ExecutionFailureCode.INVALID_REQUEST) {
            harness.prepare(oversized)
        }
    }

    /** CAP-PL-05: attempt session/decode policy stays inside its frozen candidate. */
    public fun pl05AttemptPolicyBounded(): Unit = runBlocking<Unit> {
        harness.prepare().use { prepared ->
            val attempt = prepared.newAttempt(subject.candidate)
            try {
                val model = com.touvay.runtime.api.ModelInstanceInfo(
                    estimatedRamBytes = 1,
                    maxContextLength = subject.candidate.contextLength,
                )
                val session = attempt.sessionConfig(model)
                assertTrue(session.contextLength in 1..subject.candidate.contextLength)
                val decode = attempt.decodeParams(subject.candidate.maxOutputTokens)
                assertTrue(decode.maxTokens in 1..subject.candidate.maxOutputTokens)
            } finally {
                attempt.close()
            }
        }
    }

    /** CAP-AT-01: retries receive fresh mutable attempt state. */
    public fun at01FreshAttempts(): Unit = runBlocking<Unit> {
        harness.prepare().use { prepared ->
            val first = prepared.newAttempt(subject.candidate)
            val second = prepared.newAttempt(subject.candidate)
            try {
                assertNotSame(first, second)
                first.close()
                try {
                    second.sessionConfig(
                        com.touvay.runtime.api.ModelInstanceInfo(1, 4096),
                    )
                } catch (failure: Exception) {
                    fail("closing attempt 1 invalidated attempt 2: ${failure.javaClass.simpleName}")
                }
            } finally {
                first.close()
                second.close()
            }
        }
    }

    /** CAP-AT-02: request and attempt close are idempotent. */
    public fun at02CloseIdempotent(): Unit = runBlocking<Unit> {
        val prepared = harness.prepare()
        val attempt = prepared.newAttempt(subject.candidate)
        attempt.close()
        attempt.close()
        prepared.close()
        prepared.close()
    }

    /** CAP-PR-01: a valid frozen asset renders exactly and is tokenized afterwards. */
    public fun pr01GoldenPromptRender(): Unit = runBlocking<Unit> {
        harness.prepare().use { prepared ->
            val input = harness.buildModelInput(prepared)
            assertEquals(subject.expectedRenderedText, input.text)
            assertEquals(input.text.toByteArray(Charsets.UTF_8).size, input.tokens.ids.size)
        }
    }

    /** CAP-PR-02: asset bytes must match the exact routed digest and size. */
    public fun pr02DigestMismatchRejected(): Unit = runBlocking<Unit> {
        val corrupted = subject.validPromptAsset().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        harness.prepare().use { prepared ->
            expectExecutionFailure(ExecutionFailureCode.INTERNAL) {
                harness.buildModelInput(prepared, source = harness.fixtureSource(corrupted))
            }
        }
    }

    /** CAP-PR-03: recipe identity and revision compatibility are exact. */
    public fun pr03RecipeMismatchRejected() {
        val recipe = subject.recipe()
        val incompatible = PromptRecipe(
            capabilityKey = recipe.capabilityKey,
            id = recipe.id,
            revision = Int.MAX_VALUE,
            sourceSha256 = recipe.sourceSha256,
            elements = recipe.elements,
            slots = recipe.slots,
            requiredAssetFeatures = recipe.requiredAssetFeatures,
        )
        expectCapabilityFailure {
            PromptAssetLoader(subject.supportedPromptFeatures)
                .parse(subject.validPromptAsset(), incompatible)
        }
    }

    /** CAP-PR-04: malformed and fuzz-style corpus entries fail cleanly without Errors. */
    public fun pr04MalformedCorpusClean() {
        val valid = subject.validPromptAsset()
        val variants = buildList {
            add(ByteArray(0))
            add(byteArrayOf(0x7f))
            add(ByteArray(4096) { (it * 31).toByte() })
            for (length in 1 until minOf(valid.size, 24)) add(valid.copyOf(length))
            repeat(minOf(valid.size, 32)) { index ->
                add(valid.copyOf().also { it[index] = (it[index].toInt() xor 0x80).toByte() })
            }
        }
        val loader = PromptAssetLoader(subject.supportedPromptFeatures)
        variants.forEach { bytes ->
            try {
                loader.parse(bytes, subject.recipe())
            } catch (_: CapabilityFrameworkException) {
                // Required clean rejection.
            } catch (error: Error) {
                fail("parser corpus caused ${error.javaClass.simpleName}")
            } catch (failure: Exception) {
                fail("parser corpus escaped typed failure: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** CAP-PR-05: typed slot byte limits are hard bounds before tokenization. */
    public fun pr05SlotBoundEnforced(): Unit = runBlocking<Unit> {
        val recipe = subject.recipe()
        val slot = recipe.slots.first { it.required }
        val asset = PromptAssetLoader(subject.supportedPromptFeatures)
            .parse(subject.validPromptAsset(), recipe)
        val environment = CapabilityAttemptEnvironment(
            com.touvay.runtime.api.ModelInstanceInfo(1, 4096),
            harness.fixtureSource(),
        ) { text -> com.touvay.runtime.api.TokenSequence(IntArray(text.length)) }
        try {
            PromptAssetRenderer.render(
                recipe,
                asset,
                PromptValues.of(mapOf(slot.id to "x".repeat(slot.maxUtf8Bytes + 1))),
                environment,
            )
            fail("oversized prompt slot succeeded")
        } catch (failure: CapabilityFrameworkException) {
            assertEquals(com.touvay.engine.core.CapabilityFailureCode.INPUT_TOO_LARGE, failure.code)
        }
    }

    /** CAP-ST-01: token consumption and final assembly are ordered and deterministic. */
    public fun st01OutputDeterministic(): Unit = runBlocking<Unit> {
        suspend fun execute(): Pair<List<ByteArray>, ByteArray> = harness.prepare().use { prepared ->
            val attempt = prepared.newAttempt(subject.candidate)
            try {
                val split = subject.generatedTokens.size / 2
                val deltas = attempt.consume(subject.generatedTokens.take(split)) +
                    attempt.consume(subject.generatedTokens.drop(split))
                deltas to attempt.finish()
            } finally {
                attempt.close()
            }
        }
        val first = execute()
        val second = execute()
        assertEquals(subject.expectedDeltas().map { it.toList() }, first.first.map { it.toList() })
        assertEquals(subject.expectedFinal().toList(), first.second.toList())
        assertEquals(first.first.map { it.toList() }, second.first.map { it.toList() })
        assertEquals(first.second.toList(), second.second.toList())
    }

    /** CAP-CX-01: long-running attempt work observes coroutine cancellation. */
    public fun cx01AttemptCancellation(): Unit = runBlocking<Unit> {
        harness.prepare().use { prepared ->
            val attempt = prepared.newAttempt(subject.candidate)
            try {
                val started = CountDownLatch(1)
                val completed = AtomicBoolean(false)
                val job = launch(Dispatchers.Default) {
                    subject.cancellationProbe.run(attempt, started::countDown)
                    completed.set(true)
                }
                assertTrue("cancellation probe did not start", started.await(5, TimeUnit.SECONDS))
                job.cancelAndJoin()
                assertFalse("attempt ignored cancellation", completed.get())
            } finally {
                attempt.close()
            }
        }
    }

    /** CAP-ER-01: malformed capability payloads map to a stable public failure. */
    public fun er01MalformedPayloadTyped(): Unit = runBlocking<Unit> {
        expectExecutionFailure(ExecutionFailureCode.INVALID_REQUEST) {
            harness.prepare(subject.malformedPayload())
        }
    }

    /** CAP-ER-02: payload content never appears in exception messages. */
    public fun er02NoContentInErrors(): Unit = runBlocking<Unit> {
        val sentinel = subject.malformedPayload().toString(Charsets.UTF_8)
        try {
            harness.prepare(subject.malformedPayload())
            fail("malformed payload succeeded")
        } catch (failure: Exception) {
            var cause: Throwable? = failure
            while (cause != null) {
                assertTrue(cause.message.orEmpty().contains(sentinel).not())
                cause = cause.cause
            }
        }
    }

    private fun fingerprint(plan: CapabilityExecutionPlan): String = plan.steps.joinToString("|") { step ->
        val prompt = step as PromptExecutionStep
        "${prompt.id}:${prompt.recipe.id}:${prompt.recipe.revision}:" +
            prompt.inputs.joinToString(",") { it.slotId }
    }

    private fun CapabilityTckSubject.recipe(): PromptRecipe =
        definition.prepareDirectPlan(this).steps.single().let { it as PromptExecutionStep }.recipe

    private fun com.touvay.engine.core.CapabilityDefinition.prepareDirectPlan(
        subject: CapabilityTckSubject,
    ): CapabilityExecutionPlan = runBlocking {
        prepare(
            com.touvay.engine.core.CapabilityPreparationContext(subject.context),
            com.touvay.engine.core.CapabilityPayload.copyOf(
                subject.validPayload(),
                contract.maxInlinePayloadBytes,
            ),
            com.touvay.engine.core.CapabilityContext.EMPTY,
        ).use { it.executionPlan }
    }

    private inline fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Required.
        }
    }

    private inline fun expectCapabilityFailure(block: () -> Unit) {
        try {
            block()
            fail("expected CapabilityFrameworkException")
        } catch (_: CapabilityFrameworkException) {
            // Required.
        }
    }

    private suspend inline fun expectExecutionFailure(
        code: ExecutionFailureCode,
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("expected ExecutionException")
        } catch (failure: ExecutionException) {
            assertEquals(code, failure.failureCode)
        }
    }
}

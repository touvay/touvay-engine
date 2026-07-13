package com.touvay.capability.tck

import com.touvay.engine.core.CapabilityAttempt
import com.touvay.engine.core.CapabilityAttemptBinding
import com.touvay.engine.core.CapabilityAttemptEnvironment
import com.touvay.engine.core.CapabilityContract
import com.touvay.engine.core.CapabilityDefinition
import com.touvay.engine.core.CapabilityExecutionPlan
import com.touvay.engine.core.CapabilityFrameworkException
import com.touvay.engine.core.CapabilityFailureCode
import com.touvay.engine.core.CapabilityInputModality
import com.touvay.engine.core.CapabilityKey
import com.touvay.engine.core.CapabilityPayload
import com.touvay.engine.core.CapabilityPreparationContext
import com.touvay.engine.core.CapabilityPromptStrategy
import com.touvay.engine.core.CapabilityRoutingRequirements
import com.touvay.engine.core.CapabilityStreamingMode
import com.touvay.engine.core.CapabilityOutputStrategy
import com.touvay.engine.core.CapabilityStepOutput
import com.touvay.engine.core.ExecutionCandidate
import com.touvay.engine.core.ExecutionContextCandidate
import com.touvay.engine.core.ExecutionDemand
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.ExecutionPriority
import com.touvay.engine.core.ExecutionProfileSpec
import com.touvay.engine.core.GeneratedToken
import com.touvay.engine.core.ModelRevisionRef
import com.touvay.engine.core.PreparedCapability
import com.touvay.engine.core.PreparedModelInput
import com.touvay.engine.core.PromptAssetLoader
import com.touvay.engine.core.PromptAssetRef
import com.touvay.engine.core.PromptAssetRenderer
import com.touvay.engine.core.PromptExecutionStep
import com.touvay.engine.core.PromptRecipe
import com.touvay.engine.core.PromptRecipeElement
import com.touvay.engine.core.PromptSlot
import com.touvay.engine.core.PromptValues
import com.touvay.engine.core.RequestKey
import com.touvay.engine.core.StreamLimits
import com.touvay.engine.core.proto.PromptBlock
import com.touvay.engine.core.proto.PromptFormatAsset
import com.touvay.engine.core.proto.PromptRole
import com.touvay.engine.core.proto.PromptSegment
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.SessionConfig
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertThrows
import org.junit.Test

class CapabilityTckSelfTest : AbstractCapabilityTck() {
    override fun subject(): CapabilityTckSubject = Fixture.subject()
}

class CapabilityTckSabotageTest {
    @Test
    fun reusedAttemptIsDetected() {
        val subject = Fixture.subject(reuseAttempt = true)
        assertThrows(AssertionError::class.java) {
            CapabilityTckChecks(subject).at01FreshAttempts()
        }
    }

    @Test
    fun contentLeakingParserIsDetected() {
        val subject = Fixture.subject(leakMalformedContent = true)
        assertThrows(AssertionError::class.java) {
            CapabilityTckChecks(subject).er02NoContentInErrors()
        }
    }
}

private object Fixture {
    private val key = CapabilityKey("test.synthetic", 1)
    private val recipe = PromptRecipe(
        capabilityKey = key,
        id = "test.synthetic.recipe",
        revision = 1,
        sourceSha256 = "0".repeat(64),
        elements = listOf(
            PromptRecipeElement.TrustedInstruction("synthetic.instruction"),
            PromptRecipeElement.DataSlot("input"),
        ),
        slots = listOf(PromptSlot("input", required = true, maxUtf8Bytes = 64)),
        requiredAssetFeatures = setOf("prompt.typed-slots"),
    )

    fun subject(
        reuseAttempt: Boolean = false,
        leakMalformedContent: Boolean = false,
    ): CapabilityTckSubject {
        val asset = PromptFormatAsset.newBuilder()
            .setSchemaVersion(1)
            .setAssetId("test.synthetic.asset")
            .setCapabilityId(key.id)
            .setCapabilitySchemaVersion(key.schemaVersion)
            .setRecipeId(recipe.id)
            .setMinRecipeRevision(1)
            .setMaxRecipeRevision(1)
            .addRequiredFeatures("prompt.typed-slots")
            .setMaxExpandedBytes(128)
            .addBlocks(
                PromptBlock.newBuilder()
                    .setRole(PromptRole.PROMPT_ROLE_SYSTEM)
                    .addSegments(PromptSegment.newBuilder().setLiteral("instruction:")),
            )
            .addBlocks(
                PromptBlock.newBuilder()
                    .setRole(PromptRole.PROMPT_ROLE_USER)
                    .addSegments(PromptSegment.newBuilder().setSlotId("input"))
                    .addSegments(PromptSegment.newBuilder().setLiteral(":end")),
            )
            .build()
            .toByteArray()
        val ref = PromptAssetRef("prompts/synthetic.pb", asset.size, sha256(asset))
        return CapabilityTckSubject(
            definition = SyntheticDefinition(reuseAttempt, leakMalformedContent),
            context = context(),
            validPayload = "data".toByteArray(),
            malformedPayload = "TCK-PRIVATE-SENTINEL".toByteArray(),
            candidate = ExecutionCandidate(
                id = "primary",
                modelRevision = ModelRevisionRef("pack", "1", "0".repeat(64)),
                profile = ExecutionProfileSpec(threads = 1),
                contextLength = 256,
                maxOutputTokens = 16,
                decodeQuantumTokens = 4,
                retryableFailures = setOf(ExecutionFailureCode.RUNTIME_FAILURE),
                capabilityStepId = "generate",
                promptAsset = ref,
            ),
            validPromptAsset = asset,
            promptValues = PromptValues.of(mapOf("input" to "data")),
            expectedRenderedText = "instruction:data:end",
            generatedTokens = listOf(
                GeneratedToken(1, "a"),
                GeneratedToken(2, "b"),
            ),
            expectedDeltas = listOf("a".toByteArray(), "b".toByteArray()),
            expectedFinal = "ab".toByteArray(),
            cancellationProbe = CapabilityCancellationProbe { attempt, onStarted ->
                onStarted()
                attempt.consume(listOf(GeneratedToken(-1, "")))
            },
            supportedPromptFeatures = setOf("prompt.typed-slots"),
        )
    }

    private fun context(): ExecutionContextCandidate = ExecutionContextCandidate(
        requestKey = RequestKey("tck", "request"),
        capabilityId = key.id,
        schemaVersion = key.schemaVersion,
        priority = ExecutionPriority.INTERACTIVE,
        coalesceIdentity = null,
        receivedAtNanos = 1,
        deadlineAtNanos = Long.MAX_VALUE,
        contractVersion = 1,
        negotiatedFeatures = emptySet(),
        payloadBytes = 4,
        bulkInputBytes = 0,
        policyGeneration = 1,
        correlationId = "tck-correlation",
        streamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class SyntheticDefinition(
        private val reuseAttempt: Boolean,
        private val leakMalformedContent: Boolean,
    ) : CapabilityDefinition {
        override val contract: CapabilityContract = CapabilityContract(
            key = key,
            implementationRevision = 1,
            inputModalities = setOf(CapabilityInputModality.TEXT),
            outputModalities = setOf(CapabilityInputModality.TEXT),
            streamingMode = CapabilityStreamingMode.DELTAS,
            allowsCoalescing = true,
            maxInlinePayloadBytes = 64,
            maxBulkInputBytes = 0,
            maxOutputBytes = 256,
            maxDeltaBytes = 64,
            promptStrategy = CapabilityPromptStrategy.TYPED_RECIPE,
            outputStrategies = setOf(CapabilityOutputStrategy.TEXT),
            requiredFrameworkFeatures = setOf("prompt.typed-slots"),
            conformanceProfile = "capability.v1",
        )

        override suspend fun prepare(
            context: CapabilityPreparationContext,
            payload: CapabilityPayload,
            injectedContext: com.touvay.engine.core.CapabilityContext,
        ): PreparedCapability {
            val text = payload.copyBytes().toString(Charsets.UTF_8)
            if (text != "data") {
                if (leakMalformedContent) throw IllegalArgumentException(text)
                throw CapabilityFrameworkException(CapabilityFailureCode.INVALID_PAYLOAD)
            }
            return SyntheticPrepared(reuseAttempt)
        }
    }

    private class SyntheticPrepared(private val reuseAttempt: Boolean) : PreparedCapability {
        override val executionPlan: CapabilityExecutionPlan = CapabilityExecutionPlan(
            listOf(
                PromptExecutionStep(
                    id = "generate",
                    recipe = recipe,
                    inputs = emptyList(),
                    routing = CapabilityRoutingRequirements(
                        runtimeFeatures = emptySet(),
                        minContextLength = 64,
                        maxOutputTokens = 16,
                    ),
                    demand = ExecutionDemand(1, 1, 1),
                    output = CapabilityStepOutput("final", 256),
                ),
            ),
        )
        override val executionDemand: ExecutionDemand = ExecutionDemand(1, 1, 1)
        private val shared = SyntheticAttempt(
            PromptAssetRef("prompts/synthetic.pb", 1, "0".repeat(64)),
        )

        override fun newAttempt(binding: CapabilityAttemptBinding): CapabilityAttempt =
            if (reuseAttempt) shared else SyntheticAttempt(binding.promptAsset)

        override fun close() = Unit
    }

    private class SyntheticAttempt(private val assetRef: PromptAssetRef) : CapabilityAttempt {
        private val closed = AtomicBoolean()
        private val output = StringBuilder()

        override suspend fun buildModelInput(
            environment: CapabilityAttemptEnvironment,
        ): PreparedModelInput {
            check(!closed.get())
            val asset = PromptAssetLoader(setOf("prompt.typed-slots"))
                .load(environment.promptAssets, assetRef, recipe)
            return PromptAssetRenderer.render(
                recipe,
                asset,
                PromptValues.of(mapOf("input" to "data")),
                environment,
            )
        }

        override fun sessionConfig(model: ModelInstanceInfo): SessionConfig {
            check(!closed.get())
            return SessionConfig(128)
        }
        override fun decodeParams(maxTokens: Int): DecodeParams = DecodeParams(maxTokens)
        override suspend fun consume(tokens: List<GeneratedToken>): List<ByteArray> {
            check(!closed.get())
            if (tokens.any { it.tokenId == -1 }) {
                while (true) coroutineContext.ensureActive()
            }
            return tokens.map { token ->
                output.append(token.piece)
                token.piece.toByteArray()
            }
        }
        override suspend fun finish(): ByteArray = output.toString().toByteArray()
        override fun close() {
            closed.set(true)
        }
    }
}

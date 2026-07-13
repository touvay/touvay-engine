package com.touvay.capability.rewrite

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.touvay.contract.TouvayContract
import com.touvay.contract.rewrite.v1.RewriteLength
import com.touvay.contract.rewrite.v1.RewriteRequest
import com.touvay.contract.rewrite.v1.RewriteTone
import com.touvay.engine.core.CapabilityAttempt
import com.touvay.engine.core.CapabilityAttemptBinding
import com.touvay.engine.core.CapabilityContract
import com.touvay.engine.core.CapabilityDefinition
import com.touvay.engine.core.CapabilityExecutionPlan
import com.touvay.engine.core.CapabilityFrameworkException
import com.touvay.engine.core.CapabilityFailureCode
import com.touvay.engine.core.CapabilityInputModality
import com.touvay.engine.core.CapabilityKey
import com.touvay.engine.core.CapabilityOutputStrategy
import com.touvay.engine.core.CapabilityPayload
import com.touvay.engine.core.CapabilityPreparationContext
import com.touvay.engine.core.CapabilityPromptStrategy
import com.touvay.engine.core.CapabilityRoutingRequirements
import com.touvay.engine.core.CapabilityStepInput
import com.touvay.engine.core.CapabilityStepOutput
import com.touvay.engine.core.CapabilityStreamingMode
import com.touvay.engine.core.ExecutionDemand
import com.touvay.engine.core.PreparedCapability
import com.touvay.engine.core.PromptExecutionStep
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Production definition for the exact `text.rewrite@1` capability contract. */
public class RewriteCapabilityDefinition : CapabilityDefinition {
    override val contract: CapabilityContract = CONTRACT

    override suspend fun prepare(
        context: CapabilityPreparationContext,
        payload: CapabilityPayload,
        injectedContext: com.touvay.engine.core.CapabilityContext,
    ): PreparedCapability {
        if (context.execution.capabilityId != KEY.id ||
            context.execution.schemaVersion != KEY.schemaVersion ||
            injectedContext.keys().isNotEmpty()
        ) {
            invalidPayload()
        }
        return RewritePreparedCapability(RewriteRequestParser.parse(payload.copyBytes()))
    }

    public companion object {
        public val KEY: CapabilityKey = CapabilityKey(TouvayContract.CAPABILITY_TEXT_REWRITE, 1)

        public const val MAX_SOURCE_UTF8_BYTES: Int = 8 * 1024
        public const val MAX_RESULT_UTF8_BYTES: Int = 12 * 1024
        public const val MAX_DELTA_BYTES: Int = 2 * 1024
        public const val MAX_OUTPUT_TOKENS: Int = 256

        private val CONTRACT = CapabilityContract(
            key = KEY,
            implementationRevision = 1,
            inputModalities = setOf(CapabilityInputModality.TEXT, CapabilityInputModality.STRUCTURED),
            outputModalities = setOf(CapabilityInputModality.TEXT, CapabilityInputModality.STRUCTURED),
            streamingMode = CapabilityStreamingMode.DELTAS,
            allowsCoalescing = true,
            maxInlinePayloadBytes = 12 * 1024,
            maxBulkInputBytes = 0,
            maxOutputBytes = 16 * 1024,
            maxDeltaBytes = MAX_DELTA_BYTES,
            promptStrategy = CapabilityPromptStrategy.TYPED_RECIPE,
            outputStrategies = setOf(CapabilityOutputStrategy.TEXT, CapabilityOutputStrategy.PROTOBUF),
            requiredFrameworkFeatures = RewritePrompt.REQUIRED_ASSET_FEATURES,
            conformanceProfile = "capability.rewrite.v1",
        )
    }
}

private class RewritePreparedCapability(input: RewriteInput) : PreparedCapability {
    private val closed = AtomicBoolean()
    private var input: RewriteInput? = input

    override val executionDemand: ExecutionDemand = DEMAND
    override val executionPlan: CapabilityExecutionPlan = CapabilityExecutionPlan(
        listOf(
            PromptExecutionStep(
                id = STEP_ID,
                recipe = RewritePrompt.RECIPE,
                inputs = listOf(
                    CapabilityStepInput.PreparedInput("source", "request.text"),
                    CapabilityStepInput.PreparedInput("tone", "request.tone"),
                    CapabilityStepInput.PreparedInput("length", "request.length"),
                    CapabilityStepInput.PreparedInput("locale", "request.output_locale_bcp47"),
                ),
                routing = CapabilityRoutingRequirements(
                    runtimeFeatures = emptySet(),
                    minContextLength = 1_024,
                    maxOutputTokens = RewriteCapabilityDefinition.MAX_OUTPUT_TOKENS,
                ),
                demand = DEMAND,
                output = CapabilityStepOutput("rewrite.response", 16 * 1024),
            ),
        ),
    )

    override fun newAttempt(binding: CapabilityAttemptBinding): CapabilityAttempt {
        if (closed.get() || binding.stepId != STEP_ID) internalFailure()
        return RewriteAttempt(checkNotNull(input), binding)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) input = null
    }

    private companion object {
        const val STEP_ID = "rewrite.generate"
        val DEMAND = ExecutionDemand(
            fixedRamBytes = 768L * 1024L * 1024L,
            kvBytes = 64L * 1024L * 1024L,
            loadCost = 1,
        )
    }
}

internal data class RewriteInput(
    val source: String,
    val tone: String,
    val length: String,
    val locale: String,
)

private object RewriteRequestParser {
    private const val MAX_RECURSION_DEPTH = 8
    private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")

    fun parse(bytes: ByteArray): RewriteInput {
        val request = try {
            val input = CodedInputStream.newInstance(bytes)
            input.setSizeLimit(RewriteCapabilityDefinition.MAX_SOURCE_UTF8_BYTES + 4 * 1024)
            input.setRecursionLimit(MAX_RECURSION_DEPTH)
            RewriteRequest.parseFrom(input)
        } catch (_: InvalidProtocolBufferException) {
            invalidPayload()
        } catch (_: IllegalArgumentException) {
            invalidPayload()
        }

        val sourceBytes = request.text.toByteArray(Charsets.UTF_8).size
        if (request.text.isBlank() ||
            sourceBytes !in 1..RewriteCapabilityDefinition.MAX_SOURCE_UTF8_BYTES ||
            !isValidUnicode(request.text)
        ) {
            invalidPayload()
        }

        val tone = when (request.tone) {
            RewriteTone.REWRITE_TONE_UNSPECIFIED,
            RewriteTone.REWRITE_TONE_NEUTRAL,
            -> "neutral"
            RewriteTone.REWRITE_TONE_FORMAL -> "formal"
            RewriteTone.REWRITE_TONE_CASUAL -> "casual"
            RewriteTone.UNRECOGNIZED -> invalidPayload()
        }
        val length = when (request.length) {
            RewriteLength.REWRITE_LENGTH_UNSPECIFIED,
            RewriteLength.REWRITE_LENGTH_PRESERVE,
            -> "preserve"
            RewriteLength.REWRITE_LENGTH_SHORTER -> "shorter"
            RewriteLength.REWRITE_LENGTH_LONGER -> "longer"
            RewriteLength.UNRECOGNIZED -> invalidPayload()
        }
        val locale = if (request.hasOutputLocaleBcp47()) {
            canonicalLocale(request.outputLocaleBcp47)
        } else {
            "preserve-source-language"
        }
        return RewriteInput(request.text, tone, length, locale)
    }

    private fun canonicalLocale(value: String): String {
        if (value.length > 35 || !LANGUAGE_TAG.matches(value)) invalidPayload()
        val normalized = try {
            Locale.Builder().setLanguageTag(value).build().toLanguageTag()
        } catch (_: IllformedLocaleException) {
            invalidPayload()
        }
        if (normalized == "und" || !LANGUAGE_TAG.matches(normalized)) invalidPayload()
        return normalized
    }
}

internal fun isValidUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    return false
                }
                index += 2
            }
            Character.isLowSurrogate(current) -> return false
            else -> index += 1
        }
    }
    return true
}

internal fun invalidPayload(): Nothing =
    throw CapabilityFrameworkException(CapabilityFailureCode.INVALID_PAYLOAD)

internal fun invalidOutput(): Nothing =
    throw CapabilityFrameworkException(CapabilityFailureCode.INVALID_OUTPUT)

internal fun internalFailure(): Nothing =
    throw CapabilityFrameworkException(CapabilityFailureCode.INTERNAL)

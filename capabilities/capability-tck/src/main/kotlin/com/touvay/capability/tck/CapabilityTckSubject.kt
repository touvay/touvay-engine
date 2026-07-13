package com.touvay.capability.tck

import com.touvay.engine.core.AttemptProgram
import com.touvay.engine.core.CapabilityDefinition
import com.touvay.engine.core.ExecutionCandidate
import com.touvay.engine.core.ExecutionContextCandidate
import com.touvay.engine.core.PromptValues
import com.touvay.engine.core.GeneratedToken

/** Subject-owned long-running operation used to verify cooperative cancellation. */
public fun interface CapabilityCancellationProbe {
    /** Calls [onStarted] after entering meaningful attempt work, then runs until cancelled. */
    public suspend fun run(attempt: AttemptProgram, onStarted: () -> Unit)
}

/** Fixtures supplied by every capability claiming framework v1 conformance. */
public class CapabilityTckSubject(
    public val definition: CapabilityDefinition,
    public val context: ExecutionContextCandidate,
    validPayload: ByteArray,
    malformedPayload: ByteArray,
    public val candidate: ExecutionCandidate,
    validPromptAsset: ByteArray,
    public val promptValues: PromptValues,
    public val expectedRenderedText: String,
    generatedTokens: List<GeneratedToken>,
    expectedDeltas: List<ByteArray>,
    expectedFinal: ByteArray,
    public val cancellationProbe: CapabilityCancellationProbe,
    supportedPromptFeatures: Set<String> = emptySet(),
) {
    private val validPayload = validPayload.copyOf()
    private val malformedPayload = malformedPayload.copyOf()
    private val validPromptAsset = validPromptAsset.copyOf()
    public val generatedTokens: List<GeneratedToken> = generatedTokens.toList()
    private val expectedDeltas = expectedDeltas.map(ByteArray::copyOf)
    private val expectedFinal = expectedFinal.copyOf()
    public val supportedPromptFeatures: Set<String> = supportedPromptFeatures.toSet()

    init {
        require(this.validPayload.isNotEmpty())
        require(this.malformedPayload.isNotEmpty())
        require(this.validPromptAsset.isNotEmpty())
        require(expectedRenderedText.isNotEmpty())
        require(this.generatedTokens.isNotEmpty())
        require(candidate.promptAsset != null)
    }

    /** Returns a caller-owned valid payload fixture. */
    public fun validPayload(): ByteArray = validPayload.copyOf()

    /** Returns a caller-owned invalid payload containing no secret production data. */
    public fun malformedPayload(): ByteArray = malformedPayload.copyOf()

    /** Returns a caller-owned signed-pack prompt fixture. */
    public fun validPromptAsset(): ByteArray = validPromptAsset.copyOf()

    /** Returns caller-owned expected ordered delta fixtures. */
    public fun expectedDeltas(): List<ByteArray> = expectedDeltas.map(ByteArray::copyOf)

    /** Returns a caller-owned expected final fixture. */
    public fun expectedFinal(): ByteArray = expectedFinal.copyOf()
}

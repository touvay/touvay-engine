package com.touvay.capability.rewrite

import com.touvay.capability.tck.CapabilityCancellationProbe
import com.touvay.capability.tck.CapabilityTckSubject
import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteDisposition
import com.touvay.contract.rewrite.v1.RewriteLength
import com.touvay.contract.rewrite.v1.RewriteRequest
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.contract.rewrite.v1.RewriteTone
import com.touvay.engine.core.CapabilityExecutionProgramFactory
import com.touvay.engine.core.ExecutionCandidate
import com.touvay.engine.core.ExecutionContextCandidate
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.ExecutionPriority
import com.touvay.engine.core.ExecutionProfileSpec
import com.touvay.engine.core.GeneratedToken
import com.touvay.engine.core.ModelRevisionRef
import com.touvay.engine.core.PreparedExecution
import com.touvay.engine.core.PromptAssetRef
import com.touvay.engine.core.PromptValues
import com.touvay.engine.core.RequestKey
import com.touvay.engine.core.StreamLimits
import java.security.MessageDigest
import kotlinx.coroutines.awaitCancellation

internal object RewriteTestFixtures {
    const val SOURCE = "please review this"
    const val RESULT = "Please review this."

    val assetBytes: ByteArray
        get() = RewriteReferencePromptAsset.bytes()

    val assetRef: PromptAssetRef
        get() = PromptAssetRef(
            RewriteReferencePromptAsset.LOGICAL_PATH,
            assetBytes.size,
            sha256(assetBytes),
        )

    val candidate: ExecutionCandidate
        get() = ExecutionCandidate(
            id = "rewrite-primary",
            modelRevision = ModelRevisionRef("rewrite-pack", "1.0.0", "a".repeat(64)),
            profile = ExecutionProfileSpec(threads = 1),
            contextLength = 4_096,
            maxOutputTokens = RewriteCapabilityDefinition.MAX_OUTPUT_TOKENS,
            decodeQuantumTokens = 8,
            retryableFailures = setOf(ExecutionFailureCode.RUNTIME_FAILURE),
            capabilityStepId = "rewrite.generate",
            promptAsset = assetRef,
        )

    fun request(
        text: String = SOURCE,
        tone: RewriteTone = RewriteTone.REWRITE_TONE_FORMAL,
        length: RewriteLength = RewriteLength.REWRITE_LENGTH_PRESERVE,
        locale: String? = "en-GB",
    ): RewriteRequest = RewriteRequest.newBuilder()
        .setText(text)
        .setTone(tone)
        .setLength(length)
        .also { builder -> if (locale != null) builder.outputLocaleBcp47 = locale }
        .build()

    fun context(payloadBytes: Int = request().serializedSize): ExecutionContextCandidate =
        ExecutionContextCandidate(
            requestKey = RequestKey("rewrite-tck", "rewrite-request"),
            capabilityId = RewriteCapabilityDefinition.KEY.id,
            schemaVersion = RewriteCapabilityDefinition.KEY.schemaVersion,
            priority = ExecutionPriority.INTERACTIVE,
            coalesceIdentity = null,
            receivedAtNanos = 1,
            deadlineAtNanos = Long.MAX_VALUE,
            contractVersion = 2,
            negotiatedFeatures = setOf("stream-credits-v1"),
            payloadBytes = payloadBytes,
            bulkInputBytes = 0,
            policyGeneration = 1,
            correlationId = "rewrite-tck-correlation",
            streamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
        )

    suspend fun prepare(request: RewriteRequest = request()): PreparedExecution =
        CapabilityExecutionProgramFactory(RewriteCapabilityDefinition())
            .prepare(context(request.serializedSize), request.toByteArray())

    fun subject(): CapabilityTckSubject {
        val first = "Please review "
        val second = "this."
        return CapabilityTckSubject(
            definition = RewriteCapabilityDefinition(),
            context = context(),
            validPayload = request().toByteArray(),
            malformedPayload = "TCK-REWRITE-PRIVATE-SENTINEL".toByteArray(),
            candidate = candidate,
            validPromptAsset = assetBytes,
            promptValues = PromptValues.of(
                mapOf(
                    "source" to encodePromptSource(SOURCE),
                    "tone" to "formal",
                    "length" to "preserve",
                    "locale" to "en-GB",
                ),
            ),
            expectedRenderedText = RewriteReferencePromptAsset.rendered(
                SOURCE,
                "formal",
                "preserve",
                "en-GB",
            ),
            generatedTokens = listOf(GeneratedToken(1, first), GeneratedToken(2, second)),
            expectedDeltas = listOf(delta(first), delta(second)),
            expectedFinal = response(RESULT, RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN),
            cancellationProbe = CapabilityCancellationProbe { attempt, onStarted ->
                attempt.consume(emptyList())
                onStarted()
                awaitCancellation()
            },
            supportedPromptFeatures = RewritePrompt.REQUIRED_ASSET_FEATURES,
        )
    }

    fun delta(text: String): ByteArray = RewriteDelta.newBuilder()
        .setText(text)
        .setProvisional(true)
        .build()
        .toByteArray()

    fun response(text: String, disposition: RewriteDisposition): ByteArray =
        RewriteResponse.newBuilder()
            .setText(text)
            .setDisposition(disposition)
            .build()
            .toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

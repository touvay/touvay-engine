package com.touvay.capability.tck

import com.touvay.engine.core.AttemptEnvironment
import com.touvay.engine.core.CapabilityExecutionProgramFactory
import com.touvay.engine.core.ExecutionCandidate
import com.touvay.engine.core.PreparedExecution
import com.touvay.engine.core.PreparedModelInput
import com.touvay.engine.core.PromptAssetSource
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.TokenSequence

/** Deterministic host-side harness shared by plugin unit tests and the TCK. */
public class CapabilityTestHarness(public val subject: CapabilityTckSubject) {
    private val factory = CapabilityExecutionProgramFactory(subject.definition)

    /** Runs capability preparation through the same generic adapter used in production. */
    public suspend fun prepare(payload: ByteArray = subject.validPayload()): PreparedExecution =
        factory.prepare(subject.context, payload)

    /** Builds exact model input using the subject's frozen asset binding. */
    public suspend fun buildModelInput(
        prepared: PreparedExecution,
        candidate: ExecutionCandidate = subject.candidate,
        source: PromptAssetSource = fixtureSource(),
    ): PreparedModelInput {
        val attempt = prepared.newAttempt(candidate)
        try {
            return attempt.buildModelInput(
                AttemptEnvironment(
                    model = ModelInstanceInfo(estimatedRamBytes = 1, maxContextLength = 4096),
                    promptAssets = source,
                ) { text ->
                    TokenSequence(text.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }.toIntArray())
                },
            )
        } finally {
            attempt.close()
        }
    }

    /** Exact fixture source that rejects any route-time binding mismatch. */
    public fun fixtureSource(bytes: ByteArray = subject.validPromptAsset()): PromptAssetSource =
        PromptAssetSource { ref ->
            require(ref == subject.candidate.promptAsset)
            bytes.copyOf()
        }
}

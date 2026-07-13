package com.touvay.engine.core

import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.SessionConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Supplies trusted request context without making a capability depend on engine policy. */
public fun interface CapabilityContextProvider {
    public fun contextFor(context: ExecutionContextCandidate): CapabilityContext

    public companion object {
        public val EMPTY: CapabilityContextProvider = CapabilityContextProvider { CapabilityContext.EMPTY }
    }
}

/**
 * Generic bridge from the production Capability SPI to Milestone 5 orchestration.
 * New capabilities require only composition registration; coordinator code is unchanged.
 */
public class CapabilityExecutionProgramFactory(
    private val definition: CapabilityDefinition,
    private val contexts: CapabilityContextProvider = CapabilityContextProvider.EMPTY,
) : ExecutionProgramFactory {
    override val descriptor: CapabilityDescriptor = CapabilityDescriptor(
        definition.contract.key.id,
        definition.contract.key.schemaVersion,
    )

    override suspend fun prepare(
        context: ExecutionContextCandidate,
        payload: ByteArray,
    ): PreparedExecution = suspendMapped {
        val key = CapabilityKey(context.capabilityId, context.schemaVersion)
        if (key != definition.contract.key) {
            capabilityFailure(CapabilityFailureCode.INVALID_PAYLOAD)
        }
        val bounded = CapabilityPayload.copyOf(payload, definition.contract.maxInlinePayloadBytes)
        val prepared = definition.prepare(
            CapabilityPreparationContext(context),
            bounded,
            contexts.contextFor(context),
        )
        CapabilityPreparedExecution(prepared)
    }

    private class CapabilityPreparedExecution(
        private val prepared: PreparedCapability,
    ) : PreparedExecution {
        private val closed = AtomicBoolean()

        override val demand: ExecutionDemand = prepared.executionDemand

        init {
            val plan = prepared.executionPlan
            if (plan.steps.size != 1 || plan.steps.single() !is PromptExecutionStep) {
                close()
                capabilityFailure(CapabilityFailureCode.UNSUPPORTED_PLAN)
            }
        }

        override fun newAttempt(candidate: ExecutionCandidate): AttemptProgram = syncMapped {
            if (closed.get()) capabilityFailure(CapabilityFailureCode.INTERNAL)
            val semanticPlan = prepared.executionPlan
            val stepId = candidate.capabilityStepId ?: semanticPlan.steps.single().id
            val step = semanticPlan.findStep(stepId)
            if (step !is PromptExecutionStep) {
                capabilityFailure(CapabilityFailureCode.MISSING_PLAN_BINDING)
            }
            val asset = candidate.promptAsset
                ?: capabilityFailure(CapabilityFailureCode.MISSING_PLAN_BINDING)
            val attempt = prepared.newAttempt(
                CapabilityAttemptBinding(
                    stepId = step.id,
                    candidateId = candidate.id,
                    promptAsset = asset,
                    contextLength = candidate.contextLength,
                    maxOutputTokens = candidate.maxOutputTokens,
                ),
            )
            CapabilityAttemptProgram(attempt)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) prepared.close()
        }
    }

    private class CapabilityAttemptProgram(
        private val attempt: CapabilityAttempt,
    ) : AttemptProgram {
        private val closed = AtomicBoolean()

        override suspend fun buildModelInput(environment: AttemptEnvironment): PreparedModelInput = suspendMapped {
            ensureOpen()
            attempt.buildModelInput(
                CapabilityAttemptEnvironment(
                    model = environment.model,
                    promptAssets = environment.promptAssets,
                    tokenizeAction = environment::tokenize,
                ),
            )
        }

        override fun sessionConfig(model: ModelInstanceInfo): SessionConfig = syncMapped {
            ensureOpen()
            attempt.sessionConfig(model)
        }

        override fun decodeParams(maxTokens: Int): DecodeParams = syncMapped {
            ensureOpen()
            attempt.decodeParams(maxTokens)
        }

        override suspend fun consume(tokens: List<GeneratedToken>): List<ByteArray> = suspendMapped {
            ensureOpen()
            attempt.consume(tokens)
        }

        override suspend fun finish(): ByteArray = suspendMapped {
            ensureOpen()
            attempt.finish()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) attempt.close()
        }

        private fun ensureOpen() {
            if (closed.get()) capabilityFailure(CapabilityFailureCode.INTERNAL)
        }
    }
}

private inline fun <T> syncMapped(block: () -> T): T = try {
    block()
} catch (failure: CapabilityFrameworkException) {
    throw ExecutionException(failure.code.toExecutionFailure())
}

private suspend inline fun <T> suspendMapped(crossinline block: suspend () -> T): T = try {
    block()
} catch (failure: CapabilityFrameworkException) {
    throw ExecutionException(failure.code.toExecutionFailure())
}

private fun CapabilityFailureCode.toExecutionFailure(): ExecutionFailureCode = when (this) {
    CapabilityFailureCode.INVALID_PAYLOAD,
    CapabilityFailureCode.INPUT_TOO_LARGE,
    CapabilityFailureCode.PROMPT_EXPANSION_TOO_LARGE,
    -> ExecutionFailureCode.INVALID_REQUEST
    CapabilityFailureCode.PROMPT_ASSET_UNAVAILABLE -> ExecutionFailureCode.MODEL_UNAVAILABLE
    CapabilityFailureCode.INVALID_PROMPT_ASSET,
    CapabilityFailureCode.UNSUPPORTED_PLAN,
    CapabilityFailureCode.MISSING_PLAN_BINDING,
    CapabilityFailureCode.INTERNAL,
    -> ExecutionFailureCode.INTERNAL
}

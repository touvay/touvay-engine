package com.touvay.engine.service

import com.touvay.engine.core.ExecutionCandidate
import com.touvay.engine.core.ExecutionContext
import com.touvay.engine.core.ExecutionPlan
import com.touvay.engine.core.ExecutionProfileSpec
import com.touvay.engine.core.ExecutionRouter
import com.touvay.engine.core.ModelRevisionRef
import com.touvay.engine.core.PreparedExecution
import com.touvay.engine.core.PromptAssetRef
import com.touvay.engine.models.ModelCapabilityRoute

/** Fixed validation policy for the single approved Rewrite plan. */
internal class RewriteExecutionRouter(
    private val route: ModelCapabilityRoute,
    private val threads: Int,
) : ExecutionRouter {
    override suspend fun route(
        context: ExecutionContext,
        prepared: PreparedExecution,
    ): ExecutionPlan {
        check(context.capabilityId == CAPABILITY_ID && context.schemaVersion == SCHEMA_VERSION)
        val contextLength = minOf(route.maximumContextLength, MAX_CONTEXT_LENGTH)
        check(contextLength >= MIN_CONTEXT_LENGTH)
        return ExecutionPlan(
            listOf(
                ExecutionCandidate(
                    id = "rewrite-primary",
                    modelRevision = ModelRevisionRef(
                        packId = route.revision.packId,
                        packVersion = route.revision.packVersion,
                        manifestSha256 = route.revision.manifestSha256,
                    ),
                    profile = ExecutionProfileSpec(threads = threads, useMmap = true),
                    contextLength = contextLength,
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    decodeQuantumTokens = DECODE_QUANTUM_TOKENS,
                    retryableFailures = emptySet(),
                    capabilityStepId = "rewrite.generate",
                    promptAsset = PromptAssetRef(
                        logicalPath = route.promptAsset.logicalPath,
                        byteSize = route.promptAsset.byteSize,
                        sha256 = route.promptAsset.sha256,
                    ),
                ),
            ),
        )
    }

    companion object {
        const val CAPABILITY_ID = "text.rewrite"
        const val SCHEMA_VERSION = 1
        private const val MIN_CONTEXT_LENGTH = 1_024
        private const val MAX_CONTEXT_LENGTH = 1_024
        private const val MAX_OUTPUT_TOKENS = 256
        private const val DECODE_QUANTUM_TOKENS = 8
    }
}

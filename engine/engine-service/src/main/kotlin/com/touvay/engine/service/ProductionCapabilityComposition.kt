package com.touvay.engine.service

import com.touvay.capability.rewrite.RewriteCapabilityDefinition
import com.touvay.engine.core.CapabilityExecutionProgramFactory
import com.touvay.engine.core.CapabilityFrameworkRegistry
import com.touvay.engine.core.ExecutionProgramRegistry

/** Compile-time production capability registrations owned by the composition root. */
internal object ProductionCapabilityComposition {
    private val rewrite = RewriteCapabilityDefinition()

    val capabilityRegistry: CapabilityFrameworkRegistry =
        CapabilityFrameworkRegistry.of(rewrite)

    val executionPrograms: ExecutionProgramRegistry = ExecutionProgramRegistry.of(
        CapabilityExecutionProgramFactory(rewrite),
    )
}

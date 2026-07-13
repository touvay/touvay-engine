package com.touvay.engine.service

import com.touvay.capability.rewrite.RewriteCapabilityDefinition
import kotlin.test.assertNotNull
import org.junit.Test

class ProductionCapabilityCompositionTest {
    @Test
    fun rewriteIsRegisteredInFrameworkAndExecutionRegistries() {
        assertNotNull(
            ProductionCapabilityComposition.capabilityRegistry.find(
                RewriteCapabilityDefinition.KEY,
            ),
        )
        assertNotNull(
            ProductionCapabilityComposition.executionPrograms.find(
                RewriteCapabilityDefinition.KEY.id,
                RewriteCapabilityDefinition.KEY.schemaVersion,
            ),
        )
    }
}

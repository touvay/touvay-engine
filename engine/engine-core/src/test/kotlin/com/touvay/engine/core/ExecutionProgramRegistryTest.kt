package com.touvay.engine.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecutionProgramRegistryTest {
    @Test
    fun `schema versions coexist and resolve exactly`() {
        val v1 = factory(1)
        val v2 = factory(2)
        val registry = ExecutionProgramRegistry.of(v1, v2)

        assertSame(v1, registry.find("test.versioned", 1))
        assertSame(v2, registry.find("test.versioned", 2))
        assertTrue(registry.containsId("test.versioned"))
    }

    @Test
    fun `duplicate exact execution key is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExecutionProgramRegistry.of(factory(1), factory(1))
        }
    }

    private fun factory(schemaVersion: Int): ExecutionProgramFactory =
        object : ExecutionProgramFactory {
            override val descriptor = CapabilityDescriptor("test.versioned", schemaVersion)

            override suspend fun prepare(
                context: ExecutionContextCandidate,
                payload: ByteArray,
            ): PreparedExecution = error("not used")
        }
}

package com.touvay.engine.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class CapabilityRegistryTest {

    private fun pipeline(id: String) = object : CapabilityPipeline {
        override val descriptor = CapabilityDescriptor(id, schemaVersion = 1)
        override suspend fun execute(
            payload: ByteArray,
            emit: suspend (ByteArray) -> Unit,
        ): ByteArray = payload
    }

    @Test
    fun find_returnsRegisteredPipeline() {
        val echo = pipeline("dev.echo")
        val registry = CapabilityRegistry.of(echo, pipeline("text.rewrite"))

        assertSame(echo, registry.find("dev.echo"))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun find_returnsNullForUnknownId() {
        assertNull(CapabilityRegistry.of(pipeline("dev.echo")).find("no.such"))
    }

    @Test
    fun duplicateIds_areRejected() {
        assertFailsWith<IllegalArgumentException> {
            CapabilityRegistry.of(pipeline("dev.echo"), pipeline("dev.echo"))
        }
    }
}

package com.touvay.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SemanticVersionTest {
    @Test
    fun validatesStrictSemverSyntax() {
        listOf("0.0.0", "1.2.3", "1.0.0-alpha.1", "1.0.0+build.7").forEach {
            assertNotNull(SemanticVersion.parse(it))
        }
        listOf("", "1", "1.2", "01.0.0", "1.0.0-01", "1.0.0+", "1.0.0-a..b").forEach {
            assertNull(SemanticVersion.parse(it))
        }
    }

    @Test
    fun implementsSemverPrecedenceWithoutNumericOverflow() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "999999999999999999999999.0.0",
        ).map { assertNotNull(SemanticVersion.parse(it)) }
        ordered.zipWithNext().forEach { (left, right) -> assertTrue(left < right) }
        assertEquals(
            0,
            assertNotNull(SemanticVersion.parse("1.0.0+one")).compareTo(
                assertNotNull(SemanticVersion.parse("1.0.0+two")),
            ),
        )
    }
}

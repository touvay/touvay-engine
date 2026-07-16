package com.touvay.demo.benchmark

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewriteBenchmarkModelTest {
    @Test
    fun `versioned corpus is balanced and contains at least one hundred cases`() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("corpus-v1.tsv"))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val cases = RewriteBenchmarkCorpus.parse(text)

        assertTrue(cases.size >= 100)
        RewriteBenchmarkCategory.entries.forEach { category ->
            assertTrue(cases.count { it.category == category } >= 10)
        }
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        assertEquals(64, RewriteBenchmarkCorpus.sha256(text).length)
    }

    @Test
    fun `exact reference receives a perfect quality score`() {
        val case = sampleCase()
        val score = RewriteQualityScorer.score(case, case.reference)

        assertEquals(1.0, score.characterFScore, 0.000_001)
        assertEquals(1.0, score.tokenFScore, 0.000_001)
        assertEquals(1.0, score.requiredTermRecall, 0.000_001)
        assertEquals(1.0, score.forbiddenTermCompliance, 0.000_001)
        assertEquals(1.0, score.composite, 0.000_001)
    }

    @Test
    fun `missing meaning and forbidden slang reduce quality`() {
        val score = RewriteQualityScorer.score(sampleCase(), "hey whatever")

        assertEquals(0.0, score.requiredTermRecall, 0.000_001)
        assertEquals(0.0, score.forbiddenTermCompliance, 0.000_001)
        assertTrue(score.composite < RewriteAcceptancePolicy.QUALITY_SINGLE_CASE_MIN)
    }

    @Test
    fun `percentile uses deterministic linear interpolation`() {
        assertEquals(3.7, percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.9), 0.000_001)
    }

    private fun sampleCase() = RewriteBenchmarkCase(
        id = "professional-999",
        category = RewriteBenchmarkCategory.PROFESSIONAL,
        locale = "en",
        tone = BenchmarkTone.FORMAL,
        length = BenchmarkLength.PRESERVE,
        source = "send the budget today",
        reference = "Please send the budget today.",
        requiredTerms = listOf("budget", "today"),
        forbiddenTerms = listOf("hey", "whatever"),
    )
}

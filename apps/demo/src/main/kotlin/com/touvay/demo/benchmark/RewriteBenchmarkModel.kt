package com.touvay.demo.benchmark

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal enum class RewriteBenchmarkCategory(val wireName: String) {
    GRAMMAR("grammar"),
    FORMALITY("formality"),
    SHORTENING("shortening"),
    EXPANSION("expansion"),
    PROFESSIONAL("professional"),
    SOCIAL("social"),
    MULTILINGUAL("multilingual");

    companion object {
        fun parse(value: String): RewriteBenchmarkCategory =
            entries.singleOrNull { it.wireName == value }
                ?: error("unknown benchmark category")
    }
}

internal enum class BenchmarkTone { NEUTRAL, FORMAL, CASUAL }

internal enum class BenchmarkLength { PRESERVE, SHORTER, LONGER }

internal data class RewriteBenchmarkCase(
    val id: String,
    val category: RewriteBenchmarkCategory,
    val locale: String,
    val tone: BenchmarkTone,
    val length: BenchmarkLength,
    val source: String,
    val reference: String,
    val requiredTerms: List<String>,
    val forbiddenTerms: List<String>,
)

internal object RewriteBenchmarkCorpus {
    const val ASSET_NAME = "corpus-v1.tsv"
    const val VERSION = 1
    const val MINIMUM_CASES = 100

    fun parse(text: String): List<RewriteBenchmarkCase> {
        val lines = text.lineSequence()
            .map(String::trimEnd)
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .toList()
        require(lines.isNotEmpty()) { "benchmark corpus is empty" }
        require(lines.first() == HEADER) { "benchmark corpus header mismatch" }
        val cases = lines.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == FIELD_COUNT) { "invalid corpus row ${index + 2}" }
            RewriteBenchmarkCase(
                id = fields[0],
                category = RewriteBenchmarkCategory.parse(fields[1]),
                locale = fields[2],
                tone = BenchmarkTone.valueOf(fields[3]),
                length = BenchmarkLength.valueOf(fields[4]),
                source = fields[5],
                reference = fields[6],
                requiredTerms = fields[7].terms(),
                forbiddenTerms = fields[8].terms(),
            )
        }
        validate(cases)
        return cases
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun validate(cases: List<RewriteBenchmarkCase>) {
        require(cases.size >= MINIMUM_CASES) { "benchmark corpus must contain at least 100 cases" }
        require(cases.map { it.id }.toSet().size == cases.size) { "benchmark case IDs must be unique" }
        RewriteBenchmarkCategory.entries.forEach { category ->
            require(cases.count { it.category == category } >= 10) {
                "benchmark category ${category.wireName} is underrepresented"
            }
        }
        cases.forEach { case ->
            require(ID.matches(case.id)) { "invalid benchmark case ID" }
            require(case.locale.isNotBlank()) { "benchmark locale is blank" }
            require(case.source.isNotBlank() && case.reference.isNotBlank()) {
                "benchmark source/reference is blank"
            }
            require(case.source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
                "benchmark source exceeds Rewrite v1 limit"
            }
            require(case.requiredTerms.isNotEmpty()) { "benchmark required terms are empty" }
        }
    }

    private fun String.terms(): List<String> =
        split('|').map(String::trim).filter { it.isNotEmpty() && it != "-" }

    private const val FIELD_COUNT = 9
    private const val MAX_SOURCE_BYTES = 8 * 1024
    private const val HEADER =
        "id\tcategory\tlocale\ttone\tlength\tsource\treference\trequired_terms\tforbidden_terms"
    private val ID = Regex("[a-z]+-[0-9]{3}")
}

internal data class RewriteQualityScore(
    val characterFScore: Double,
    val tokenFScore: Double,
    val requiredTermRecall: Double,
    val forbiddenTermCompliance: Double,
    val lengthCompliance: Double,
    val composite: Double,
)

internal object RewriteQualityScorer {
    fun score(case: RewriteBenchmarkCase, output: String): RewriteQualityScore {
        val character = characterFScore(case.reference, output)
        val token = multisetFScore(tokens(case.reference), tokens(output))
        val normalizedOutput = normalize(output)
        val required = case.requiredTerms.fractionMatching { term ->
            normalizedOutput.contains(normalize(term))
        }
        val forbidden = if (case.forbiddenTerms.none { term ->
                normalizedOutput.contains(normalize(term))
            }
        ) 1.0 else 0.0
        val length = lengthCompliance(case, output)
        val constraints = (forbidden + length) / 2.0
        val composite = 0.40 * character +
            0.20 * token +
            0.25 * required +
            0.15 * constraints
        return RewriteQualityScore(character, token, required, forbidden, length, composite)
    }

    private fun characterFScore(reference: String, output: String): Double {
        val expected = normalize(reference)
        val actual = normalize(output)
        if (expected.isEmpty() || actual.isEmpty()) return 0.0
        return (1..6).map { n ->
            multisetFScore(ngrams(expected, n), ngrams(actual, n))
        }.average()
    }

    private fun ngrams(value: String, size: Int): List<String> = when {
        value.length < size -> listOf(value)
        else -> (0..value.length - size).map { value.substring(it, it + size) }
    }

    private fun tokens(value: String): List<String> = WORD.findAll(normalize(value))
        .map { it.value }
        .toList()

    private fun multisetFScore(expected: List<String>, actual: List<String>): Double {
        if (expected.isEmpty() || actual.isEmpty()) return 0.0
        val remaining = expected.groupingBy { it }.eachCount().toMutableMap()
        var matches = 0
        actual.forEach { item ->
            val available = remaining[item] ?: 0
            if (available > 0) {
                matches++
                remaining[item] = available - 1
            }
        }
        val precision = matches.toDouble() / actual.size
        val recall = matches.toDouble() / expected.size
        return if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
    }

    private fun lengthCompliance(case: RewriteBenchmarkCase, output: String): Double {
        val sourceWords = max(1, tokens(case.source).size)
        val outputWords = tokens(output).size
        val ratio = outputWords.toDouble() / sourceWords
        return when (case.length) {
            BenchmarkLength.SHORTER -> rampDown(ratio, ideal = 0.75, failure = 1.05)
            BenchmarkLength.LONGER -> rampUp(ratio, ideal = 1.35, failure = 0.95)
            BenchmarkLength.PRESERVE -> when {
                ratio in 0.65..1.55 -> 1.0
                ratio < 0.65 -> (ratio / 0.65).coerceIn(0.0, 1.0)
                else -> (1.55 / ratio).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun rampDown(value: Double, ideal: Double, failure: Double): Double = when {
        value <= ideal -> 1.0
        value >= failure -> 0.0
        else -> (failure - value) / (failure - ideal)
    }

    private fun rampUp(value: Double, ideal: Double, failure: Double): Double = when {
        value >= ideal -> 1.0
        value <= failure -> 0.0
        else -> (value - failure) / (ideal - failure)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()

    private fun List<String>.fractionMatching(predicate: (String) -> Boolean): Double =
        if (isEmpty()) 1.0 else count(predicate).toDouble() / size

    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?")
    private val WHITESPACE = Regex("\\s+")
}

internal object RewriteAcceptancePolicy {
    const val VERSION = 1
    const val QUALITY_COMPOSITE_MEAN_MIN = 0.72
    const val QUALITY_CATEGORY_MEAN_MIN = 0.65
    const val QUALITY_SINGLE_CASE_MIN = 0.40
    const val REQUIRED_TERM_RECALL_MIN = 0.95
    const val FORBIDDEN_TERM_COMPLIANCE_MIN = 0.98
    const val WARM_TTFT_P95_MS_MAX = 2_500.0
    const val COLD_TTFT_P95_MS_MAX = 8_000.0
    const val WARM_END_TO_END_P95_MS_MAX = 15_000.0
    const val CANCELLATION_P95_MS_MAX = 150.0
    const val CANCELLATION_MAX_MS_MAX = 500.0
    const val PEAK_ENGINE_PSS_KB_MAX = 900 * 1024L
    const val THERMAL_STATUS_MAX = 3
    const val SUSTAINED_TTFT_DEGRADATION_MAX = 0.35
    const val ENERGY_PER_CASE_MWH_MAX = 15.0
    const val DECODE_TOKENS_PER_SECOND_P50_MIN = 8.0

    const val QUALITY_REGRESSION_ABSOLUTE_MAX = 0.02
    const val CATEGORY_REGRESSION_ABSOLUTE_MAX = 0.03
    const val LATENCY_REGRESSION_RELATIVE_MAX = 0.10
    const val THROUGHPUT_REGRESSION_RELATIVE_MAX = 0.08
    const val MEMORY_REGRESSION_RELATIVE_MAX = 0.10
    const val ENERGY_REGRESSION_RELATIVE_MAX = 0.10
    const val CANCELLATION_REGRESSION_MS_MAX = 50.0
}

internal fun percentile(values: List<Double>, percentile: Double): Double {
    require(percentile in 0.0..1.0)
    if (values.isEmpty()) return Double.NaN
    val sorted = values.sorted()
    val position = percentile * (sorted.size - 1)
    val lower = position.toInt()
    val upper = min(sorted.lastIndex, lower + 1)
    val fraction = position - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
}

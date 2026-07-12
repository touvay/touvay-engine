package com.touvay.engine.models

internal class SemanticVersion private constructor(
    private val major: String,
    private val minor: String,
    private val patch: String,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        compareNumeric(major, other.major).takeIf { it != 0 }?.let { return it }
        compareNumeric(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareNumeric(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val shared = minOf(prerelease.size, other.prerelease.size)
        repeat(shared) { index ->
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            val comparison = when {
                leftNumeric && rightNumeric -> compareNumeric(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val coreNumber = Regex("0|[1-9][0-9]*")
        private val identifier = Regex("[0-9A-Za-z-]+")

        fun parse(value: String): SemanticVersion? {
            if (value.isEmpty() || value.length > MAX_VERSION_CHARS) return null
            val buildSplit = value.split('+', limit = 3)
            if (buildSplit.size > 2) return null
            val build = buildSplit.getOrNull(1)
            if (build != null && !validIdentifiers(build, numericLeadingZerosAllowed = true)) {
                return null
            }

            val prereleaseSplit = buildSplit[0].split('-', limit = 2)
            val core = prereleaseSplit[0].split('.')
            if (core.size != 3 || core.any { !coreNumber.matches(it) }) return null
            val prerelease = prereleaseSplit.getOrNull(1)
            if (prerelease != null &&
                !validIdentifiers(prerelease, numericLeadingZerosAllowed = false)
            ) {
                return null
            }
            return SemanticVersion(
                major = core[0],
                minor = core[1],
                patch = core[2],
                prerelease = prerelease?.split('.') ?: emptyList(),
            )
        }

        private fun validIdentifiers(value: String, numericLeadingZerosAllowed: Boolean): Boolean {
            val parts = value.split('.')
            return parts.isNotEmpty() && parts.all { part ->
                identifier.matches(part) &&
                    (numericLeadingZerosAllowed ||
                        !part.all(Char::isDigit) ||
                        part == "0" ||
                        !part.startsWith('0'))
            }
        }

        private fun compareNumeric(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)

        private const val MAX_VERSION_CHARS: Int = 128
    }
}

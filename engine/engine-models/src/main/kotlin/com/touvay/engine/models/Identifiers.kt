package com.touvay.engine.models

import java.nio.charset.StandardCharsets

internal object Identifiers {
    private val general = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    private val signingKey = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun isGeneral(value: String, maxUtf8Bytes: Int): Boolean =
        value.toByteArray(StandardCharsets.UTF_8).size <= maxUtf8Bytes && general.matches(value)

    fun isSigningKey(value: String): Boolean = signingKey.matches(value)
}

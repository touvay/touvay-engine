package com.touvay.engine.models

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun assertVerificationFailure(
    expected: VerificationFailure,
    block: () -> Unit,
): ModelPackVerificationException {
    val failure = assertFailsWith<ModelPackVerificationException>(block = block)
    assertEquals(expected, failure.failure)
    return failure
}

package com.touvay.engine.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamCreditWindowTest {
    private val limits = StreamLimits(2, 10, 2, 10)

    @Test
    fun countAndBytes_areBothEnforced() {
        val window = StreamCreditWindow(limits)

        assertTrue(window.tryConsume(7))
        assertFalse(window.tryConsume(4))
        assertTrue(window.tryConsume(3))
        assertFalse(window.tryConsume(0))
    }

    @Test
    fun duplicateAndOutOfOrderGrants_doNotInflateCredit() {
        val window = StreamCreditWindow(limits)
        assertTrue(window.tryConsume(5))

        assertEquals(CreditGrantResult.ACCEPTED, window.grant(1, 1, 5))
        assertEquals(CreditGrantResult.STALE_OR_OUT_OF_ORDER, window.grant(1, 1, 5))
        assertEquals(CreditGrantResult.STALE_OR_OUT_OF_ORDER, window.grant(3, 1, 5))
        assertTrue(window.tryConsume(5))
        assertFalse(window.tryConsume(6))
    }

    @Test
    fun waiterResumesOnlyAfterValidCredit() = runTest {
        val window = StreamCreditWindow(StreamLimits(1, 4, 1, 4))
        assertTrue(window.tryConsume(4))
        val waiter = async { window.awaitAndConsume(4) }
        runCurrent()
        assertFalse(waiter.isCompleted)

        assertEquals(CreditGrantResult.INVALID, window.grant(1, 2, 4))
        runCurrent()
        assertFalse(waiter.isCompleted)

        assertEquals(CreditGrantResult.ACCEPTED, window.grant(1, 1, 4))
        runCurrent()
        assertTrue(waiter.isCompleted)
    }
}

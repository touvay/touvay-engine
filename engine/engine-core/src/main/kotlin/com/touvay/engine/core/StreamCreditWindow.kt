package com.touvay.engine.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** Result of a sequenced client credit grant. */
public enum class CreditGrantResult {
    ACCEPTED,
    STALE_OR_OUT_OF_ORDER,
    INVALID,
    CLOSED,
}

/**
 * Count-and-byte stream window from ADR-018. Accounting is thread-safe, bounded, and
 * sequence-checked so duplicate oneway grants cannot inflate credit.
 */
public class StreamCreditWindow(public val limits: StreamLimits) : AutoCloseable {
    private val lock = ReentrantLock()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var deltaCredits = limits.initialDeltaCredits
    private var byteCredits = limits.initialByteCredits
    private var nextGrantSequence = 1L
    private var closed = false

    /** Atomically consumes credit when available without waiting. */
    public fun tryConsume(payloadBytes: Int): Boolean {
        if (payloadBytes < 0 || payloadBytes.toLong() > limits.maxByteCredits) return false
        return lock.withLock {
            if (closed || deltaCredits <= 0 || byteCredits < payloadBytes) {
                false
            } else {
                deltaCredits -= 1
                byteCredits -= payloadBytes.toLong()
                true
            }
        }
    }

    /** Waits without retaining caller compute permission and consumes exactly once. */
    public suspend fun awaitAndConsume(payloadBytes: Int) {
        if (payloadBytes < 0 || payloadBytes.toLong() > limits.maxByteCredits) {
            throw ExecutionException(ExecutionFailureCode.BACKPRESSURE_EXCEEDED)
        }
        while (!tryConsume(payloadBytes)) {
            if (lock.withLock { closed }) {
                throw CancellationException("stream credit window closed")
            }
            wakeup.receive()
        }
    }

    /** Applies one strictly ordered grant. Invalid grants leave accounting unchanged. */
    public fun grant(
        sequence: Long,
        deltaCount: Int,
        payloadBytes: Long,
    ): CreditGrantResult = lock.withLock {
        if (closed) return CreditGrantResult.CLOSED
        if (sequence != nextGrantSequence) return CreditGrantResult.STALE_OR_OUT_OF_ORDER
        if (deltaCount <= 0 || payloadBytes < 0) return CreditGrantResult.INVALID
        if (deltaCount > limits.maxDeltaCredits || payloadBytes > limits.maxByteCredits) {
            return CreditGrantResult.INVALID
        }
        if (deltaCredits > limits.maxDeltaCredits - deltaCount ||
            byteCredits > limits.maxByteCredits - payloadBytes
        ) {
            return CreditGrantResult.INVALID
        }
        deltaCredits += deltaCount
        byteCredits += payloadBytes
        nextGrantSequence += 1
        wakeup.trySend(Unit)
        CreditGrantResult.ACCEPTED
    }

    override fun close() {
        lock.withLock { closed = true }
        wakeup.trySend(Unit)
    }
}

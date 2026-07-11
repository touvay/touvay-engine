package com.touvay.runtime.api

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative cancellation flag crossing the Kotlin ↔ native boundary.
 *
 * Coroutine cancellation in the engine flips this flag; native token loops poll it via a
 * JNI callback (ARCHITECTURE.md §14.2). Deliberately not a coroutine type: the SPI stays
 * usable from plain threads and native code.
 */
public interface CancelSignal {
    public val isCancelled: Boolean

    public companion object {
        /** A signal that never fires; for callers with nothing to cancel. */
        public val NONE: CancelSignal = object : CancelSignal {
            override val isCancelled: Boolean get() = false
        }
    }
}

/** Thread-safe [CancelSignal] the engine flips when a request is cancelled. */
public class AtomicCancelSignal : CancelSignal {
    private val cancelled = AtomicBoolean(false)

    override val isCancelled: Boolean
        get() = cancelled.get()

    public fun cancel() {
        cancelled.set(true)
    }
}

package com.touvay.runtime.llamacpp

import android.util.Log
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Leak backstop for native handles (SPI-OW-2). If an owner is garbage-collected
 * without close(), the guard frees the native resource and logs loudly — triggering
 * it is an engine bug being contained, never a supported cleanup path.
 *
 * PhantomReference-based because java.lang.ref.Cleaner needs API 33 and minSdk is 29.
 * The watcher thread is a daemon, started lazily on first registration.
 */
internal object NativeGuardRegistry {

    private const val TAG = "TouvayLlamaCpp"

    private val queue = ReferenceQueue<Any>()
    private val live = ConcurrentHashMap<Guard, Unit>()
    private val watcherStarted = AtomicBoolean(false)

    internal class Guard(
        owner: Any,
        queue: ReferenceQueue<Any>,
        val label: String,
        val handle: Long,
        /** Must not capture [owner], or the phantom reference can never enqueue. */
        val free: (Long) -> Unit,
    ) : PhantomReference<Any>(owner, queue) {
        val closed = AtomicBoolean(false)
    }

    fun register(owner: Any, label: String, handle: Long, free: (Long) -> Unit): Guard {
        val guard = Guard(owner, queue, label, handle, free)
        live[guard] = Unit
        if (watcherStarted.compareAndSet(false, true)) {
            thread(name = "touvay-native-guard", isDaemon = true) {
                while (true) {
                    val reference = queue.remove() as Guard
                    live.remove(reference)
                    if (!reference.closed.get()) {
                        Log.wtf(TAG, "leaked native ${reference.label}; freeing defensively")
                        runCatching { reference.free(reference.handle) }
                    }
                    reference.clear()
                }
            }
        }
        return guard
    }

    /** Called from close(): the owner released its resource properly. */
    fun markClosed(guard: Guard) {
        guard.closed.set(true)
        live.remove(guard)
    }
}

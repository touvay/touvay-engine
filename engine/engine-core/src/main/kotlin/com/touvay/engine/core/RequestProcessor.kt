package com.touvay.engine.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes capability requests: routing to pipelines, streaming, cooperative
 * cancellation, and same-client request coalescing (ARCHITECTURE.md §11, §14.1).
 *
 * This is the Task-0 execution core. The two-class priority scheduler and admission
 * control from §14.1 layer on top of it in a later task; the semantics here (exactly one
 * terminal callback, cancellation, coalescing) are the invariants that scheduler must
 * preserve.
 *
 * Thread-safe: [submit] and [cancel] may be called from any thread (binder threads in
 * production).
 *
 * @param dispatcher where pipeline work runs; injectable for virtual-time tests.
 * @param clock millisecond source for [ExecutionStats]; injectable for tests.
 */
public class RequestProcessor(
    private val registry: CapabilityRegistry,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val active = ConcurrentHashMap<RequestKey, ActiveRequest>()
    private val coalesceIndex = ConcurrentHashMap<CoalesceKey, RequestKey>()

    /**
     * Accepts a job for execution. Always results in exactly one terminal callback on
     * [listener], possibly before this method returns (validation failures).
     */
    public fun submit(job: RequestJob, listener: RequestListener) {
        val pipeline = registry.find(job.capabilityId)
        if (pipeline == null) {
            listener.onFailed(job.requestId, RequestFailure.UnknownCapability(job.capabilityId))
            return
        }
        val supported = pipeline.descriptor.schemaVersion
        if (job.schemaVersion != supported) {
            listener.onFailed(
                job.requestId,
                RequestFailure.SchemaVersionMismatch(job.capabilityId, job.schemaVersion, supported),
            )
            return
        }

        val requestKey = RequestKey(job.clientId, job.requestId)
        lateinit var request: ActiveRequest
        val coroutine = scope.launch(start = CoroutineStart.LAZY) {
            var firstDeltaAt = -1L
            var sequence = 0
            try {
                val result = pipeline.execute(job.payload) { deltaPayload ->
                    if (firstDeltaAt < 0) firstDeltaAt = clock()
                    listener.onDelta(job.requestId, sequence, deltaPayload)
                    sequence++
                }
                val finishedAt = clock()
                request.deliverTerminal {
                    listener.onCompleted(
                        job.requestId,
                        result,
                        ExecutionStats(
                            ttftMillis = (if (firstDeltaAt < 0) finishedAt else firstDeltaAt) -
                                request.startedAt,
                            totalMillis = finishedAt - request.startedAt,
                            deltaCount = sequence,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                request.deliverTerminal {
                    listener.onFailed(job.requestId, RequestFailure.Cancelled(request.superseded))
                }
                throw cancellation
            } catch (_: Throwable) {
                request.deliverTerminal {
                    listener.onFailed(
                        job.requestId,
                        RequestFailure.Internal(INTERNAL_FAILURE_MESSAGE),
                    )
                }
            }
        }
        request = ActiveRequest(job.requestId, listener, coroutine, clock())
        if (active.putIfAbsent(requestKey, request) != null) {
            coroutine.cancel()
            listener.onFailed(
                job.requestId,
                RequestFailure.Internal(DUPLICATE_REQUEST_MESSAGE),
            )
            return
        }

        listener.onAccepted(job.requestId)

        // Coalescing: registering before cancelling the predecessor closes the window in
        // which a third submit could observe neither request.
        val coalesceKey = job.coalesceKey?.let { CoalesceKey(job.clientId, it) }
        if (coalesceKey != null) {
            val previousKey = coalesceIndex.put(coalesceKey, requestKey)
            if (previousKey != null) {
                cancelInternal(previousKey, superseded = true)
            }
        }

        // Fallback for jobs cancelled before their coroutine ever ran (cancel raced the
        // dispatch, or shutdown): the body's catch never executes, but the terminal
        // callback must still fire exactly once.
        request.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                request.deliverTerminal {
                    listener.onFailed(job.requestId, RequestFailure.Cancelled(request.superseded))
                }
            }
            active.remove(requestKey, request)
            if (coalesceKey != null) {
                coalesceIndex.remove(coalesceKey, requestKey)
            }
        }
        coroutine.start()
    }

    /**
     * Requests cooperative cancellation. Returns false when the request is unknown —
     * already finished or never existed; distinguishing those is deliberately not
     * supported (the engine holds no history, ADR-012).
     */
    public fun cancel(clientId: String, requestId: String): Boolean =
        cancelInternal(RequestKey(clientId, requestId), superseded = false)

    /** Cancels everything in flight and stops accepting work. Idempotent. */
    public fun shutdown() {
        scope.cancel()
    }

    private fun cancelInternal(requestKey: RequestKey, superseded: Boolean): Boolean {
        val request = active[requestKey] ?: return false
        if (superseded) request.superseded = true
        request.job.cancel()
        return true
    }

    private data class CoalesceKey(val clientId: String, val key: String)

    private class ActiveRequest(
        val requestId: String,
        val listener: RequestListener,
        val job: Job,
        val startedAt: Long,
    ) {
        @Volatile
        var superseded: Boolean = false

        private val terminalDelivered = AtomicBoolean(false)

        /** Runs [block] only for the first terminal outcome; later attempts are no-ops. */
        inline fun deliverTerminal(block: () -> Unit) {
            if (terminalDelivered.compareAndSet(false, true)) {
                block()
            }
        }
    }

    private companion object {
        const val INTERNAL_FAILURE_MESSAGE = "internal execution failure"
        const val DUPLICATE_REQUEST_MESSAGE = "duplicate live request"
    }
}

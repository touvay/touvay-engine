package com.touvay.engine.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/** Fixed v1 admission and concurrency limits. */
public data class SchedulerLimits(
    public val maxLiveRequests: Int,
    public val maxLiveRequestsPerPrincipal: Int,
    public val maxConcurrentExecutions: Int,
    public val maxFixedRamBytes: Long = DEFAULT_FIXED_RAM_BYTES,
    public val maxKvBytes: Long = DEFAULT_KV_BYTES,
    public val maxLoadCost: Int = 1,
) {
    init {
        require(maxLiveRequests > 0)
        require(maxLiveRequestsPerPrincipal in 1..maxLiveRequests)
        require(maxConcurrentExecutions in 1..maxLiveRequests)
        require(maxFixedRamBytes > 0)
        require(maxKvBytes > 0)
        require(maxLoadCost > 0)
    }

    public companion object {
        public val DEFAULT: SchedulerLimits = SchedulerLimits(
            maxLiveRequests = 32,
            maxLiveRequestsPerPrincipal = 8,
            maxConcurrentExecutions = 1,
        )

        private const val DEFAULT_FIXED_RAM_BYTES: Long = 2L * 1024L * 1024L * 1024L
        private const val DEFAULT_KV_BYTES: Long = 512L * 1024L * 1024L
    }
}

public sealed interface SchedulerAdmissionResult {
    public class Accepted(public val admission: SchedulerAdmission) : SchedulerAdmissionResult
    public data class Rejected(public val failure: ExecutionFailureCode) : SchedulerAdmissionResult
}

/** Admission reservation retained until the request reaches a terminal state. */
public class SchedulerAdmission internal constructor(
    private val scheduler: PriorityExecutionScheduler,
    internal val entry: PriorityExecutionScheduler.Entry,
    public val acceptedAtNanos: Long,
) : AutoCloseable {
    public suspend fun acquirePermit(): DispatchPermit = scheduler.acquire(entry)
    override fun close(): Unit = scheduler.closeAdmission(entry)
}

/** One exclusive execution slot. Closing releases it; a retry must acquire again. */
public class DispatchPermit internal constructor(
    private val scheduler: PriorityExecutionScheduler,
    internal val entry: PriorityExecutionScheduler.Entry,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) scheduler.releasePermit(entry)
    }
}

/** Payload-free Scheduler state useful for consistency tests and local diagnostics. */
public data class SchedulerSnapshot(
    public val liveRequests: Int,
    public val queuedInteractive: Int,
    public val queuedBackground: Int,
    public val running: Int,
)

/**
 * Thread-safe v1 Scheduler: strict interactive priority, FIFO within class, atomic
 * same-principal coalescing, bounded admission, and cancellation preemption (ADR-010).
 */
public class PriorityExecutionScheduler(
    private val limits: SchedulerLimits = SchedulerLimits.DEFAULT,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    internal val maxLiveRequests: Int get() = limits.maxLiveRequests
    internal val maxLiveRequestsPerPrincipal: Int get() = limits.maxLiveRequestsPerPrincipal

    private val lock = ReentrantLock()
    private val entries = mutableMapOf<RequestKey, Entry>()
    private val coalesced = mutableMapOf<CoalesceIdentity, Entry>()
    private val interactiveQueue = mutableListOf<Entry>()
    private val backgroundQueue = mutableListOf<Entry>()
    private var nextSequence = 0L
    private var running = 0
    private var reservedFixedRamBytes = 0L
    private var reservedKvBytes = 0L
    private var reservedLoadCost = 0

    public fun admit(
        context: ExecutionContextCandidate,
        demand: ExecutionDemand,
        cancellation: (ExecutionCancellationReason) -> Unit,
    ): SchedulerAdmissionResult {
        val cancellations = mutableListOf<() -> Unit>()
        val result = lock.withLock {
            val key = context.requestKey
            if (entries.containsKey(key)) {
                return@withLock SchedulerAdmissionResult.Rejected(
                    ExecutionFailureCode.DUPLICATE_REQUEST,
                )
            }
            val identity = context.coalesceIdentity?.let {
                CoalesceIdentity(key.principal, it)
            }
            val predecessor = identity?.let(coalesced::get)
            if (predecessor != null && predecessor.ingressSequence > context.ingressSequence) {
                return@withLock SchedulerAdmissionResult.Rejected(
                    ExecutionFailureCode.SUPERSEDED,
                )
            }
            val replaceable = predecessor?.takeIf {
                it.state == EntryState.QUEUED || it.state == EntryState.ADMITTED
            }
            val principalCount = entries.keys.count { it.principal == key.principal }
            val replaceableCount = if (replaceable != null) 1 else 0
            if (entries.size - replaceableCount >= limits.maxLiveRequests ||
                principalCount - replaceableCount >= limits.maxLiveRequestsPerPrincipal
            ) {
                return@withLock SchedulerAdmissionResult.Rejected(ExecutionFailureCode.BUSY)
            }
            val reclaimedDemand = replaceable?.demand ?: ZERO_DEMAND
            if (wouldExceedResourceLimits(demand, reclaimedDemand)) {
                return@withLock SchedulerAdmissionResult.Rejected(ExecutionFailureCode.BUSY)
            }

            val inheritedSequence = replaceable?.takeIf {
                it.state == EntryState.QUEUED && it.priority == context.priority
            }?.sequence
            val entry = Entry(
                key = key,
                priority = context.priority,
                coalesceIdentity = identity,
                demand = demand,
                sequence = inheritedSequence ?: nextSequence++,
                ingressSequence = context.ingressSequence,
                cancellation = cancellation,
            )
            if (replaceable != null) removeEntryLocked(replaceable)
            entries[key] = entry
            reserveDemand(entry.demand)
            if (identity != null) coalesced[identity] = entry

            if (predecessor != null) {
                cancellations += {
                    predecessor.cancellation(ExecutionCancellationReason.SUPERSEDED)
                }
            }

            if (context.priority == ExecutionPriority.INTERACTIVE &&
                running >= limits.maxConcurrentExecutions
            ) {
                entries.values.firstOrNull {
                    it.state == EntryState.RUNNING &&
                        it.priority == ExecutionPriority.BACKGROUND &&
                        !it.preemptionRequested
                }?.let { background ->
                    background.preemptionRequested = true
                    cancellations += {
                        background.cancellation(ExecutionCancellationReason.PREEMPTED)
                    }
                }
            }
            SchedulerAdmissionResult.Accepted(
                SchedulerAdmission(this, entry, clockNanos()),
            )
        }
        cancellations.forEach { it() }
        return result
    }

    public fun snapshot(): SchedulerSnapshot = lock.withLock {
        SchedulerSnapshot(
            liveRequests = entries.size,
            queuedInteractive = interactiveQueue.size,
            queuedBackground = backgroundQueue.size,
            running = running,
        )
    }

    internal suspend fun acquire(entry: Entry): DispatchPermit {
        val waiter = lock.withLock {
            when (entry.state) {
                EntryState.ADMITTED -> Unit
                EntryState.CLOSED -> throw CancellationException("admission closed")
                EntryState.QUEUED, EntryState.RUNNING -> error("dispatch already requested")
            }
            val deferred = CompletableDeferred<DispatchPermit>()
            entry.waiter = deferred
            entry.state = EntryState.QUEUED
            queueFor(entry).add(entry)
            dispatchLocked()
            deferred
        }
        return waiter.await()
    }

    internal fun releasePermit(entry: Entry) = lock.withLock {
        if (entry.state != EntryState.RUNNING) return
        entry.state = EntryState.ADMITTED
        entry.waiter = null
        running -= 1
        check(running >= 0)
        dispatchLocked()
    }

    internal fun closeAdmission(entry: Entry) = lock.withLock {
        if (entry.state == EntryState.CLOSED) return
        if (entry.state == EntryState.QUEUED) queueFor(entry).remove(entry)
        if (entry.state == EntryState.RUNNING) {
            running -= 1
            check(running >= 0)
        }
        entry.waiter?.cancel(CancellationException("admission closed"))
        removeEntryLocked(entry)
        dispatchLocked()
    }

    private fun dispatchLocked() {
        while (running < limits.maxConcurrentExecutions) {
            val next = popNextLocked() ?: return
            if (next.state != EntryState.QUEUED) continue
            next.state = EntryState.RUNNING
            running += 1
            next.waiter?.complete(DispatchPermit(this, next))
        }
    }

    private fun popNextLocked(): Entry? = popFirst(interactiveQueue) ?: popFirst(backgroundQueue)

    private fun popFirst(queue: MutableList<Entry>): Entry? {
        val next = queue.minByOrNull { it.sequence } ?: return null
        queue.remove(next)
        return next
    }

    private fun queueFor(entry: Entry): MutableList<Entry> =
        if (entry.priority == ExecutionPriority.INTERACTIVE) interactiveQueue else backgroundQueue

    private fun removeEntryLocked(entry: Entry) {
        if (entry.state == EntryState.CLOSED) return
        if (entry.state == EntryState.QUEUED) queueFor(entry).remove(entry)
        entries.remove(entry.key, entry)
        releaseDemand(entry.demand)
        entry.coalesceIdentity?.let { coalesced.remove(it, entry) }
        entry.state = EntryState.CLOSED
        entry.waiter?.cancel(CancellationException("request superseded"))
        entry.waiter = null
    }

    private fun wouldExceedResourceLimits(
        demand: ExecutionDemand,
        reclaimed: ExecutionDemand,
    ): Boolean =
        exceeds(reservedFixedRamBytes, reclaimed.fixedRamBytes, demand.fixedRamBytes, limits.maxFixedRamBytes) ||
            exceeds(reservedKvBytes, reclaimed.kvBytes, demand.kvBytes, limits.maxKvBytes) ||
            exceeds(reservedLoadCost.toLong(), reclaimed.loadCost.toLong(), demand.loadCost.toLong(), limits.maxLoadCost.toLong())

    private fun reserveDemand(demand: ExecutionDemand) {
        reservedFixedRamBytes = Math.addExact(reservedFixedRamBytes, demand.fixedRamBytes)
        reservedKvBytes = Math.addExact(reservedKvBytes, demand.kvBytes)
        reservedLoadCost = Math.addExact(reservedLoadCost, demand.loadCost)
    }

    private fun releaseDemand(demand: ExecutionDemand) {
        reservedFixedRamBytes -= demand.fixedRamBytes
        reservedKvBytes -= demand.kvBytes
        reservedLoadCost -= demand.loadCost
        check(reservedFixedRamBytes >= 0 && reservedKvBytes >= 0 && reservedLoadCost >= 0)
    }

    private fun exceeds(current: Long, reclaimed: Long, added: Long, limit: Long): Boolean =
        added > limit || current - reclaimed > limit - added

    internal class Entry(
        val key: RequestKey,
        val priority: ExecutionPriority,
        val coalesceIdentity: CoalesceIdentity?,
        val demand: ExecutionDemand,
        val sequence: Long,
        val ingressSequence: Long,
        val cancellation: (ExecutionCancellationReason) -> Unit,
        var state: EntryState = EntryState.ADMITTED,
        var waiter: CompletableDeferred<DispatchPermit>? = null,
        var preemptionRequested: Boolean = false,
    )

    internal data class CoalesceIdentity(val principal: String, val key: String)

    internal enum class EntryState {
        ADMITTED,
        QUEUED,
        RUNNING,
        CLOSED,
    }

    private companion object {
        val ZERO_DEMAND = ExecutionDemand(0, 0, 0)
    }
}

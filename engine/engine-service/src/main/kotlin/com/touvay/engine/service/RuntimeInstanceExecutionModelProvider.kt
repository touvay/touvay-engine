package com.touvay.engine.service

import com.touvay.engine.core.ExecutionCandidate
import com.touvay.engine.core.ExecutionContext
import com.touvay.engine.core.ExecutionException
import com.touvay.engine.core.ExecutionFailureCode
import com.touvay.engine.core.ExecutionModelLease
import com.touvay.engine.core.ExecutionModelProvider
import com.touvay.engine.models.ExecutionProfileRequest
import com.touvay.engine.models.InstanceKey
import com.touvay.engine.models.ModelRevisionIdentity
import com.touvay.engine.models.RuntimeInstanceManager
import com.touvay.engine.models.RuntimeModelLease
import com.touvay.engine.models.VersionSelection
import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.ModelInstance
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** Composition-root adapter from execution-core's port to the Slice 4 Model Manager. */
internal class RuntimeInstanceExecutionModelProvider(
    private val manager: RuntimeInstanceManager,
    private val deviceProfile: () -> DeviceProfile,
) : ExecutionModelProvider, AutoCloseable {
    private val lanes = InferenceLaneRegistry()

    override suspend fun acquire(
        context: ExecutionContext,
        candidate: ExecutionCandidate,
    ): ExecutionModelLease {
        val revision = candidate.modelRevision
        val managerLease = try {
            manager.acquire(
                selection = VersionSelection.Exact(
                    ModelRevisionIdentity(
                        packId = revision.packId,
                        packVersion = revision.packVersion,
                        manifestSha256 = revision.manifestSha256,
                    ),
                ),
                device = deviceProfile(),
                request = ExecutionProfileRequest(
                    threads = candidate.profile.threads,
                    useMmap = candidate.profile.useMmap,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw ExecutionException(ExecutionFailureCode.MODEL_UNAVAILABLE)
        } catch (_: LinkageError) {
            throw ExecutionException(ExecutionFailureCode.MODEL_UNAVAILABLE)
        }
        val lane = try {
            lanes.borrow(managerLease.key)
        } catch (failure: Throwable) {
            managerLease.close()
            throw failure
        }
        return Lease(managerLease, lane)
    }

    override fun close() {
        lanes.close()
    }

    private class Lease(
        private val managerLease: RuntimeModelLease,
        private val lane: BorrowedLane,
    ) : ExecutionModelLease {
        private val closed = AtomicBoolean(false)
        override val instance: ModelInstance get() = managerLease.instance
        override val inferenceDispatcher: CoroutineDispatcher get() = lane.dispatcher

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    lane.close()
                } finally {
                    managerLease.close()
                }
            }
        }
    }
}

private class InferenceLaneRegistry : AutoCloseable {
    private val lock = ReentrantLock()
    private val entries = mutableMapOf<InstanceKey, LaneEntry>()
    private val threadNumber = AtomicInteger()
    private var closed = false

    fun borrow(key: InstanceKey): BorrowedLane = lock.withLock {
        check(!closed) { "inference lane registry is closed" }
        val entry = entries[key] ?: LaneEntry(newDispatcher()).also { entries[key] = it }
        check(entry.references < Int.MAX_VALUE)
        entry.references += 1
        BorrowedLane(entry.dispatcher) { release(key, entry) }
    }

    override fun close() {
        val dispatchers = lock.withLock {
            check(entries.values.none { it.references != 0 }) {
                "inference lanes are still referenced"
            }
            closed = true
            entries.values.map { it.dispatcher }.also { entries.clear() }
        }
        dispatchers.forEach(ExecutorCoroutineDispatcher::close)
    }

    private fun release(key: InstanceKey, entry: LaneEntry) {
        val dispatcher = lock.withLock {
            check(entries[key] === entry && entry.references > 0)
            entry.references -= 1
            if (entry.references == 0) {
                entries.remove(key)
                entry.dispatcher
            } else {
                null
            }
        }
        dispatcher?.close()
    }

    private fun newDispatcher(): ExecutorCoroutineDispatcher {
        val factory = ThreadFactory { runnable ->
            Thread(runnable, "touvay-inference-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        return Executors.newSingleThreadExecutor(factory).asCoroutineDispatcher()
    }

    private class LaneEntry(
        val dispatcher: ExecutorCoroutineDispatcher,
        var references: Int = 0,
    )
}

private class BorrowedLane(
    val dispatcher: CoroutineDispatcher,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

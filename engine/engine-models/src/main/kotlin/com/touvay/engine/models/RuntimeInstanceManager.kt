package com.touvay.engine.models

import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ResolvedModelPack
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exact revision plus canonical execution profile (ADR-015). */
public class InstanceKey internal constructor(
    public val revision: ModelRevisionIdentity,
    public val profile: ExecutionProfile,
) {
    override fun equals(other: Any?): Boolean = other is InstanceKey &&
        revision == other.revision && profile == other.profile

    override fun hashCode(): Int = 31 * revision.hashCode() + profile.hashCode()

    override fun toString(): String = "InstanceKey(redacted)"
}

internal enum class RuntimeInstanceState {
    LOADING,
    READY_IDLE,
    ACTIVE,
    UNLOADING,
}

internal class RuntimeCacheEntrySnapshot(
    val key: InstanceKey,
    val state: RuntimeInstanceState,
    val referenceCount: Int,
)

internal class RuntimeCacheSnapshot(
    entries: List<RuntimeCacheEntrySnapshot>,
) {
    val entries: List<RuntimeCacheEntrySnapshot> = Collections.unmodifiableList(entries.toList())
}

/** Idempotent borrow of one cached Runtime [ModelInstance]. */
public class RuntimeModelLease internal constructor(
    public val key: InstanceKey,
    public val instance: ModelInstance,
    private val readAssetAction: suspend (String, Int) -> ByteArray,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Reads one bounded file from the exact pinned revision without exposing its path. */
    public suspend fun readAsset(logicalPath: String, maxBytes: Int): ByteArray {
        require(logicalPath.isNotBlank() && maxBytes in 1..MAX_ASSET_BYTES)
        if (closed.get()) throw IllegalStateException("model lease is closed")
        return readAssetAction(logicalPath, maxBytes)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }

    public companion object {
        /** Hard heap bound for data assets read through a loaded model lease. */
        public const val MAX_ASSET_BYTES: Int = 512 * 1024
    }
}

/**
 * Runtime Registry-backed, single-flight loaded-instance owner. Construction remains in
 * the model subsystem; the composition root receives this narrow acquisition boundary.
 */
public class RuntimeInstanceManager internal constructor(
    private val catalog: ModelCatalogManager,
    private val registry: RuntimeRegistry,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = ReentrantLock()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + loadDispatcher)
    private val loaded = mutableMapOf<InstanceKey, LoadedEntry>()
    private val loading = mutableMapOf<InstanceKey, LoadingOperation>()
    private val unloading = mutableMapOf<InstanceKey, CompletableDeferred<Unit>>()
    private val consistencyVerifier = RuntimeCacheConsistencyVerifier()
    private val instanceVerifier = RuntimeInstanceConsistencyVerifier()
    private var managerState = ManagerState.OPEN
    private var shutdownCompletion: CompletableDeferred<Unit>? = null

    /** Acquires an exact/selected revision and pins its instance and backing files. */
    public suspend fun acquire(
        selection: VersionSelection,
        device: DeviceProfile,
        request: ExecutionProfileRequest,
    ): RuntimeModelLease {
        val (selected, resolution) = withContext(loadDispatcher) {
            val revision = catalog.select(selection)
            revision to registry.resolve(revision.resolved.manifest.runtime, device, request)
        }
        val key = InstanceKey(selected.resolved.identity, resolution.profile)

        while (true) {
            var warm: LoadedEntry? = null
            var operation: LoadingOperation? = null
            var waitForUnload: CompletableDeferred<Unit>? = null
            var startLoad = false
            lock.withLock {
                ensureOpen()
                waitForUnload = unloading[key]
                if (waitForUnload == null) {
                    warm = loaded[key]
                    if (warm != null) {
                        increment(warm!!)
                    } else {
                        operation = loading[key]
                        if (operation == null) {
                            operation = LoadingOperation()
                            loading[key] = operation!!
                            startLoad = true
                        }
                        if (operation!!.reservations == Int.MAX_VALUE) {
                            runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
                        }
                        operation!!.reservations += 1
                    }
                }
                verifyLocked()
            }

            warm?.let { entry ->
                return newLease(entry)
            }
            if (waitForUnload != null) {
                waitForUnload!!.await()
                continue
            }

            val reservedOperation = checkNotNull(operation)
            if (startLoad) {
                scope.launch {
                    load(key, resolution, reservedOperation)
                }
            }
            val entry = try {
                reservedOperation.completion.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                abandonReservation(key, reservedOperation)
                throw cancelled
            }
            return claimReservation(key, reservedOperation, entry)
        }
    }

    internal suspend fun releaseIdle(key: InstanceKey): Boolean {
        val entry: LoadedEntry
        val completion: CompletableDeferred<Unit>
        lock.withLock {
            ensureOpen()
            entry = loaded[key] ?: return false
            if (entry.referenceCount != 0) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.INSTANCE_REFERENCED)
            }
            loaded.remove(key)
            entry.state = RuntimeInstanceState.UNLOADING
            completion = CompletableDeferred()
            if (unloading.put(key, completion) != null) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
            }
            verifyLocked()
        }
        closeEntry(entry)
        lock.withLock {
            if (unloading.remove(key) !== completion) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
            }
            entry.closed = true
            completion.complete(Unit)
            verifyLocked()
        }
        return true
    }

    internal suspend fun releaseAllIdle(): Int {
        val keys = lock.withLock {
            ensureOpen()
            loaded.values.filter { it.referenceCount == 0 }.map { it.key }
        }
        var released = 0
        keys.forEach { key ->
            if (releaseIdle(key)) released += 1
        }
        return released
    }

    internal fun snapshot(): RuntimeCacheSnapshot = lock.withLock {
        verifyLocked()
        snapshotLocked()
    }

    internal suspend fun shutdown() {
        val existing: CompletableDeferred<Unit>?
        val completion: CompletableDeferred<Unit>
        val first: Boolean
        lock.withLock {
            existing = shutdownCompletion
            if (existing == null) {
                managerState = ManagerState.CLOSING
                completion = CompletableDeferred()
                shutdownCompletion = completion
                first = true
            } else {
                completion = existing
                first = false
            }
        }
        if (!first) {
            completion.await()
            return
        }

        withContext(NonCancellable) {
            val inFlight = lock.withLock {
                loading.values.map { it.completion } + unloading.values.toList()
            }
            inFlight.map { deferred ->
                async {
                    runCatching { deferred.await() }
                }
            }.awaitAll()

            val entries = lock.withLock {
                loaded.values.toList().also { values ->
                    loaded.clear()
                    values.forEach { it.state = RuntimeInstanceState.UNLOADING }
                }
            }
            entries.forEach { entry -> closeEntry(entry) }
            lock.withLock {
                entries.forEach { it.closed = true }
                if (loading.isNotEmpty() || unloading.isNotEmpty() || loaded.isNotEmpty()) {
                    runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
                }
                managerState = ManagerState.CLOSED
                completion.complete(Unit)
            }
            job.complete()
        }
    }

    private suspend fun load(
        key: InstanceKey,
        resolution: RuntimeResolution,
        operation: LoadingOperation,
    ) {
        var storageLease: PackRevisionLease? = null
        var instance: ModelInstance? = null
        var terminalFailure: RuntimeException? = null
        try {
            storageLease = catalog.acquire(VersionSelection.Exact(key.revision))
            val pack = project(storageLease.revision)
            instance = resolution.binding.runtime.loadModel(pack, resolution.profile.loadConfig())
            instanceVerifier.verify(key, resolution, pack, instance)
            if (publishLoaded(key, operation, resolution.binding, instance, storageLease)) {
                instance = null
                storageLease = null
            } else {
                terminalFailure = RuntimeLifecycleException(RuntimeLifecycleFailure.MANAGER_CLOSED)
            }
        } catch (failure: ModelCatalogException) {
            terminalFailure = failure
        } catch (failure: RuntimeLifecycleException) {
            terminalFailure = failure
        } catch (_: Exception) {
            terminalFailure = RuntimeLifecycleException(RuntimeLifecycleFailure.MODEL_LOAD_FAILED)
        } catch (_: LinkageError) {
            terminalFailure = RuntimeLifecycleException(RuntimeLifecycleFailure.MODEL_LOAD_FAILED)
        } finally {
            closeInstance(instance)
            closeStorageLease(storageLease)
        }
        terminalFailure?.let { failLoad(key, operation, it) }
    }

    private fun publishLoaded(
        key: InstanceKey,
        operation: LoadingOperation,
        binding: RuntimeBinding,
        instance: ModelInstance,
        storageLease: PackRevisionLease,
    ): Boolean {
        val entry: LoadedEntry?
        lock.withLock {
            if (loading.remove(key) !== operation || loaded.containsKey(key)) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
            }
            if (managerState == ManagerState.OPEN) {
                entry = LoadedEntry(
                    key = key,
                    binding = binding,
                    instance = instance,
                    storageLease = storageLease,
                    referenceCount = operation.reservations,
                    state = if (operation.reservations == 0) {
                        RuntimeInstanceState.READY_IDLE
                    } else {
                        RuntimeInstanceState.ACTIVE
                    },
                )
                loaded[key] = entry
                operation.publishedEntry = entry
            } else {
                entry = null
            }
            verifyLocked()
        }
        if (entry != null) {
            operation.completion.complete(entry)
            return true
        }
        return false
    }

    private fun failLoad(
        key: InstanceKey,
        operation: LoadingOperation,
        failure: RuntimeException,
    ) {
        lock.withLock {
            if (loading[key] === operation) loading.remove(key)
            verifyLocked()
        }
        operation.completion.completeExceptionally(failure)
    }

    private fun claimReservation(
        key: InstanceKey,
        operation: LoadingOperation,
        entry: LoadedEntry,
    ): RuntimeModelLease = lock.withLock {
        if (managerState != ManagerState.OPEN || loaded[key] !== entry || entry.closed) {
            decrement(entry)
            runtimeLifecycleFailure(RuntimeLifecycleFailure.MANAGER_CLOSED)
        }
        if (operation.publishedEntry !== entry || entry.referenceCount <= 0) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
        }
        newLease(entry)
    }

    private fun newLease(entry: LoadedEntry): RuntimeModelLease = RuntimeModelLease(
        key = entry.key,
        instance = entry.instance,
        readAssetAction = { logicalPath, maxBytes -> readAsset(entry, logicalPath, maxBytes) },
        releaseAction = { release(entry) },
    )

    private suspend fun readAsset(
        entry: LoadedEntry,
        logicalPath: String,
        maxBytes: Int,
    ): ByteArray {
        val file = lock.withLock {
            if (managerState != ManagerState.OPEN || loaded[entry.key] !== entry || entry.closed ||
                entry.referenceCount <= 0
            ) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
            }
            entry.storageLease.revision.files.singleOrNull { it.logicalPath == logicalPath }
                ?: runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
        }
        if (file.byteSize !in 1..maxBytes.toLong() || file.byteSize > Int.MAX_VALUE) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
        }
        return withContext(loadDispatcher) {
            try {
                if (Files.size(file.absolutePath) != file.byteSize) {
                    runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
                }
                val bytes = ByteArray(file.byteSize.toInt())
                Files.newInputStream(file.absolutePath).use { input ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val read = input.read(bytes, offset, bytes.size - offset)
                        if (read < 0) {
                            runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
                        }
                        offset += read
                    }
                    if (input.read() != -1) {
                        runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
                    }
                }
                bytes
            } catch (failure: RuntimeLifecycleException) {
                throw failure
            } catch (_: Exception) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.MODEL_ASSET_UNAVAILABLE)
            }
        }
    }

    private fun abandonReservation(key: InstanceKey, operation: LoadingOperation) = lock.withLock {
        val entry = operation.publishedEntry
        if (entry != null) {
            decrement(entry)
        } else if (loading[key] === operation) {
            if (operation.reservations <= 0) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
            }
            operation.reservations -= 1
        }
        verifyLocked()
    }

    private fun release(entry: LoadedEntry) = lock.withLock {
        decrement(entry)
        verifyLocked()
    }

    private fun increment(entry: LoadedEntry) {
        if (entry.closed || entry.referenceCount == Int.MAX_VALUE) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
        }
        entry.referenceCount += 1
        entry.state = RuntimeInstanceState.ACTIVE
    }

    private fun decrement(entry: LoadedEntry) {
        if (entry.referenceCount <= 0) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
        }
        entry.referenceCount -= 1
        if (!entry.closed && entry.referenceCount == 0) {
            entry.state = RuntimeInstanceState.READY_IDLE
        }
    }

    private fun project(revision: ResolvedModelRevision): ResolvedModelPack {
        val files = revision.files.associate { it.logicalPath to it.absolutePath }
        if (files.size != revision.files.size || files.values.any { !it.isAbsolute }) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.INSTANCE_INCONSISTENT)
        }
        return ResolvedModelPack(
            id = revision.identity.packId,
            version = revision.identity.packVersion,
            files = Collections.unmodifiableMap(files),
        )
    }

    private suspend fun closeEntry(entry: LoadedEntry) = withContext(loadDispatcher) {
        closeInstance(entry.instance)
        closeStorageLease(entry.storageLease)
    }

    private fun closeInstance(instance: ModelInstance?) {
        if (instance == null) return
        try {
            instance.close()
        } catch (_: Exception) {
            // Runtime SPI requires non-throwing close; ownership cleanup must still continue.
        } catch (_: LinkageError) {
            // A broken native unload must not prevent release of the immutable file hold.
        }
    }

    private fun closeStorageLease(lease: PackRevisionLease?) {
        if (lease == null) return
        try {
            lease.close()
        } catch (_: Exception) {
            // Catalog recovery revalidates durable state after an unexpected cleanup failure.
        }
    }

    private fun ensureOpen() {
        if (managerState != ManagerState.OPEN) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.MANAGER_CLOSED)
        }
    }

    private fun snapshotLocked(): RuntimeCacheSnapshot {
        val entries = buildList {
            loading.forEach { (key, operation) ->
                add(RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.LOADING, operation.reservations))
            }
            loaded.values.forEach { entry ->
                add(RuntimeCacheEntrySnapshot(entry.key, entry.state, entry.referenceCount))
            }
            unloading.forEach { (key, _) ->
                add(RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.UNLOADING, 0))
            }
        }
        return RuntimeCacheSnapshot(entries)
    }

    private fun verifyLocked() {
        consistencyVerifier.verify(snapshotLocked())
        loaded.forEach { (key, entry) ->
            if (entry.key != key ||
                entry.binding.identity != key.profile.bindingIdentity ||
                entry.storageLease.revision.identity != key.revision ||
                entry.closed
            ) {
                runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
            }
        }
    }

    private class LoadingOperation {
        val completion = CompletableDeferred<LoadedEntry>()
        var reservations: Int = 0
        var publishedEntry: LoadedEntry? = null
    }

    private class LoadedEntry(
        val key: InstanceKey,
        val binding: RuntimeBinding,
        val instance: ModelInstance,
        val storageLease: PackRevisionLease,
        var referenceCount: Int,
        var state: RuntimeInstanceState,
        var closed: Boolean = false,
    )

    private enum class ManagerState {
        OPEN,
        CLOSING,
        CLOSED,
    }
}

internal class RuntimeInstanceConsistencyVerifier {
    fun verify(
        key: InstanceKey,
        resolution: RuntimeResolution,
        pack: ResolvedModelPack,
        instance: ModelInstance,
    ) {
        if (resolution.binding.identity != resolution.profile.bindingIdentity ||
            key.profile != resolution.profile ||
            key.revision.packId != pack.id ||
            key.revision.packVersion != pack.version ||
            pack.files.isEmpty() ||
            pack.files.keys.any { it.isEmpty() } ||
            pack.files.values.any { !it.isAbsolute } ||
            instance.info.estimatedRamBytes < 0 ||
            instance.info.maxContextLength <= 0
        ) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.INSTANCE_INCONSISTENT)
        }
    }
}

internal class RuntimeCacheConsistencyVerifier {
    fun verify(snapshot: RuntimeCacheSnapshot) {
        if (snapshot.entries.groupingBy { it.key }.eachCount().values.any { it != 1 }) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
        }
        snapshot.entries.forEach { entry ->
            val valid = when (entry.state) {
                RuntimeInstanceState.LOADING -> entry.referenceCount >= 0
                RuntimeInstanceState.READY_IDLE -> entry.referenceCount == 0
                RuntimeInstanceState.ACTIVE -> entry.referenceCount > 0
                RuntimeInstanceState.UNLOADING -> entry.referenceCount == 0
            }
            if (!valid) runtimeLifecycleFailure(RuntimeLifecycleFailure.CACHE_INCONSISTENT)
        }
    }
}

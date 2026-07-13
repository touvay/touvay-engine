package com.touvay.engine.models

import com.touvay.runtime.api.ModelInstanceInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeInstanceLifecycleTest {
    private val root: Path = Files.createTempDirectory("touvay-runtime-lifecycle-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `cold acquire owns storage until explicit idle release`() = runBlocking<Unit> {
        val context = context()
        val lease = context.manager.acquire(
            VersionSelection.Active(context.pack.identity.packId),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 4),
        )
        val instance = assertIs<FakeModelInstance>(lease.instance)

        assertEquals(1, context.runtime.loadCalls.get())
        assertEquals(1, storageReferences(context))
        assertEquals(RuntimeInstanceState.ACTIVE, context.manager.snapshot().entries.single().state)
        assertEquals(0, instance.sessionCalls.get())

        lease.close()
        lease.close()
        assertEquals(RuntimeInstanceState.READY_IDLE, context.manager.snapshot().entries.single().state)
        assertEquals(1, storageReferences(context))

        assertTrue(context.manager.releaseIdle(lease.key))
        assertEquals(1, instance.closeCalls.get())
        assertEquals(0, storageReferences(context))
        assertTrue(context.manager.snapshot().entries.isEmpty())
    }

    @Test
    fun `lease reads only bounded files from its pinned revision`() = runBlocking<Unit> {
        val context = context()
        val lease = context.manager.acquire(
            VersionSelection.Exact(context.pack.identity),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 2),
        )

        assertEquals(
            "model-1.0.0",
            lease.readAsset("weights/model.gguf", 64).toString(Charsets.UTF_8),
        )
        assertFailsWith<RuntimeLifecycleException> {
            lease.readAsset("weights/model.gguf", 2)
        }
        assertFailsWith<RuntimeLifecycleException> {
            lease.readAsset("prompts/missing.pb", 64)
        }

        lease.close()
        assertFailsWith<IllegalStateException> {
            lease.readAsset("weights/model.gguf", 64)
        }
        context.manager.releaseAllIdle()
    }

    @Test
    fun `concurrent acquisition is single flight and reserves every lease`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val context = context { runtime ->
            runtime.beforeLoad = {
                entered.countDown()
                check(releaseLoad.await(10, TimeUnit.SECONDS))
            }
        }
        val workers = 12
        val leases = List(workers) {
            async(Dispatchers.Default) {
                context.manager.acquire(
                    VersionSelection.Exact(context.pack.identity),
                    RuntimeTestFixtures.device,
                    ExecutionProfileRequest(threads = 4),
                )
            }
        }

        assertTrue(entered.await(10, TimeUnit.SECONDS))
        awaitCondition {
            context.manager.snapshot().entries.singleOrNull()?.referenceCount == workers
        }
        assertEquals(1, context.runtime.loadCalls.get())
        releaseLoad.countDown()
        val acquired = leases.map { it.await() }

        assertTrue(acquired.all { it.instance === acquired.first().instance })
        assertEquals(workers, context.manager.snapshot().entries.single().referenceCount)
        acquired.forEach(RuntimeModelLease::close)
        assertEquals(RuntimeInstanceState.READY_IDLE, context.manager.snapshot().entries.single().state)
        context.manager.releaseAllIdle()
    }

    @Test
    fun `cancelling one waiter does not cancel a shared load`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val context = context { runtime ->
            runtime.beforeLoad = {
                entered.countDown()
                check(releaseLoad.await(10, TimeUnit.SECONDS))
            }
        }
        val cancelled = async(Dispatchers.Default) {
            context.manager.acquire(
                VersionSelection.Exact(context.pack.identity),
                RuntimeTestFixtures.device,
                ExecutionProfileRequest(2),
            )
        }
        val surviving = async(Dispatchers.Default) {
            context.manager.acquire(
                VersionSelection.Exact(context.pack.identity),
                RuntimeTestFixtures.device,
                ExecutionProfileRequest(2),
            )
        }

        assertTrue(entered.await(10, TimeUnit.SECONDS))
        awaitCondition { context.manager.snapshot().entries.single().referenceCount == 2 }
        cancelled.cancelAndJoin()
        awaitCondition { context.manager.snapshot().entries.single().referenceCount == 1 }
        releaseLoad.countDown()

        val lease = surviving.await()
        assertEquals(1, context.runtime.loadCalls.get())
        assertEquals(1, context.manager.snapshot().entries.single().referenceCount)
        lease.close()
        context.manager.releaseAllIdle()
    }

    @Test
    fun `revision and execution profile changes create distinct instances`() = runBlocking<Unit> {
        val context = context()
        val secondPack = StorageTestFixtures.pack("2.0.0")
        context.harness.manager.install(secondPack.source)
        val first = context.manager.acquire(
            VersionSelection.Exact(context.pack.identity),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 2),
        )
        val differentProfile = context.manager.acquire(
            VersionSelection.Exact(context.pack.identity),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 4),
        )
        val differentRevision = context.manager.acquire(
            VersionSelection.Exact(secondPack.identity),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 2),
        )

        assertEquals(3, context.runtime.loadCalls.get())
        assertNotEquals(first.key, differentProfile.key)
        assertNotEquals(first.key, differentRevision.key)
        assertFalse(first.instance === differentProfile.instance)
        assertFalse(first.instance === differentRevision.instance)
        listOf(first, differentProfile, differentRevision).forEach(RuntimeModelLease::close)
        assertEquals(3, context.manager.releaseAllIdle())
    }

    @Test
    fun `runtime load failure releases storage and permits retry without leaking details`() =
        runBlocking<Unit> {
            val context = context { runtime ->
                runtime.loadFailure = IllegalStateException("user payload and private path")
            }

            val failure = try {
                context.manager.acquire(
                    VersionSelection.Exact(context.pack.identity),
                    RuntimeTestFixtures.device,
                    ExecutionProfileRequest(2),
                )
                error("expected failure")
            } catch (failure: RuntimeLifecycleException) {
                failure
            }
            assertEquals(RuntimeLifecycleFailure.MODEL_LOAD_FAILED, failure.failure)
            assertEquals("model runtime load failed", failure.message)
            assertEquals(null, failure.cause)
            assertEquals(0, storageReferences(context))
            assertTrue(context.manager.snapshot().entries.isEmpty())

            context.runtime.loadFailure = null
            val lease = context.manager.acquire(
                VersionSelection.Exact(context.pack.identity),
                RuntimeTestFixtures.device,
                ExecutionProfileRequest(2),
            )
            assertEquals(2, context.runtime.loadCalls.get())
            lease.close()
            context.manager.releaseAllIdle()
        }

    @Test
    fun `post-load consistency failure closes instance before releasing storage`() =
        runBlocking<Unit> {
            lateinit var invalid: FakeModelInstance
            val context = context { runtime ->
                runtime.instanceFactory = {
                    FakeModelInstance(ModelInstanceInfo(-1, 0)).also { invalid = it }
                }
            }

            val failure = try {
                context.manager.acquire(
                    VersionSelection.Exact(context.pack.identity),
                    RuntimeTestFixtures.device,
                    ExecutionProfileRequest(2),
                )
                error("expected failure")
            } catch (failure: RuntimeLifecycleException) {
                failure
            }

            assertEquals(RuntimeLifecycleFailure.INSTANCE_INCONSISTENT, failure.failure)
            assertEquals(1, invalid.closeCalls.get())
            assertEquals(0, storageReferences(context))
            assertTrue(context.manager.snapshot().entries.isEmpty())
        }

    @Test
    fun `native load linkage failure completes waiters and releases storage`() =
        runBlocking<Unit> {
            val context = context { runtime ->
                runtime.loadFailure = UnsatisfiedLinkError("private library path")
            }

            val failure = try {
                context.manager.acquire(
                    VersionSelection.Exact(context.pack.identity),
                    RuntimeTestFixtures.device,
                    ExecutionProfileRequest(2),
                )
                error("expected failure")
            } catch (failure: RuntimeLifecycleException) {
                failure
            }

            assertEquals(RuntimeLifecycleFailure.MODEL_LOAD_FAILED, failure.failure)
            assertEquals(0, storageReferences(context))
            assertTrue(context.manager.snapshot().entries.isEmpty())
        }

    @Test
    fun `pending deletion waits for instance close and storage release`() = runBlocking<Unit> {
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        val harness = StorageTestFixtures.catalog(root)
        harness.manager.install(v1.source)
        harness.manager.install(v2.source)
        harness.manager.activate(v2.identity)
        val payload = harness.manager.select(VersionSelection.Exact(v1.identity))
            .resolved.files.single().absolutePath
        lateinit var instance: FakeModelInstance
        val runtime = FakeRuntime().apply {
            instanceFactory = {
                FakeModelInstance(closeAction = { assertTrue(Files.exists(payload)) })
                    .also { instance = it }
            }
        }
        val manager = RuntimeInstanceManager(harness.manager, RuntimeTestFixtures.registry(runtime))
        val lease = manager.acquire(
            VersionSelection.Exact(v1.identity),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(2),
        )
        lease.close()

        assertEquals(
            RemovalDisposition.DEFERRED_UNTIL_RELEASE,
            harness.manager.requestRemoval(v1.identity),
        )
        assertTrue(Files.exists(payload))
        manager.releaseIdle(lease.key)

        assertEquals(1, instance.closeCalls.get())
        assertFalse(Files.exists(payload))
        assertTrue(harness.manager.snapshot().revisions.none { it.resolved.identity == v1.identity })
    }

    @Test
    fun `acquire waits for same-key teardown before starting a new load`() = runBlocking<Unit> {
        val closeEntered = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val context = context { runtime ->
            var created = 0
            runtime.instanceFactory = {
                created += 1
                if (created == 1) {
                    FakeModelInstance(closeAction = {
                        closeEntered.countDown()
                        check(finishClose.await(10, TimeUnit.SECONDS))
                    })
                } else {
                    FakeModelInstance()
                }
            }
        }
        val first = context.manager.acquire(
            VersionSelection.Exact(context.pack.identity),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(2),
        )
        first.close()
        val unload = async(Dispatchers.Default) { context.manager.releaseIdle(first.key) }
        assertTrue(closeEntered.await(10, TimeUnit.SECONDS))

        val reacquire = async(Dispatchers.Default) {
            context.manager.acquire(
                VersionSelection.Exact(context.pack.identity),
                RuntimeTestFixtures.device,
                ExecutionProfileRequest(2),
            )
        }
        delay(100)
        assertFalse(reacquire.isCompleted)
        assertEquals(1, context.runtime.loadCalls.get())

        finishClose.countDown()
        assertTrue(unload.await())
        val second = reacquire.await()
        assertEquals(2, context.runtime.loadCalls.get())
        second.close()
        context.manager.releaseIdle(second.key)
    }

    @Test
    fun `shutdown rejects acquisition and closes loaded instances exactly once`() =
        runBlocking<Unit> {
            val context = context()
            val lease = context.manager.acquire(
                VersionSelection.Exact(context.pack.identity),
                RuntimeTestFixtures.device,
                ExecutionProfileRequest(2),
            )
            val instance = assertIs<FakeModelInstance>(lease.instance)

            context.manager.shutdown()
            context.manager.shutdown()
            assertEquals(1, instance.closeCalls.get())
            assertEquals(0, storageReferences(context))

            lease.close()
            lease.close()
            val failure = try {
                context.manager.acquire(
                    VersionSelection.Exact(context.pack.identity),
                    RuntimeTestFixtures.device,
                    ExecutionProfileRequest(2),
                )
                error("expected failure")
            } catch (failure: RuntimeLifecycleException) {
                failure
            }
            assertEquals(RuntimeLifecycleFailure.MANAGER_CLOSED, failure.failure)
        }

    private fun context(configure: (FakeRuntime) -> Unit = {}): RuntimeContext {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        harness.manager.activate(pack.identity)
        val runtime = FakeRuntime().also(configure)
        return RuntimeContext(
            harness,
            pack,
            runtime,
            RuntimeInstanceManager(harness.manager, RuntimeTestFixtures.registry(runtime)),
        )
    }

    private fun storageReferences(context: RuntimeContext): Int = context.harness.manager.snapshot()
        .revisions.single { it.resolved.identity == context.pack.identity }
        .storageReferenceCount

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }
}

private class RuntimeContext(
    val harness: CatalogTestHarness,
    val pack: TestPack,
    val runtime: FakeRuntime,
    val manager: RuntimeInstanceManager,
)

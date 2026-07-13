package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogLeaseTest {
    private val root: Path = Files.createTempDirectory("touvay-lease-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `lease acquisition and idempotent release balance storage references`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        harness.manager.activate(pack.identity)

        val first = harness.manager.acquire(VersionSelection.Active(pack.identity.packId))
        val second = harness.manager.acquire(VersionSelection.Exact(pack.identity))
        assertEquals(2, referenceCount(harness, pack.identity))

        first.close()
        first.close()
        assertEquals(1, referenceCount(harness, pack.identity))
        second.close()
        assertEquals(0, referenceCount(harness, pack.identity))
    }

    @Test
    fun `pending removal blocks new leases and deletes only after final release`() {
        val harness = StorageTestFixtures.catalog(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        harness.manager.install(v1.source)
        harness.manager.install(v2.source)
        harness.manager.activate(v2.identity)
        val lease = harness.manager.acquire(VersionSelection.Exact(v1.identity))
        val payload = lease.revision.files.single().absolutePath

        assertEquals(
            RemovalDisposition.DEFERRED_UNTIL_RELEASE,
            harness.manager.requestRemoval(v1.identity),
        )
        assertTrue(Files.exists(payload))
        val failure = assertFailsWith<ModelCatalogException> {
            harness.manager.acquire(VersionSelection.Exact(v1.identity))
        }
        assertEquals(ModelCatalogFailure.REVISION_PENDING_REMOVAL, failure.failure)

        lease.close()
        assertFalse(Files.exists(payload))
        assertTrue(harness.manager.snapshot().revisions.none { it.resolved.identity == v1.identity })
    }

    @Test
    fun `active revision removal is rejected`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        harness.manager.activate(pack.identity)

        val failure = assertFailsWith<ModelCatalogException> {
            harness.manager.requestRemoval(pack.identity)
        }

        assertEquals(ModelCatalogFailure.ACTIVE_REVISION_REMOVE_FORBIDDEN, failure.failure)
    }

    @Test
    fun `concurrent leases remain balanced`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        val workers = 12
        val ready = CountDownLatch(workers)
        val release = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)
        repeat(workers) {
            executor.execute {
                val lease = harness.manager.acquire(VersionSelection.Exact(pack.identity))
                ready.countDown()
                release.await()
                lease.close()
                done.countDown()
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        assertEquals(workers, referenceCount(harness, pack.identity))
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(0, referenceCount(harness, pack.identity))
    }

    @Test
    fun `catalog rebuild preserves live storage references`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        val lease = harness.manager.acquire(VersionSelection.Exact(pack.identity))

        harness.manager.rebuild()

        assertEquals(1, referenceCount(harness, pack.identity))
        lease.close()
        assertEquals(0, referenceCount(harness, pack.identity))
    }

    @Test
    fun `unreferenced inactive revision is removed immediately`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)

        assertEquals(RemovalDisposition.REMOVED, harness.manager.requestRemoval(pack.identity))
        assertTrue(harness.manager.snapshot().revisions.isEmpty())
    }

    private fun referenceCount(harness: CatalogTestHarness, identity: ModelRevisionIdentity): Int =
        harness.manager.snapshot().revisions.single { it.resolved.identity == identity }
            .storageReferenceCount
}

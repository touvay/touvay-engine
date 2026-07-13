package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogRebuildTest {
    private val root: Path = Files.createTempDirectory("touvay-catalog-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `rebuild derives immutable catalog and active state from committed storage`() {
        val harness = StorageTestFixtures.catalog(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        harness.store.install(v1.source)
        harness.store.install(v2.source)
        harness.store.activate(v1.identity)

        val report = harness.manager.rebuild()
        val snapshot = harness.manager.snapshot()

        assertEquals(2, report.revisionCount)
        assertEquals(2, snapshot.revisions.size)
        assertEquals(
            v1.identity,
            snapshot.revisions.single { it.state == CatalogRevisionState.ACTIVE }.resolved.identity,
        )
        assertTrue(snapshot.revisions.all { it.loadHealth == CatalogLoadHealth.NEVER_LOADED })
        assertTrue(Files.isRegularFile(root.resolve("catalog-cache.pb")))
    }

    @Test
    fun `valid metadata cache avoids rehashing unchanged payloads`() {
        val clock = MutableCatalogClock(10_000)
        val first = StorageTestFixtures.catalog(root, clock)
        first.manager.install(StorageTestFixtures.pack().source)
        val verifiedAt = first.manager.snapshot().revisions.single().fullyVerifiedAtEpochMillis
        clock.now = 20_000

        val second = StorageTestFixtures.catalog(root, clock)
        val report = second.manager.rebuild()

        assertTrue(report.metadataCacheUsed)
        assertEquals(verifiedAt, second.manager.snapshot().revisions.single().fullyVerifiedAtEpochMillis)
    }

    @Test
    fun `corrupt metadata cache is disposable and rebuilt from source of truth`() {
        val first = StorageTestFixtures.catalog(root)
        first.manager.install(StorageTestFixtures.pack().source)
        Files.writeString(root.resolve("catalog-cache.pb"), "corrupt")

        val second = StorageTestFixtures.catalog(root)
        val report = second.manager.rebuild()

        assertFalse(report.metadataCacheUsed)
        assertEquals(1, report.revisionCount)
        assertTrue(Files.size(root.resolve("catalog-cache.pb")) > "corrupt".length)
    }

    @Test
    fun `changed payload metadata forces full integrity verification and quarantine`() {
        val first = StorageTestFixtures.catalog(root)
        first.manager.install(StorageTestFixtures.pack().source)
        val payload = StorageTestFixtures.revisionDirectory(root).resolve("files/weights/model.gguf")
        val bytes = Files.readAllBytes(payload)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        Files.write(payload, bytes)
        Files.setLastModifiedTime(payload, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

        val second = StorageTestFixtures.catalog(root)
        val report = second.manager.rebuild()

        assertEquals(1, report.recovery.removedInvalidRevisions)
        assertEquals(0, report.revisionCount)
        assertTrue(second.manager.snapshot().revisions.isEmpty())
    }

    @Test
    fun `catalog snapshot generations publish atomically and remain immutable`() {
        val harness = StorageTestFixtures.catalog(root)
        val initial = harness.manager.snapshot()
        harness.manager.install(StorageTestFixtures.pack().source)
        val installed = harness.manager.snapshot()

        assertTrue(installed.generation > initial.generation)
        assertTrue(initial.revisions.isEmpty())
        assertEquals(1, installed.revisions.size)
        @Suppress("UNCHECKED_CAST")
        val mutableView = installed.revisions as MutableList<CatalogRevision>
        kotlin.test.assertFailsWith<UnsupportedOperationException> { mutableView.clear() }
    }
}

private class MutableCatalogClock(var now: Long) : CatalogClock {
    override fun epochMillis(): Long = now
}

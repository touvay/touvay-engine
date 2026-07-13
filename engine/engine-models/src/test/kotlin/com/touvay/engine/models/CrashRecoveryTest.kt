package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashRecoveryTest {
    private val root: Path = Files.createTempDirectory("touvay-recovery-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `recovery removes every incomplete staging transaction`() {
        val preCommitPoints = listOf(
            StoreCheckpoint.STAGING_CREATED,
            StoreCheckpoint.MANIFESTS_DURABLE,
            StoreCheckpoint.FILES_DURABLE,
            StoreCheckpoint.MARKER_DURABLE,
        )
        preCommitPoints.forEachIndexed { index, checkpoint ->
            val caseRoot = Files.createDirectory(root.resolve("staging-$index"))
            val crashing = StorageTestFixtures.store(
                caseRoot,
                StoreFaultInjector { if (it == checkpoint) throw SimulatedProcessDeath() },
            )
            assertFailsWith<SimulatedProcessDeath> { crashing.install(StorageTestFixtures.pack().source) }

            val report = StorageTestFixtures.store(caseRoot).recover()

            assertEquals(1, report.removedStagingEntries)
            assertEquals(0, Files.list(caseRoot.resolve("staging")).use { it.count() })
        }
    }

    @Test
    fun `recovery preserves a revision committed before process death`() {
        val pack = StorageTestFixtures.pack()
        val crashing = StorageTestFixtures.store(
            root,
            StoreFaultInjector {
                if (it == StoreCheckpoint.REVISION_COMMITTED) throw SimulatedProcessDeath()
            },
        )
        assertFailsWith<SimulatedProcessDeath> { crashing.install(pack.source) }

        val report = StorageTestFixtures.store(root).recover()

        assertEquals(0, report.removedInvalidRevisions)
        StorageTestFixtures.store(root).activate(pack.identity)
        assertEquals(pack.identity, StorageTestFixtures.store(root).activeRevision(pack.identity.packId))
    }

    @Test
    fun `recovery drains a deletion interrupted after rename to trash`() {
        val pack = StorageTestFixtures.pack()
        StorageTestFixtures.store(root).install(pack.source)
        val crashing = StorageTestFixtures.store(
            root,
            StoreFaultInjector {
                if (it == StoreCheckpoint.REVISION_MOVED_TO_TRASH) throw SimulatedProcessDeath()
            },
        )
        assertFailsWith<SimulatedProcessDeath> { crashing.deleteInactive(pack.identity) }
        assertEquals(1, Files.list(root.resolve("trash")).use { it.count() })

        val report = StorageTestFixtures.store(root).recover()

        assertEquals(1, report.completedTrashDeletes)
        assertEquals(0, Files.list(root.resolve("trash")).use { it.count() })
    }

    @Test
    fun `recovery removes corrupt revisions and repairs active pointer to newest valid version`() {
        val store = StorageTestFixtures.store(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        store.install(v1.source)
        store.install(v2.source)
        store.activate(v2.identity)
        val v2Directory = Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString() == "manifest.pb" }
                .filter { Files.readAllBytes(it).contentEquals(v2.manifestBytes) }
                .findFirst().orElseThrow().parent
        }
        Files.writeString(v2Directory.resolve("files/weights/model.gguf"), "corrupt")

        val report = StorageTestFixtures.store(root).recover()

        assertEquals(1, report.removedInvalidRevisions)
        assertEquals(1, report.repairedOrClearedActivePointers)
        assertEquals(v1.identity, StorageTestFixtures.store(root).activeRevision(v1.identity.packId))
    }

    @Test
    fun `recovery clears invalid active pointer when no valid revision remains`() {
        val pack = StorageTestFixtures.pack()
        val store = StorageTestFixtures.store(root)
        store.install(pack.source)
        store.activate(pack.identity)
        val revision = StorageTestFixtures.revisionDirectory(root)
        Files.delete(revision.resolve("installed.ok"))

        val report = store.recover()

        assertTrue(report.removedInvalidRevisions >= 1)
        assertEquals(1, report.repairedOrClearedActivePointers)
        assertNull(store.activeRevision(pack.identity.packId))
    }
}

package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageIntegrityTest {
    private val root: Path = Files.createTempDirectory("touvay-integrity-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `activation performs full committed revision integrity verification`() {
        val pack = StorageTestFixtures.pack()
        val store = StorageTestFixtures.store(root)
        store.install(pack.source)
        Files.writeString(
            StorageTestFixtures.revisionDirectory(root).resolve("files/weights/model.gguf"),
            "tampered",
        )

        val failure = assertFailsWith<ModelStoreException> { store.activate(pack.identity) }

        assertEquals(ModelStoreFailure.STORE_CORRUPT, failure.failure)
    }

    @Test
    fun `recovery rejects extra files inside an immutable revision`() {
        val store = StorageTestFixtures.store(root)
        store.install(StorageTestFixtures.pack().source)
        Files.writeString(StorageTestFixtures.revisionDirectory(root).resolve("files/extra"), "extra")

        val report = store.recover()

        assertEquals(1, report.removedInvalidRevisions)
    }

    @Test
    fun `recovery rejects a marker that no longer authenticates stored manifest bytes`() {
        val store = StorageTestFixtures.store(root)
        store.install(StorageTestFixtures.pack().source)
        val marker = StorageTestFixtures.revisionDirectory(root).resolve("installed.ok")
        val bytes = Files.readAllBytes(marker)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(marker, bytes)

        val report = store.recover()

        assertEquals(1, report.removedInvalidRevisions)
    }

    @Test
    fun `process death after active replacement leaves a valid selection`() {
        val pack = StorageTestFixtures.pack()
        StorageTestFixtures.store(root).install(pack.source)
        val crashing = StorageTestFixtures.store(
            root,
            StoreFaultInjector {
                if (it == StoreCheckpoint.ACTIVE_REPLACED) throw SimulatedProcessDeath()
            },
        )

        assertFailsWith<SimulatedProcessDeath> { crashing.activate(pack.identity) }
        val report = StorageTestFixtures.store(root).recover()

        assertEquals(0, report.repairedOrClearedActivePointers)
        assertEquals(pack.identity, StorageTestFixtures.store(root).activeRevision(pack.identity.packId))
    }

    @Test
    fun `recovery reconstructs redundant pack identity from a verified revision`() {
        val pack = StorageTestFixtures.pack()
        val store = StorageTestFixtures.store(root)
        store.install(pack.source)
        val identityRecord = Files.walk(root.resolve("packs")).use { paths ->
            paths.filter { it.fileName.toString() == "identity.pb" }.findFirst().orElseThrow()
        }
        Files.writeString(identityRecord, "corrupt")

        val report = store.recover()

        assertEquals(0, report.removedInvalidRevisions)
        store.activate(pack.identity)
        assertEquals(pack.identity, store.activeRevision(pack.identity.packId))
    }
}

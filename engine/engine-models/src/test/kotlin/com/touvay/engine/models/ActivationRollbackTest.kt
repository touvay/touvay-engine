package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActivationRollbackTest {
    private val root: Path = Files.createTempDirectory("touvay-activation-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `activation and rollback atomically select exact installed revisions`() {
        val store = StorageTestFixtures.store(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        store.install(v1.source)
        store.install(v2.source)

        store.activate(v1.identity)
        assertEquals(v1.identity, store.activeRevision(v1.identity.packId))
        store.activate(v2.identity)
        assertEquals(v2.identity, store.activeRevision(v1.identity.packId))
        store.rollback(v1.identity)
        assertEquals(v1.identity, store.activeRevision(v1.identity.packId))
    }

    @Test
    fun `active revision cannot be deleted and inactive revision is safely removed`() {
        val store = StorageTestFixtures.store(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        store.install(v1.source)
        store.install(v2.source)
        store.activate(v1.identity)

        val failure = assertFailsWith<ModelStoreException> { store.deleteInactive(v1.identity) }
        assertEquals(ModelStoreFailure.ACTIVE_REVISION_DELETE_FORBIDDEN, failure.failure)

        store.deleteInactive(v2.identity)
        val missing = assertFailsWith<ModelStoreException> { store.activate(v2.identity) }
        assertEquals(ModelStoreFailure.REVISION_NOT_FOUND, missing.failure)
        assertEquals(0, Files.list(root.resolve("trash")).use { it.count() })
    }

    @Test
    fun `activation rejects an uninstalled revision`() {
        val pack = StorageTestFixtures.pack()
        val failure = assertFailsWith<ModelStoreException> {
            StorageTestFixtures.store(root).activate(pack.identity)
        }
        assertEquals(ModelStoreFailure.REVISION_NOT_FOUND, failure.failure)
    }
}

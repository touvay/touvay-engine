package com.touvay.engine.models

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionalModelStoreTest {
    private val root: Path = Files.createTempDirectory("touvay-model-store-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `install commits exact immutable bytes and is idempotent`() {
        val pack = StorageTestFixtures.pack()
        val store = StorageTestFixtures.store(root)

        val first = store.install(pack.source)
        val second = store.install(pack.source)

        assertEquals(InstallDisposition.INSTALLED, first.disposition)
        assertEquals(InstallDisposition.ALREADY_INSTALLED, second.disposition)
        assertEquals(first.identity, second.identity)
        val revision = StorageTestFixtures.revisionDirectory(root)
        assertContentEquals(pack.manifestBytes, Files.readAllBytes(revision.resolve("manifest.pb")))
        assertTrue(Files.isRegularFile(revision.resolve("installed.ok")))
        assertTrue(Files.isRegularFile(revision.resolve("files/weights/model.gguf")))
        assertEquals(0, Files.list(root.resolve("staging")).use { it.count() })
    }

    @Test
    fun `same pack version with a different digest is a conflict`() {
        val store = StorageTestFixtures.store(root)
        store.install(StorageTestFixtures.pack(content = "first".toByteArray()).source)

        val failure = assertFailsWith<ModelStoreException> {
            store.install(StorageTestFixtures.pack(content = "second".toByteArray()).source)
        }

        assertEquals(ModelStoreFailure.INSTALL_CONFLICT, failure.failure)
    }

    @Test
    fun `source must exactly match declared regular single-link files`() {
        val unsafeCases = listOf(
            listOf(ModelPackSourceEntry("weights/model.gguf", ModelPackSourceEntryKind.SYMBOLIC_LINK)),
            listOf(ModelPackSourceEntry("weights/model.gguf", ModelPackSourceEntryKind.REGULAR_FILE, 2)),
            listOf(
                ModelPackSourceEntry("weights/model.gguf", ModelPackSourceEntryKind.REGULAR_FILE),
                ModelPackSourceEntry("extra", ModelPackSourceEntryKind.REGULAR_FILE),
            ),
            listOf(
                ModelPackSourceEntry("weights/model.gguf", ModelPackSourceEntryKind.REGULAR_FILE),
                ModelPackSourceEntry("weights/model.gguf", ModelPackSourceEntryKind.REGULAR_FILE),
            ),
        )

        unsafeCases.forEach { entries ->
            val caseRoot = Files.createDirectory(root.resolve("case-${Files.list(root).use { it.count() }}"))
            val failure = assertFailsWith<ModelStoreException> {
                StorageTestFixtures.store(caseRoot).install(StorageTestFixtures.pack(entries = entries).source)
            }
            assertTrue(
                failure.failure == ModelStoreFailure.SOURCE_ENTRY_UNSAFE ||
                    failure.failure == ModelStoreFailure.SOURCE_LAYOUT_INVALID,
            )
        }
    }

    @Test
    fun `short oversized and digest-mismatched files are rejected and staging is cleaned`() {
        val expected = "model-1.0.0".toByteArray()
        val variants = listOf(
            expected.copyOf(expected.size - 1),
            expected + 1,
            expected.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        variants.forEachIndexed { index, bytes ->
            val caseRoot = Files.createDirectory(root.resolve("integrity-$index"))
            val failure = assertFailsWith<ModelStoreException> {
                StorageTestFixtures.store(caseRoot).install(
                    StorageTestFixtures.pack(openedContent = bytes).source,
                )
            }
            assertEquals(ModelStoreFailure.FILE_INTEGRITY_MISMATCH, failure.failure)
            assertEquals(0, Files.list(caseRoot.resolve("staging")).use { it.count() })
        }
    }

    @Test
    fun `unsupported root version is rejected`() {
        Files.writeString(root.resolve("root.version"), "unknown")
        val failure = assertFailsWith<ModelStoreException> {
            StorageTestFixtures.store(root).install(StorageTestFixtures.pack().source)
        }
        assertEquals(ModelStoreFailure.UNSUPPORTED_ROOT, failure.failure)
    }

    @Test
    fun `store owns manifest read bounds and emits content-free failures`() {
        val pack = StorageTestFixtures.pack()
        val oversized = object : ModelPackSource by pack.source {
            override fun openManifest() = ByteArrayInputStream(ByteArray(1024 * 1024 + 1))
        }
        val failure = assertFailsWith<ModelStoreException> {
            StorageTestFixtures.store(root).install(oversized)
        }

        assertEquals(ModelStoreFailure.SOURCE_LAYOUT_INVALID, failure.failure)
        assertEquals(ModelStoreFailure.SOURCE_LAYOUT_INVALID.safeMessage, failure.message)
        assertEquals(null, failure.cause)
    }
}

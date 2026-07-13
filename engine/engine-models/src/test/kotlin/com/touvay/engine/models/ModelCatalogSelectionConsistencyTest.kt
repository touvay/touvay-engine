package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelCatalogSelectionConsistencyTest {
    private val root: Path = Files.createTempDirectory("touvay-selection-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `selection supports active exact and highest compatible without plan ranking`() {
        val harness = StorageTestFixtures.catalog(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        harness.manager.install(v1.source)
        harness.manager.install(v2.source)
        harness.manager.activate(v1.identity)

        assertEquals(
            v1.identity,
            harness.manager.select(VersionSelection.Active(v1.identity.packId)).resolved.identity,
        )
        assertEquals(
            v1.identity,
            harness.manager.select(VersionSelection.Exact(v1.identity)).resolved.identity,
        )
        assertEquals(
            v2.identity,
            harness.manager.select(VersionSelection.HighestCompatible(v1.identity.packId))
                .resolved.identity,
        )
    }

    @Test
    fun `incompatible inactive revision remains visible but cannot be leased`() {
        val pack = StorageTestFixtures.pack()
        StorageTestFixtures.store(root).install(pack.source)
        val incompatible = TestFixtures.environment(engineVersion = "3.0.0")
        val harness = StorageTestFixtures.catalog(root, environment = incompatible)
        harness.manager.rebuild()

        assertEquals(
            CatalogCompatibility.INCOMPATIBLE,
            harness.manager.snapshot().revisions.single().compatibility,
        )
        val failure = assertFailsWith<ModelCatalogException> {
            harness.manager.acquire(VersionSelection.Exact(pack.identity))
        }
        assertEquals(ModelCatalogFailure.REVISION_INCOMPATIBLE, failure.failure)
    }

    @Test
    fun `version selection remains isolated across multiple packs`() {
        val harness = StorageTestFixtures.catalog(root)
        val first = StorageTestFixtures.pack("1.0.0", packId = "touvay.pack.first")
        val second = StorageTestFixtures.pack("9.0.0", packId = "touvay.pack.second")
        harness.manager.install(first.source)
        harness.manager.install(second.source)

        assertEquals(
            first.identity,
            harness.manager.select(VersionSelection.HighestCompatible(first.identity.packId))
                .resolved.identity,
        )
        assertEquals(
            second.identity,
            harness.manager.select(VersionSelection.HighestCompatible(second.identity.packId))
                .resolved.identity,
        )
    }

    @Test
    fun `consistency verifier rejects duplicate identities and active divergence`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        val revision = harness.manager.snapshot().revisions.single()
        val verifier = CatalogConsistencyVerifier()

        val duplicate = CatalogSnapshot(1, listOf(revision, revision))
        assertFailsWith<ModelCatalogException> { verifier.verify(duplicate, emptySet()) }

        val activeRevision = CatalogRevision(
            resolved = revision.resolved,
            state = CatalogRevisionState.ACTIVE,
            compatibility = revision.compatibility,
            compatibilityFailure = revision.compatibilityFailure,
            storageReferenceCount = 0,
            fullyVerifiedAtEpochMillis = revision.fullyVerifiedAtEpochMillis,
        )
        assertFailsWith<ModelCatalogException> {
            verifier.verify(CatalogSnapshot(2, listOf(activeRevision)), emptySet())
        }
    }

    @Test
    fun `consistency verifier rejects a resolved path outside its immutable revision`() {
        val harness = StorageTestFixtures.catalog(root)
        val pack = StorageTestFixtures.pack()
        harness.manager.install(pack.source)
        val revision = harness.manager.snapshot().revisions.single()
        val resolved = revision.resolved
        val file = resolved.files.single()
        val escaped = ResolvedModelRevision(
            identity = resolved.identity,
            manifest = resolved.manifest,
            trust = resolved.trust,
            revisionDirectory = resolved.revisionDirectory,
            files = listOf(
                ResolvedModelFile(
                    logicalPath = file.logicalPath,
                    absolutePath = root.resolve("outside"),
                    byteSize = file.byteSize,
                    sha256 = file.sha256,
                ),
            ),
            installBytes = resolved.installBytes,
        )
        val inconsistent = CatalogRevision(
            resolved = escaped,
            state = revision.state,
            compatibility = revision.compatibility,
            compatibilityFailure = revision.compatibilityFailure,
            storageReferenceCount = revision.storageReferenceCount,
            fullyVerifiedAtEpochMillis = revision.fullyVerifiedAtEpochMillis,
        )

        assertFailsWith<ModelCatalogException> {
            CatalogConsistencyVerifier().verify(CatalogSnapshot(1, listOf(inconsistent)), emptySet())
        }
    }
}

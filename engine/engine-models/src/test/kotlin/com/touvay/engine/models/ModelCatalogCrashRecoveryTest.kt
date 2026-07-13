package com.touvay.engine.models

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelCatalogCrashRecoveryTest {
    private val root: Path = Files.createTempDirectory("touvay-catalog-crash-test")

    @AfterTest
    fun cleanUp() = StorageTestFixtures.deleteTree(root)

    @Test
    fun `restart recovers cache transaction interrupted before replacement`() {
        val pack = StorageTestFixtures.pack()
        val crashing = StorageTestFixtures.catalog(
            root,
            cacheFaultInjector = CatalogCacheFaultInjector {
                if (it == CatalogCacheCheckpoint.TEMP_DURABLE) throw SimulatedProcessDeath()
            },
        )
        assertFailsWith<SimulatedProcessDeath> { crashing.manager.install(pack.source) }
        assertTrue(Files.list(root).use { paths ->
            paths.anyMatch { it.fileName.toString().startsWith("catalog-cache.") }
        })

        val restarted = StorageTestFixtures.catalog(root)
        val report = restarted.manager.rebuild()

        assertEquals(1, report.revisionCount)
        assertEquals(pack.identity, restarted.manager.snapshot().revisions.single().resolved.identity)
        assertTrue(Files.list(root).use { paths ->
            paths.noneMatch { it.fileName.toString().endsWith(".tmp") }
        })
    }

    @Test
    fun `restart accepts atomically replaced cache after process death`() {
        val pack = StorageTestFixtures.pack()
        val crashing = StorageTestFixtures.catalog(
            root,
            cacheFaultInjector = CatalogCacheFaultInjector {
                if (it == CatalogCacheCheckpoint.REPLACED) throw SimulatedProcessDeath()
            },
        )
        assertFailsWith<SimulatedProcessDeath> { crashing.manager.install(pack.source) }

        val restarted = StorageTestFixtures.catalog(root)
        val report = restarted.manager.rebuild()

        assertTrue(report.metadataCacheUsed)
        assertEquals(1, report.revisionCount)
    }

    @Test
    fun `catalog rebuild repairs corrupt active pointer through storage recovery`() {
        val harness = StorageTestFixtures.catalog(root)
        val v1 = StorageTestFixtures.pack("1.0.0")
        val v2 = StorageTestFixtures.pack("2.0.0")
        harness.manager.install(v1.source)
        harness.manager.install(v2.source)
        harness.manager.activate(v1.identity)
        val active = Files.walk(root.resolve("packs")).use { paths ->
            paths.filter { it.fileName.toString() == "active.pb" }.findFirst().orElseThrow()
        }
        Files.writeString(active, "corrupt")

        val restarted = StorageTestFixtures.catalog(root)
        val report = restarted.manager.rebuild()

        assertEquals(1, report.recovery.repairedOrClearedActivePointers)
        assertEquals(
            v2.identity,
            restarted.manager.snapshot().revisions.single {
                it.state == CatalogRevisionState.ACTIVE
            }.resolved.identity,
        )
    }
}

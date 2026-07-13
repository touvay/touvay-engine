package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.touvay.engine.models.proto.FileRole
import com.touvay.engine.models.proto.ModelFile
import com.touvay.engine.models.proto.ModelPackManifest
import com.touvay.runtime.api.DeviceProfile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object StorageTestFixtures {
    fun pack(
        version: String = "1.0.0",
        packId: String = "touvay.pack.compact-writer-q4",
        content: ByteArray = "model-$version".toByteArray(),
        entries: List<ModelPackSourceEntry>? = null,
        openedContent: ByteArray = content,
    ): TestPack {
        val manifest = TestFixtures.manifest().toBuilder()
            .setPackId(packId)
            .setPackVersion(version)
            .clearCapabilities()
            .clearFiles()
            .clearLicense()
            .addFiles(
                ModelFile.newBuilder()
                    .setLogicalPath("weights/model.gguf")
                    .setByteSize(content.size.toLong())
                    .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(content)))
                    .setRole(FileRole.FILE_ROLE_WEIGHTS),
            )
            .build()
        val manifestBytes = manifest.toByteArray()
        val signature = TestFixtures.envelope(
            TestFixtures.KEY_ID,
            TestFixtures.sign(manifestBytes),
        )
        val sourceEntries = entries ?: listOf(
            ModelPackSourceEntry("weights/model.gguf", ModelPackSourceEntryKind.REGULAR_FILE),
        )
        return TestPack(
            source = MemoryPackSource(manifestBytes, signature, sourceEntries, openedContent),
            manifestBytes = manifestBytes,
            identity = ModelRevisionIdentity(manifest.packId, version, sha256Hex(manifestBytes)),
        )
    }

    fun store(
        root: Path,
        faultInjector: StoreFaultInjector = StoreFaultInjector.NONE,
        environment: CompatibilityEnvironment = TestFixtures.environment(),
        runtimeCompatibility: RuntimeRequirementCompatibility = environment,
    ): TransactionalModelStore = TransactionalModelStore(
        root = root,
        verifier = BoundedModelPackVerifier(TestFixtures.trustStore()),
        environment = environment,
        runtimeCompatibility = runtimeCompatibility,
        durability = TestStorageDurability,
        faultInjector = faultInjector,
    )

    fun catalog(
        root: Path,
        clock: CatalogClock = CatalogClock { 1_000 },
        environment: CompatibilityEnvironment = TestFixtures.environment(),
        runtimeRegistry: RuntimeRegistry? = null,
        runtimeDevice: DeviceProfile? = null,
        cacheFaultInjector: CatalogCacheFaultInjector = CatalogCacheFaultInjector.NONE,
    ): CatalogTestHarness {
        val runtimeCompatibility = when {
            runtimeRegistry == null && runtimeDevice == null -> environment
            runtimeRegistry != null && runtimeDevice != null ->
                RegistryRuntimeRequirementCompatibility(runtimeRegistry, runtimeDevice)
            else -> error("runtime registry test configuration is incomplete")
        }
        val store = store(
            root = root,
            environment = environment,
            runtimeCompatibility = runtimeCompatibility,
        )
        return CatalogTestHarness(
            store = store,
            manager = ModelCatalogManager(
                root = root,
                store = store,
                environment = environment,
                runtimeCompatibility = runtimeCompatibility,
                durability = TestStorageDurability,
                clock = clock,
                cacheFaultInjector = cacheFaultInjector,
            ),
        )
    }

    fun revisionDirectory(root: Path): Path = Files.walk(root.resolve("packs")).use { paths ->
        paths.filter { it.fileName.toString() == "installed.ok" }
            .findFirst()
            .orElseThrow()
            .parent
    }

    fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

internal class TestPack(
    val source: ModelPackSource,
    val manifestBytes: ByteArray,
    val identity: ModelRevisionIdentity,
)

internal class CatalogTestHarness(
    val store: TransactionalModelStore,
    val manager: ModelCatalogManager,
)

private class MemoryPackSource(
    private val manifest: ByteArray,
    private val signature: ByteArray,
    private val sourceEntries: List<ModelPackSourceEntry>,
    private val content: ByteArray,
) : ModelPackSource {
    override fun openManifest(): InputStream = ByteArrayInputStream(manifest)

    override fun openSignatureEnvelope(): InputStream = ByteArrayInputStream(signature)

    override fun entries(): List<ModelPackSourceEntry> = sourceEntries

    override fun open(logicalPath: String): InputStream = ByteArrayInputStream(content)
}

internal object TestStorageDurability : StorageDurability {
    override fun forceFile(path: Path) = Unit

    override fun forceDirectory(path: Path) = Unit

    override fun commitDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source, target)
        }
    }

    override fun replaceFile(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal class SimulatedProcessDeath : Error()

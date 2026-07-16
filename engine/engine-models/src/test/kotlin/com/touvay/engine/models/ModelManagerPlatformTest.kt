package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.touvay.engine.models.proto.CapabilityDescriptor
import com.touvay.engine.models.proto.FileRole
import com.touvay.engine.models.proto.ModelFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ModelManagerPlatformTest {
    @Test
    fun signedDirectory_installActivateRouteAndRuntimeResolution(): Unit = runBlocking {
        val source = createTempDirectory("platform-source")
        val store = createTempDirectory("platform-store")
        val runtime = FakeRuntime()
        val prompt = "signed prompt".toByteArray()
        val weights = "tiny validated weights".toByteArray()
        writePack(source, prompt, weights)
        val platform = platform(store, runtime)
        try {
            val identity = platform.installAndActivate(source)
            val route = platform.activeRoutes("text.rewrite", 1).single()
            assertEquals(identity, route.revision)
            assertEquals("llamacpp", route.runtimeId)
            assertEquals("prompts/text-rewrite-v1.pb", route.promptAsset.logicalPath)

            platform.runtimeInstances.acquire(
                VersionSelection.Exact(identity),
                RuntimeTestFixtures.device,
                ExecutionProfileRequest(threads = 2),
            ).use { lease ->
                assertContentEquals(
                    prompt,
                    lease.readAsset(route.promptAsset.logicalPath, route.promptAsset.byteSize),
                )
            }
            assertEquals(1, runtime.loadCalls.get())
        } finally {
            platform.shutdown()
        }
    }

    @Test
    fun tamperedSignature_neverInstallsOrRoutes(): Unit = runBlocking {
        val source = createTempDirectory("platform-tampered")
        val store = createTempDirectory("platform-tampered-store")
        writePack(source, "prompt".toByteArray(), "weights".toByteArray())
        val signature = source.resolve("manifest.sig")
        Files.write(signature, Files.readAllBytes(signature).also { it[it.lastIndex] = (it.last() + 1).toByte() })
        val platform = platform(store, FakeRuntime())
        try {
            assertFails { platform.installAndActivate(source) }
            assertEquals(emptyList(), platform.activeRoutes("text.rewrite", 1))
        } finally {
            platform.shutdown()
        }
    }

    @Test
    fun developerOperations_preserveCatalogActivationRollbackAndDeletion(): Unit = runBlocking {
        val first = createTempDirectory("platform-first")
        val second = createTempDirectory("platform-second")
        val store = createTempDirectory("platform-operations-store")
        writePack(first, "first prompt".toByteArray(), "first weights".toByteArray())
        writePack(second, "second prompt".toByteArray(), "second weights".toByteArray(), "1.0.1")
        val platform = platform(store, FakeRuntime())
        try {
            val firstIdentity = platform.install(first)
            platform.activate(firstIdentity)
            val secondIdentity = platform.install(second)
            platform.activate(secondIdentity)

            assertEquals(
                ModelManagerRevisionState.ACTIVE,
                platform.revisions().single { it.identity == secondIdentity }.state,
            )
            platform.rollback(firstIdentity)
            assertEquals(
                ModelManagerRevisionState.ACTIVE,
                platform.revisions().single { it.identity == firstIdentity }.state,
            )
            assertEquals(ModelManagerRemovalResult.REMOVED, platform.deleteInactive(secondIdentity))
            assertTrue(platform.revisions().none { it.identity == secondIdentity })
        } finally {
            platform.shutdown()
        }
    }

    private fun platform(root: Path, runtime: FakeRuntime): ModelManagerPlatform =
        ModelManagerPlatform(
            root,
            ModelManagerPlatformConfiguration(
                engineVersion = "1.0.0",
                androidApi = 34,
                deviceTier = ModelManagerDeviceTier.T1,
                totalRamBytes = RuntimeTestFixtures.device.totalRamBytes,
                isLowRamDevice = false,
                supportedAbis = setOf("x86_64"),
                trustedKeys = listOf(ModelManagerTrustedKey(TestFixtures.KEY_ID, TestFixtures.PUBLIC_KEY)),
            ),
            listOf(
                ModelManagerRuntimeRegistration(
                    bindingIdentity = "llamacpp.test",
                    runtime = runtime,
                    adapterVersion = "1.0.0",
                ),
            ),
            TestStorageDurability,
        )

    private fun writePack(
        root: Path,
        prompt: ByteArray,
        weights: ByteArray,
        packVersion: String = "1.0.0",
    ) {
        val promptPath = "prompts/text-rewrite-v1.pb"
        val manifest = TestFixtures.manifest().toBuilder()
            .setEngineMinVersion("1.0.0")
            .setPackVersion(packVersion)
            .clearFiles()
            .clearCapabilities()
            .addCapabilities(
                CapabilityDescriptor.newBuilder()
                    .setId("text.rewrite")
                    .setSchemaVersion(1)
                    .setQualityScore(100)
                    .setTemplatePath(promptPath),
            )
            .addFiles(file("weights.gguf", weights, FileRole.FILE_ROLE_WEIGHTS))
            .addFiles(file(promptPath, prompt, FileRole.FILE_ROLE_TEMPLATE))
            .clearLicense()
            .build()
        val manifestBytes = manifest.toByteArray()
        Files.createDirectories(root.resolve("files/prompts"))
        Files.write(root.resolve("manifest.pb"), manifestBytes)
        Files.write(
            root.resolve("manifest.sig"),
            TestFixtures.envelope(TestFixtures.KEY_ID, TestFixtures.sign(manifestBytes)),
        )
        Files.write(root.resolve("files/weights.gguf"), weights)
        Files.write(root.resolve("files").resolve(promptPath), prompt)
    }

    private fun file(path: String, bytes: ByteArray, role: FileRole): ModelFile =
        ModelFile.newBuilder()
            .setLogicalPath(path)
            .setByteSize(bytes.size.toLong())
            .setSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .setRole(role)
            .build()
}

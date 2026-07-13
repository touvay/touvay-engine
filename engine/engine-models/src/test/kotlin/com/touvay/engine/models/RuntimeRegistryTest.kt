package com.touvay.engine.models

import com.touvay.engine.models.proto.RuntimeRequirement
import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.RuntimeAvailability
import com.touvay.runtime.api.RuntimeId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimeRegistryTest {
    @Test
    fun `registry rejects duplicate runtime ids and binding identities`() {
        val first = FakeRuntime()
        val second = FakeRuntime()
        val duplicateRuntime = assertFailsWith<RuntimeLifecycleException> {
            DefaultRuntimeRegistry(
                listOf(
                    RuntimeBinding("binding.one", first, "1.0.0"),
                    RuntimeBinding("binding.two", second, "1.0.0"),
                ),
            )
        }
        assertEquals(RuntimeLifecycleFailure.DUPLICATE_REGISTRATION, duplicateRuntime.failure)

        val duplicateBinding = assertFailsWith<RuntimeLifecycleException> {
            DefaultRuntimeRegistry(
                listOf(
                    RuntimeBinding("binding.one", first, "1.0.0"),
                    RuntimeBinding(
                        "binding.one",
                        FakeRuntime(RuntimeId("other")),
                        "1.0.0",
                    ),
                ),
            )
        }
        assertEquals(RuntimeLifecycleFailure.DUPLICATE_REGISTRATION, duplicateBinding.failure)
    }

    @Test
    fun `compatibility enforces adapter version features and availability`() {
        val runtime = FakeRuntime()
        val registry = RuntimeTestFixtures.registry(runtime)

        assertIs<RuntimeRegistryCompatibility.Compatible>(
            registry.compatibility(requirement(features = listOf("tokenize")), RuntimeTestFixtures.device),
        )
        assertEquals(
            RuntimeLifecycleFailure.RUNTIME_INCOMPATIBLE,
            assertIs<RuntimeRegistryCompatibility.Incompatible>(
                registry.compatibility(requirement(version = "2.0.0"), RuntimeTestFixtures.device),
            ).failure,
        )
        assertEquals(
            RuntimeLifecycleFailure.RUNTIME_INCOMPATIBLE,
            assertIs<RuntimeRegistryCompatibility.Incompatible>(
                registry.compatibility(requirement(features = listOf("vision")), RuntimeTestFixtures.device),
            ).failure,
        )

        runtime.availability = RuntimeAvailability.Unavailable("sensitive backend detail")
        registry.invalidateProbeCache()
        assertEquals(
            RuntimeLifecycleFailure.RUNTIME_UNAVAILABLE,
            assertIs<RuntimeRegistryCompatibility.Incompatible>(
                registry.compatibility(requirement(), RuntimeTestFixtures.device),
            ).failure,
        )
    }

    @Test
    fun `probe result is cached per device snapshot and explicitly invalidated`() {
        val runtime = FakeRuntime()
        val registry = RuntimeTestFixtures.registry(runtime)
        val requirement = requirement()

        registry.compatibility(requirement, RuntimeTestFixtures.device)
        registry.resolve(requirement, RuntimeTestFixtures.device, ExecutionProfileRequest(threads = 4))
        assertEquals(1, runtime.probeCalls.get())

        val otherDevice = DeviceProfile(4L * 1024 * 1024 * 1024, true, listOf("x86_64"))
        registry.compatibility(requirement, otherDevice)
        assertEquals(2, runtime.probeCalls.get())

        registry.invalidateProbeCache()
        registry.compatibility(requirement, RuntimeTestFixtures.device)
        assertEquals(3, runtime.probeCalls.get())
    }

    @Test
    fun `resolution returns canonical load-affecting profile`() {
        val runtime = FakeRuntime()
        val registry = RuntimeTestFixtures.registry(runtime)
        val first = registry.resolve(
            requirement(),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 4, useMmap = true),
        )
        val same = registry.resolve(
            requirement(),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 4, useMmap = true),
        )
        val different = registry.resolve(
            requirement(),
            RuntimeTestFixtures.device,
            ExecutionProfileRequest(threads = 2, useMmap = true),
        )

        assertEquals("llamacpp.b5199", first.profile.bindingIdentity)
        assertEquals(first.profile, same.profile)
        assertNotEquals(first.profile, different.profile)
        assertEquals(4, first.profile.loadConfig().threads)
        assertEquals(true, first.profile.loadConfig().useMmap)
    }

    @Test
    fun `runtime failures remain content free`() {
        val runtime = FakeRuntime().apply {
            probeFailure = IllegalStateException("payload text and native path")
        }
        val registry = RuntimeTestFixtures.registry(runtime)

        val failure = assertFailsWith<RuntimeLifecycleException> {
            registry.resolve(requirement(), RuntimeTestFixtures.device, ExecutionProfileRequest(2))
        }

        assertEquals(RuntimeLifecycleFailure.RUNTIME_UNAVAILABLE, failure.failure)
        assertEquals("model runtime is unavailable", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `native probe linkage failure becomes typed unavailability`() {
        val runtime = FakeRuntime().apply {
            probeFailure = UnsatisfiedLinkError("private library path")
        }
        val registry = RuntimeTestFixtures.registry(runtime)

        val failure = assertFailsWith<RuntimeLifecycleException> {
            registry.resolve(requirement(), RuntimeTestFixtures.device, ExecutionProfileRequest(2))
        }

        assertEquals(RuntimeLifecycleFailure.RUNTIME_UNAVAILABLE, failure.failure)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `catalog compatibility is resolved by the runtime registry when configured`() {
        val root = Files.createTempDirectory("touvay-registry-catalog-test")
        try {
            val runtime = FakeRuntime()
            val registry = RuntimeTestFixtures.registry(runtime)
            val harness = StorageTestFixtures.catalog(
                root = root,
                environment = TestFixtures.environment(runtimes = emptyList()),
                runtimeRegistry = registry,
                runtimeDevice = RuntimeTestFixtures.device,
            )
            val pack = StorageTestFixtures.pack()

            harness.manager.install(pack.source)
            assertEquals(
                CatalogCompatibility.COMPATIBLE,
                harness.manager.select(VersionSelection.Exact(pack.identity)).compatibility,
            )

            runtime.availability = RuntimeAvailability.Unavailable("private native detail")
            registry.invalidateProbeCache()
            harness.manager.rebuild()
            assertEquals(
                CatalogCompatibility.INCOMPATIBLE,
                harness.manager.snapshot().revisions.single().compatibility,
            )
        } finally {
            StorageTestFixtures.deleteTree(root)
        }
    }

    @Test
    fun `installation rejects a runtime unavailable through the registry`() {
        val root = Files.createTempDirectory("touvay-registry-install-test")
        try {
            val runtime = FakeRuntime().apply {
                availability = RuntimeAvailability.Unavailable("private native detail")
            }
            val harness = StorageTestFixtures.catalog(
                root = root,
                environment = TestFixtures.environment(runtimes = emptyList()),
                runtimeRegistry = RuntimeTestFixtures.registry(runtime),
                runtimeDevice = RuntimeTestFixtures.device,
            )

            val failure = assertFailsWith<ModelPackVerificationException> {
                harness.manager.install(StorageTestFixtures.pack().source)
            }

            assertEquals(VerificationFailure.INCOMPATIBLE_RUNTIME, failure.failure)
            assertTrue(harness.manager.snapshot().revisions.isEmpty())
        } finally {
            StorageTestFixtures.deleteTree(root)
        }
    }

    private fun requirement(
        version: String = "1.0.0",
        features: List<String> = emptyList(),
    ): RuntimeRequirement = RuntimeRequirement.newBuilder()
        .setId("llamacpp")
        .setMinAdapterVersion(version)
        .addAllRequiredFeatures(features)
        .build()
}

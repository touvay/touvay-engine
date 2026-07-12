package com.touvay.engine.models

import com.touvay.engine.models.proto.DeviceTier
import kotlin.test.Test

internal class CompatibilityVerifierTest {
    private val parser = ManifestParser()
    private val verifier = CompatibilityVerifier()

    @Test
    fun acceptsInclusiveEngineRangeAndSemverPrecedence() {
        val manifest = parsed()
        verifier.verify(manifest, TestFixtures.environment(engineVersion = "1.0.0"))
        verifier.verify(manifest, TestFixtures.environment(engineVersion = "2.0.0"))

        val prereleaseMinimum = parsed(
            TestFixtures.manifest().toBuilder().setEngineMinVersion("1.0.0-rc.1").build(),
        )
        verifier.verify(
            prereleaseMinimum,
            TestFixtures.environment(engineVersion = "1.0.0"),
        )
    }

    @Test
    fun rejectsEngineOutsideRange() {
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_ENGINE,
            environment = TestFixtures.environment(engineVersion = "0.9.9"),
        )
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_ENGINE,
            environment = TestFixtures.environment(engineVersion = "2.0.1"),
        )
    }

    @Test
    fun rejectsMissingOldOrFeatureIncompleteRuntime() {
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_RUNTIME,
            environment = TestFixtures.environment(runtimes = emptyList()),
        )
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_RUNTIME,
            environment = TestFixtures.environment(
                runtimes = listOf(RuntimeCompatibility("llamacpp", "0.9.9")),
            ),
        )

        val requiringFeature = parsed(
            TestFixtures.manifest().toBuilder()
                .setRuntime(
                    TestFixtures.manifest().runtime.toBuilder()
                        .addRequiredFeatures("prefix-cache"),
                )
                .build(),
        )
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_RUNTIME,
            manifest = requiringFeature,
            environment = TestFixtures.environment(),
        )
        verifier.verify(
            requiringFeature,
            TestFixtures.environment(
                runtimes = listOf(
                    RuntimeCompatibility("llamacpp", "1.0.0", setOf("prefix-cache")),
                ),
            ),
        )
    }

    @Test
    fun rejectsUnknownRequiredManifestFeature() {
        val manifest = parsed(
            TestFixtures.manifest().toBuilder()
                .addRequiredManifestFeatures("manifest.future")
                .build(),
        )
        assertCompatibilityFailure(
            VerificationFailure.UNKNOWN_REQUIRED_FEATURE,
            manifest,
            TestFixtures.environment(),
        )
        verifier.verify(
            manifest,
            TestFixtures.environment(manifestFeatures = setOf("manifest.future")),
        )
    }

    @Test
    fun enforcesApiTierAndAbiConstraints() {
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_DEVICE,
            environment = TestFixtures.environment(androidApi = 28),
        )
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_DEVICE,
            environment = TestFixtures.environment(tier = DeviceTier.DEVICE_TIER_T0),
        )
        assertCompatibilityFailure(
            VerificationFailure.INCOMPATIBLE_DEVICE,
            environment = TestFixtures.environment(abis = setOf("armeabi-v7a")),
        )
        verifier.verify(
            parsed(),
            TestFixtures.environment(
                tier = DeviceTier.DEVICE_TIER_T3,
                abis = setOf("arm64-v8a", "other"),
            ),
        )
    }

    @Test
    fun rejectsDuplicateRuntimeConfiguration() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TestFixtures.environment(
                runtimes = listOf(
                    RuntimeCompatibility("llamacpp", "1.0.0"),
                    RuntimeCompatibility("llamacpp", "1.1.0"),
                ),
            )
        }
    }

    @Test
    fun snapshotsMutableCompatibilityInputs() {
        val abis = mutableSetOf("x86_64")
        val features = mutableSetOf("prefix-cache")
        val runtime = RuntimeCompatibility("llamacpp", "1.0.0", features)
        val environment = TestFixtures.environment(abis = abis, runtimes = listOf(runtime))

        abis.clear()
        features.clear()

        verifier.verify(parsed(), environment)
        kotlin.test.assertEquals(setOf("x86_64"), environment.supportedAbis)
        kotlin.test.assertEquals(setOf("prefix-cache"), runtime.features)
    }

    private fun parsed(
        manifest: com.touvay.engine.models.proto.ModelPackManifest = TestFixtures.manifest(),
    ) = parser.parse(manifest.toByteArray())

    private fun assertCompatibilityFailure(
        failure: VerificationFailure,
        manifest: com.touvay.engine.models.proto.ModelPackManifest = parsed(),
        environment: CompatibilityEnvironment,
    ) {
        assertVerificationFailure(failure) { verifier.verify(manifest, environment) }
    }
}

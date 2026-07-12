package com.touvay.engine.models

import com.touvay.engine.models.proto.DeviceTier
import com.touvay.engine.models.proto.ModelPackManifest

internal class RuntimeCompatibility(
    runtimeId: String,
    adapterVersion: String,
    features: Set<String> = emptySet(),
) {
    val runtimeId: String = runtimeId
    val adapterVersion: SemanticVersion = requireNotNull(SemanticVersion.parse(adapterVersion)) {
        "invalid runtime compatibility configuration"
    }
    val features: Set<String> = features.toSet()

    init {
        require(Identifiers.isGeneral(runtimeId, 128)) {
            "invalid runtime compatibility configuration"
        }
        require(this.features.all { Identifiers.isGeneral(it, 128) }) {
            "invalid runtime compatibility configuration"
        }
    }
}

internal class CompatibilityEnvironment(
    engineVersion: String,
    val androidApi: Int,
    val deviceTier: DeviceTier,
    supportedAbis: Set<String>,
    runtimes: Collection<RuntimeCompatibility>,
    supportedManifestFeatures: Set<String> = emptySet(),
) {
    val engineVersion: SemanticVersion = requireNotNull(SemanticVersion.parse(engineVersion)) {
        "invalid engine compatibility configuration"
    }
    val supportedAbis: Set<String> = supportedAbis.toSet()
    val supportedManifestFeatures: Set<String> = supportedManifestFeatures.toSet()
    val runtimes: Map<String, RuntimeCompatibility>

    init {
        require(androidApi > 0) { "invalid device compatibility configuration" }
        require(deviceTier != DeviceTier.DEVICE_TIER_UNSPECIFIED &&
            deviceTier != DeviceTier.UNRECOGNIZED
        ) {
            "invalid device compatibility configuration"
        }
        require(this.supportedAbis.isNotEmpty() &&
            this.supportedAbis.all { Identifiers.isGeneral(it, 128) }
        ) {
            "invalid device compatibility configuration"
        }
        require(this.supportedManifestFeatures.all { Identifiers.isGeneral(it, 128) }) {
            "invalid manifest-feature configuration"
        }
        require(runtimes.groupingBy { it.runtimeId }.eachCount().values.none { it > 1 }) {
            "duplicate runtime compatibility configuration"
        }
        this.runtimes = runtimes.associateBy { it.runtimeId }
    }
}

internal class CompatibilityVerifier {
    fun verify(manifest: ModelPackManifest, environment: CompatibilityEnvironment) {
        verifyManifestFeatures(manifest, environment)
        verifyEngine(manifest, environment)
        verifyRuntime(manifest, environment)
        verifyDevice(manifest, environment)
    }

    private fun verifyManifestFeatures(
        manifest: ModelPackManifest,
        environment: CompatibilityEnvironment,
    ) {
        if (!environment.supportedManifestFeatures.containsAll(
                manifest.requiredManifestFeaturesList,
            )
        ) {
            verificationFailure(VerificationFailure.UNKNOWN_REQUIRED_FEATURE)
        }
    }

    private fun verifyEngine(
        manifest: ModelPackManifest,
        environment: CompatibilityEnvironment,
    ) {
        val minimum = SemanticVersion.parse(manifest.engineMinVersion)
            ?: verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        val maximum = manifest.engineMaxVersion.takeIf { manifest.hasEngineMaxVersion() }
            ?.let(SemanticVersion::parse)
            ?: if (manifest.hasEngineMaxVersion()) {
                verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
            } else {
                null
            }
        if (environment.engineVersion < minimum ||
            (maximum != null && environment.engineVersion > maximum)
        ) {
            verificationFailure(VerificationFailure.INCOMPATIBLE_ENGINE)
        }
    }

    private fun verifyRuntime(
        manifest: ModelPackManifest,
        environment: CompatibilityEnvironment,
    ) {
        val available = environment.runtimes[manifest.runtime.id]
            ?: verificationFailure(VerificationFailure.INCOMPATIBLE_RUNTIME)
        val minimum = SemanticVersion.parse(manifest.runtime.minAdapterVersion)
            ?: verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        if (available.adapterVersion < minimum ||
            !available.features.containsAll(manifest.runtime.requiredFeaturesList)
        ) {
            verificationFailure(VerificationFailure.INCOMPATIBLE_RUNTIME)
        }
    }

    private fun verifyDevice(
        manifest: ModelPackManifest,
        environment: CompatibilityEnvironment,
    ) {
        val constraints = manifest.deviceConstraints
        if (tierRank(environment.deviceTier) < tierRank(constraints.minTier) ||
            constraints.supportedAbisList.none(environment.supportedAbis::contains) ||
            (constraints.hasMinAndroidApi() && environment.androidApi < constraints.minAndroidApi)
        ) {
            verificationFailure(VerificationFailure.INCOMPATIBLE_DEVICE)
        }
    }

    private fun tierRank(tier: DeviceTier): Int = when (tier) {
        DeviceTier.DEVICE_TIER_T0 -> 0
        DeviceTier.DEVICE_TIER_T1 -> 1
        DeviceTier.DEVICE_TIER_T2 -> 2
        DeviceTier.DEVICE_TIER_T3 -> 3
        DeviceTier.DEVICE_TIER_UNSPECIFIED,
        DeviceTier.UNRECOGNIZED,
        -> verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
    }
}

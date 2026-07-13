package com.touvay.engine.models

import com.touvay.engine.models.proto.RuntimeRequirement
import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.InferenceRuntime
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.RuntimeAvailability
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal interface RuntimeRegistry {
    fun compatibility(
        requirement: RuntimeRequirement,
        device: DeviceProfile,
    ): RuntimeRegistryCompatibility

    fun resolve(
        requirement: RuntimeRequirement,
        device: DeviceProfile,
        request: ExecutionProfileRequest,
    ): RuntimeResolution

    fun invalidateProbeCache()
}

internal class RuntimeBinding(
    val identity: String,
    val runtime: InferenceRuntime,
    adapterVersion: String,
    features: Set<String> = emptySet(),
) {
    val adapterVersionText: String = adapterVersion
    val adapterVersion: SemanticVersion = SemanticVersion.parse(adapterVersion)
        ?: runtimeLifecycleFailure(RuntimeLifecycleFailure.INVALID_REGISTRATION)
    val features: Set<String> = Collections.unmodifiableSet(features.toSet())

    init {
        if (!Identifiers.isGeneral(identity, 128) ||
            !Identifiers.isGeneral(runtime.id.value, 128) ||
            !this.features.all { Identifiers.isGeneral(it, 128) }
        ) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.INVALID_REGISTRATION)
        }
    }
}

/** Load-affecting request resolved and canonicalized through Runtime Registry. */
public class ExecutionProfileRequest(
    public val threads: Int,
    public val useMmap: Boolean = true,
)

/** Canonical loaded-instance profile; part of ADR-015's process-local cache identity. */
public class ExecutionProfile internal constructor(
    public val bindingIdentity: String,
    public val threads: Int,
    public val useMmap: Boolean,
) {
    internal fun loadConfig(): LoadConfig = LoadConfig(threads = threads, useMmap = useMmap)

    override fun equals(other: Any?): Boolean = other is ExecutionProfile &&
        bindingIdentity == other.bindingIdentity &&
        threads == other.threads &&
        useMmap == other.useMmap

    override fun hashCode(): Int {
        var result = bindingIdentity.hashCode()
        result = 31 * result + threads
        result = 31 * result + useMmap.hashCode()
        return result
    }

    override fun toString(): String = "ExecutionProfile(redacted)"
}

internal class RuntimeResolution(
    val binding: RuntimeBinding,
    val profile: ExecutionProfile,
)

internal sealed interface RuntimeRegistryCompatibility {
    class Compatible(val bindingIdentity: String) : RuntimeRegistryCompatibility

    class Incompatible(
        val failure: RuntimeLifecycleFailure,
    ) : RuntimeRegistryCompatibility
}

internal class RegistryRuntimeRequirementCompatibility(
    private val registry: RuntimeRegistry,
    device: DeviceProfile,
) : RuntimeRequirementCompatibility {
    private val device = DeviceProfile(
        device.totalRamBytes,
        device.isLowRamDevice,
        device.supportedAbis.toList(),
    )

    override fun isCompatible(requirement: RuntimeRequirement): Boolean =
        registry.compatibility(requirement, device) is RuntimeRegistryCompatibility.Compatible
}

internal class DefaultRuntimeRegistry(
    bindings: Collection<RuntimeBinding>,
) : RuntimeRegistry {
    private val lock = ReentrantLock()
    private val bindingsByRuntimeId: Map<String, RuntimeBinding>
    private val probeCache = mutableMapOf<ProbeKey, RuntimeAvailability>()

    init {
        if (bindings.groupingBy { it.identity }.eachCount().values.any { it > 1 } ||
            bindings.groupingBy { it.runtime.id.value }.eachCount().values.any { it > 1 }
        ) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.DUPLICATE_REGISTRATION)
        }
        bindingsByRuntimeId = Collections.unmodifiableMap(bindings.associateBy { it.runtime.id.value })
    }

    override fun compatibility(
        requirement: RuntimeRequirement,
        device: DeviceProfile,
    ): RuntimeRegistryCompatibility {
        val binding = compatibleBinding(requirement)
            ?: return RuntimeRegistryCompatibility.Incompatible(
                RuntimeLifecycleFailure.RUNTIME_INCOMPATIBLE,
            )
        return when (probe(binding, device)) {
            is RuntimeAvailability.Available -> RuntimeRegistryCompatibility.Compatible(binding.identity)
            is RuntimeAvailability.Unavailable -> RuntimeRegistryCompatibility.Incompatible(
                RuntimeLifecycleFailure.RUNTIME_UNAVAILABLE,
            )
        }
    }

    override fun resolve(
        requirement: RuntimeRequirement,
        device: DeviceProfile,
        request: ExecutionProfileRequest,
    ): RuntimeResolution {
        if (request.threads <= 0) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.INVALID_EXECUTION_PROFILE)
        }
        val binding = compatibleBinding(requirement)
            ?: runtimeLifecycleFailure(RuntimeLifecycleFailure.RUNTIME_INCOMPATIBLE)
        val availability = probe(binding, device)
        if (availability !is RuntimeAvailability.Available) {
            runtimeLifecycleFailure(RuntimeLifecycleFailure.RUNTIME_UNAVAILABLE)
        }
        return RuntimeResolution(
            binding = binding,
            profile = ExecutionProfile(
                bindingIdentity = binding.identity,
                threads = request.threads,
                useMmap = request.useMmap,
            ),
        )
    }

    override fun invalidateProbeCache() = lock.withLock {
        probeCache.clear()
    }

    private fun compatibleBinding(requirement: RuntimeRequirement): RuntimeBinding? {
        val minimum = SemanticVersion.parse(requirement.minAdapterVersion) ?: return null
        val binding = bindingsByRuntimeId[requirement.id] ?: return null
        return binding.takeIf {
            it.adapterVersion >= minimum && it.features.containsAll(requirement.requiredFeaturesList)
        }
    }

    private fun probe(binding: RuntimeBinding, device: DeviceProfile): RuntimeAvailability {
        val snapshot = DeviceSnapshot.from(device)
        val key = ProbeKey(binding.identity, snapshot)
        return lock.withLock {
            probeCache[key] ?: safeProbe(binding, snapshot.toProfile()).also {
                probeCache[key] = it
            }
        }
    }

    private fun safeProbe(binding: RuntimeBinding, device: DeviceProfile): RuntimeAvailability = try {
        when (val result = binding.runtime.probe(device)) {
            is RuntimeAvailability.Available -> {
                if (result.accelerators.any { !Identifiers.isGeneral(it, 128) }) {
                    RuntimeAvailability.Unavailable("redacted")
                } else {
                    RuntimeAvailability.Available(Collections.unmodifiableSet(result.accelerators.toSet()))
                }
            }
            is RuntimeAvailability.Unavailable -> RuntimeAvailability.Unavailable("redacted")
        }
    } catch (_: Exception) {
        RuntimeAvailability.Unavailable("redacted")
    } catch (_: LinkageError) {
        RuntimeAvailability.Unavailable("redacted")
    }

    private class ProbeKey(
        private val bindingIdentity: String,
        private val device: DeviceSnapshot,
    ) {
        override fun equals(other: Any?): Boolean = other is ProbeKey &&
            bindingIdentity == other.bindingIdentity && device == other.device

        override fun hashCode(): Int = 31 * bindingIdentity.hashCode() + device.hashCode()
    }

    private class DeviceSnapshot(
        private val totalRamBytes: Long,
        private val lowRam: Boolean,
        supportedAbis: List<String>,
    ) {
        private val supportedAbis = supportedAbis.toList()

        fun toProfile(): DeviceProfile = DeviceProfile(totalRamBytes, lowRam, supportedAbis)

        override fun equals(other: Any?): Boolean = other is DeviceSnapshot &&
            totalRamBytes == other.totalRamBytes &&
            lowRam == other.lowRam &&
            supportedAbis == other.supportedAbis

        override fun hashCode(): Int {
            var result = totalRamBytes.hashCode()
            result = 31 * result + lowRam.hashCode()
            result = 31 * result + supportedAbis.hashCode()
            return result
        }

        companion object {
            fun from(profile: DeviceProfile): DeviceSnapshot = DeviceSnapshot(
                profile.totalRamBytes,
                profile.isLowRamDevice,
                profile.supportedAbis,
            )
        }
    }
}

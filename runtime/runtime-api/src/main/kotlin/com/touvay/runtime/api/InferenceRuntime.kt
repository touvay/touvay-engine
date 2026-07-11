package com.touvay.runtime.api

/**
 * The Runtime SPI: mandatory core every inference runtime adapter implements
 * (ARCHITECTURE.md §12).
 *
 * Threading contract: all SPI calls arrive on the engine's inference executor, never the
 * main thread. [loadModel] may take seconds. Implementations do not need to be thread-safe
 * across instances of [InferenceSession]; a session has a single owner.
 *
 * Optional typed feature interfaces (prefix caching, constrained decoding, vision, LoRA,
 * delegated capabilities) are added alongside this core, additively, when the first
 * runtime that supports them lands.
 */
public interface InferenceRuntime {
    public val id: RuntimeId

    /**
     * Cheap availability check for this device: native libraries loadable, ABI supported,
     * accelerators present. Must not allocate model-scale resources.
     */
    public fun probe(device: DeviceProfile): RuntimeAvailability

    /**
     * Loads a model into memory. Slow; called only by the model manager, which owns
     * refcounting, caching, and eviction. Implementations must release all native
     * resources when the returned instance is closed.
     */
    public fun loadModel(pack: ResolvedModelPack, config: LoadConfig): ModelInstance
}

/** Stable identifier of a runtime adapter, e.g. `RuntimeId("llamacpp")`. */
@JvmInline
public value class RuntimeId(public val value: String)

/** Result of [InferenceRuntime.probe]. */
public sealed interface RuntimeAvailability {
    /** @property accelerators backend names usable on this device, e.g. "gpu-opencl". */
    public data class Available(val accelerators: Set<String> = emptySet()) : RuntimeAvailability

    /** @property reason developer-facing; surfaces in diagnostics, never to end users. */
    public data class Unavailable(val reason: String) : RuntimeAvailability
}

/**
 * Facts about the device that routing and probing decide on. Produced by engine-device;
 * kept Android-free so the SPI and engine-core stay pure JVM.
 */
public data class DeviceProfile(
    val totalRamBytes: Long,
    val isLowRamDevice: Boolean,
    val supportedAbis: List<String>,
)

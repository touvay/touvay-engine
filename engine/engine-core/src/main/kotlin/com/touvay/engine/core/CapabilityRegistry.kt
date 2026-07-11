package com.touvay.engine.core

/**
 * Immutable lookup of the capability pipelines compiled into this engine build. Built
 * once at the composition root (ARCHITECTURE.md §7); never mutated afterwards, so it is
 * safe to read from any thread.
 */
public class CapabilityRegistry private constructor(
    private val pipelines: Map<String, CapabilityPipeline>,
) {

    public fun find(capabilityId: String): CapabilityPipeline? = pipelines[capabilityId]

    public fun all(): Collection<CapabilityPipeline> = pipelines.values

    public companion object {
        /** @throws IllegalArgumentException on duplicate capability ids. */
        public fun of(vararg pipelines: CapabilityPipeline): CapabilityRegistry {
            val byId = LinkedHashMap<String, CapabilityPipeline>(pipelines.size)
            pipelines.forEach { pipeline ->
                val id = pipeline.descriptor.id
                require(byId.put(id, pipeline) == null) {
                    "Duplicate capability id registered: $id"
                }
            }
            return CapabilityRegistry(byId)
        }
    }
}

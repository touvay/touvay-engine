package com.touvay.engine.core

/** Immutable registry of execution-capable, schema-versioned program factories. */
public class ExecutionProgramRegistry private constructor(
    private val factories: Map<CapabilityKey, ExecutionProgramFactory>,
) {
    /** Resolves the exact capability id and wire schema version. */
    public fun find(capabilityId: String, schemaVersion: Int): ExecutionProgramFactory? =
        factories[CapabilityKey(capabilityId, schemaVersion)]

    /** Reports whether any registered schema version owns this capability id. */
    public fun containsId(capabilityId: String): Boolean =
        factories.keys.any { it.id == capabilityId }

    public companion object {
        public fun of(vararg factories: ExecutionProgramFactory): ExecutionProgramRegistry {
            val byKey = LinkedHashMap<CapabilityKey, ExecutionProgramFactory>(factories.size)
            factories.forEach { factory ->
                val key = CapabilityKey(factory.descriptor.id, factory.descriptor.schemaVersion)
                require(byKey.put(key, factory) == null) {
                    "Duplicate execution capability key"
                }
            }
            return ExecutionProgramRegistry(byKey)
        }
    }
}

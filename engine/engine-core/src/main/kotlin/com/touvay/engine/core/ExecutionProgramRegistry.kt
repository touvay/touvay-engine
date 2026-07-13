package com.touvay.engine.core

/** Immutable registry of execution-capable, schema-versioned program factories. */
public class ExecutionProgramRegistry private constructor(
    private val factories: Map<String, ExecutionProgramFactory>,
) {
    public fun find(capabilityId: String): ExecutionProgramFactory? = factories[capabilityId]

    public companion object {
        public fun of(vararg factories: ExecutionProgramFactory): ExecutionProgramRegistry {
            val byId = LinkedHashMap<String, ExecutionProgramFactory>(factories.size)
            factories.forEach { factory ->
                require(byId.put(factory.descriptor.id, factory) == null) {
                    "Duplicate execution capability id"
                }
            }
            return ExecutionProgramRegistry(byId)
        }
    }
}

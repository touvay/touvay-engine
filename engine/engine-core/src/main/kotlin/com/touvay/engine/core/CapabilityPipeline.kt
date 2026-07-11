package com.touvay.engine.core

/**
 * Identity of a capability implementation: namespaced id plus the payload schema version
 * it speaks (ARCHITECTURE.md §9, ADR-009). Two pipelines may share an id only with
 * different schema versions.
 */
public data class CapabilityDescriptor(
    public val id: String,
    public val schemaVersion: Int,
)

/**
 * One capability implementation. Pipelines own prompt templates, payload parsing, and
 * result assembly; engine-core treats payloads as opaque bytes, which is what keeps this
 * module schema-free and pure JVM (ARCHITECTURE.md §8 rule 3).
 *
 * Contract for implementations:
 * - Cancellation is coroutine cancellation. Suspension points (delay, runtime calls) are
 *   cancellable already; CPU-bound loops must call `coroutineContext.ensureActive()` at
 *   least once per unit of streamed work.
 * - [execute] runs on the engine's processing dispatcher, never the main thread.
 * - Payload contents are user content: never log or persist them (ARCHITECTURE.md §16).
 *
 * @see RequestProcessor
 */
public interface CapabilityPipeline {
    public val descriptor: CapabilityDescriptor

    /**
     * Executes one request.
     *
     * @param payload request message bytes for this capability's schema.
     * @param emit streams one delta's bytes to the client; called zero or more times.
     * @return the final response message bytes.
     * @throws IllegalArgumentException if [payload] is not a valid message; surfaces to
     *   the client as an internal error without payload contents.
     */
    public suspend fun execute(payload: ByteArray, emit: suspend (ByteArray) -> Unit): ByteArray
}

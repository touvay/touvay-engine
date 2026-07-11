package com.touvay.engine.service

import com.touvay.engine.core.CapabilityRegistry
import com.touvay.engine.core.RequestProcessor

/**
 * Composition root of the engine process — the single place where concrete pipelines
 * (and, in later tasks, runtimes, the model manager, and the device adapter) are wired
 * together (ARCHITECTURE.md §7). Everything below this class depends on abstractions.
 */
internal class EngineComponent {

    val registry: CapabilityRegistry = CapabilityRegistry.of(
        EchoPipeline(),
    )

    val processor: RequestProcessor = RequestProcessor(registry)

    fun shutdown() {
        processor.shutdown()
    }

    companion object {
        /** Engine build version reported in the handshake; diagnostics only. */
        const val ENGINE_VERSION_NAME: String = "0.1.0"
    }
}

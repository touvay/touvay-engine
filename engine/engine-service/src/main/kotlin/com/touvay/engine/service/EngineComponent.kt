package com.touvay.engine.service

import android.content.Context
import com.touvay.contract.CapabilityInfo
import com.touvay.contract.CapabilityStatusCodes
import com.touvay.contract.TouvayContract
import com.touvay.engine.core.CapabilityRegistry
import com.touvay.engine.core.RequestProcessor

/**
 * Composition root of the engine process — the single place where concrete pipelines
 * (and, in later tasks, runtimes, the model manager, and the device adapter) are wired
 * together (ARCHITECTURE.md §7). Everything below this class depends on abstractions.
 */
internal class EngineComponent(context: Context? = null) {

    val registry: CapabilityRegistry = CapabilityRegistry.of(
        EchoPipeline(),
    )

    val processor: RequestProcessor = RequestProcessor(registry)

    val production: ProductionExecutionController? = context
        ?.let(OfflineModelConfiguration::read)
        ?.let(::ProductionExecutionController)

    fun capabilities(): List<CapabilityInfo> = buildList {
        registry.all().forEach { pipeline ->
            add(
                CapabilityInfo(
                    id = pipeline.descriptor.id,
                    schemaVersion = pipeline.descriptor.schemaVersion,
                    statusCode = CapabilityStatusCodes.READY,
                ),
            )
        }
        add(
            CapabilityInfo(
                id = TouvayContract.CAPABILITY_TEXT_REWRITE,
                schemaVersion = RewriteExecutionRouter.SCHEMA_VERSION,
                statusCode = production?.capabilityStatus()
                    ?: CapabilityStatusCodes.DOWNLOAD_REQUIRED,
            ),
        )
    }

    fun isProductionCapability(id: String, schemaVersion: Int): Boolean =
        id == TouvayContract.CAPABILITY_TEXT_REWRITE &&
            schemaVersion == RewriteExecutionRouter.SCHEMA_VERSION

    fun isProductionCapabilityId(id: String): Boolean =
        id == TouvayContract.CAPABILITY_TEXT_REWRITE

    fun shutdown() {
        production?.shutdown()
        processor.shutdown()
    }

    companion object {
        /** Engine build version reported in the handshake; diagnostics only. */
        const val ENGINE_VERSION_NAME: String = "0.1.0"
    }
}

package com.touvay.runtime.tck

import java.nio.file.Files
import java.nio.file.Path

/** Writes the informative device performance evidence required by TCK-PF. */
internal object TckReport {

    fun write(
        directory: Path,
        runtimeId: String,
        totalRamBytes: Long,
        modelLoadMillis: Double,
        prefillMillis: Double,
        firstTokenMillis: Double,
        decodeTokensPerSecond: Double,
    ): Path {
        Files.createDirectories(directory)
        val destination = directory.resolve("runtime-$runtimeId-tck-v1.json")
        val json = """
            {
              "schema": 1,
              "tckVersion": "1.0",
              "runtimeId": "$runtimeId",
              "timestamp": ${System.currentTimeMillis()},
              "device": { "totalRamBytes": $totalRamBytes },
              "performanceInformative": {
                "modelLoadMs": ${modelLoadMillis.finite()},
                "prefillMs": ${prefillMillis.finite()},
                "firstTokenMs": ${firstTokenMillis.finite()},
                "decodeTokensPerSecond": ${decodeTokensPerSecond.finite()}
              }
            }
        """.trimIndent() + "\n"
        Files.writeString(destination, json)
        return destination
    }

    private fun Double.finite(): String = if (isFinite()) toString() else "-1.0"
}

package com.touvay.demo

import android.content.Context
import android.os.SystemClock
import com.touvay.sdk.CapabilityStatus
import com.touvay.sdk.TouvayException
import java.text.DateFormat
import java.util.Date

internal enum class ConsoleDiagnosticCode(val message: String) {
    READY("Engine connected and Rewrite is ready"),
    ENGINE_NOT_INSTALLED("Engine not installed"),
    ENGINE_NOT_RUNNING("Engine not running"),
    MODEL_MISSING("No compatible Rewrite model installed"),
    MODEL_VERIFICATION_FAILED("Model verification failed"),
    ENGINE_BUSY("Engine busy"),
    INSUFFICIENT_MEMORY("Insufficient memory"),
    ENGINE_UPDATE_REQUIRED("Engine update required"),
    REQUEST_CANCELLED("Request cancelled"),
    INVALID_INPUT("Rewrite input is not valid"),
    RUNTIME_FAILURE("Local Rewrite could not complete"),
    UNKNOWN("Local Engine is unavailable"),
}

internal object ConsoleDiagnostics {
    fun fromCapability(status: CapabilityStatus?): ConsoleDiagnosticCode = when (status) {
        CapabilityStatus.Ready -> ConsoleDiagnosticCode.READY
        is CapabilityStatus.DownloadRequired -> ConsoleDiagnosticCode.MODEL_MISSING
        is CapabilityStatus.DeviceNotSupported -> ConsoleDiagnosticCode.INSUFFICIENT_MEMORY
        CapabilityStatus.DisabledByPolicy -> ConsoleDiagnosticCode.MODEL_MISSING
        is CapabilityStatus.Unknown -> ConsoleDiagnosticCode.ENGINE_UPDATE_REQUIRED
        null -> ConsoleDiagnosticCode.MODEL_MISSING
    }

    fun fromFailure(failure: Throwable): ConsoleDiagnosticCode = when (failure) {
        is TouvayException.EngineUnavailable -> ConsoleDiagnosticCode.ENGINE_NOT_INSTALLED
        is TouvayException.EngineIncompatible -> ConsoleDiagnosticCode.ENGINE_UPDATE_REQUIRED
        is TouvayException.EngineDisconnected,
        is TouvayException.ClientClosed,
        -> ConsoleDiagnosticCode.ENGINE_NOT_RUNNING
        is TouvayException.CapabilityUnavailable -> ConsoleDiagnosticCode.MODEL_MISSING
        is TouvayException.InvalidRequest -> ConsoleDiagnosticCode.INVALID_INPUT
        is TouvayException.RequestCancelled,
        is TouvayException.RequestSuperseded,
        -> ConsoleDiagnosticCode.REQUEST_CANCELLED
        is TouvayException.EngineFailure -> when (failure.code) {
            ENGINE_ERROR_BUSY -> ConsoleDiagnosticCode.ENGINE_BUSY
            ENGINE_ERROR_MODEL_UNAVAILABLE -> ConsoleDiagnosticCode.MODEL_MISSING
            else -> ConsoleDiagnosticCode.RUNTIME_FAILURE
        }
        is OutOfMemoryError -> ConsoleDiagnosticCode.INSUFFICIENT_MEMORY
        else -> ConsoleDiagnosticCode.UNKNOWN
    }

    private const val ENGINE_ERROR_BUSY = 5
    private const val ENGINE_ERROR_MODEL_UNAVAILABLE = 11
}

internal data class ConsoleLogEntry(
    val elapsedMillis: Long,
    val wallMillis: Long,
    val event: String,
    val detail: String,
)

/** Process-local, content-free developer log. User inputs and model outputs are forbidden. */
internal class ConsoleLog(private val capacity: Int = 200) {
    private val entries = ArrayDeque<ConsoleLogEntry>()

    @Synchronized
    fun record(event: String, detail: String = "") {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            ConsoleLogEntry(
                elapsedMillis = SystemClock.elapsedRealtime(),
                wallMillis = System.currentTimeMillis(),
                event = event.take(64),
                detail = detail.take(160),
            ),
        )
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun render(): String = if (entries.isEmpty()) {
        "No developer events recorded."
    } else {
        entries.joinToString("\n") { entry ->
            "${DateFormat.getTimeInstance().format(Date(entry.wallMillis))}  " +
                entry.event + entry.detail.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()
        }
    }
}

internal data class ConsoleOptions(
    val verboseLogs: Boolean,
    val retainBenchmarkResults: Boolean,
    val experimentalFeatures: Boolean,
)

internal class ConsoleOptionsStore(context: Context) {
    private val preferences = context.getSharedPreferences("developer_console", Context.MODE_PRIVATE)

    fun read(): ConsoleOptions = ConsoleOptions(
        verboseLogs = preferences.getBoolean(KEY_VERBOSE, false),
        retainBenchmarkResults = preferences.getBoolean(KEY_RETAIN_RESULTS, true),
        experimentalFeatures = preferences.getBoolean(KEY_EXPERIMENTAL, false),
    )

    fun update(value: ConsoleOptions) {
        preferences.edit()
            .putBoolean(KEY_VERBOSE, value.verboseLogs)
            .putBoolean(KEY_RETAIN_RESULTS, value.retainBenchmarkResults)
            .putBoolean(KEY_EXPERIMENTAL, value.experimentalFeatures)
            .apply()
    }

    private companion object {
        const val KEY_VERBOSE = "verbose_logs"
        const val KEY_RETAIN_RESULTS = "retain_benchmark_results"
        const val KEY_EXPERIMENTAL = "experimental_features"
    }
}

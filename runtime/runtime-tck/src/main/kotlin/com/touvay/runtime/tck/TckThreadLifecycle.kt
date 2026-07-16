package com.touvay.runtime.tck

internal fun awaitCancellableThreadTermination(
    worker: Thread,
    operation: String,
    initialTimeoutMillis: Long = 60_000,
    cancellationTimeoutMillis: Long = 60_000,
    cancel: () -> Unit,
) {
    require(initialTimeoutMillis > 0) { "initial timeout must be positive" }
    require(cancellationTimeoutMillis > 0) { "cancellation timeout must be positive" }

    worker.join(initialTimeoutMillis)
    if (!worker.isAlive) return

    cancel()
    worker.join(cancellationTimeoutMillis)
    if (!worker.isAlive) return

    // Never let resource cleanup race a worker that still owns Runtime state. Once it
    // eventually terminates, fail the test for missing the cancellation deadline.
    worker.join()
    throw AssertionError(
        "$operation did not terminate within $cancellationTimeoutMillis ms after cancellation",
    )
}

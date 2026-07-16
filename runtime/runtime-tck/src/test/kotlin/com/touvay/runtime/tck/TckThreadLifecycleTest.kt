package com.touvay.runtime.tck

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TckThreadLifecycleTest {

    @Test
    fun timedOutWorker_isCancelledAndJoined() {
        val cancelled = CountDownLatch(1)
        val worker = thread(name = "tck-helper-cancellable") {
            cancelled.await()
        }

        awaitCancellableThreadTermination(
            worker = worker,
            operation = "test worker",
            initialTimeoutMillis = 1,
            cancellationTimeoutMillis = 1_000,
            cancel = cancelled::countDown,
        )

        assertFalse(worker.isAlive)
    }

    @Test
    fun cancellationDeadlineMiss_failsOnlyAfterWorkerTerminates() {
        val cancelled = CountDownLatch(1)
        val worker = thread(name = "tck-helper-slow-cancel") {
            cancelled.await()
            Thread.sleep(50)
        }

        assertFailsWith<AssertionError> {
            awaitCancellableThreadTermination(
                worker = worker,
                operation = "slow test worker",
                initialTimeoutMillis = 1,
                cancellationTimeoutMillis = 1,
                cancel = cancelled::countDown,
            )
        }
        assertFalse(worker.isAlive)
    }
}

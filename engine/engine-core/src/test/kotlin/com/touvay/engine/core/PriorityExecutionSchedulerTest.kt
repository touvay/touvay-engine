package com.touvay.engine.core

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityExecutionSchedulerTest {
    private val demand = ExecutionDemand(0, 0, 0)

    @Test
    fun interactivePreemptsRunningBackground_andDispatchesAfterRelease() = runTest {
        var backgroundCancellation: ExecutionCancellationReason? = null
        val scheduler = PriorityExecutionScheduler(
            SchedulerLimits(8, 8, 1),
            clockNanos = { testScheduler.currentTime * 1_000_000 },
        )
        val background = accepted(
            scheduler,
            candidate("bg", ExecutionPriority.BACKGROUND),
        ) { backgroundCancellation = it }
        val backgroundPermit = background.acquirePermit()

        val interactive = accepted(
            scheduler,
            candidate("ui", ExecutionPriority.INTERACTIVE),
        ) {}
        assertEquals(ExecutionCancellationReason.PREEMPTED, backgroundCancellation)
        val waiting = async { interactive.acquirePermit() }
        runCurrent()
        assertFalse(waiting.isCompleted)

        backgroundPermit.close()
        runCurrent()
        assertTrue(waiting.isCompleted)
        waiting.await().close()
        background.close()
        interactive.close()
    }

    @Test
    fun rejectedCoalescedSuccessor_doesNotCancelPredecessor() {
        var cancelled = false
        val scheduler = PriorityExecutionScheduler(SchedulerLimits(1, 1, 1))
        accepted(scheduler, candidate("old", coalesce = "same")) { cancelled = true }

        val rejected = scheduler.admit(candidate("new", coalesce = "different"), demand) {}

        assertIs<SchedulerAdmissionResult.Rejected>(rejected)
        assertFalse(cancelled)
    }

    @Test
    fun admittedCoalescedSuccessor_cancelsPredecessorAtomically() {
        var reason: ExecutionCancellationReason? = null
        val scheduler = PriorityExecutionScheduler(SchedulerLimits(1, 1, 1))
        accepted(scheduler, candidate("old", coalesce = "same")) { reason = it }

        val successor = scheduler.admit(candidate("new", coalesce = "same"), demand) {}

        assertIs<SchedulerAdmissionResult.Accepted>(successor)
        assertEquals(ExecutionCancellationReason.SUPERSEDED, reason)
        assertEquals(1, scheduler.snapshot().liveRequests)
    }

    @Test
    fun admissionReservesAndReleasesResourceDemand() {
        val scheduler = PriorityExecutionScheduler(
            SchedulerLimits(
                maxLiveRequests = 2,
                maxLiveRequestsPerPrincipal = 2,
                maxConcurrentExecutions = 1,
                maxFixedRamBytes = 10,
                maxKvBytes = 10,
                maxLoadCost = 1,
            ),
        )
        val first = assertIs<SchedulerAdmissionResult.Accepted>(
            scheduler.admit(candidate("first"), ExecutionDemand(8, 8, 1)) {},
        ).admission

        assertIs<SchedulerAdmissionResult.Rejected>(
            scheduler.admit(candidate("second"), ExecutionDemand(3, 3, 1)) {},
        )

        first.close()
        assertIs<SchedulerAdmissionResult.Accepted>(
            scheduler.admit(candidate("second"), ExecutionDemand(3, 3, 1)) {},
        ).admission.close()
    }

    @Test
    fun olderPreparationCannotSupersedeNewerAdmittedRequest() {
        var newerCancelled = false
        val scheduler = PriorityExecutionScheduler(SchedulerLimits(2, 2, 1))
        accepted(scheduler, candidate("newer", coalesce = "same", ingressSequence = 2)) {
            newerCancelled = true
        }

        val older = scheduler.admit(
            candidate("older", coalesce = "same", ingressSequence = 1),
            demand,
        ) {}

        val rejection = assertIs<SchedulerAdmissionResult.Rejected>(older)
        assertEquals(ExecutionFailureCode.SUPERSEDED, rejection.failure)
        assertFalse(newerCancelled)
        assertEquals(1, scheduler.snapshot().liveRequests)
    }

    private fun accepted(
        scheduler: PriorityExecutionScheduler,
        candidate: ExecutionContextCandidate,
        cancellation: (ExecutionCancellationReason) -> Unit,
    ): SchedulerAdmission = assertIs<SchedulerAdmissionResult.Accepted>(
        scheduler.admit(candidate, demand, cancellation),
    ).admission

    private fun candidate(
        requestId: String,
        priority: ExecutionPriority = ExecutionPriority.INTERACTIVE,
        coalesce: String? = null,
        ingressSequence: Long = 0,
    ) = ExecutionContextCandidate(
        requestKey = RequestKey("principal", requestId),
        capabilityId = "test.execute",
        schemaVersion = 1,
        priority = priority,
        coalesceIdentity = coalesce,
        receivedAtNanos = 0,
        deadlineAtNanos = 10_000_000,
        contractVersion = 2,
        negotiatedFeatures = emptySet(),
        payloadBytes = 0,
        bulkInputBytes = 0,
        policyGeneration = 0,
        correlationId = requestId,
        streamLimits = StreamLimits.DIAGNOSTIC_DEFAULT,
        ingressSequence = ingressSequence,
    )
}

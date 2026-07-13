package com.touvay.engine.core

import com.touvay.runtime.api.AtomicCancelSignal
import com.touvay.runtime.api.InferenceSession
import com.touvay.runtime.api.TokenSink
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Production request orchestrator from ADR-017–019. It owns lifecycle mechanics while
 * the Scheduler owns policy and Model Manager owns loaded instances.
 */
public class ExecutionCoordinator(
    private val programs: ExecutionProgramRegistry,
    private val router: ExecutionRouter,
    private val scheduler: PriorityExecutionScheduler,
    private val models: ExecutionModelProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockNanos: () -> Long = { System.nanoTime() and Long.MAX_VALUE },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val active = ConcurrentHashMap<RequestKey, ActiveExecution>()
    private val ingressLock = ReentrantLock()
    private val activeByPrincipal = mutableMapOf<String, Int>()
    private val nextIngressSequence = AtomicLong()
    private var activePayloadBytes = 0L
    private var closed = false

    /** Enqueues a request without running preparation or Runtime work on the caller thread. */
    public fun submit(request: ExecutionRequest, observer: ExecutionObserver): Boolean {
        val key = RequestKey(request.principal, request.requestId)
        val factory = programs.find(request.capabilityId)
        if (factory == null) {
            safeFail(observer, key, ExecutionFailureCode.UNKNOWN_CAPABILITY)
            return false
        }
        if (factory.descriptor.schemaVersion != request.schemaVersion) {
            safeFail(observer, key, ExecutionFailureCode.SCHEMA_VERSION_MISMATCH)
            return false
        }

        val payloadSnapshot = request.payloadSnapshot.copyOf()
        val received = clockNanos()
        val timeoutNanos = request.timeoutMillis * NANOS_PER_MILLI
        val deadline = if (Long.MAX_VALUE - received < timeoutNanos) {
            Long.MAX_VALUE
        } else {
            received + timeoutNanos
        }
        val candidate = ExecutionContextCandidate(
            requestKey = key,
            capabilityId = request.capabilityId,
            schemaVersion = request.schemaVersion,
            priority = request.priority,
            coalesceIdentity = request.coalesceKey?.let(::opaqueCoalesceIdentity),
            receivedAtNanos = received,
            deadlineAtNanos = deadline,
            contractVersion = request.contractVersion,
            negotiatedFeatures = request.negotiatedFeatures,
            payloadBytes = payloadSnapshot.size,
            bulkInputBytes = 0,
            policyGeneration = request.policyGeneration,
            correlationId = UUID.randomUUID().toString(),
            streamLimits = request.streamLimits,
            ingressSequence = nextIngressSequence.getAndIncrement().also { check(it >= 0) },
        )
        lateinit var execution: ActiveExecution
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            execute(execution, factory, candidate, payloadSnapshot)
        }
        execution = ActiveExecution(
            key,
            candidate.coalesceIdentity,
            observer,
            StreamCreditWindow(request.streamLimits),
            job,
        )

        when (reserveIngress(execution, payloadSnapshot.size)) {
            IngressReservation.ACCEPTED -> Unit
            IngressReservation.DUPLICATE -> {
                safeFail(observer, key, ExecutionFailureCode.DUPLICATE_REQUEST)
                job.cancel()
                return false
            }
            IngressReservation.BUSY -> {
                safeFail(observer, key, ExecutionFailureCode.BUSY)
                job.cancel()
                return false
            }
            IngressReservation.CLOSED -> {
                safeFail(observer, key, ExecutionFailureCode.CANCELLED)
                job.cancel()
                return false
            }
        }
        job.invokeOnCompletion {
            execution.terminal.claimFailure(execution.cancellation.get().toFailureCode())
            execution.terminal.deliver()
            execution.credits.close()
            releaseIngress(execution, payloadSnapshot.size)
        }
        job.start()
        return true
    }

    /** Cancels only within the authenticated principal's request namespace. */
    public fun cancel(
        principal: String,
        requestId: String,
        reason: ExecutionCancellationReason = ExecutionCancellationReason.CLIENT_CANCELLED,
    ): Boolean {
        val execution = active[RequestKey(principal, requestId)] ?: return false
        return execution.cancel(reason)
    }

    /** Applies one sequenced stream grant to an authenticated live request. */
    public fun grantCredits(
        principal: String,
        requestId: String,
        sequence: Long,
        deltaCount: Int,
        payloadBytes: Long,
    ): CreditGrantResult {
        val execution = active[RequestKey(principal, requestId)] ?: return CreditGrantResult.CLOSED
        return execution.credits.grant(sequence, deltaCount, payloadBytes)
    }

    /** Cancels all work and stops the coordinator. Idempotent. */
    public fun shutdown() {
        closeIngress().forEach { it.cancel(ExecutionCancellationReason.ENGINE_SHUTDOWN) }
        scope.cancel()
    }

    /** Cancels work and awaits reverse-order request cleanup before returning. */
    public suspend fun shutdownAndAwait() {
        val jobs = closeIngress().map { execution ->
            execution.cancel(ExecutionCancellationReason.ENGINE_SHUTDOWN)
            execution.job
        }
        jobs.joinAll()
        scope.cancel()
    }

    private fun reserveIngress(
        execution: ActiveExecution,
        payloadBytes: Int,
    ): IngressReservation = ingressLock.withLock {
        if (closed) return IngressReservation.CLOSED
        if (active.containsKey(execution.key)) return IngressReservation.DUPLICATE
        val principal = execution.key.principal
        val principalCount = activeByPrincipal[principal] ?: 0
        val sameIdentityCount = execution.coalesceIdentity?.let { identity ->
            active.values.count {
                it.key.principal == principal && it.coalesceIdentity == identity
            }
        } ?: 0
        if (sameIdentityCount >= MAX_LIVE_PER_COALESCE_IDENTITY) return IngressReservation.BUSY
        val replacementSlot = sameIdentityCount == 1
        val ordinaryCapacityExceeded = active.size >= scheduler.maxLiveRequests ||
            principalCount >= scheduler.maxLiveRequestsPerPrincipal
        val hardCapacityExceeded =
            active.size.toLong() >= scheduler.maxLiveRequests.toLong() * 2 ||
                principalCount.toLong() >= scheduler.maxLiveRequestsPerPrincipal.toLong() * 2
        if (hardCapacityExceeded || ordinaryCapacityExceeded && !replacementSlot) {
            return IngressReservation.BUSY
        }
        active[execution.key] = execution
        activeByPrincipal[principal] = principalCount + 1
        activePayloadBytes += payloadBytes.toLong()
        check(activePayloadBytes <= maxIngressPayloadBytes())
        IngressReservation.ACCEPTED
    }

    private fun releaseIngress(execution: ActiveExecution, payloadBytes: Int) {
        ingressLock.withLock {
            if (!active.remove(execution.key, execution)) return
            val principal = execution.key.principal
            val remaining = checkNotNull(activeByPrincipal[principal]) - 1
            if (remaining == 0) activeByPrincipal.remove(principal)
            else activeByPrincipal[principal] = remaining
            activePayloadBytes -= payloadBytes.toLong()
            check(activePayloadBytes >= 0)
        }
    }

    private fun closeIngress(): List<ActiveExecution> = ingressLock.withLock {
        closed = true
        active.values.toList()
    }

    private fun maxIngressPayloadBytes(): Long =
        scheduler.maxLiveRequests.toLong() * MAX_LIVE_PER_COALESCE_IDENTITY *
            ExecutionRequest.MAX_INLINE_PAYLOAD_BYTES

    private suspend fun execute(
        execution: ActiveExecution,
        factory: ExecutionProgramFactory,
        candidate: ExecutionContextCandidate,
        payload: ByteArray,
    ) {
        var prepared: PreparedExecution? = null
        var admission: SchedulerAdmission? = null
        try {
            prepared = factory.prepare(candidate, payload.copyOf())
            val admitted = scheduler.admit(candidate, prepared.demand) { reason ->
                execution.cancel(reason)
            }
            if (admitted is SchedulerAdmissionResult.Rejected) {
                execution.terminal.claimFailure(admitted.failure)
                return
            }
            admission = (admitted as SchedulerAdmissionResult.Accepted).admission
            val admittedAt = clockNanos()
            if (admittedAt >= candidate.deadlineAtNanos) {
                execution.cancel(ExecutionCancellationReason.DEADLINE_EXCEEDED)
                throw CancellationException("deadline")
            }
            val context = candidate.admitted(admittedAt)
            execution.context.set(context)
            if (!safeAccepted(execution, context)) return

            val remainingNanos = context.deadlineAtNanos - clockNanos()
            if (remainingNanos <= 0) throw DeadlineCancellationException()
            try {
                withTimeout(nanosToTimeoutMillis(remainingNanos)) {
                    val success = executeAdmitted(execution, context, prepared, admission)
                    execution.terminal.claimSuccess(context, success.payload, success.stats)
                }
            } catch (_: TimeoutCancellationException) {
                execution.cancel(ExecutionCancellationReason.DEADLINE_EXCEEDED)
                throw DeadlineCancellationException()
            }
        } catch (deadline: DeadlineCancellationException) {
            execution.terminal.claimFailure(ExecutionFailureCode.DEADLINE_EXCEEDED)
        } catch (cancelled: CancellationException) {
            execution.terminal.claimFailure(execution.cancellation.get().toFailureCode())
        } catch (failure: ExecutionException) {
            execution.terminal.claimFailure(failure.failureCode)
        } catch (_: Exception) {
            execution.terminal.claimFailure(ExecutionFailureCode.INTERNAL)
        } catch (_: LinkageError) {
            execution.terminal.claimFailure(ExecutionFailureCode.INTERNAL)
        } finally {
            withContext(NonCancellable) {
                runCatching { admission?.close() }
                runCatching { prepared?.close() }
            }
            execution.terminal.deliver()
        }
    }

    private suspend fun executeAdmitted(
        execution: ActiveExecution,
        context: ExecutionContext,
        prepared: PreparedExecution,
        admission: SchedulerAdmission,
    ): ExecutionSuccess {
        var lastFailure = ExecutionFailureCode.INTERNAL
        val progress = PlanProgress(startedAtNanos = context.admittedAtNanos)
        val plan = router.route(context, prepared)
        plan.candidates.forEachIndexed { index, candidate ->
            progress.attemptCount += 1
            val failure = try {
                val result = runAttempt(
                    execution = execution,
                    context = context,
                    prepared = prepared,
                    admission = admission,
                    candidate = candidate,
                    progress = progress,
                )
                val finishedAt = clockNanos()
                val stats = ExecutionStats(
                    ttftMillis = nanosToMillis(
                        (progress.firstDeltaAtNanos ?: finishedAt) - progress.startedAtNanos,
                    ),
                    totalMillis = nanosToMillis(finishedAt - progress.startedAtNanos),
                    deltaCount = progress.sequence,
                )
                return ExecutionSuccess(result, stats)
            } catch (failure: ExecutionException) {
                failure.failureCode
            }
            lastFailure = failure
            val hasFallback = index + 1 < plan.candidates.size
            val retryAllowed = hasFallback &&
                progress.sequence == 0 &&
                execution.cancellation.get() == null &&
                failure in candidate.retryableFailures
            if (!retryAllowed) throw ExecutionException(failure)
        }
        throw ExecutionException(lastFailure)
    }

    private suspend fun runAttempt(
        execution: ActiveExecution,
        context: ExecutionContext,
        prepared: PreparedExecution,
        admission: SchedulerAdmission,
        candidate: ExecutionCandidate,
        progress: PlanProgress,
    ): ByteArray {
        var permit: DispatchPermit? = null
        var lease: ExecutionModelLease? = null
        var session: InferenceSession? = null
        var program: AttemptProgram? = null
        var signal: AtomicCancelSignal? = null
        try {
            permit = admission.acquirePermit()
            ensureActive(execution)
            program = prepared.newAttempt(candidate)
            lease = models.acquire(context, candidate)
            ensureActive(execution)

            val instance = lease.instance
            val prompt = program.prompt()
            val tokens = runtimeCall(lease) { instance.tokenize(prompt) }
            val contextCap = min(candidate.contextLength, instance.info.maxContextLength)
            if (tokens.ids.size > contextCap) {
                throw ExecutionException(ExecutionFailureCode.INVALID_REQUEST)
            }
            signal = AtomicCancelSignal()
            execution.signal.set(signal)
            session = runtimeCall(lease) { instance.createSession(program.sessionConfig(instance.info)) }
            runtimeCall(lease) { session.prefill(tokens, signal) }
            ensureRuntimeUsable(execution, signal)

            var generated = 0
            while (generated < candidate.maxOutputTokens) {
                ensureActive(execution)
                if (permit == null) permit = admission.acquirePermit()
                val requested = min(candidate.decodeQuantumTokens, candidate.maxOutputTokens - generated)
                val accumulator = TokenAccumulator(requested, MAX_TOKEN_ACCUMULATOR_BYTES, signal)
                val params = program.decodeParams(requested)
                if (params.maxTokens !in 1..requested) {
                    throw ExecutionException(ExecutionFailureCode.INTERNAL)
                }
                try {
                    runtimeCall(lease) {
                        session.decode(params, signal, TokenSink(accumulator::add))
                    }
                } catch (_: TokenAccumulatorOverflow) {
                    throw ExecutionException(ExecutionFailureCode.BACKPRESSURE_EXCEEDED)
                } catch (failure: ExecutionException) {
                    throw failure
                } catch (_: Exception) {
                    throw ExecutionException(ExecutionFailureCode.RUNTIME_FAILURE)
                } catch (_: LinkageError) {
                    throw ExecutionException(ExecutionFailureCode.RUNTIME_FAILURE)
                }
                ensureRuntimeUsable(execution, signal)
                val emitted = accumulator.tokens.size
                generated += emitted
                val deltas = program.consume(accumulator.tokens)
                for (delta in deltas) {
                    if (delta.size > context.streamLimits.maxByteCredits) {
                        throw ExecutionException(ExecutionFailureCode.BACKPRESSURE_EXCEEDED)
                    }
                    if (!execution.credits.tryConsume(delta.size)) {
                        permit?.close()
                        permit = null
                        execution.credits.awaitAndConsume(delta.size)
                    }
                    ensureActive(execution)
                    if (progress.firstDeltaAtNanos == null) {
                        progress.firstDeltaAtNanos = clockNanos()
                    }
                    if (!safeDelta(execution, context, progress.sequence, delta)) {
                        throw CancellationException("client gone")
                    }
                    progress.sequence += 1
                }
                if (emitted < params.maxTokens) break
            }
            val result = program.finish()
            if (result.size > ExecutionRequest.MAX_INLINE_PAYLOAD_BYTES) {
                throw ExecutionException(ExecutionFailureCode.INVALID_OUTPUT)
            }
            return result
        } catch (failure: ExecutionException) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw ExecutionException(ExecutionFailureCode.RUNTIME_FAILURE)
        } catch (_: LinkageError) {
            throw ExecutionException(ExecutionFailureCode.RUNTIME_FAILURE)
        } finally {
            withContext(NonCancellable) {
                execution.signal.compareAndSet(signal, null)
                if (session != null && lease != null) {
                    runCatching { runtimeCall(lease) { session.close() } }
                }
                runCatching { lease?.close() }
                runCatching { program?.close() }
                runCatching { permit?.close() }
            }
        }
    }

    private suspend fun <T> runtimeCall(lease: ExecutionModelLease, block: () -> T): T =
        withContext(lease.inferenceDispatcher) { block() }

    private fun ensureActive(execution: ActiveExecution) {
        if (execution.cancellation.get() != null) throw CancellationException("cancelled")
    }

    private fun ensureRuntimeUsable(execution: ActiveExecution, signal: AtomicCancelSignal) {
        if (signal.isCancelled || execution.cancellation.get() != null) {
            throw CancellationException("runtime cancellation observed")
        }
    }

    private fun safeAccepted(execution: ActiveExecution, context: ExecutionContext): Boolean = try {
        execution.observer.onAccepted(context)
        true
    } catch (_: Exception) {
        execution.cancel(ExecutionCancellationReason.CLIENT_GONE)
        false
    }

    private fun safeDelta(
        execution: ActiveExecution,
        context: ExecutionContext,
        sequence: Int,
        payload: ByteArray,
    ): Boolean = try {
        execution.observer.onDelta(context, sequence, payload)
        true
    } catch (_: Exception) {
        execution.cancel(ExecutionCancellationReason.CLIENT_GONE)
        false
    }

    private fun safeFail(
        observer: ExecutionObserver,
        key: RequestKey,
        code: ExecutionFailureCode,
    ) {
        runCatching { observer.onFailed(key, ExecutionFailure(code)) }
    }

    private inner class ActiveExecution(
        val key: RequestKey,
        val coalesceIdentity: String?,
        val observer: ExecutionObserver,
        val credits: StreamCreditWindow,
        val job: Job,
    ) {
        val cancellation = AtomicReference<ExecutionCancellationReason?>(null)
        val signal = AtomicReference<AtomicCancelSignal?>(null)
        val context = AtomicReference<ExecutionContext?>(null)
        val terminal = TerminalArbiter(this)

        fun cancel(reason: ExecutionCancellationReason): Boolean {
            if (!terminal.claimFailure(reason.toFailureCode())) return false
            check(cancellation.compareAndSet(null, reason))
            signal.get()?.cancel()
            credits.close()
            job.cancel(CancellationException(reason.name))
            return true
        }
    }

    private inner class TerminalArbiter(private val execution: ActiveExecution) {
        private val outcome = AtomicReference<TerminalOutcome?>(null)
        private val delivered = AtomicBoolean(false)

        fun claimSuccess(
            context: ExecutionContext,
            payload: ByteArray,
            stats: ExecutionStats,
        ): Boolean = outcome.compareAndSet(null, TerminalOutcome.Success(context, payload, stats))

        fun claimFailure(code: ExecutionFailureCode): Boolean =
            outcome.compareAndSet(null, TerminalOutcome.Failure(code))

        fun deliver() {
            val terminal = outcome.get() ?: return
            if (!delivered.compareAndSet(false, true)) return
            when (terminal) {
                is TerminalOutcome.Success -> runCatching {
                    execution.observer.onCompleted(
                        terminal.context,
                        terminal.payload,
                        terminal.stats,
                    )
                }
                is TerminalOutcome.Failure -> runCatching {
                    execution.observer.onFailed(
                        execution.key,
                        ExecutionFailure(terminal.code),
                    )
                }
            }
        }
    }

    private sealed interface TerminalOutcome {
        class Success(
            val context: ExecutionContext,
            val payload: ByteArray,
            val stats: ExecutionStats,
        ) : TerminalOutcome

        class Failure(val code: ExecutionFailureCode) : TerminalOutcome
    }

    private class ExecutionSuccess(
        val payload: ByteArray,
        val stats: ExecutionStats,
    )

    private class PlanProgress(
        val startedAtNanos: Long,
        var firstDeltaAtNanos: Long? = null,
        var sequence: Int = 0,
        var attemptCount: Int = 0,
    )

    private class TokenAccumulatorOverflow : RuntimeException()

    private class TokenAccumulator(
        private val maxTokens: Int,
        private val maxBytes: Int,
        private val signal: AtomicCancelSignal,
    ) {
        val tokens = mutableListOf<GeneratedToken>()
        private var bytes = 0

        fun add(tokenId: Int, piece: String) {
            val pieceBytes = piece.toByteArray(StandardCharsets.UTF_8).size
            if (tokens.size >= maxTokens || pieceBytes > maxBytes - bytes) {
                signal.cancel()
                throw TokenAccumulatorOverflow()
            }
            bytes += pieceBytes
            tokens += GeneratedToken(tokenId, piece)
        }
    }

    private class DeadlineCancellationException : CancellationException("deadline exceeded")

    private enum class IngressReservation {
        ACCEPTED,
        DUPLICATE,
        BUSY,
        CLOSED,
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_TOKEN_ACCUMULATOR_BYTES = 1024 * 1024
        const val MAX_LIVE_PER_COALESCE_IDENTITY = 2

        fun nanosToMillis(nanos: Long): Long = (nanos.coerceAtLeast(0) / NANOS_PER_MILLI)

        fun nanosToTimeoutMillis(nanos: Long): Long =
            ((nanos.coerceAtLeast(1) + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)

        fun opaqueCoalesceIdentity(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun ExecutionCancellationReason?.toFailureCode(): ExecutionFailureCode = when (this) {
            ExecutionCancellationReason.SUPERSEDED -> ExecutionFailureCode.SUPERSEDED
            ExecutionCancellationReason.PREEMPTED,
            ExecutionCancellationReason.RESOURCE_PRESSURE,
            -> ExecutionFailureCode.PREEMPTED
            ExecutionCancellationReason.DEADLINE_EXCEEDED -> ExecutionFailureCode.DEADLINE_EXCEEDED
            ExecutionCancellationReason.BACKPRESSURE_EXCEEDED ->
                ExecutionFailureCode.BACKPRESSURE_EXCEEDED
            ExecutionCancellationReason.CLIENT_CANCELLED,
            ExecutionCancellationReason.CLIENT_GONE,
            ExecutionCancellationReason.ENGINE_SHUTDOWN,
            null,
            -> ExecutionFailureCode.CANCELLED
        }
    }
}

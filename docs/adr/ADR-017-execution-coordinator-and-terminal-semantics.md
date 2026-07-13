# ADR-017 — Execution coordinator and terminal semantics

**Status:** Accepted for Execution Architecture v1.0 (2026-07-13)

## Context

The walking-skeleton `RequestProcessor` proved Binder-to-pipeline streaming,
cancellation, and coalescing, but it intentionally combines public request state,
capability execution, callback publication, and coroutine ownership. Production model
execution adds Scheduler admission, model leases, Runtime sessions, bounded streaming,
retry, deadlines, and multiple cancellation sources. Extending the monolith would make
terminal races and resource cleanup difficult to prove.

Execution also needs stable per-request metadata without passing divergent scalar
parameters through every boundary, and retry must be able to choose an approved fallback
without rerunning routing against a changing catalog or policy snapshot.

## Decision

### Execution ownership

The engine introduces an `ExecutionCoordinator`. One submitted operation owns a mutable
`RequestRecord`, one immutable `ExecutionContext`, one immutable `ExecutionPlan`, and
one or two sequential `ExecutionAttempt` objects.

`ExecutionContext` contains content-free request metadata: authenticated request key,
capability/schema identity, clamped priority, opaque coalescing identity, monotonic
timing/deadline, negotiated transport features, size/resource facts, policy generation,
correlation identity, and immutable request limits. It contains no payload, prompt,
callback, mutable state, counter, cancellation signal, plan, model identity, lease,
Runtime object, or session.

Routing runs at first dispatch and returns an immutable `ExecutionPlan` with one primary
candidate and at most one ordered, retry-safe fallback in v1. An `ExecutionAttempt`
selects one candidate and owns all mutable attempt resources. Retry closes the failed
attempt and selects the precomputed fallback; it never reruns routing. A stale fallback
fails rather than silently selecting a third plan.

### Acceptance and terminal semantics

`onAccepted` means validation completed and Scheduler admission was committed.
Pre-admission validation or admission rejection emits only a terminal failure. Accepted
requests emit `onAccepted`, zero or more ordered deltas, and exactly one terminal
callback.

One atomic terminal arbiter is created before admission. Success, validation failure,
runtime failure, cancellation, client death, deadline, shutdown, and preemption compete
through it. The winner owns stream closure and terminal publication. Losing paths may
only perform idempotent cleanup.

Request identity is `(authenticated principal, requestId)`. Cancellation and coalescing
never operate on a raw request id across principals. The first client-visible delta is
the stream commit point; internal retry is forbidden afterward.

Resources are acquired as leases and released in reverse order. A Runtime session closes
before its model lease. Cleanup is idempotent and cancellation-safe.

## Consequences

- Lifecycle, callback ordering, retry, and cleanup become executable state-machine
  invariants rather than incidental coroutine behavior.
- Immutable context prevents metadata drift and accidental content retention.
- Frozen plans make fallback deterministic across catalog or policy changes.
- Admitted-only acceptance requires aligning existing `RequestListener` documentation
  and tests.
- The current `RequestProcessor` remains a diagnostic walking skeleton until replaced by
  reviewed orchestration wiring.

## Alternatives considered

### Extend the monolithic RequestProcessor

Rejected. It conflates request, attempt, transport, and resource ownership and already
contains a publication/cancellation race.

### Emit onAccepted before validation

Rejected. It gives acceptance no admission meaning and forces invalid requests through
an accepted lifecycle.

### Reroute after each failure

Rejected. Retry would observe a different catalog/policy world, complicate resource
authorization, and make attempt behavior non-deterministic.

### Put mutable lifecycle state in ExecutionContext

Rejected. A shared mutable context would obscure ownership and allow layers to mutate
unrelated execution policy.

# ADR-018 — Credit-based Binder streaming and bounded decode quanta

**Status:** Accepted for Execution Architecture v1.0 (2026-07-13)

## Context

Binder callbacks are `oneway`, so blocking a Runtime token sink on client consumption is
unsafe. The walking skeleton avoids that by using an unbounded SDK channel, which is
acceptable for its bounded diagnostic echo but permits a slow or abandoned client to
grow memory without limit during model generation. Dropping deltas would violate stream
correctness, and a single unbounded Runtime decode call provides no scheduling safe point
for credit waits or preemption.

## Decision

Production streaming uses additive, negotiated count-and-byte credits from the SDK to
the engine. A new submit operation supplies an initial bounded window and a `oneway`
credit-grant operation replenishes it. Existing contract-v1 methods remain unchanged.
Production model streaming is advertised only when credit flow control is negotiated;
the legacy path remains limited to bounded unary or diagnostic streams.

The engine consumes one delta credit and the exact payload-byte credit before issuing a
delta. Terminal and cancellation callbacks do not consume credit. Credit accounting is
saturation-safe, rejects stale/duplicate/malicious grants, and never exceeds negotiated
ceilings. Each request has one serialized outbound lane.

The SDK uses a bounded channel no larger than its granted window. It replenishes credit
only after the delta is accepted by downstream Flow collection, not merely received by a
Binder callback.

Runtime decode runs in bounded token quanta. The non-blocking `TokenSink` writes to a
fixed-capacity, byte-bounded per-quantum accumulator. Between quanta the coordinator
transforms tokens, publishes deltas, checks cancellation/deadline, and cooperates with
the Scheduler. While waiting for credits it releases compute permission but retains its
explicitly budgeted Runtime session and model lease. Overflow cancels and closes the
attempt; tokens are never dropped.

This amends the explanatory parenthetical in Runtime SPI rule SPI-TH-5 that names an
unbounded Binder-layer channel. The adapter guarantee remains unchanged:
`TokenSink.onToken` is non-blocking and non-I/O-bound. No Runtime SPI source API changes.

## Consequences

- Every client-output buffer is bounded by count and bytes.
- Slow clients exert backpressure without blocking Binder or native token callbacks.
- Decode quanta create cancellation, deadline, preemption, and credit safe points.
- AIDL/SDK evolution is additive but requires negotiation, API compatibility updates,
  and hostile credit-accounting tests.
- Holding KV/model resources while credit-blocked is explicit Scheduler budget, not
  hidden memory growth.

## Alternatives considered

### Keep unbounded queues

Rejected. A slow client can exhaust the engine process.

### Block the Runtime token sink

Rejected. It can stall native inference and violate adapter callback assumptions.

### Drop or overwrite deltas

Rejected. It silently corrupts structured and ordered streams.

### Make all responses unary

Rejected. It removes required low-latency streaming and still needs a bounded result
size.

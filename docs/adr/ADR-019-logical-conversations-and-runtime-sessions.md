# ADR-019 — Logical conversations versus Runtime sessions

**Status:** Accepted for Execution Architecture v1.0 (2026-07-13)

## Context

Architecture v1 requires design-for-process-death and client-held transcript
reconstruction, while the Runtime SPI exposes mutable `InferenceSession` KV state. A
public concept of a conversation must not accidentally promise that native session or KV
state survives process death, model upgrades, retries, or engine restart.

Long-lived engine-owned sessions would also pin model files and KV memory across client
think time, complicating eviction, fairness, cancellation, and rollback.

## Decision

A Runtime `InferenceSession` is request-attempt scoped in v1. An attempt acquires a model
lease, tokenizes, creates one session, prefills once, decodes sequentially, closes the
session, and then closes the model lease. Retry creates a fresh session and does not
reuse partial KV state.

A logical conversation is client-owned transcript/context identity. It is not a Runtime
session. The engine persists no prompt, transcript, Runtime session, KV cache, or request
queue. After process death or reconnect, the client reconstructs the request from its
transcript and the engine re-prefills.

A future ephemeral session optimization may be introduced only through an additive
contract. It must preserve transcript-based reconstruction as the correctness path,
carry explicit lease/expiry/budget semantics, and remain disposable on process death.

## Consequences

- Process death remains equivalent to cleanup; no execution recovery log is required.
- Model Manager ownership and rollback remain independent of conversational UX.
- Each request pays prefill cost unless a future typed prefix/session optimization is
  approved.
- Client facades may expose logical sessions without exposing model or Runtime identity.
- Runtime-session memory has a bounded request/attempt owner and deterministic release.

## Alternatives considered

### Long-lived engine-owned Runtime sessions

Rejected for v1. They pin KV/model resources and create durable identity, eviction, and
death-recovery obligations.

### Persist transcripts or KV snapshots in the engine

Rejected. It violates the no-durable-user-state baseline and expands the privacy and
compatibility surface.

### No logical session facade ever

Rejected as a permanent constraint. Client-owned logical conversations are useful and
can remain reconstructible without promising durable native state.

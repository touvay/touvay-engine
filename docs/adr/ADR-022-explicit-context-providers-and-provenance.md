# ADR-022 — Explicit request-scoped context providers and provenance

**Status:** Accepted for Context Architecture v1.0 (2026-07-13)

## Context

Capabilities need selection, conversation, locale, clipboard, and future local
retrieval/memory context without giving engine code ambient authority over user data.
ADR-017 also requires `ExecutionContext` to remain immutable and content-free, while
ADR-019 makes conversation reconstruction client-owned. A mutable generic context map
or attempt-time provider lookup would weaken those ownership guarantees and make retry
observe changing source state.

Security and policy enforcement also require knowing where each fragment came from.
Flattening origin into a string key or exposing provider implementation details through
public schemas would create a brittle public fingerprinting and compatibility surface.

## Decision

Context Providers are immutable compile-time registrations wired only at the
composition root. A provider has no ambient authority: it receives a narrow,
request-scoped request/snapshot and may return only authorized kinds within hard item,
byte, work, and deadline limits. Providers do not route, schedule, tokenize, acquire
models, create Runtime sessions, or access the network.

Provider collection occurs before capability preparation and Scheduler admission. The
resolver validates and freezes one immutable `ResolvedContext`; every attempt and retry
uses that same snapshot. Providers are never invoked from an `ExecutionAttempt`.
`ExecutionContext` remains content-free.

Every fragment carries immutable internal `ContextProvenance`. The initial origin
categories are `USER_SELECTION`, `CLIPBOARD`, `SYSTEM`, `CLIENT`, `CONVERSATION`,
`RETRIEVAL`, and `MEMORY`. Bounded internal facts may include provider contract version,
request-local source identity, capture time, and grant/purpose identity. Provenance does
not grant logging permission.

Provenance remains engine-internal. AIDL, SDK, and public capability schemas do not
expose provider IDs, resolver topology, grant mechanisms, or storage/retrieval details.
Capability code receives only the narrow provenance projection its approved policy
needs. Unknown origins fail closed.

## Consequences

- Context is explicit, bounded, request-scoped, and reproducible across retry.
- Ambient clipboard, editor, screen, file, contact, and network reads remain forbidden.
- Origin-aware privacy and merge policy can be tested without exposing implementation
  details publicly.
- Providers cannot be discovered by capabilities or used as a device fingerprint.
- The current map-backed `CapabilityContext` remains a transitional adapter; a general
  resolver requires a separately authorized implementation slice.
- Provider collection adds bounded pre-admission latency, accepted because it holds no
  Scheduler/model/Runtime resource and cannot block typing.

## Alternatives considered

### Ambient engine context reads

Rejected. They violate explicit authorization and make context dependent on hidden
device state.

### Mutable shared context

Rejected. Ownership, cleanup, retry determinism, and cross-principal isolation become
unprovable.

### Attempt-time provider calls

Rejected. Retry could observe different clipboard, transcript, locale, or policy state
while holding scarce Runtime resources.

### Public provider/provenance schema

Rejected. It leaks implementation topology, encourages client coupling, and creates a
fingerprinting surface without improving capability semantics.

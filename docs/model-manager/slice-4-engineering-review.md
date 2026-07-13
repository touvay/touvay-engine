# Task 3 Slice 4 — Engineering Review

**Date:** 2026-07-13
**Status:** Approved (2026-07-13); ready for merge
**Scope:** Runtime Registry and loaded-`ModelInstance` ownership only

## Outcome

Slice 4 satisfies the authorized pure-JVM scope and follows ADR-015 without modifying
the Runtime SPI. The implementation is approved. It is intentionally not wired
into `engine-service` or llama.cpp, and Slice 5 has not begun.

## Implemented

- immutable Runtime Registry bindings with stable binding identity, adapter SemVer,
  supported features, runtime-id/binding uniqueness checks, and cached device probing;
- Registry-backed catalog compatibility plus acquire-time runtime resolution;
- canonical Runtime v1 execution profiles containing only binding identity, threads,
  and mmap mode;
- exact `InstanceKey = ModelRevisionIdentity + ExecutionProfile` cache identity;
- exact-revision projection to the unchanged Runtime SPI `ResolvedModelPack`;
- suspending acquisition on an injected worker dispatcher and one single-flight load per
  instance key;
- reference-counted, idempotent `RuntimeModelLease` borrowing;
- `LOADING → ACTIVE ↔ READY_IDLE → UNLOADING` mechanics with explicit idle release;
- shutdown that rejects new acquisition, settles in-flight loads, closes each instance,
  and then releases its storage lease;
- typed, content-free Registry/load/cache failures, including native linkage failures;
- runtime-instance and cache consistency verification.

## Architecture conformance

- **Module boundaries:** All production changes are confined to `engine-models`; the
  already-approved dependency edge to `runtime-api` is now declared. No concrete runtime,
  Android, Scheduler, routing, service, or networking dependency was added.
- **Runtime SPI:** Unchanged. Slice 4 calls only `probe`, `loadModel`, `ModelInstance.info`,
  and `ModelInstance.close`. It does not call `createSession`, `tokenize`, prefill, or
  decode.
- **Ownership:** A loaded entry owns exactly one instance and one exact-revision storage
  lease. Instance close completes before storage release, preserving mmap safety.
- **Identity:** Exact revision plus typed canonical execution profile implements ADR-015.
  A speculative accelerator field was rejected during review because Runtime v1 has no
  SPI input for it.
- **Lifecycle:** No speculative `PREPARING` state was introduced. No timer, budget,
  pressure-eviction, or Scheduler policy was implemented.
- **Architecture records:** No ADR amendment is required; this slice implements ADR-015
  and does not alter a frozen decision.

## Concurrency and failure review

The manager's lock protects only short map/reference transitions; no load or instance
close occurs while it is held. Runtime probing and loading run on the injected worker
dispatcher. Concurrent same-key acquisition shares one load and reserves one reference
per waiter before delivery. Cancelling one waiter only abandons its reservation and does
not cancel the shared load. Same-key acquisition waits for teardown, preventing an old
and replacement instance from overlapping in the cache.

Adapter exceptions and `LinkageError` become typed failures without copying adapter
messages or retaining an untrusted cause. Failed acquisition completes only after the
instance/storage cleanup path settles, allowing immediate safe retry. Close remains
best-effort because the Runtime SPI requires non-throwing close; storage release still
runs if a broken native close violates that contract.

## API and dependency review

- Public API dumps changed: **No**
- Runtime SPI changed: **No**
- Manifest/durable storage format changed: **No**
- New project dependency: `engine-models → runtime-api` (already approved and enforced)
- New external version: **No**; `kotlinx-coroutines-core` 1.9.0 was already pinned and
  used elsewhere in the repository
- Dependency-rule violations: **None**
- Network code/dependency introduced: **No**

## Test coverage

The `engine-models` suite is **96/96 green** (75 pre-existing verifier/storage/catalog
tests plus 21 Registry/lifecycle/cache tests). Slice 4 coverage includes:

- duplicate/invalid registration, adapter version, required feature, unavailable probe,
  probe caching/invalidation, and content-free probe/linkage failure;
- Registry-driven catalog compatibility;
- cold/warm acquisition, exact revision/profile separation, and multi-instance caching;
- 12-way same-key single-flight acquisition and waiter cancellation;
- idempotent lease release and reference-count state transitions;
- load failure cleanup/retry and post-load consistency rejection;
- instance-close-before-storage-release ordering with pending deletion;
- same-key acquire-versus-unload serialization;
- shutdown idempotence, active-instance cleanup, and post-shutdown rejection;
- negative cache state/reference and duplicate-key consistency checks;
- explicit assertion that no inference session is created.

## Validation

```text
./gradlew :engine:engine-models:test
  PASS — 96 tests, 0 failures

./gradlew build apiCheck checkDependencyRules
  PASS — full build, API compatibility, and dependency rules
```

`git diff --check` passes. No files in `runtime-api`, `runtime-tck`, `engine-core`, or
`engine-service` were modified.

## Known limitations and remaining risk

- Runtime behavior is validated with fake runtimes only. Real llama.cpp/Android
  composition is explicitly outside Slice 4 and remains a later gate.
- Zero-reference instances stay `READY_IDLE` until explicit release or shutdown. This is
  intentional for this slice, but engine wiring must not proceed without the approved
  Scheduler/budget/idle-eviction policy.
- Runtime Registry registrations are not yet supplied by the Android composition root;
  therefore no production adapter is currently reachable through the manager.
- No inference session or request lifecycle has been exercised, by requirement.
- Device/RSS calibration and power-loss/device integration tests remain outstanding
  before Android production wiring.

## Recommendation

Task 3 Slice 4 is approved for merge as the Runtime Registry and loaded-instance
ownership foundation. Do not begin Slice 5 until it receives explicit authorization.

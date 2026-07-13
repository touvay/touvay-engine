# Task 3 Slice 3 — Engineering Review

**Status:** Approved (2026-07-13). Slice 4 was authorized separately.

## Delivered scope

- Immutable catalog snapshots published atomically through a lock-free read reference.
- Catalog rebuild integrated with storage crash recovery and active-pointer repair.
- Bounded, disposable protobuf metadata cache with atomic replacement and cache-crash
  recovery.
- Rich internal resolved revisions with signed metadata and exact immutable file paths.
- Explicit active, exact, and highest-compatible version selection.
- Idempotent exact-revision storage leases with concurrent reference counting.
- Pending-removal state and deletion deferred until the last storage lease releases.
- Catalog consistency verification across durable activation, identity, compatibility,
  manifest file descriptors, path containment, and reference state.

## Architecture and scope review

- Approved architecture changed: **no**. Slice 3 implements the catalog and
  `PackRevisionHold` prerequisite already frozen in Model Manager Architecture v1.0; no
  ADR is required.
- Public API changed: **no**. All declarations remain internal to the BCV-ignored
  `engine-models` module; Runtime SPI, contract, and SDK are untouched.
- Dependencies changed: **no**. The cache reuses protobuf-lite and storage durability.
- Runtime/Scheduler/Engine routing touched: **no**. There are no Runtime SPI imports,
  Runtime instances, inference sessions, scheduling calls, or routing/plan ranking.
- Networking/telemetry introduced: **no**.

## Correctness and security review

Committed revisions and active pointers remain authoritative. The cache cannot create a
catalog entry: rebuild re-verifies authenticity, marker identity, exact file set, and
path containment. A cache entry only avoids a payload rehash when its exact revision and
versioned metadata fingerprint match; otherwise streaming SHA-256 runs and corruption is
quarantined. Cache parsing is bounded and malformed cache bytes are discarded.

Storage leases bind to `(packId, packVersion, manifestDigest)`, increment before return,
and release exactly once even after duplicate `close()`. Pending revisions reject new
leases. Physical deletion occurs only at zero references, preserving future mmap safety
without creating a Runtime object prematurely.

## Performance and concurrency review

Snapshot reads are lock-free. Mutations, recovery, selection/acquire transitions, and
publication are serialized by the manager; store mutations retain their existing
serialization. Rebuild reads small signed metadata and file attributes on a valid cache
hit, avoiding model-weight I/O. A missing/stale cache uses the existing 64 KiB streaming
digest path. No operation is engine-wired or on the typing path.

## Validation

- `:engine:engine-models:test`: pass, 75/75 total; 19 Slice 3 catalog, cache,
  selection, lease, concurrency, consistency, and crash-recovery tests.
- `build`: pass (688 tasks; full debug/release build, tests, native build, and lint graph).
- `apiCheck`: pass; no approved public API surface changed.
- `checkDependencyRules`: pass; no new project edge or external dependency.

## Remaining risks and gates

1. Storage reference counts are intentionally process-local. Process death safely drops
   holds because no Runtime instance survives, but a pending removal request is not a
   durable tombstone and may need to be reissued.
2. Metadata fingerprints are a startup optimization, not authentication. Full digest
   verification still occurs for first build, cache loss/corruption, metadata change,
   explicit storage recovery, or policy request.
3. Android fsync/power-loss tests and backup exclusion remain composition gates.
4. Runtime Registry resolution, execution profiles, `ModelInstance`/`ModelLease`, loaded
   lifecycle, mmap handoff, scheduling, and routing remain outside Slice 3.

## Recommendation

Slice 3 meets its acceptance criteria and is approved. Its approval does not expand the
separately bounded Slice 4 runtime-lifecycle scope.

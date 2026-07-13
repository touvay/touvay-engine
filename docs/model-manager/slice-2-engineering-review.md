# Task 3 Slice 2 — Engineering Review

**Status:** Approved (2026-07-13). Slice 3 was authorized separately.

## Delivered scope

- Local `ModelPackSource` streaming boundary with store-owned manifest/signature bounds.
- Hashed v1 root, pack, and revision layout with bounded protobuf durable records.
- Same-filesystem random staging and marker-last immutable revision transactions.
- Streaming exact-size and SHA-256 verification for every declared payload file.
- Idempotent identical installs and supply-chain conflict rejection for the same
  `(pack id, version)` with different signed manifest bytes.
- Atomic active-pointer selection, exact-version rollback, and inactive-only deletion by
  rename to trash.
- Startup recovery for abandoned staging, interrupted trash deletion, corrupt revisions,
  and invalid active pointers.
- File/directory fsync abstraction with strict production NIO behavior and deterministic
  fault injection for transaction-boundary tests.

## Architecture and scope review

- Approved architecture changed: **no**. The implementation fills the storage subset in
  Model Manager Architecture v1.0 and does not require a new ADR.
- Public API changed: **no**. All hand-written storage declarations are module-internal;
  contract, SDK, and Runtime API dumps are unchanged.
- Repository dependency changed: **no**. Slice 2 reuses protobuf-lite and the Slice 1
  verifier dependencies already present in `engine-models`.
- Runtime/Scheduler/Engine routing touched: **no**. There is no Runtime SPI import,
  model loading, scheduler edge, engine-service wiring, or catalog publication.
- Networking/telemetry introduced: **no**. The source port is local and explicitly
  prohibits network I/O; failures are typed and content-free.

## Correctness and security review

The commit authority is `installed.ok`, written last after payload and directory
durability. Final revisions are never overwritten. Active selection requires atomic file
replacement; unsupported atomic replacement fails closed. Installation rejects missing,
extra, duplicate, short, oversized, digest-mismatched, symlink, hard-link, and non-regular
source entries. Destination paths are derived only from already validated manifest paths
under a random private staging root.

Recovery does not rebuild a catalog. It independently re-verifies signature, marker,
layout, exact file set, sizes, and digests. Invalid revisions are first renamed into
`trash`, then deleted without following links. An invalid active pointer is repaired only
to a compatible, fully verified installed revision; otherwise it is removed.

## Performance and lifecycle review

Install and recovery are streaming and use a 64 KiB buffer, so memory is bounded by
manifest/signature limits rather than model size. Mutations are serialized because v1
has one Model Manager writer. Full payload hashing before activation prioritizes safety;
the approved catalog fingerprint optimization remains deferred until catalog work is
authorized. No work runs on the typing path because the module is not engine-wired.

## Validation

- `:engine:engine-models:test`: pass, 56/56 total; 19 Slice 2 transaction, rollback,
  crash-recovery, integrity, and negative tests.
- `build`: pass (688 tasks; full debug/release build, tests, and lint graph).
- `apiCheck`: pass; no approved public API surface changed.
- `checkDependencyRules`: pass; no new project edge or external dependency.

## Remaining risks and gates

1. Android app-private path selection and backup exclusion are composition work and are
   not implemented in this pure-JVM slice.
2. Production `NioStorageDurability` intentionally fails closed on fsync errors. Android
   device power-loss testing remains mandatory when storage is first composed into an
   APK; Windows unit tests use an injected durability adapter because Java directory
   channel semantics differ on NTFS.
3. The v1 store assumes one process-owned writer. Shared-engine or multi-process writers
   require an explicit locking architecture change.
4. Safe deletion currently relies on the authorized no-loading invariant. Reference and
   mmap holds must land before Runtime integration, not retroactively after it.
5. Catalog rebuild, installation discovery APIs, policy-driven retention, and downloader
   behavior remain deliberately absent.

## Recommendation

Slice 2 meets its acceptance criteria and is approved. Its approval does not expand the
separately bounded Slice 3 scope.

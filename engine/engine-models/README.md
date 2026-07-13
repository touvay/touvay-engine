# engine-models — Task 3 Slices 1–4 + Milestone 5 boundary

**Status:** Slices 1–4 approved; Milestone 5 execution acquisition boundary implemented.

This pure-JVM, offline module owns the model-pack schema, bounded verification boundary,
transactional local storage, immutable catalog/storage ownership, and the internal
Runtime Registry/loaded-instance lifecycle. The normative contract is
`docs/model-manager/model-manager.md`; ADR-015 and ADR-016 freeze the boundaries and
signed envelope.

## Implemented scope

- protobuf-lite `ModelPackManifest` schema v1;
- strict, bounded protobuf parsing and semantic validation;
- detached `TVMPSIG\0` Ed25519 envelope parsing;
- immutable engine-pinned/user-approved trust-store model with revocation status;
- domain-separated signature verification over exact manifest bytes;
- engine/runtime/device compatibility verification using immutable facts;
- typed, content-free failures with no untrusted parser cause attached;
- golden, RFC 8032, security, negative, boundary, SemVer, and deterministic fuzz-style
  tests;
- store-owned bounded source reads and streaming file digest/size verification;
- same-filesystem staging with marker-last immutable revision commits;
- hashed pack/revision directories, atomic active-pointer replacement, rollback, and
  idempotent duplicate installation;
- inactive-only rename-to-trash deletion and deterministic crash recovery;
- immutable atomic catalog snapshots rebuilt from committed storage;
- disposable bounded metadata cache with per-revision payload metadata fingerprints;
- explicit active/exact/highest-compatible version selection without plan ranking;
- idempotent exact-revision storage leases, reference counts, and deferred deletion;
- immutable Runtime Registry bindings with adapter-version/feature checks and
  per-device probe caching;
- canonical execution profiles keyed by binding identity, thread count, and mmap mode;
- exact-revision `ResolvedModelPack` projection under a storage lease;
- single-flight `ModelInstance` loading, idempotent runtime leases, idle caching,
  explicit release, shutdown, and cache consistency verification;
- narrow public acquisition identity/profile/lease types used only by the
  `engine-service` composition adapter. `engine-models` remains unpublished.

There is no inference-session creation, tokenization/inference execution, scheduler,
engine routing, downloader, or network code in this module. Milestone 5 performs those
request-time operations in `engine-core` through an `engine-service` adapter. Manifest,
catalog, storage, Registry, and generated protobuf details remain module-internal, and
`engine-models` is not a published API artifact.

## Durable storage contract

The caller supplies a local, reopenable `ModelPackSource`; the store copies every byte
and never adopts a source path. It serializes mutations, verifies authenticity and
compatibility before copying, verifies every declared file while streaming, writes
`installed.ok` last, and commits a revision by same-filesystem rename. `active.pb` is the
only mutable pack record and requires atomic replacement. Recovery removes abandoned
staging, drains trash, quarantines invalid revisions, and repairs an invalid active
pointer to the newest compatible verified revision or clears it.

`NioStorageDurability` treats file and directory fsync failures as failed transactions.
Unit tests inject an NTFS-compatible durability adapter; production Android composition
and device power-loss testing remain a later, explicitly gated integration concern.

## Catalog and ownership contract

`ModelCatalogManager` owns one lock-free immutable snapshot and serializes mutation. Its
cache is derived acceleration data: signed manifests, commit markers, active pointers,
and exact file metadata are still checked on rebuild; a missing, malformed, stale, or
future-dated cache entry forces full payload verification. The cache never becomes a
source of truth.

`PackRevisionLease` pins one exact immutable revision and remains separate from a
Runtime lease. A loaded cache entry owns one `ModelInstance` and one storage lease;
`RuntimeModelLease` contributes only a process-local instance reference. Instance close
always precedes storage-lease release. Removal of a referenced inactive revision becomes
pending and the directory remains present until the loaded entry is explicitly released
or the manager shuts down.

Zero-reference entries remain `READY_IDLE`; Slice 4 deliberately adds no timer,
memory-pressure policy, or scheduler-owned eviction ranking. `RuntimeModelLease` exposes
the borrowed SPI instance for the later integration boundary, but this slice never calls
`createSession`, `tokenize`, prefill, or decode.

## Verification bounds

| Input | Hard bound |
|---|---|
| Manifest bytes | 1 MiB |
| Protobuf recursion | 32 levels |
| Signature envelope | 4 KiB |
| Files | 256 |
| Capabilities | 128 |
| Logical path | 240 UTF-8 bytes |
| Total declared file bytes | 8 GiB (later policy may lower) |

Paths must be relative NFC UTF-8 and reject traversal, empty/dot segments, backslashes,
Windows drive/device names, control characters, and overlong encodings. Referenced
template/config/license paths must exist with the declared role. SemVer parsing is strict
and compares prereleases without numeric overflow.

## Ed25519 implementation for API 29–32

Android's platform JCA only guarantees `Ed25519` from API 33. Slice 1 pins
`com.google.crypto.tink:tink-android:1.23.0`, which Google documents as fully supported
from API 24. Touvay uses the public raw-key `Ed25519Verify(byte[])` constructor. In the
pinned artifact that constructor selects Tink's pure-Java verifier directly, so API 29,
32, 33, and later do not choose different platform providers.

This was selected over:

- platform JCA plus a pre-33 fallback: two implementations and cross-provider drift;
- Bouncy Castle: substantially broader provider surface for one primitive;
- custom Ed25519: prohibited by ADR-016 and unacceptable security risk.

Tink is isolated behind `PackSignatureVerifier`. The suite verifies RFC 8032 test vector
1 plus fixed manifest/envelope golden bytes, tampering, wrong-domain signatures,
malformed keys/signatures, revocation, and user-approved raw-key fingerprints. A Tink pin
bump requires the same tests, dependency/provenance review, and APK-size comparison.

The resolved 1.23.0 JAR is 3,320,451 bytes before R8. The final shrunk APK contribution
is intentionally not claimed yet because Slice 1 does not wire this module into an APK;
that measurement is a gate before Android composition.

## Validation

```text
./gradlew :engine:engine-models:test # verifier + storage + catalog + runtime lifecycle
./gradlew build apiCheck checkDependencyRules
```

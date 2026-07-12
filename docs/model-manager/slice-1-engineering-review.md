# Task 3 Slice 1 — Engineering Review

**Status:** Approved (2026-07-12). Slice 2 was authorized separately after this review.

## Delivered

- New internal `engine/engine-models` pure-JVM module.
- ADR-016 protobuf-lite manifest schema v1.
- Strict bounded parser with identifier, SemVer, path, reference, enum, digest, count,
  recursion, raw-size, and declared-size validation.
- Exact detached signature-envelope parser with no trailing-byte tolerance.
- Immutable trust store for engine-pinned and explicitly user-approved raw Ed25519 keys,
  including revocation state and user-key fingerprint enforcement.
- Domain-separated Ed25519 verification over exact manifest bytes.
- Compatibility verifier for engine range, manifest features, runtime id/version/features,
  Android API, device tier, and ABI.
- Fixed, content-free failure codes; untrusted protobuf exceptions are not attached as
  causes.

No storage, catalog, installation, lifecycle, Runtime Registry/runtime loading,
scheduler, routing, downloader, or network behavior was added.

## Security and compatibility evidence

The focused suite contains 37 tests across nine suites with zero failures, errors, or
skips. Coverage includes:

- fixed v1 serialized manifest and detached-envelope golden bytes;
- deterministic producer compatibility and exact manifest digest;
- RFC 8032 Ed25519 test vector 1;
- manifest/signature/domain tampering;
- key-id mismatch, unknown/revoked keys, and user-approved fingerprints;
- malformed/oversized envelopes, invalid UTF-8, and trailing bytes;
- malformed protobuf, unknown optional fields, hostile logical paths, duplicate entries,
  bad references/digests/enums, wrapped unsigned values, and hard bounds;
- strict SemVer syntax/precedence and engine/runtime/device incompatibility;
- 5,000 bounded random parser/envelope inputs plus 1,000 deterministic signed-pack
  mutations, all contained by typed failures.

## Ed25519 dependency review

Chosen dependency: `com.google.crypto.tink:tink-android:1.23.0`.

Why:

- Google documents the Android artifact as supported from API 24, covering Touvay API 29;
- the raw-key constructor used by Touvay selects one pure-Java implementation on all API
  levels, avoiding an API-33 JCA/fallback split;
- Tink supplies a reviewed Ed25519 implementation and malformed-input handling;
- the dependency is contained behind `PackSignatureVerifier`, so upgrades are localized.

Measured unshrunk JAR: 3,320,451 bytes. This is the principal cost. R8 should remove
unused Tink primitives/transitives, but no shrunk-APK number is claimed until the module
is deliberately wired into an Android host. That measurement is a later composition
gate, not a reason to expand Slice 1.

## Architecture and API review

- Architecture changed: no; implementation follows accepted ADR-015/016.
- Public API changed: no; `engine-models` is BCV-ignored, all hand-written declarations
  are internal, and generated protobuf classes are not exposed through a cross-module
  port.
- Repository dependency edge added: none. The dependency-rule allowlist permits the
  approved future `engine-models → runtime-api` edge, but Slice 1 does not declare it.
- External dependencies added: protobuf-javalite (already version-pinned in the repo) and
  Tink Android 1.23.0. No network library is present.
- Runtime SPI, scheduler, router, engine service, and runtime adapter code changed: no.
- Startup/memory impact: none until a later authorized composition slice wires the module.
  Verification itself is bounded to 1 MiB manifest input and executes off-main when
  integrated.
- Privacy: verifier failures are fixed and content-free; no logging, persistence, or
  telemetry was added.

## Acceptance evidence

- `:engine:engine-models:test`: pass, 37/37.
- Full `build`: pass (`688` tasks; 2026-07-12 checkpoint).
- `apiCheck`: pass; no published API changed.
- `checkDependencyRules`: pass; no illegal project edge.
- Documentation: updated for module scope, crypto choice, limits, and Slice 2 gate.

## Remaining risks and gates

1. Run cross-API Android instrumentation and measure the R8-shrunk Tink contribution when
   Android composition is explicitly authorized.
2. Treat every Tink pin bump as a security/dependency change and rerun golden/RFC/fuzz
   tests plus size comparison.
3. Keep the generated manifest schema additive. A breaking durable-format change requires
   a new schema/envelope version and ADR amendment.
4. Slice 2 authorization is limited to durable transactional storage; it does not approve
   catalog rebuild, model loading, reference counting, scheduler/runtime integration, or
   Slice 3.

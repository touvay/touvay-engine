# engine-models — Task 3 Slice 1

**Status:** Slice 1 approved; Slice 2 durable storage work is separately authorized.

This pure-JVM, offline module currently owns only the durable model-pack schema and its
bounded trust/compatibility verification boundary. The normative contract is
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
  tests.

There is no storage, catalog, installer, lifecycle, runtime registry/integration,
scheduler, routing, downloader, or network code. All hand-written Slice 1 declarations
remain module-internal; generated protobuf classes are not exposed through a cross-module
port, and `engine-models` is not a published API artifact.

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
./gradlew :engine:engine-models:test
./gradlew build apiCheck checkDependencyRules
```

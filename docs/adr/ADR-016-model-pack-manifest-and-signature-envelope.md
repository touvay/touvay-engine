# ADR-016 — Model pack manifest and signature envelope

**Status:** Accepted for Model Manager Architecture v1.0 (2026-07-12)

## Context

Architecture v1.0 requires signed, data-only model packs with per-file SHA-256 digests.
Its JSONC example was illustrative and left canonicalization, signature placement,
schema ownership, and exact signed bytes unresolved. The first Model Manager slice must
create and verify this durable format, so deferring the choice would turn an
implementation detail into an accidental long-lived security contract.

The format must support additive evolution, bounded hostile-input parsing, exact
signature verification, reproducible pack tooling, engine-pinned publishers, and
explicitly user-approved untrusted publishers. It is durable pack metadata, not a
capability/AIDL wire schema covered by ADR-014.

## Decision

### Manifest

Each pack contains a protobuf-lite `manifest.pb`. Its schema is owned by
`engine-models`. Schema evolution is additive: unknown optional fields are tolerated,
while unknown entries in `required_manifest_features` reject installation.

Verification and manifest identity use the exact stored bytes. The verifier never
reserializes before checking the signature. The manifest digest is:

```text
SHA-256(exact manifest.pb bytes)
```

Publisher tooling must use deterministic serialization to avoid producing different
revision identities for semantically identical manifests, but verifier correctness does
not depend on canonical protobuf serialization.

### Detached signature envelope

Each pack contains `manifest.sig` with this version-1 binary layout:

```text
8 bytes   magic = ASCII "TVMPSIG\0"
1 byte    envelope_version = 1
1 byte    algorithm = 1 (Ed25519)
2 bytes   key_id_length, unsigned big-endian
N bytes   key_id, UTF-8
64 bytes  Ed25519 signature
```

No trailing bytes are allowed. `key_id` is 1–64 UTF-8 bytes and must satisfy the
identifier grammar defined by the manifest schema. It must exactly match
`manifest.signing_key_id`. The complete signature envelope is bounded to 4 KiB even
though the v1 layout is much smaller.

The signature input is:

```text
UTF8("TOUVAY_MODEL_PACK_V1\0") || exact manifest.pb bytes
```

The domain separator prevents a valid signature for another artifact type or protocol
from being accepted as a model-pack signature. Ed25519 signatures are exactly 64 bytes.

### Trust

Engine-distributed packs must resolve `key_id` through the build-pinned, versioned engine
trust store. User-imported packs remain signed: a future consent boundary supplies an
explicitly approved public key and locally verifiable approval proof. For such imports,
`key_id` is the lowercase hexadecimal SHA-256 fingerprint of the raw 32-byte Ed25519
public key.
They remain classified as untrusted and are excluded from automatic routing.

Unsigned imports are not part of Model Manager v1. Key revocation and installed-pack
continuation are explicit policy inputs; revocation data must itself be signed and must
not require telemetry.

Verification is exposed internally through a narrow `PackSignatureVerifier`. Android's
[platform `Signature` documentation](https://developer.android.com/reference/java/security/Signature)
guarantees Ed25519 only from API 33, while Touvay supports API 29. Before implementation,
the project must approve and pin an audited verifier dependency/provider for API 29–32
(or one uniform provider for all supported APIs). Implementing Ed25519 in project code is
forbidden. Cross-provider golden vectors must cover API 29, 32, 33, and the current target
API.

### File integrity

The signed manifest carries every declared file's logical path, exact byte count, role,
and SHA-256 digest. Installation streams and hashes each file. Signature validation
authenticates the expected digests; digest validation proves that staged bytes match.
Both checks are mandatory.

## Consequences

- There is no JSON canonicalization algorithm in the trust boundary.
- Signature verification is over immutable raw bytes and safely covers unknown fields.
- Exact manifest bytes become part of revision identity, making side-by-side conflict
  detection deterministic.
- Manifest authoring is less human-friendly than JSON and requires publisher tooling and
  text-format/debug views.
- `engine-models` gains a protobuf-lite dependency when implementation is authorized,
  plus a reviewed Ed25519 verifier dependency/provider for API 29–32;
- the crypto dependency's binary size and provenance must be accepted before slice 1,
  but `touvay-contract` and its capability wire schemas remain unchanged;
- Detached signatures avoid a self-referential inline-signature representation and allow
  the signature envelope to evolve independently under an explicit version.

## Alternatives considered

### Canonical JSON with inline signature

Rejected. A precise canonicalization standard, Unicode normalization rules, number
handling, duplicate-key behavior, and signature-field exclusion would all become
security-critical and require identical publisher/verifier implementations.

### Canonical JSON with detached signature

Rejected. Detachment removes self-reference but not canonicalization or duplicate-key
ambiguity.

### Protobuf with inline signature

Rejected. It requires clearing or excluding the signature field before serialization and
therefore reintroduces canonical serialization into verification.

### Defer the format decision

Rejected. Schema and verifier are the recommended first implementation slice; deferral
would ship a durable compatibility and supply-chain boundary without an ADR.

## Slice 1 implementation note (2026-07-12)

Slice 1 pins `com.google.crypto.tink:tink-android:1.23.0` behind
`PackSignatureVerifier`. Touvay uses Tink's raw-key constructor, which selects its
pure-Java Ed25519 implementation directly on every supported API. This satisfies the
API 29–32 provider gate without custom cryptography or an API-33 JCA/fallback split.
RFC 8032 and fixed pack-envelope golden vectors are release tests. The unshrunk resolved
JAR is 3,320,451 bytes; a pin bump or Android wiring requires dependency, security,
cross-API, and shrunk-APK-size review.

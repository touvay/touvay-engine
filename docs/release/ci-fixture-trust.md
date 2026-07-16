# CI Rewrite Fixture Trust Chain

**Status:** Active Internal Alpha fixture

**Scope:** Trusted model-backed Engine validation only

The CI Rewrite fixture is a signed, immutable test input. It is not a production model
pack and is not distributed to Keyboard users. Model weights remain outside Git.

## Immutable identity

The canonical identity is committed in
`ci/fixtures/rewrite-ci-fixture.lock.json`. The `ci-v1` tag is only a human-readable
discovery label and must never be used as a CI input.

| Field | Value |
|---|---|
| Engine commit | `1e22deddbc682b10d5739304250d1acb4a7cd281` |
| Runtime | `llamacpp@1.0.0` |
| Pack | `touvay.ci.qwen2.5-0.5b-rewrite@1.0.0` |
| OCI reference | `ghcr.io/touvay/touvay-engine-ci-rewrite-fixture@sha256:085ac31370c072ab3bf1099a1469c09c654bd282eb06a366ea6bc041e5286716` |
| Pack archive SHA-256 | `5f818d38dfc0a68e21752decf1b53420839334b413312c29ab4614993cc5b0cb` |
| Manifest SHA-256 | `4bffa628baadf0ae7c3f943ead0b3e43e691b0e94d679f2a0b98a7b87c5811f4` |
| Publisher key ID | `0da7a9841f80d2970e70947891e484462f9501a3cfd4ff9dba6cd679a162c5a7` |

The GHCR package is private. A credential-free token request returns `401`. Transport
authorization is not part of fixture trust: the trusted model-backed job uses only its
ephemeral, repository-scoped `GITHUB_TOKEN` with `packages: read`, then logs out before
building or executing tests. It does not receive a fixture private key, a production
publisher key, or a long-lived package token.

## Verification order

A trusted model-backed job must fail closed in this order:

1. Parse the committed lock and reject an unrecognized schema.
2. Pull the private OCI package by `ociReference`, including its digest. Never pull by
   tag.
3. SHA-256 the extracted `fixture-pack.tar` and compare it with `packDigest`.
4. Extract into a new empty directory without symbolic links or path traversal.
5. SHA-256 `manifest.pb` and compare it with `manifestDigest`.
6. Load only the committed public key and confirm its SHA-256 equals `keyId`.
7. Install through the existing Model Manager. Its bounded verifier must authenticate
   the ADR-016 Ed25519 envelope, exact manifest bytes, declared paths, sizes, file
   digests, compatibility, and Runtime requirement.
8. Run the exact expected model-backed test groups and reject missing or unexpected
   test counts.

The published fixture passed the existing bounded verifier and transactional Model Store
installation path before publication. It was then pulled back by immutable OCI digest;
the extracted archive reproduced the committed pack SHA-256.

## Expected device tests

| Trust class | Test group | Count |
|---|---|---:|
| Model-independent | Echo cross-process | 1 |
| Model-independent | Engine resilience cross-process | 2 |
| Model-dependent | Runtime TCK | 28 |
| Model-dependent | Rewrite end to end | 1 |
| Model-dependent | Benchmark smoke | 1 |
| Model-dependent | Rewrite benchmark | 1 |
|  | **Total** | **34** |

Model-independent jobs do not download the fixture. Model-dependent jobs are authorized
only for trusted `main`, RC, and release workflows. Missing fixture access, a digest
mismatch, a key mismatch, signature failure, incomplete provisioning, or a test-count
mismatch is a hard failure for those workflows.

## Workflow split

`.github/workflows/ci.yml` defines three independent gates:

- `build` runs on every pull request and trusted push with Gradle dependency verification
  explicitly set to `strict`.
- `Connected Core` runs the explicit Echo and Engine Resilience class allowlist on every
  pull request and trusted push. `scripts/ci/assert-connected-core-results.ps1` requires
  exactly three tests with zero failures, errors, skips, or unexpected classes.
- `Connected Model-Backed` runs only for trusted pushes and manual release execution. It
  authenticates to private GHCR with `packages: read`, pulls by immutable digest, calls
  `scripts/ci/verify-ci-fixture.ps1`, removes the transport credential, and then runs the
  31 locked Runtime, Rewrite, and benchmark tests.

The model-backed demo APK is composed with the committed CI pack ID, public key, and key
ID. A complete externally provisioned signed pack is preserved; partial external
metadata fails before trust domains can be mixed. The existing Model Manager remains the
only component that authenticates and installs the pack for end-to-end Rewrite tests.
Runtime TCK and benchmark consumers receive the weights only after the OCI archive,
public key, pack digest, and manifest digest have been verified.

## Trust chain

```text
Reviewed fixture-lock manifest
  -> immutable private OCI digest
  -> exact fixture-pack.tar SHA-256
  -> committed CI Fixture Publisher public key and SHA-256 key ID
  -> detached Ed25519 envelope authenticates manifest.pb
  -> manifest.pb authenticates every declared path, size, and SHA-256
  -> existing Model Manager verifies and transactionally installs the pack
  -> existing Runtime Registry resolves llamacpp@1.0.0
  -> model-backed Rewrite, Runtime TCK, and benchmark tests execute
```

CI verifies fixtures; it never creates or signs them. Publishing is a separate,
human-authorized release operation under the key-management policy.

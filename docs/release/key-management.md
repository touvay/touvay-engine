# Release Key-Management Policy

**Status:** Active for Internal Alpha

This policy separates test-fixture trust from production publishing. Private signing
material is never committed, placed in Android resources, uploaded to GHCR, or exposed
to CI.

## Trust domains

| Domain | Purpose | Private-key location | Permitted consumers |
|---|---|---|---|
| Developer Fixture Key | Local demo and developer-only fixtures | Developer workstation only; normally ephemeral | Local developer tooling and explicitly demo-only hosts |
| CI Fixture Publisher Key | Immutable signed fixtures for trusted model-backed CI | Release custodian workstation under the controls below | CI receives only its public key through the fixture lock |
| Production Publisher Key | Release model packs for product hosts | Separate production release custody; never shared with either fixture domain | Production host trust configuration only |

Keys, public-key IDs, signatures, and packs must not cross these domains. A fixture that
was signed by a Developer or CI key cannot be promoted into a production model pack.

## Active CI Fixture Publisher identity

| Field | Value |
|---|---|
| Algorithm | Ed25519 |
| Key ID | `0da7a9841f80d2970e70947891e484462f9501a3cfd4ff9dba6cd679a162c5a7` |
| Key-ID derivation | Lowercase SHA-256 of the 32-byte raw public key |
| Public key | `ci/fixtures/ci-fixture-publisher-public-key.b64` |
| Custody root | `%LOCALAPPDATA%\Touvay\release-keys\ci-fixture-publisher\<key-id>\` |
| Protected private key | `private-key.pkcs8.dpapi` |
| Custody record | `custody.json` |

The active private key is encrypted using Windows DPAPI for the release custodian's
current-user context. The key directory has inheritance disabled and grants only that
user non-inherited full control. The plaintext PKCS#8 key is absent after signing. A
DPAPI decrypt-and-hash round trip was completed when the key was stored.

The encrypted private key is the long-lived CI fixture publishing identity. It may be
used only on the custodian workstation to sign immutable CI fixture manifests. Loss of
the protected key is recoverable by rotation; existing fixtures remain verifiable with
their committed public keys. The DPAPI blob must not be copied to CI or treated as a
portable backup, and plaintext export is prohibited.

## Signing and publication ceremony

1. Start from reviewed model, prompt, manifest, Runtime, and Engine inputs.
2. Decrypt the CI private key only inside the local signing process. Do not write a
   plaintext private-key file.
3. Sign exactly the ADR-016 message and build its detached envelope.
4. Verify the pack with the existing bounded verifier and transactional Model Store.
5. Build the deterministic pack archive twice and require identical SHA-256 values.
6. Publish with a short-lived, human-controlled GHCR credential. That transport token
   is not a signing key and is never provided to CI.
7. Pull by immutable OCI digest and reproduce the pack SHA-256.
8. Commit the public key, immutable fixture lock, and audit documentation. Never commit
   the model archive, private key, or transport credential.

## Rotation

Review the CI identity before each release milestone and rotate it when any of the
following occurs:

- suspected private-key disclosure or unauthorized signing;
- custody workstation or operating-system identity replacement;
- loss of the protected private key;
- cryptographic-policy change;
- fixture publisher ownership change; or
- planned annual key review determines rotation is appropriate.

Rotation is an atomic trust update:

1. Generate an independent Ed25519 successor in protected local custody.
2. Record its custody facts and independently verify its public-key-derived key ID.
3. Sign and publish a new immutable fixture under a new pack version and OCI digest.
4. Verify the new fixture through the existing Model Manager.
5. Replace the active lock and public key in one reviewed commit.
6. Mark the predecessor `RETIRED` or `REVOKED` in release records. Do not delete its
   public key while archived fixture evidence still depends on it.

Compromise triggers immediate revocation: stop publishing, revoke package credentials,
remove the affected key from active locks, preserve audit evidence, generate a successor,
and republish every fixture that must remain usable. Public exposure of a signed fixture
does not by itself compromise the private key, but unauthorized signatures do.

## Prohibited practices

- No private signing key in GitHub Actions, repository secrets, Gradle properties, APKs,
  containers, model packs, logs, or shared storage.
- No key reuse between Developer, CI, and Production domains.
- No signing inside CI.
- No mutable tag as a trusted fixture identity.
- No unsigned, self-signed-at-runtime, or signature-bypass fixture path.
- No use of the CI key to sign a Keyboard production pack.

# Signed Rewrite Demo Pack

**Status:** Offline engineering-validation fixture

**Trust classification:** Developer Fixture Key only; never CI or production trust

The demo validates the production path without a network or verification bypass. The
APK carries a demo public key, a detached signature envelope, the signed manifest, and
the signed Rewrite prompt asset. The Qwen2.5 GGUF remains a local, gitignored input and
is copied into the app-specific pack source before validation.

## Pinned pack

| Field | Value |
|---|---|
| Pack | `touvay.demo.qwen2.5-0.5b-rewrite@1.0.0` |
| Runtime | `llamacpp`, adapter `>=1.0.0` |
| Capability | `text.rewrite@1` |
| Key ID | `d086218cf3b91da80208646e6a4e0ded99da431e8848d74417d08fb09851edb6` |
| Manifest SHA-256 | `53ae93adc1d0a53df5ceaab4771eb916b849b20e44ae6bc09857f9292a489cb8` |
| GGUF SHA-256 | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| GGUF bytes | `491400032` |

The committed `.b64` files in `docs/demo/pack/` are transport-safe fixture assets.
`DemoPackProvisioner` decodes them to the app-specific external-files source. It does
not generate, replace, or weaken signatures.

When a release-controlled test host provisions a complete external `manifest.pb`,
`manifest.sig`, and signed prompt before execution, the provisioner preserves that set
instead of replacing it with Developer assets. A partial external metadata set fails
closed. CI composes the non-production demo host with its independently committed CI
Fixture Publisher public key; ordinary developer builds retain the defaults below.

The demo key is the Developer Fixture Key trust domain. It is independent of the
long-lived CI Fixture Publisher Key and the separately controlled Production Publisher
Key described in `docs/release/key-management.md`.

## Demo signing workflow

Signing is performed on a trusted offline workstation:

1. Generate a new Ed25519 key pair with a cryptographically secure implementation.
2. Export the 32-byte raw public key. Compute its lowercase SHA-256 hex digest and use
   that digest as `manifest.signing_key_id` and the envelope key ID.
3. Build the deterministic protobuf manifest. It must enumerate every pack file with
   its exact logical path, byte count, SHA-256 digest, and role. Bind
   `text.rewrite@1` to `prompts/text-rewrite-v1.pb`.
4. Sign the exact byte sequence `TOUVAY_MODEL_PACK_V1`, followed by one NUL byte,
   followed by the serialized manifest bytes.
5. Encode the signature using ADR-016's detached `TVMPSIG\0` version-1 Ed25519
   envelope. Do not sign a textual, JSON, or Base64 representation.
6. Verify the resulting directory with the bounded verifier and the public key before
   committing fixture metadata. Delete the private key from the build workspace.

The current fixture was created this way. Its private key was discarded and is not in
Git, Gradle inputs, the APK, Android resources, or application storage.

## Trust chain

```text
Demo application manifest
  -> explicit demo public key and SHA-256 key ID
  -> detached Ed25519 envelope authenticates manifest.pb
  -> manifest.pb authenticates every file path, size, and SHA-256
  -> transactional Model Manager copies and verifies every declared file
  -> verified active catalog revision supplies route and prompt identity
  -> Runtime Registry resolves llama.cpp
  -> Execution Coordinator acquires the exact revision and runs Rewrite
```

There is no unsigned-pack mode. A missing key, wrong key ID, malformed envelope,
modified manifest, missing file, size mismatch, digest mismatch, incompatible device,
or unresolved Runtime fails closed before routing becomes ready.

## Local validation

Fetch the pinned model into the gitignored `models/` directory:

```powershell
.\scripts\fetch-model.ps1
```

Build and install the demo and its test APK, then copy the GGUF to the app-specific
offline source as `files/weights.gguf`. For a running emulator or device:

```powershell
.\gradlew.bat :apps:demo:assembleDebug :apps:demo:assembleDebugAndroidTest
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r apps/demo/build/outputs/apk/debug/demo-debug.apk
& $adb install -r apps/demo/build/outputs/apk/androidTest/debug/demo-debug-androidTest.apk
& $adb shell am start -W -n com.touvay.demo/.MainActivity
& $adb shell am force-stop com.touvay.demo
& $adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.touvay.demo/files/touvay-demo-pack/files/weights.gguf
& $adb shell am instrument -w -e class com.touvay.demo.RewriteEndToEndTest com.touvay.demo.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumentation test deliberately fails when the local GGUF is absent or has the
wrong byte count. The Model Manager then checks its signed digest during installation.
Do not create the app-specific directory with `adb shell mkdir`: that makes `shell` its
owner on current Android releases. Launching the demo once creates the directory with
the application UID before the local file is pushed.

## Key replacement

Demo key replacement is an atomic fixture update, not an in-place trust expansion:

1. Generate a fresh offline key pair and rebuild/sign the entire fixture.
2. Verify all paths, sizes, hashes, compatibility declarations, capability binding,
   and signature with an independent test invocation.
3. Replace the public key, key ID, manifest, envelope, and prompt fixture together in
   one reviewed change. Update the demo manifest and this document in that change.
4. Increment the pack version when pack content changes. Uninstall the old demo or
   clear its app data so stale catalog state cannot obscure validation.
5. Revoke the old demo key by removing it; do not retain both keys for convenience.
6. Destroy the replacement private key after signing unless a separately controlled
   demo release process has an explicit retention policy.

CI and production trust must use independently generated keys in their own custody and
composition sources. Copying this Developer Fixture public key into CI or a production
host is prohibited.

## Security considerations

- Public-key metadata is explicit only in the demo application. The engine-service
  library contributes no default trusted key.
- The private key never crosses into Android. Possession of the public key cannot sign
  or alter a pack.
- The source directory accepts no symbolic links, undeclared files, traversal paths,
  or mutable shortcuts around bounded verification.
- User request content is neither logged nor persisted. The model store contains only
  signed model-pack material and content-free catalog metadata.
- Base64 is packaging, not security. Authenticity comes only from Ed25519 verification
  and the manifest's file hashes.
- This key proves demo-fixture provenance, not model quality, licensing approval, or
  production publisher identity.

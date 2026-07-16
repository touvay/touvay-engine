# Touvay Engine Developer Console

**Status:** Engineering-only application. It is not a production client and must not be
distributed as a user-facing Engine application.

The Developer Console is implemented by `apps/demo`. Capability execution always uses
the public Touvay SDK and the packaged `:touvay` Engine service. Developer-only model
administration uses the existing unpublished Model Manager composition facade; it does
not add a Binder, SDK, Runtime SPI, capability, or production routing surface.

The Console is an independent Android host (`com.touvay.demo`). Its embedded Engine and
private Model Store are not shared with Touvay Keyboard. Importing, installing, or
activating a pack here affects only the Console. Keyboard Internal Alpha uses its own
production host configuration and private provisioning workflow.

## Surfaces

- **Dashboard:** connection state, Rewrite readiness, llama.cpp adapter, active signed
  revision, Engine-process PSS, thermal status, and battery percentage.
- **Capability Tester:** structured Rewrite tone/length/locale input, unary or streaming
  execution, cancellation, TTFT, total time, delta count, disposition, and final output.
- **Model Manager:** installed catalog snapshot, signed-directory import, isolated full
  verification, transactional installation, exact activation, rollback, and inactive
  deletion. Active revisions cannot be deleted.
- **Benchmark:** quick or full production-SDK runs over Rewrite Benchmark Corpus v1,
  including cold/warm TTFT, end-to-end time, quality, PSS, battery energy, thermal state,
  and cancellation. Exact token throughput remains in `apps/benchmark`, invoked by the
  existing host runner.
- **Diagnostics:** stable human-readable conditions and recommended actions. Raw
  exception messages are never rendered.
- **Logs:** a bounded process-local log of content-free operation and lifecycle codes.
  User input and model output are not accepted by the logger.
- **Settings:** developer logging/result options, experimental-feature switch, the
  engine-pinned trusted key, and installed revision summary.

## Model administration rules

1. Import accepts a Storage Access Framework directory with `manifest.pb`,
   `manifest.sig`, and `files/`. It applies the signed-pack file/depth/byte bounds while
   copying into app-private staging.
2. Verify runs the complete Model Manager install pipeline against an isolated temporary
   store, then removes that store. A successful result is keyed by exact revision
   identity; the production store is unchanged.
3. Install runs the same verifier and transactional store against the Engine's durable
   store without activation.
4. Before activation, rollback, deletion, or catalog inspection, the Console closes its
   SDK client. This releases the embedded Engine and leaves exactly one Model Manager
   owner. The Console reconnects after the mutation.
5. Activation and rollback publish the exact staged signed source used by the embedded
   Engine on its next start. The Engine independently verifies and activates it again.
6. The Official Model Catalog appears as a disabled acquisition source. Its source ID
   and UI slot are separate from local directory import, but no downloader or network
   code exists in this milestone.

Only engine-pinned trusted public keys are displayed. Private signing material never
enters the application. Imported packs cannot bypass signature, compatibility, file
integrity, or transactional storage checks.

## Build and validation

```powershell
.\gradlew.bat :apps:demo:assembleDebug :apps:demo:assembleDebugAndroidTest
.\gradlew.bat :apps:demo:connectedDebugAndroidTest
```

The Console APK is `apps/demo/build/outputs/apk/debug/demo-debug.apk`. A compatible
signed pack remains required for real Rewrite and benchmark execution.

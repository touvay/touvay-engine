# Changelog

All notable changes to Touvay Engine are documented in this file.

The project follows milestone tags while the public API remains pre-1.0.

## Unreleased

### Release engineering — 2026-07-16

- Made clean-checkout CI fetch and verify the commit-pinned llama.cpp source before
  native configuration.
- Pinned every GitHub Action to an immutable commit SHA and added the official Gradle
  8.14.3 wrapper distribution checksum.
- Added SHA-256 dependency verification metadata and dependency lock state for every
  Engine project.
- Added a dedicated Ed25519 CI Fixture Publisher trust domain and published the signed
  Rewrite fixture to private GHCR with immutable OCI, pack, and manifest digests.
- Added the fixture lock, CI public verification key, expected model-backed test counts,
  three-domain key-custody policy, and publisher rotation procedure. CI holds no signing
  key and no model weights were committed.
- Split device CI into the mandatory model-independent Connected Core gate and the
  trusted-push Model-Backed gate. The latter pulls private GHCR only by immutable digest,
  verifies the locked archive/public key/manifest, removes its transport credential,
  and requires exact non-skipped test counts.
- Added CI-only demo host trust composition for the CI Fixture Publisher while retaining
  Developer defaults and the existing Model Manager verification path.
- Added a test-APK-only provisioning activity so Runtime TCK fixtures are copied into an
  application-owned external directory without shell ownership or shared storage.
- Added the equivalent debug-only benchmark provisioner; neither fixture activity is
  included in production release packaging.
- Kept Runtime, Model Manager, Execution, Capability, SDK, and architecture contracts
  unchanged.

### Embedded host provisioning — 2026-07-15

- Added fail-closed production host metadata for one signed offline pack, including
  pack identity, public verification key, key-ID binding, and an application-private
  no-backup source directory.
- Preserved the embedded, non-exported Engine service and per-host canonical Model
  Store; no shared storage, cross-application IPC, SDK, Model Manager, Runtime,
  Execution, or Capability contract changed.
- Kept legacy demo trust isolated to the Developer Console and prevented invalid
  production host configuration from falling back to demo trust.

### Developer Experience — 2026-07-15

- Expanded the engineering demo into the Touvay Engine Developer Console with Dashboard,
  Capability Tester, Model Manager, Benchmark, Diagnostics, Logs, and Settings surfaces.
- Added developer-tool projections for the Model Manager's existing signed install,
  catalog snapshot, activation, rollback, and inactive-removal operations. Verification,
  storage transactions, leases, and Runtime resolution semantics are unchanged.
- Integrated production SDK Rewrite benchmarking and retained the companion Runtime host
  for exact decoded tokens per second.
- Added bounded content-free developer logging and stable human-readable diagnostics;
  raw exception messages are never displayed.
- Added a disabled Official Model Catalog acquisition source without downloader or
  network implementation.

### Rewrite product-quality baseline — 2026-07-15

- Added a frozen 105-case Rewrite corpus balanced across grammar-shaped inputs,
  formality, shortening, expansion, professional writing, social writing, and
  multilingual writing.
- Added production SDK/Binder corpus measurement for TTFT, end-to-end latency,
  Engine PSS, battery counters, thermal state, output quality, cancellation latency,
  and cold versus warm execution.
- Extended the existing production Runtime benchmark with model-variant identity and
  suite-level battery/thermal/resource measurements while retaining exact token
  throughput measurement.
- Added deterministic quality scoring, absolute acceptance thresholds, baseline
  regression policy, a repeatable physical-device runner, and a result-comparison gate.
- No production Engine, SDK, Runtime, capability contract, model routing, or
  architecture was changed; no new capability was introduced.

### External SDK integration handoff — 2026-07-15

- Approved and marked Keyboard Architecture v1.0 Stable, merging it into the frozen
  platform architecture by reference.
- Finalized the Engine/SDK integration contract for the separate Touvay Keyboard
  repository; no keyboard product code is included here.
- Documented local composite-build consumption, embedded engine hosting, capability
  discovery, lifecycle ownership, streaming, cancellation, error handling, and
  validation requirements.
- Clarified that the client connection owner performs bounded reconnection by calling
  `Touvay.connect` again; the SDK never auto-resubmits generative work.

### Platform Freeze — 2026-07-15

- Published Platform Validation commit `1c4a490a405a5f6323173aeb57170666739d9d17`
  and annotated tag `platform-v1-validated`.
- Marked the approved Platform Foundation specifications Stable.
- Established the rule that future semantic architectural changes require an accepted
  ADR before implementation.
- Added and approved Keyboard Architecture v1.0 as a Stable external-client contract.
  No keyboard, Grammar, or Translation production code is included.

## Platform Foundation v0.5 — 2026-07-13

Milestone tags: `foundation-v0.5` and `platform-v1-validated`.

### Added

- Runtime v1.0 SPI, 28-check Runtime TCK, and production llama.cpp adapter.
- Signed offline model-pack verification, transactional installation, catalog rebuild,
  leases, reference counting, Runtime Registry resolution, and cached instance lifecycle.
- Execution Coordinator with scheduling, request-attempt sessions, bounded streaming
  credits, cancellation, retry coordination, and terminal arbitration.
- Capability Framework v1, Context Architecture v1, and the production
  `text.rewrite@1` capability.
- Stable typed Rewrite SDK API and an engineering demo for streaming, cancellation,
  structured results, timing, capability metadata, and runtime diagnostics.
- Real signed-pack end-to-end validation through SDK, Binder, Model Manager, Runtime
  Registry, Execution Engine, and llama.cpp.

### Security

- Model manifests and prompt assets are verified using detached Ed25519 signatures.
- Demo trust is explicitly separated from production trust.
- No unsigned-model or debug-trust bypass exists.
- Private signing keys, model weights, local SDK paths, IDE state, and build products are
  excluded from version control.

### Validation

- Full build, API compatibility checks, and dependency rules pass.
- Runtime TCK passes 28/28 on the production adapter.
- Capability Framework and Rewrite conformance suites pass.
- Cross-process signed Rewrite integration, streaming, cancellation, invalid-request,
  error, discovery, and structured-output paths are covered.

### Known limitations

- Representative 4 GB arm64 device calibration remains required before product launch.
- Model downloading and networking are intentionally absent; demo provisioning is local
  and offline.
- Keyboard integration, production UI, retrieval, memory, and additional capabilities
  are outside this milestone.

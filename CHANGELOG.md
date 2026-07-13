# Changelog

All notable changes to Touvay Engine are documented in this file.

The project follows milestone tags while the public API remains pre-1.0.

## Unreleased

No implementation milestone is currently authorized.

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

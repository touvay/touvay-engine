# End-to-End Platform Validation Engineering Review

**Scope:** Signed offline Rewrite path

**Approval:** Approved 2026-07-13

**Architecture decision:** No new ADR. This change composes already approved Model
Manager, Runtime Registry, Execution, Capability, Binder, and SDK contracts in the
existing `engine-service` composition root.

## Implemented path

The demo provisions signed pack metadata and a signed prompt asset alongside a locally
supplied GGUF. The engine pins the demo public key only when the host explicitly opts
in, verifies and transactionally installs the pack, activates the compatible revision,
resolves the existing llama.cpp Runtime registration, and creates the existing
Execution Coordinator with a fixed `text.rewrite@1` route. The Binder delegates Rewrite
to that Coordinator. The SDK presents typed Rewrite input, streaming events, terminal
structured output, errors, discovery, and cancellation.

The diagnostic `dev.echo` path remains isolated. No Runtime SPI, Model Manager
architecture, Capability SPI, Scheduler contract, or engine-routing architecture was
changed.

## Review findings

- **Security:** Fail-closed signed verification is mandatory. No unsigned/debug bypass
  exists. Only the demo public key is packaged; private key material is absent. Errors
  are content-free and requests are not persisted.
- **Dependencies:** The only production edge added is
  `engine-service -> runtime-llamacpp`, the approved composition-root concrete wiring.
  It is now enforced by `checkDependencyRules`.
- **Public API:** SDK changes are additive and capability-level. Runtime, model,
  execution, prompt, and routing types remain hidden. The contract adds one additive
  error-code constant for typed invalid requests.
- **Concurrency:** Model installation occurs on an IO supervisor. Requests fail closed
  while initialization is incomplete. Coordinator credit, terminal arbitration, and
  cancellation semantics are reused unchanged.
- **Resource impact:** First initialization copies and hashes a 491,400,032-byte model;
  later starts verify the installed revision and mmap it on acquisition. The demo uses
  at most four inference threads. Representative arm64 memory/latency calibration is
  still a product-release gate.

## Known limitations and residual risks

- Model delivery is deliberately manual and offline; downloader/networking is absent.
- The fixed policy has one model and no fallback. This is intentional for validation.
- The demo key establishes fixture provenance only and must never enter production
  trust.
- Model quality is not asserted by structural conformance tests. Product quality and
  representative 4 GB arm64 calibration remain separate gates.
- If the offline source is missing or invalid when the engine process initializes, the
  host must repair the source and restart the process. Automatic source monitoring is
  outside this validation scope.

## Validation evidence

Validated on 2026-07-13:

- `gradlew build apiCheck checkDependencyRules` plus the explicit Runtime host,
  Capability, and Rewrite TCK tasks: green (`BUILD SUCCESSFUL`, 820 tasks).
- llama.cpp Android Runtime TCK on API 36.1 x86_64 with 4,007,756 kB RAM: 28/28
  green in 119.089 seconds.
- Real cross-process `RewriteEndToEndTest`: 1/1 green in 35.774 seconds. It verified
  discovery readiness, streaming deltas, structured terminal output, invalid input,
  cancellation, and post-cancellation recovery through the signed installed pack.
- Read-only device inspection confirmed `manifest.pb`, `manifest.sig`, `installed.ok`,
  the copied GGUF, catalog cache, and `active.pb` in the private 469 MB model store.
- Native loader evidence confirmed `libtouvay_llama.so` loaded for the engine process;
  no fatal exception appeared in the inspected device log window.
- A manual engineering-demo run showed `Status: Ready`, streamed 32 deltas, replaced the
  preview with a structured `REWRITTEN` terminal result, and displayed TTFT 5,828 ms,
  engine total 17,104 ms, and wall total 17,298 ms on the validation emulator.

All acceptance gates are green. No P1 or P2 finding remains open in this scope.

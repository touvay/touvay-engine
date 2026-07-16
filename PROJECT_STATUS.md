# Touvay Engine Project Status

**Last updated:** 2026-07-16

## Current summary

The Platform Foundation is complete, validated, and published at commit `1c4a490a` with
the annotated tag `platform-v1-validated`. Runtime v1.0, Model Manager, Runtime Registry,
Execution Engine, Capability Framework, Context Architecture, typed Rewrite SDK, and the
real signed offline llama.cpp path are green end to end. The approved platform contracts
are now Stable; Keyboard Architecture v1.0 is approved and merged into the frozen
platform. Future semantic architectural changes require an accepted ADR before
implementation. Touvay Keyboard is a separate sibling repository and will consume the
validated SDK; this Engine repository contains no keyboard implementation. Grammar,
Translation, networking, retrieval, and memory remain unauthorized.

Rewrite product-quality work is now authorized as measurement infrastructure around
the frozen platform. Benchmark Suite v1 provides a 105-case synthetic corpus,
production-path quality/latency/resource measurement, exact Runtime token throughput,
model-variant labeling, cancellation and cold/warm evidence, and versioned acceptance
and regression policy. It changes no production platform architecture or capability.

Developer Experience tooling is now implemented through the non-production `apps/demo`
Developer Console. It consumes the existing SDK, signed-pack pipeline, Model Manager
operations, and Rewrite benchmark assets without changing Engine architecture, Runtime
SPI, model storage semantics, Capability Framework, or production routing.

Embedded production hosts can now provide one fail-closed signed-pack configuration
using a pinned public key and an application-private no-backup source. The canonical
Model Store remains private to each host. Demo trust remains Console-only; no shared
store, exported service, cross-application administration, or standalone Engine phase
was introduced.

Milestone 6 release controls now cover clean-checkout native bootstrap, immutable CI
Action pins, a checksum-pinned Gradle wrapper, dependency verification metadata, and
dependency locks. A dedicated CI Fixture Publisher identity now signs an immutable,
private GHCR Rewrite fixture pinned by OCI, pack, and manifest digests. Developer, CI,
and Production publisher trust domains are explicitly separated; CI receives no signing
key. CI now separates the mandatory model-independent Connected Core gate from the
trusted-push Model-Backed gate, which downloads and verifies the fixture by digest before
running 31 device tests. The platform architecture and runtime behavior are unchanged.
Final Internal Alpha promotion remains owned by the Keyboard release gate and requires
its production-signed Pixel 8 signed-pack evidence and archived artifact hashes.

## Completed milestones

| Milestone | State | Evidence |
|---|---|---|
| Architecture v1.0 and walking skeleton | Complete | `docs/ARCHITECTURE.md`, Binder/AIDL, SDK, diagnostic `dev.echo` |
| Runtime v1.0 Foundation | Complete | `runtime-v1.0-foundation`; Runtime SPI, Runtime TCK, llama.cpp adapter; 28/28 device TCK |
| Task 2.1 Production Hardening | Complete | Runtime API/dependency/build validation and production adapter hardening |
| Model Manager Architecture v1.0 | Complete | ADR-015/016 and `docs/model-manager/model-manager.md` |
| Model Manager Slice 1 | Complete | Manifest/signature/compatibility verification and security tests |
| Model Manager Slice 2 | Complete | Transactional storage, activation, rollback, deletion, and recovery |
| Model Manager Slice 3 | Complete | Catalog, rebuild, metadata cache, leases, and reference counting |
| Model Manager Slice 4 | Complete | Runtime Registry resolution and cached `ModelInstance` lifecycle |
| Execution Architecture v1.0 | Complete | ADR-017/018/019 and `docs/execution/execution-architecture.md` |
| Milestone 5 Execution Engine | Complete | Coordinator, Scheduler, Runtime sessions, credits, cancellation, retry, terminal arbitration |
| Platform Foundation v0.5 checkpoint | Complete | Commit `9288fe77081918e0f42ddd75563cd60d81d3e637`, tag `foundation-v0.5` |
| Capability Framework Architecture v1.0 | Complete | `docs/capabilities/CAPABILITY_SPEC.md`, ADR-020/021 |
| Capability Framework implementation | Complete | Commit `a40caa29648f58ce48f01cde6dc8c080e05ee571`; SPI, registry/discovery, prompt assets, semantic plans, 20-check TCK |
| Context Architecture v1.0 | Complete | `docs/context/CONTEXT_SPEC.md`, ADR-022/023 |
| Capability 1 Rewrite | Complete | Commit `53cb87d`; `text.rewrite@1`, golden/streaming/cancellation/Coordinator integration tests |
| End-to-End Platform Validation | Complete and approved | Signed pack, typed SDK, demo, real llama.cpp Rewrite, 28/28 device TCK |
| Platform Validation publication | Complete | Commit `1c4a490a405a5f6323173aeb57170666739d9d17`, tag `platform-v1-validated`, published `main` |
| Platform Freeze | Complete | Approved platform specifications marked Stable; ADR gate recorded |
| Keyboard Architecture v1.0 | Approved and Stable | `docs/keyboard/KEYBOARD_ARCHITECTURE.md`; merged into frozen platform by reference |
| External SDK integration handoff | Complete | Composite-build consumer smoke test; SDK lifecycle and validation guide |
| Rewrite Benchmark Suite v1 | Implemented; physical baseline pending | 105-case corpus, production Rewrite runner, Runtime throughput runner, scoring and regression policy |
| Engine Developer Console | Implemented; device UX validation pending | Dashboard, Rewrite tester, signed model administration, benchmark, diagnostics, logs, developer settings |
| CI Rewrite fixture trust | Published and locked | Private digest-pinned GHCR fixture, dedicated publisher key, three-domain custody and rotation policy |
| CI device-test split | Implemented | Build and Connected Core on every PR; fail-closed Model-Backed gate on trusted main, RC, and release execution |

## Current milestone

**Milestone 6 Release Engineering — repository controls complete; product signing gate pending**

`docs/benchmarks/rewrite-benchmark-suite.md` defines the controlled device procedure,
scoring, acceptance floors, and baseline comparison policy. The first three full Pixel
8 runs remain required to promote the initial model baseline. No Runtime, Model Manager,
Execution, Capability Framework, SDK, Binder API, or capability behavior is changed.

`docs/developer-console.md` defines the engineering-only Console and its single-owner
model-administration procedure. Physical-device validation still requires signed Rewrite
weights; the three-run Rewrite quality baseline also remains pending.

## Upcoming milestones

These are sequencing candidates, not implementation authorization:

1. Run and approve three full Rewrite Benchmark Suite repetitions on Pixel 8.
2. Run representative 4 GB arm64 calibration before any model capability ships.
3. Additional keyboard product work only after separate authorization.
4. Additional capabilities only after their own scoped approval.

## Active ADRs

All ADR-001 through ADR-023 are accepted and active. Any future semantic architectural
change requires a new accepted ADR before implementation. The decisions most directly
governing current work are:

- ADR-004: structured capability APIs, never prompt tunnels;
- ADR-009: additive capability schema versioning and discovery;
- ADR-014: public capability wire schemas belong to `touvay-contract`;
- ADR-015: Runtime Registry resolution and exact model revision/profile identity;
- ADR-017: immutable request plan, attempt ownership, retry, and terminal semantics;
- ADR-018: bounded credit-based streaming;
- ADR-019: request-attempt Runtime sessions and client-owned conversations;
- ADR-020: production Capability SPI, ordered semantic plans, and exact-key registry;
- ADR-021: typed prompt recipes and frozen signed-asset binding;
- ADR-022: request-scoped context providers and internal provenance; and
- ADR-023: deterministic merging and exact tokenizer budgeting.

The canonical ADR list is maintained in `docs/specs/README.md`.

## Current architectural state

- Public transport is contract v2 Binder/AIDL with additive negotiation and bounded
  streaming credits; v1 transaction IDs remain frozen.
- `engine-core` is pure JVM and owns request orchestration, scheduling, terminal
  arbitration, and the internal Capability SPI boundary.
- `engine-models` owns verified immutable packs, transactional storage, catalog,
  reference counting, Runtime Registry resolution, and loaded-instance lifecycle.
- Runtime v1.0 remains a narrow stable SPI; the composition root now registers llama.cpp
  and resolves it only for the verified fixed Rewrite route.
- Model/runtime/prompt identity is hidden from clients. Apps request capability keys and
  structured schemas.
- Engine process state is disposable. User content, prompts, transcripts, and Runtime
  sessions are never persisted.
- No inference-path module has network access. The future downloader remains the only
  permitted network boundary.
- The current ID-only `CapabilityPipeline`/`dev.echo` path is diagnostic compatibility
  code. Production capabilities use the committed exact-key Capability Framework.

## Open gates and risks

- Rewrite is merged; the real signed-pack path and fixed Router passed device validation.
  This structural validation does not make a language-quality claim.
- Representative 4 GB arm64 calibration remains required before a model capability ships.
- Tink 1.23.0 shrunk APK contribution must be measured before a user-facing engine
  release.
- Multi-step execution is structurally represented but not executable in the initial
  framework slice; the Coordinator initially accepts exactly one prompt step.
- Public logical conversations and multimodal bulk handles remain future additive work
  behind separate architecture gates.
- Keyboard implementation and validation belong to the separate Touvay Keyboard
  repository; this repository owns only Engine, SDK, and integration contracts.

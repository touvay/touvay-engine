# Touvay Engine Project Status

**Last updated:** 2026-07-13

## Current summary

The platform foundation is complete and tagged `foundation-v0.5`. Capability 1 Rewrite
is approved and committed as `53cb87d`. The Platform Validation milestone composes the
approved Model Manager, Runtime Registry, llama.cpp adapter, Execution Coordinator,
Binder, typed SDK, and engineering demo around a real signed offline model pack. It is
build-green, 28/28 Runtime device-TCK green, and real cross-process Rewrite validation
green. The project is at its public release-readiness checkpoint. Keyboard integration,
production UI, downloader, networking, retrieval, memory, and additional capabilities
remain unauthorized.

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

## Current milestone

**Platform Validation release-readiness checkpoint**

The engine now has a fixed production Rewrite route backed by an active, verified
catalog revision and the real llama.cpp Runtime registration. The SDK exposes only a
stable typed Rewrite API. The demo carries a demo-only public key and signed metadata,
while the pinned GGUF remains a local offline input. The engineering screen exercises
discovery, streaming, structured completion, cancellation, timing, and diagnostics.

No approved architecture or SPI was changed. Keyboard, production UI, networking,
retrieval, memory, and additional capabilities remain excluded.

## Upcoming milestones

These are sequencing candidates, not implementation authorization:

1. Representative 4 GB arm64 calibration before any model capability ships.
2. Keyboard integration only after a separate authorization and design review.
3. Additional capabilities only after their own scoped approval.

## Active ADRs

All ADR-001 through ADR-023 are accepted and active. The decisions most directly
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
- Public logical conversations, multimodal bulk handles, and keyboard integration remain
  future additive work behind separate architecture gates.

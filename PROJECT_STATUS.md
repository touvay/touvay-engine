# Touvay Engine Project Status

**Last updated:** 2026-07-13

## Current summary

The platform foundation is complete and tagged `foundation-v0.5`. Runtime, Model
Manager, and Execution foundations are implemented and build-green. Capability Framework
Architecture v1.0 plus ADR-020/021 are approved. Capability Framework implementation is
complete and in final engineering review. No user-facing capability, keyboard integration, UI, downloader,
or networking work is authorized in this milestone.

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

## Current milestone

**Capability Framework implementation**

Implementation is complete: production SPI, exact registration/discovery and execution
lookup, semantic plans, typed prompt recipes, bounded protobuf-lite asset verification,
exact-revision asset access, generic execution adapter, and the 20-check Capability TCK
with two sabotage tests. Final repository validation and engineering review are active.

Authorized scope:

- Capability SPI;
- exact-key registration and discovery;
- PromptRecipe abstraction;
- semantic, ordered ExecutionPlan abstraction;
- bounded prompt asset loading and verification;
- Capability TCK; and
- reusable capability test harness.

Explicit exclusions:

- rewrite, grammar, translation, summarization, or other user-facing capabilities;
- production prompt content;
- keyboard integration, UI, or engine routing policy;
- downloader or networking; and
- unreviewed Runtime SPI, SDK, or AIDL changes.

## Upcoming milestones

These are sequencing candidates, not implementation authorization:

1. Capability Framework engineering review and merge checkpoint.
2. First capability contract/design, including wire schema, SDK facade, prompt recipe,
   eligible model packs, quality evaluation, and privacy review.
3. First capability implementation only after separate approval.
4. Representative 4 GB arm64 calibration before any model capability ships.
5. Keyboard integration only after capability/runtime behavior is separately approved.

## Active ADRs

All ADR-001 through ADR-021 are accepted and active. The decisions most directly
governing current work are:

- ADR-004: structured capability APIs, never prompt tunnels;
- ADR-009: additive capability schema versioning and discovery;
- ADR-014: public capability wire schemas belong to `touvay-contract`;
- ADR-015: Runtime Registry resolution and exact model revision/profile identity;
- ADR-017: immutable request plan, attempt ownership, retry, and terminal semantics;
- ADR-018: bounded credit-based streaming;
- ADR-019: request-attempt Runtime sessions and client-owned conversations;
- ADR-020: production Capability SPI, ordered semantic plans, and exact-key registry;
  and
- ADR-021: typed prompt recipes and frozen signed-asset binding.

The canonical ADR list is maintained in `docs/specs/README.md`.

## Current architectural state

- Public transport is contract v2 Binder/AIDL with additive negotiation and bounded
  streaming credits; v1 transaction IDs remain frozen.
- `engine-core` is pure JVM and owns request orchestration, scheduling, terminal
  arbitration, and the internal Capability SPI boundary.
- `engine-models` owns verified immutable packs, transactional storage, catalog,
  reference counting, Runtime Registry resolution, and loaded-instance lifecycle.
- Runtime v1.0 remains a narrow stable SPI; llama.cpp is production-adapter complete but
  intentionally not routed to a user capability.
- Model/runtime/prompt identity is hidden from clients. Apps request capability keys and
  structured schemas.
- Engine process state is disposable. User content, prompts, transcripts, and Runtime
  sessions are never persisted.
- No inference-path module has network access. The future downloader remains the only
  permitted network boundary.
- The current ID-only `CapabilityPipeline`/`dev.echo` path is diagnostic compatibility
  code. Production capabilities use the exact-key Capability Framework after this
  milestone is reviewed.

## Open gates and risks

- Capability Framework must pass its TCK, full build, API compatibility, and dependency
  rules before the first capability design begins.
- Representative 4 GB arm64 calibration remains required before a model capability ships.
- Tink 1.23.0 shrunk APK contribution must be measured before a user-facing engine
  release.
- Multi-step execution is structurally represented but not executable in the initial
  framework slice; the Coordinator initially accepts exactly one prompt step.
- Public logical conversations, multimodal bulk handles, and keyboard integration remain
  future additive work behind separate architecture gates.

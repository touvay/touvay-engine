# Touvay Engine

A privacy-first, offline AI runtime platform for Android. Applications request
**capabilities** (rewrite, proofread, summarize, translate, …); the engine resolves each
request to a model and inference runtime appropriate for the device. Apps never depend on
models, runtimes, prompts, or hardware details.

**Status:** Platform Foundation v0.5, Runtime v1.0, Model Manager Slices 1–4,
Execution Engine, Capability Framework v1, and Context Architecture v1 are complete.
Capability 1 `text.rewrite@1` is implemented, build-green, and ready for merge review.
Keyboard, UI, networking, retrieval, and memory remain out of scope.

> **AI coding agents:** read [AGENTS.md](AGENTS.md) first — it replaces repo
> exploration (module map, rules, commands, known gotchas). `CLAUDE.md` points there.

## Architecture

The approved architecture lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and is the
source of truth; major decisions are recorded as ADRs in §20. In one paragraph: a thin
async **SDK** speaks a versioned **AIDL contract** to an **engine process** (embedded in
the host app today, promotable to a shared engine app later) containing a pure-Kotlin
core (capability router, scheduler), capability pipelines, a model manager for signed
data-only model packs, and a runtime SPI behind which llama.cpp, LiteRT-LM, and others
plug in.

## Modules

| Module | What it is |
|---|---|
| `contract/touvay-contract` | AIDL + protobuf wire contract (most stable artifact) |
| `sdk/touvay-sdk` | Public client API (Kotlin coroutines/Flow) |
| `engine/engine-core` | Pure-JVM request routing/execution core |
| `engine/engine-models` | Offline pack verification, transactional storage, catalog/leases, Runtime Registry resolution, and instance lifecycle (Task 3 Slices 1–4) |
| `engine/engine-service` | Bound service hosting the engine in the `:touvay` process |
| `capabilities/capability-tck` | Capability Framework executable conformance kit |
| `capabilities/capability-rewrite` | Production `text.rewrite@1` plugin |
| `runtime/runtime-api` | Runtime SPI |
| `runtime/runtime-tck` | Runtime v1.0 executable conformance specification |
| `runtime/runtime-llamacpp` | Production llama.cpp adapter, pinned to b5199; not engine-wired |
| `apps/demo` | Demo client + cross-process instrumented tests |
| `apps/benchmark` | Production-adapter benchmark host (`:spike` process name retained for compatibility, JSON results) |

Module dependency rules (ARCHITECTURE.md §8) are enforced by `./gradlew checkDependencyRules`,
which runs as part of `check`/`build`.

## Building

```
./gradlew build          # assemble + unit tests + lint + dependency rules
./gradlew :apps:demo:connectedDebugAndroidTest   # cross-process test (device required)
```

Requires JDK 17 and the Android SDK (platform 35).

## Privacy posture

Offline by default; no telemetry of any kind; user content is never logged or persisted;
diagnostics stay on device unless the user manually exports them (ADR-013). The model
downloader — not yet present — will be the only networked component.

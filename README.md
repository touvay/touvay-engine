# Touvay Engine

A privacy-first, offline AI runtime platform for Android. Applications request
**capabilities** (rewrite, proofread, summarize, translate, …); the engine resolves each
request to a model and inference runtime appropriate for the device. Apps never depend on
models, runtimes, prompts, or hardware details.

**Status:** walking skeleton — the SDK ↔ engine contract works end to end over Binder
(diagnostic `dev.echo` capability); no inference runtimes are wired yet.

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
| `engine/engine-service` | Bound service hosting the engine in the `:touvay` process |
| `runtime/runtime-api` | Runtime SPI |
| `runtime/runtime-llamacpp-spike` | **Spike (isolated)**: llama.cpp behind the SPI; see [docs/spikes/llamacpp-feasibility.md](docs/spikes/llamacpp-feasibility.md) |
| `apps/demo` | Demo client + cross-process instrumented tests |
| `apps/benchmark` | Spike benchmark host (`:spike` process, JSON results) |

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

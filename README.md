# Touvay Engine

Touvay Engine is a privacy-first, offline AI runtime platform for Android. Applications
request typed capabilities such as `text.rewrite`; the engine owns model selection,
signed model packs, prompt assets, scheduling, and inference runtimes.

## Status

Platform Foundation v0.5 and end-to-end Platform Validation are complete. The validated
path runs a real, locally signed Rewrite-compatible model pack through the typed SDK,
Binder service, Model Manager, Runtime Registry, Execution Engine, and the production
llama.cpp adapter. Streaming, cancellation, structured results, and diagnostics are
exercised by the engineering demo and integration tests.

Runtime v1.0, Model Manager Slices 1–4, the Execution Engine, Capability Framework v1,
Context Architecture v1, and Rewrite v1 are complete. Keyboard integration, production
UI, networking, retrieval, memory, and additional capabilities are not part of this
release.

AI coding agents should read [AGENTS.md](AGENTS.md) before making changes.

## Architecture and specifications

- [Architecture](docs/ARCHITECTURE.md)
- [Canonical specification index](docs/specs/README.md)
- [Project status](PROJECT_STATUS.md)
- [Changelog](CHANGELOG.md)

The SDK remains capability-driven: applications do not see runtimes, model files,
prompts, the scheduler, or Model Manager internals.

## Modules

| Module | Responsibility |
|---|---|
| `contract/touvay-contract` | Versioned AIDL and protobuf wire contract |
| `sdk/touvay-sdk` | Stable typed Android client API |
| `engine/engine-core` | Execution orchestration and Capability SPI |
| `engine/engine-models` | Signed-pack verification, transactional storage, catalog, leases, and runtime instance ownership |
| `engine/engine-service` | Android composition root and Binder service |
| `capabilities/capability-tck` | Capability conformance kit |
| `capabilities/capability-rewrite` | Production `text.rewrite@1` capability |
| `runtime/runtime-api` | Runtime SPI |
| `runtime/runtime-tck` | Runtime v1.0 conformance kit |
| `runtime/runtime-llamacpp` | Production llama.cpp adapter, pinned to b5199 |
| `apps/demo` | Engineering validation client and cross-process tests |
| `apps/benchmark` | Production-adapter benchmark host |

Module dependency rules are enforced by `checkDependencyRules` and run as part of the
build.

## Build and validate

Requirements: JDK 17, Android SDK platform 35, NDK 27.2.12479018, and CMake 3.22.1.

```shell
./gradlew build
./gradlew apiCheck checkDependencyRules
./gradlew :runtime:runtime-tck:test
./gradlew :capabilities:capability-tck:test
./gradlew :capabilities:capability-rewrite:testDebugUnitTest
```

Device validation requires a locally provisioned GGUF model. Model weights and private
signing material are deliberately excluded from Git. See the [model setup](models/README.md)
and [demo pack workflow](docs/demo/demo-pack.md).

For the public Rewrite API and a compact Android example, see the
[SDK quick start](sdk/touvay-sdk/README.md).

## Privacy and security

- Offline by default; there is no telemetry.
- User content is never logged or persisted.
- Model packs and prompt assets are authenticated before activation.
- The repository contains only the demo public trust key and signed public metadata;
  private signing keys and model weights are never versioned.
- Networking is not implemented in this milestone.

Licensed under the [Apache License 2.0](LICENSE).

# AGENTS.md — Touvay Engine agent context

Universal context file for ANY coding agent (Claude, Codex, Cursor, Gemini, …).
Purpose: **replace repo exploration**. Read this first; open deeper docs only via the
"what to read when" table. Tool-specific files (`CLAUDE.md`) just point here.
Keep this file current when structure/rules change — it loses to `docs/ARCHITECTURE.md`
on any conflict.

## Project in one paragraph

Touvay Engine is a privacy-first, **offline** AI runtime platform for Android
(first client: Touvay Keyboard). Apps request **capabilities** (`text.rewrite`,
`dev.echo`, …) via a thin SDK over a versioned Binder/AIDL contract; an engine process
(embedded `:touvay` process now, promotable to a shared engine app later) routes each
request to a (model pack, runtime, execution plan). Apps never see models, runtimes,
prompts, or hardware.

**Status:** Platform Foundation v0.5 is tagged and build-green. Runtime v1.0, Model
Manager Slices 1–4, Execution Engine, Capability Framework v1, Context Architecture,
and Rewrite v1 are complete; Rewrite is committed as `53cb87d`. Platform Validation is
approved and published at `platform-v1-validated`: the real signed offline Rewrite pack,
Runtime Registry, llama.cpp, Execution Coordinator, Binder, typed SDK, and demo are green
end to end. The platform specifications and Keyboard Architecture v1.0 are Stable;
semantic changes require an ADR. The keyboard is maintained in a separate sibling
repository and must integrate only through the validated SDK; no keyboard product code
belongs in this repository. General Context Provider implementation, networking,
retrieval, memory, and additional capabilities remain out of scope.

## Non-negotiable rules

1. `docs/ARCHITECTURE.md` is the source of truth. **Never silently change
   architecture** — propose (issue → trade-offs → alternatives → recommendation),
   then wait for approval. Record accepted changes as ADRs (§20).
2. Privacy: offline by default; **no telemetry**; no network code anywhere except the
   (future) model downloader; **never log or persist user content** — error messages
   must be content-free (payload bytes never reach logs or exception messages).
3. Public APIs (`touvay-contract`, `touvay-sdk`, `runtime-api`) evolve **additively
   only**; KDoc required; guarded by BCV (`apiCheck` in `check`). Run `gradlew apiDump`
   only as part of a reviewed API change.
4. Tests are mandatory, including cancellation, streaming, and failure paths.
   "Code without tests is incomplete."
5. Per-task workflow: Understand → Design review → Implement (production-ready, no
   placeholders) → Validate (build + tests green) → Engineering review (architecture
   changed? deps added? APIs changed? memory/startup impact? security? ADR needed?).
6. Kotlin preferred; C/C++ only inside runtime adapters behind narrow JNI. Integrate
   proven tech (llama.cpp, LiteRT, protobuf, coroutines, AIDL); don't rebuild it.
7. Measure before optimizing; state bottleneck/trade-off/expected impact.

## What to read when (token budget map)

| Task touches… | Read | Skip |
|---|---|---|
| Any code change | this file only, then the touched module | full ARCHITECTURE.md |
| Wire format / AIDL / protos | `contract/` sources + ARCHITECTURE.md ADR-003, ADR-009, ADR-014 | rest of doc |
| New capability | **`docs/capabilities/CAPABILITY_SPEC.md` (normative)** + ARCHITECTURE.md §9 §11; `CapabilityPipeline`/`EchoPipeline` are diagnostic compatibility code, not the production SPI | runtime adapter internals |
| Context / prompt inputs | `docs/context/CONTEXT_SPEC.md` + `docs/capabilities/CAPABILITY_SPEC.md` §10 | runtime adapter internals |
| Runtime / inference | **`docs/runtime/runtime-spi.md` (normative)** + `runtime-api`; TCK work → `docs/runtime/runtime-tck.md`; llama.cpp adapter → `docs/runtime/runtime-llamacpp-design.md` | full ARCHITECTURE.md |
| SDK surface | ARCHITECTURE.md §9 + `sdk/touvay-sdk` + `.api` dumps | engine internals |
| Model packs / downloads | `docs/model-manager/model-manager.md` (normative) + ADR-011/015/016 + `engine/engine-models/README.md` | — |
| Scheduling / memory / threading | ARCHITECTURE.md §14 | — |
| Security/privacy question | ARCHITECTURE.md §16 + ADR-013 | — |
| Process topology / IPC design | ARCHITECTURE.md §5 §6 ADR-001 ADR-002 | — |

## Module map (dependency edges enforced by `checkDependencyRules`)

| Module | Role | May depend on (repo) |
|---|---|---|
| `contract/touvay-contract` | AIDL surface + `@Parcelize` envelope + proto payload schemas (owns ALL wire schemas, ADR-014). Most stable artifact | nothing |
| `sdk/touvay-sdk` | Public client API: `Touvay.connect`, `TouvayClient`, Flow streaming, `TouvayException`, binder-death handling | contract |
| `engine/engine-core` | Pure JVM: execution orchestration plus production Capability SPI, exact registry/adapter, semantic plans, typed recipes, and bounded prompt assets | runtime-api |
| `capabilities/capability-tck` | Pure-JVM Capability SPI conformance kit and deterministic harness; 20 mandatory checks plus sabotage self-tests | engine-core, runtime-api |
| `capabilities/capability-rewrite` | Production `text.rewrite@1`: contract parsing, semantic plan/recipe, signed-pack reference asset, structured streaming/final assembly, and TCK | contract, engine-core |
| `engine/engine-models` | Pure JVM, offline Task 3 boundary: pack verification, transactional storage, catalog/leases, Runtime Registry resolution, and cached `ModelInstance` ownership. No sessions, inference, scheduler, routing, downloader, or network | runtime-api |
| `engine/engine-service` | Bound service in `:touvay` process; binder impl; same-app UID policy; composition root; diagnostic echo plus signed-pack production Rewrite routing | contract, capability-rewrite, engine-core, engine-models, runtime-api, runtime-llamacpp |
| `runtime/runtime-api` | Runtime SPI (`InferenceRuntime`/`ModelInstance`/`InferenceSession`, `tokenize`, `CancelSignal`); normative spec `docs/runtime/runtime-spi.md` | nothing |
| `runtime/runtime-tck` | Conformance kit (pure JVM): `AbstractRuntimeTck` + sabotage self-test; adapters conform via one androidTest subclass | runtime-api |
| `runtime/runtime-llamacpp` | Production llama.cpp adapter (pinned b5199; abort-callback cancellation); registered only by the engine-service composition root | runtime-api |
| `apps/demo` | Demo client + cross-process instrumented tests | sdk, engine-service |
| `apps/benchmark` | Benchmark host (`:spike` process, JSON results) targeting the production adapter | runtime-api, runtime-llamacpp |
| `build-logic` | Convention plugins + `DependencyRulesPlugin` (new modules MUST be added to its allowlist) | — |

New modules: add to `settings.gradle.kts`, `DependencyRulesPlugin`, and (if not public
API) `apiValidation.ignoredProjects` in root `build.gradle.kts`.

## Commands & environment

```
./gradlew build                      # assemble + unit tests + lint + dep rules + apiCheck
./gradlew :engine:engine-models:test # Task 3 verifier, storage, catalog, and lifecycle suite
./gradlew :capabilities:capability-tck:test # Capability framework conformance self-test
./gradlew :capabilities:capability-rewrite:testDebugUnitTest # Rewrite TCK + capability tests
./gradlew :apps:demo:connectedDebugAndroidTest      # cross-process tests (device/emulator)
./gradlew apiDump                    # ONLY as a reviewed API change
scripts/fetch-llamacpp.ps1           # pinned b5199 -> production adapter third_party/ (gitignored)
scripts/fetch-model.ps1              # Qwen2.5-0.5B Q4_K_M, hash-verified -> models/ (gitignored)
```

This machine: Windows 11; JDK 17 (Adoptium, on PATH); Android SDK at
`%LOCALAPPDATA%\Android\Sdk` (platforms 34-36, NDK 27.2.12479018, CMake 3.22.1);
Gradle 8.14.3 wrapper; AGP 8.7.3; Kotlin 2.1.0; AVD `Medium_Phone_API_36.1`
(x86_64, data partition resized to 12G). No physical device attached as of 2026-07-12.

## Conventions

- Kotlin coroutines/Flow; SDK is async-only and main-safe; binder IPC never on main.
- `explicitApi()` on public modules; KDoc on every public declaration.
- No `data class` for ByteArray-carrying types (misleading equality).
- Comments state constraints the code can't show — never narrate the code.
- Token/payload bytes cross JNI as `ByteArray`, never `NewStringUTF` (BPE pieces can
  split UTF-8 code points; CheckJNI aborts on invalid UTF-8).
- Deterministic tests: virtual time (`runTest` + injected dispatcher) in engine-core;
  latches + real threads for binder tests; golden/greedy decoding for future model tests.

## Hard-earned gotchas (do not rediscover)

- **Kotlin plugins already on build-logic classpath**: apply `org.jetbrains.kotlin.plugin.parcelize`
  in modules **by id without version**, or resolution fails.
- **JUnit4 test methods must return Unit**: write `= runBlocking<Unit> { … }` —
  a trailing `assertIs`/`assertEquals` can leak a return type and invalidate the class.
- **`runBlocking` is single-threaded**: `Thread.sleep` in a test body starves `async`
  child coroutines — synchronize on latches/first-emission, run collectors on
  `Dispatchers.Default`.
- **AGP `connectedAndroidTest` uninstalls the APK afterwards**, deleting
  `/sdcard/Android/data/<pkg>/` (pushed models, results). For state-preserving runs use
  `adb shell am instrument -w -e <args> <pkg>.test/androidx.test.runner.AndroidJUnitRunner`.
- **Every APK-producing module needs `ndkVersion`** or native libs ship unstripped
  (25 MB instead of 3.3 MB) with only a silent warning.
- **16 KB pages**: keep `-Wl,-z,max-page-size=16384` in native links (NDK r27 doesn't
  default it); verify with `llvm-readelf -l` (LOAD align 0x4000).
- **llama.cpp API churns fast**; the spike pins tag `b5199` (`ced44be`). Bumping the pin
  is a reviewed change and must re-run the (future) runtime-tck.
- Robolectric: pin `@Config(sdk = [34])`; set `ShadowBinder.setCallingUid(Process.myUid())`
  in `@Before` for binder tests.
- PowerShell 5.1 on this machine: no `&&`; `Set-Content` writes BOM — prefer the
  agent's file-write tooling over shell redirection for source files.

## Open items / approval gates

- Representative 4 GB arm64 calibration remains required before a model capability ships.
- **Gate:** Signed-pack Platform Validation is approved and published. Keyboard
  Architecture v1.0 is Stable; implementation belongs to the separate Touvay Keyboard
  repository. Additional capabilities, Router policy changes, networking, and follow-on
  engine milestones remain unauthorized.
- Measure Tink 1.23.0's shrunk APK contribution before a user-facing engine release;
  Slice 1 uses one pure-Java verifier path across API 29+.
- Deferred (additive): public logical-session facade; convention-plugin guard for
  `ndkVersion`.

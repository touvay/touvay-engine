# Touvay Engine — Architecture Design Document

**Status:** Approved — v1.0 (2026-07-11), amended at review: ADR-013 (local-only diagnostics).
Amended 2026-07-12: ADR-014 (schema ownership); spike-evidence refinements in §12/§14.3;
normative runtime specs split out to `docs/runtime/` (runtime-spi.md, runtime-tck.md).
**Audience:** Engine maintainers, SDK consumers, contributors
**Scope:** Architecture only. No production code until this document is approved.

---

## 1. Executive Summary

Touvay Engine is a privacy-first, offline AI runtime platform for Android. Applications request
**capabilities** (rewrite, proofread, summarize, translate, generate, describe, transcribe) and the
engine resolves each request to a concrete *(model pack, runtime, execution plan)* appropriate for
the device — the application never learns or cares which model ran.

The recommended architecture is a **contract-first, out-of-process engine**:

- A thin, async **SDK library** that client apps embed. It speaks a **versioned Binder/AIDL
  contract** and nothing else.
- An **engine process** that initially ships *inside* the host app (Touvay Keyboard) as a separate
  Android process, and can later be **promoted to a standalone shared "Touvay Engine" app** that
  many apps bind to — without changing the SDK contract.
- Inside the engine: a **pure-Kotlin core** (capability router, scheduler, budget manager) that
  depends only on abstractions; **capability pipelines** that own prompts/templates/post-processing;
  a **model manager** with signed, data-only model packs; and a narrow **Runtime SPI** behind which
  concrete runtimes (llama.cpp, LiteRT-LM, ONNX Runtime, ExecuTorch, and Android's AICore/ML Kit
  GenAI as a *delegating* backend) are interchangeable, compile-time plugins.

The three decisions that most determine whether this platform survives ten years:

1. **The IPC contract is the product.** Everything behind it is replaceable; the contract itself
   evolves only additively, with explicit version negotiation and capability discovery.
2. **Capabilities are typed, structured APIs — not prompt tunnels.** This is what lets a 4 GB phone
   serve `text.proofread` with a small task-specific model while a flagship serves it with an LLM,
   with zero client changes.
3. **Model packs are data, never code.** Runtimes are code and ship only with the engine. This one
   rule eliminates an entire class of security, Play-policy, and API-ossification failures.

---

## 2. Goals and Non-Goals

### Goals

| # | Goal | Architectural consequence |
|---|------|---------------------------|
| G1 | Offline by default | No network on the inference path; network confined to an isolated model-downloader component |
| G2 | Privacy-first | User text never persisted, never leaves the device, redacted from all logs/crash reports |
| G3 | Multiple models | Model packs + manifest-driven capability registry |
| G4 | Multiple runtimes | Runtime SPI with conformance test kit |
| G5 | 4 GB → flagship | Device tiering; same capability, different execution plans |
| G6 | Stable SDK for any app | Contract-first AIDL boundary, additive evolution, feature detection |
| G7 | Hide models from apps | Capability-based API; engine owns prompts, templates, decoding |
| G8 | Modular, extensible | Compile-time plugin modules; strict dependency rules |
| G9 | OSS-friendly, 10-year maintainable | ADR discipline, conformance kits, small stable surfaces |

### Non-Goals (v1)

- **Cloud inference or hybrid fallback.** Explicitly out of scope; the contract leaves room for it
  (an execution plan is just another backend) but v1 ships nothing that opens a socket at inference
  time.
- **Third-party runtime plugins loaded at runtime.** Rejected for security and Play-policy reasons
  (see ADR-006). Forks can add runtimes at compile time.
- **iOS / desktop.** Deferred; see §20 for the trigger conditions that would justify extracting a
  cross-platform core.
- **Training or fine-tuning on device.** LoRA *loading* is planned for; training is not.

---

## 3. Assumptions Challenged

You asked for pushback. These are the places where the brief, taken literally, would produce a
worse system.

### 3.1 "Think of this as an Android system component" — you cannot be one, so don't design like one

Third-party code cannot run as an Android system service. The realistic analogue is Google Play
Services: an ordinary app that becomes *de facto* infrastructure through adoption. But copying that
model on day one fails on distribution: no app developer depends on an engine their users must
separately install, and no user installs an engine no app needs yet. **Resolution:** ship
library-first, but put the out-of-process seam and versioned contract in place from day one, so the
central-app promotion later is a deployment change, not an architecture change (§6, ADR-001).

### 3.2 "Offline by default" collides with model distribution

Useful models are 0.5–4 GB. They must arrive somehow: Play Asset Delivery caps, CDN downloads,
OEM preloads, or user-imported files. *Offline inference* is achievable; *offline acquisition* is
not, unless models ship in the APK (severely limiting size/choice). The architecture must treat
model acquisition as a first-class, consent-gated, resumable, integrity-verified subsystem — not an
installer afterthought (§13). The privacy guarantee is then precise: **the inference path is
offline; the acquisition path uses the network only with explicit consent, only to fetch signed
artifacts, and never carries user data.**

### 3.3 "Support multiple runtimes" invites the wrong abstraction

The classic failure is a universal `Runtime` interface flattened to the lowest common denominator,
which forfeits exactly the features that make each runtime worth having (LiteRT-LM's prefix
caching, llama.cpp's grammar-constrained decoding, ExecuTorch's NPU backends). The SPI therefore
has a small mandatory core plus **typed optional feature interfaces** that the router can detect
and exploit (§12). Abstraction lives at the *capability* level; the runtime layer is deliberately
leaky-by-design through typed features, never through stringly-typed option maps.

### 3.4 "4 GB RAM baseline" needs honest arithmetic

On a 4 GB device, the OS + IME + foreground app leave roughly 1–1.5 GB realistically claimable, and
the low-memory killer will reclaim it under pressure. A 1B-parameter Q4 model is ~0.6–0.8 GB of
weights plus KV cache. Consequences the architecture must embrace rather than paper over:

- **One resident model instance** at the baseline tier; concurrent multi-model is a flagship
  feature.
- Some capabilities on low tiers should be served by **small task-specific models** (e.g., a
  compact grammar-correction model) rather than a general LLM — which only works because
  capabilities are structured APIs (§3.6).
- **Engine process death is normal, not exceptional.** Sessions must be reconstructible from
  client-held state (§11.4).
- The dominant *dynamic* cost is KV cache, so context limits are per-tier policy, not constants.

### 3.5 The keyboard is the most hostile host imaginable — that's a feature

An IME must never jank, must never crash (the user literally cannot type), gets killed and
respawned freely, and carries maximal privacy expectations. Two consequences:

- **Inference must not run in the IME process.** A native crash or OOM in a runtime must never
  take down typing. This forces the out-of-process design even before any cross-app story.
- **No per-keystroke LLM calls.** Latency and thermals rule it out; recent guidance from teams
  shipping on-device LLMs is consistent that heat, not RAM, is the binding constraint, and the
  viable UX is *bursty*: short, explicit, user-invoked requests. Next-word prediction stays in the
  IME with classical/tiny models; the engine serves invoked transformations (rewrite selection,
  proofread message, summarize thread).

### 3.6 "Capabilities, not models" — agreed, but close the back door

If the only rich API is a generic `generate(prompt)`, every app will tunnel rewriting, translation,
and everything else through it, and the model-hiding abstraction collapses: prompts written against
one model's behavior break when the engine swaps models. Therefore: capability APIs are **typed and
structured** (proofread returns correction spans; translate takes a language pair; rewrite takes a
tone enum), the engine owns all prompt templates, and the generic `text.generate` capability exists
but is explicitly documented as model-agnostic (engine-owned system preamble, no model-identity
leakage, output-schema support instead of prompt tricks).

### 3.7 "Plugin architecture" must not mean dynamic code loading

`DexClassLoader`-style third-party plugins are a security hole, a Google Play policy violation
(remotely loaded code), and an API-ossification trap (every plugin interface becomes un-changeable
public API). The plugin system is therefore: **code plugins at compile time** (Gradle modules,
discovered via a registry at the composition root), **data packs at runtime** (models, templates —
signed, verified, never executed) (§15, ADR-006).

### 3.8 Should Touvay exist at all? Alternatives considered honestly

| Alternative | What it offers | Why it's insufficient alone | Role in Touvay |
|---|---|---|---|
| **ML Kit GenAI APIs / AICore (Gemini Nano)** | Google-managed on-device models; summarize/proofread/rewrite/prompt/image-description APIs; zero RAM cost to the app; Nano 4 arriving on flagships | Premium-SoC devices only (optimized Snapdragon/Dimensity/Tensor); closed models, no control or auditability; capability set fixed by Google; not OSS; unavailable on exactly the 4 GB devices Touvay targets | **A backend, not a foundation** — `runtime-aicore` delegates matching capabilities to it when present and policy allows (ADR-008) |
| **MediaPipe LLM Inference API** | Easy Android LLM integration | In maintenance mode; Google directs new work to LiteRT-LM | Not used; LiteRT-LM adapter instead |
| **llama.cpp directly in the app** | Maximum model freedom (GGUF ecosystem) | It's a runtime, not a platform: no capability layer, no scheduling, no model management, unstable API surface to track | One runtime adapter among several |
| **ExecuTorch** | Clean PyTorch→mobile path; strongest NPU story (QNN, MediaTek, XNNPACK) | Same — a runtime, not a platform | Planned runtime adapter |
| **MLC-LLM / others** | Compiler-driven performance | Smaller ecosystem, heavier toolchain | Possible future adapter; nothing in the architecture precludes it |

**Verdict:** nothing existing provides the *platform* layer — capability routing, model lifecycle,
scheduling, device tiering, a stable multi-app SDK, and privacy guarantees — which is precisely the
layer Touvay builds. Touvay should **not** build inference kernels; it orchestrates existing
runtimes. That is a defensible, maintainable slice.

---

## 4. Design Principles

1. **Contract-first.** The AIDL contract and SDK surface are designed, reviewed, and frozen before
   implementations; everything behind them is replaceable.
2. **Capabilities over models.** Apps declare intent; the engine chooses execution. Typed,
   structured capability APIs; engine-owned prompts.
3. **Ports and adapters (hexagonal core).** `engine-core` is pure Kotlin/JVM with zero Android
   dependencies; Android, runtimes, and storage are adapters behind interfaces. Dependency
   inversion everywhere a boundary exists.
4. **Data/code separation.** Model packs are signed data. Code plugins compile in.
5. **Design for death.** The engine process can be killed at any instant; every stateful feature
   must define its reconstruction story.
6. **Additive evolution.** Public surfaces (SDK, AIDL, manifest schema, SPI) change by addition +
   feature detection, never by breaking. Deprecation windows are measured in years.
7. **Budgets are first-class.** Memory, latency, and thermal budgets are explicit inputs to
   routing and scheduling, not tuning afterthoughts.
8. **Test the contract, not the implementation.** Conformance kits (runtime TCK, capability golden
   suites, SDK fakes) are shipped artifacts, so forks and contributors can prove compatibility.
9. **Composition over inheritance; no framework magic.** Manual DI at a single composition root;
   no reflection-based containers in the engine process.
10. **Boring where possible.** Novelty budget is spent on the capability/routing layer; transport,
    serialization, and build tooling stay conventional.

---

## 5. Architectural Approaches Compared

### Approach A — In-process library (ML Kit style)

Each app embeds the whole engine and models in its own process.

### Approach B — Central engine app from day one (Play Services style)

A standalone APK all clients bind to.

### Approach C — Out-of-process engine, embedded first, promotable later *(recommended)*

The engine runs in a **separate process inside the host app** (`android:process=":touvay"`),
behind the same AIDL contract a future standalone engine app would expose. The SDK discovers the
best available engine: a signature-pinned standalone engine app if installed, else the embedded
one.

### Trade-off matrix

| Criterion | A: In-process | B: Central app day one | C: Embedded process → promotable |
|---|---|---|---|
| Distribution friction | None | **Fatal** (users must install a second app before any app benefits) | None at launch; opt-in later |
| Crash isolation (IME survives native crash) | ✗ | ✓ | ✓ |
| Memory attribution/kill behavior | Host app pays; IME with 1 GB heap is killed constantly | Engine app pays | Engine process pays; host stays lean |
| RAM/disk sharing across apps | ✗ (N copies) | ✓ | ✗ at launch → ✓ after promotion |
| IPC overhead | None | Binder per request | Binder per request (negligible vs. inference cost; ~µs vs. ~s) |
| Update cadence | Per-app releases | Independent | Per-app → independent after promotion |
| Forces API discipline | ✗ (internal calls ossify accidentally) | ✓ | ✓ from day one |
| Complexity now | Low | High | Medium |
| OSS forkability | High | Medium (who runs the central app?) | High |

**Why not A:** it fails the two hardest requirements — crash isolation for the IME and the eventual
multi-app sharing story — and, worse, it lets the SDK boundary decay into internal calls that are
brutal to break apart later.

**Why not B:** the distribution chicken-and-egg kills adoption, and it forces solving cross-app
consent, quotas, and engine-app governance before a single user types a word.

**Why C:** it buys A's distribution story and B's isolation and discipline. The cost — designing
the AIDL contract early — is not really a cost: that design work is the heart of the platform and
must happen anyway. This is the recommendation. (ADR-001)

---

## 6. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  CLIENT APP PROCESS      (Touvay Keyboard IME; later any app)        │
│                                                                      │
│   App code ──► touvay-sdk (thin, async, feature-detecting)           │
│                  │  engine discovery: standalone engine app if       │
│                  │  present+signature-pinned, else embedded          │
└──────────────────┼───────────────────────────────────────────────────┘
                   │  Binder / AIDL  (versioned contract; SharedMemory
                   │  for bulk payloads: images, audio, long docs)
┌──────────────────▼───────────────────────────────────────────────────┐
│  ENGINE PROCESS   (":touvay" in host app  →  standalone app later)   │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ engine-service (Android)  — composition root                   │  │
│  │   binder impl · caller auth · quotas · session registry        │  │
│  │   lifecycle (bound service, idle shutdown)                     │  │
│  ├────────────────────────────────────────────────────────────────┤  │
│  │ engine-core (pure Kotlin, no Android)                          │  │
│  │   capability router · scheduler · budget manager ·             │  │
│  │   request state machine                                        │  │
│  ├──────────────────┬─────────────────────┬───────────────────────┤  │
│  │ capability       │ model manager       │ device adapter        │  │
│  │ pipelines        │  registry · packs   │  tiering · thermal ·  │  │
│  │  text / vision / │  install · verify   │  memory pressure      │  │
│  │  speech          │  load/unload cache  │  (Android APIs)       │  │
│  ├──────────────────┴─────────────────────┴───────────────────────┤  │
│  │ Runtime SPI  (small mandatory core + typed optional features)  │  │
│  ├──────────────┬──────────────┬─────────────┬────────────────────┤  │
│  │ runtime-     │ runtime-     │ runtime-    │ runtime-aicore     │  │
│  │ llamacpp     │ litert(-lm)  │ onnx /      │ (delegates to      │  │
│  │ (JNI→C++)    │              │ executorch  │  ML Kit GenAI)     │  │
│  └──────────────┴──────────────┴─────────────┴────────────────────┘  │
│         │ mmap'd weights, signed model packs (data only)             │
│  ┌──────▼──────────────┐   ┌───────────────────────────────────┐     │
│  │ model store (disk)  │   │ downloader (separate component;   │     │
│  │ app-private, signed │◄──│ Wi-Fi + consent; the ONLY         │     │
│  └─────────────────────┘   │ network-touching module)          │     │
│                            └───────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘
```

Key structural facts:

- **The SDK depends only on the contract module.** It contains no engine logic.
- **`engine-core` is pure JVM.** It can be unit-tested at full speed and, if ever needed, extracted
  to KMP with minimal surgery.
- **Runtimes are peers behind one SPI**; the composition root decides which are compiled in per
  build flavor (e.g., a minimal build with only llama.cpp).
- **The downloader is the only module with network access**, enforced by dependency rules, code
  review, and StrictMode network-detection in tests (§16).

---

## 7. Module Diagram and Responsibilities

```
                    ┌───────────────┐
                    │  touvay-sdk   │           (public API artifact)
                    └───────┬───────┘
                            │
                    ┌───────▼───────┐
                    │ touvay-contract│          (AIDL + wire schemas + version negotiation)
                    └───────┬───────┘
            ┌───────────────┼────────────────┐
            │               │                │
    ┌───────▼──────┐ ┌──────▼───────┐ ┌──────▼────────┐
    │engine-service│ │ engine-core  │ │ testing kits  │
    │ (Android)    │►│ (pure Kotlin)│ │ sdk-fakes,    │
    └───┬──────────┘ └──────┬───────┘ │ runtime-tck   │
        │  composition      │ ports   └───────────────┘
        │  root only        │
  ┌─────▼─────┬─────────────▼──┬────────────────┬──────────────┐
  │capability-│ engine-models  │ engine-device  │ runtime-api  │
  │pipelines  │ (packs,store,  │ (tier,thermal, │ (SPI)        │
  │(text,     │  integrity,    │  memory)       └──────┬───────┘
  │ vision,   │  downloader)   │                       │
  │ speech)   └────────────────┘        ┌──────────┬───┴─────┬──────────┐
  └───────────┘                         │runtime-  │runtime- │runtime-  │
                                        │llamacpp  │litert   │aicore ...│
                                        └──────────┴─────────┴──────────┘
```

| Module | Responsibility | Depends on |
|---|---|---|
| `touvay-sdk` | Public client API: connection/discovery, capability facades, Flow streaming, error mapping, reconnect policy, Java interop layer | `touvay-contract` only |
| `touvay-contract` | AIDL interfaces, wire schemas (protobuf-lite payloads), version negotiation types, error codes. **The most stable artifact in the repo.** | nothing |
| `engine-service` | Android `Service`, binder implementation, caller authentication, per-client quotas, session registry, process lifecycle, **composition root** (the only place concrete implementations are wired) | everything below |
| `engine-core` | Capability router, scheduler, budget manager, request state machine. Pure Kotlin; all effects behind ports | `runtime-api`, capability + model + device *interfaces* |
| `capability-*` | One module per domain (text, vision, speech). Owns prompt templates, tokenizer-safe truncation policy, output parsing/validation, structured result assembly, per-capability quality eval definitions | `runtime-api`, `engine-models` interfaces |
| `engine-models` | Pack registry, manifest parsing/validation, signature + digest verification, storage layout, install/uninstall, load/unload with refcounting, LRU/idle eviction, downloader (isolated) | device interfaces |
| `engine-device` | Device tier detection, memory pressure (`onTrimMemory`, `ActivityManager`), thermal (`PowerManager` thermal status/headroom), accelerator probing | Android SDK |
| `runtime-api` | The Runtime SPI: mandatory core + typed feature interfaces + conformance-testable semantics | nothing |
| `runtime-<impl>` | One adapter per runtime; owns its native libs (16 KB-page-aligned — a hard Play requirement for API 35+ targets since Nov 2025), JNI bridge, cancellation plumbing | `runtime-api` |
| `runtime-tck` | Conformance test kit every runtime adapter must pass | `runtime-api` |
| `sdk-fakes` | `FakeTouvay` in-memory engine for app developers' tests | `touvay-sdk` |
| `apps/keyboard`, `apps/demo`, `apps/engine-app` | Clients and the future standalone engine host | `touvay-sdk` (+ engine modules only in hosts) |

---

## 8. Dependency Rules

Enforced in CI (Gradle module graph check / Konsist):

1. `touvay-contract` depends on **nothing** in the repo.
2. `touvay-sdk` depends **only** on `touvay-contract`. If an SDK feature needs engine code, the
   design is wrong.
3. `engine-core` has **no Android dependency** (verified by compiling against JVM-only Kotlin).
4. Nothing depends on a concrete `runtime-*` module **except** `engine-service` (composition
   root). Same for concrete capability pipelines.
5. `runtime-*` modules depend only on `runtime-api` (+ their own native code). They may not see
   the model registry, scheduler, or each other.
6. Only `engine-models`' downloader component may declare network dependencies; inference-path
   modules are checked (StrictMode `detectNetwork` in instrumentation, dependency lint) to be
   socket-free.
7. Client apps depend only on `touvay-sdk` (+ `sdk-fakes` in tests).
8. All cross-boundary types are owned by the lower layer (`runtime-api` owns SPI types;
   `touvay-contract` owns wire types); no leaking runtime types up through the router.

---

## 9. Public SDK Design

Kotlin-first, coroutine-native, Java-interop layer on top. Everything asynchronous; nothing on the
SDK surface can block the main thread by construction (no synchronous inference entry points at
all).

```kotlin
// Connection & discovery ------------------------------------------------
val touvay: TouvayClient = Touvay.connect(context)   // suspend: binds, negotiates versions
// TouvayClient is AutoCloseable; SDK auto-reconnects on binder death (see §11.4)

interface TouvayClient : AutoCloseable {
    suspend fun capabilities(): Map<CapabilityId, CapabilityStatus>
    fun rewriter(): Rewriter
    fun proofreader(): Proofreader
    fun summarizer(): Summarizer
    fun translator(): Translator
    fun generator(): Generator            // model-agnostic generic text capability
    fun models(): ModelController         // install/ensure flows (consent handled by client UI)
}

sealed interface CapabilityStatus {
    data object Ready : CapabilityStatus
    data class DownloadRequired(val approxBytes: Long) : CapabilityStatus
    data class DeviceNotSupported(val reason: String) : CapabilityStatus
    data object DisabledByPolicy : CapabilityStatus
}

// A structured capability facade (note: no prompts, no model names) -----
interface Rewriter {
    suspend fun rewrite(text: String, options: RewriteOptions = RewriteOptions()): RewriteResult
    fun rewriteStreaming(text: String, options: RewriteOptions = RewriteOptions()): Flow<TextDelta>
}

data class RewriteOptions(
    val tone: Tone = Tone.Neutral,
    val length: LengthTarget = LengthTarget.Similar,
    val locale: Locale? = null,
    val priority: Priority = Priority.Interactive,
    val coalesceKey: String? = null,   // new request with same key cancels the older one
)

// Proofread returns structure, not a rewritten blob ---------------------
data class ProofreadResult(
    val corrections: List<Correction>,   // span + replacement + category
    val stats: RequestStats,             // ttftMillis, tokens, modelTier — no model identity
)

// Errors: sealed, exhaustive, retryability explicit ----------------------
sealed class TouvayException : Exception() {
    class CapabilityUnavailable(val status: CapabilityStatus) : TouvayException()
    class EngineBusy(val retryAfterMillis: Long) : TouvayException()          // retryable
    class InputTooLarge(val maxUnits: Int) : TouvayException()
    class EngineDisconnected() : TouvayException()                            // retryable
    class RequestCancelled() : TouvayException()
    class InternalEngineError(val incidentId: String) : TouvayException()     // no details leak
}
```

Design rules for the surface:

- **Feature detection, never version checks.** Apps branch on `CapabilityStatus`, not SDK version.
  New capabilities appear as new facades + new `CapabilityId`s; old clients simply don't see them.
- **Structured results** wherever the domain permits (correction spans, translation segments,
  summary with optional key points) so backends can change class entirely.
- **Streaming is `Flow`**; cancellation of the collecting coroutine propagates to the engine and
  from there to the native runtime within one token boundary.
- **`RequestStats` deliberately excludes model identity** — apps get tier + timing, preventing
  accidental app-side coupling to specific models.
- **Consent UX belongs to the client, policy to the engine**: `models().ensure(capabilityId)`
  returns a `Flow<InstallProgress>` and requires that the client has shown a download consent step;
  the engine enforces Wi-Fi/metered policy and integrity regardless of what the client claims.
- Binary compatibility is enforced with Kotlin's binary-compatibility-validator; the SDK is
  semver'd; nothing public is exposed accidentally (explicit API mode).

---

## 10. IPC Contract (`touvay-contract`)

**Transport:** Binder via bound service — Android-native security (caller UID/PID), lifecycle
integration, zero extra daemons. Alternatives rejected: `Messenger` (untyped, no streaming
ergonomics), gRPC over Unix domain sockets (extra runtime, no Binder security context, worse
lifecycle), `ContentProvider` (wrong shape). (ADR-002)

**Envelope typed by AIDL, payloads by protobuf-lite** (ADR-003): AIDL defines the small, stable
call surface; each capability's request/response payload is a versioned proto message carried as
bytes. Rationale: Parcelable evolution across independently-updated client/engine builds is
brittle (field-order coupling), while proto gives forward/backward-compatible schema evolution and
a language-neutral definition the docs and TCK can share. Bulk payloads (images, audio, long
documents) travel via `SharedMemory`/`ParcelFileDescriptor`, never inline parcels (1 MB binder
transaction limit).

```aidl
interface ITouvayEngine {
    EngineHello negotiate(in ClientHello hello);       // versions, capability catalog etag
    List<CapabilityInfo> listCapabilities();
    void submit(in RequestEnvelope req, ITouvayResponseCallback cb);
    oneway void cancel(String requestId);
    ISession createSession(in SessionSpec spec);       // multi-turn context reuse
}
oneway interface ITouvayResponseCallback {             // oneway: engine never blocks on clients
    void onAccepted(String requestId);
    void onDelta(in ResponseDelta delta);              // streamed chunks
    void onCompleted(in ResponseFinal fin);
    void onFailed(in EngineError err);
}
```

**Evolution rules:** methods are added, never changed; `negotiate()` exchanges
`{contractVersion, minSupported}` both ways; capabilities and their payload schema versions are
discovered at runtime (`text.rewrite@2` can coexist with `@1`). AIDL files are frozen per release
(checked-in golden copies; CI fails on incompatible diffs).

---

## 11. Request Lifecycle

### 11.1 Warm path (model already loaded)

```
App: rewriter.rewrite(text, opts)
 1. SDK validates locally (size caps, options), builds proto payload, envelope
 2. Binder submit() → engine-service
 3. Auth: caller UID → client record (v1: same-app only; later: consent table) ; quota check
 4. engine-core: request admitted to scheduler queue (class = Interactive)
 5. Router resolves capability → ExecutionPlan {modelPack, runtime, params, template}
    using: capability registry × device tier × loaded-model affinity
 6. Model manager: acquire(modelInstance) — refcount++ (already READY → immediate)
 7. Capability pipeline: build prompt from engine-owned template; tokenize; enforce
    context budget (tier policy) with capability-specific truncation
 8. Runtime session: prefill (prefix-cached system template where supported) → decode
 9. Tokens → pipeline post-processing (parse/validate structure) → onDelta callbacks
10. onCompleted(final result + stats); refcount--; model → IDLE (unload timer armed)
11. SDK completes suspend / Flow
```

### 11.2 Cold path additions

Between 5 and 6: if the plan's model is not loaded — scheduler may first have to *evict* the
resident model (tier T1) — the model manager transitions INSTALLED → LOADING (weights mmap'd,
runtime init) while the request waits with a deadline; SDK surfaces `onAccepted` early so UIs can
show progress. If the capability's pack isn't installed: fail fast with
`CapabilityUnavailable(DownloadRequired)` — the engine never silently downloads.

### 11.3 Cancellation

Client cancels (coroutine cancellation / `coalesceKey` superseded) → `cancel(requestId)` → scheduler
removes if queued; if executing, a cancellation flag is checked by the runtime adapter at **every
token boundary** (the SPI contract requires ≤1 token of cancellation latency). Keyboard usage makes
this hot-path: a newer rewrite supersedes an in-flight one constantly.

### 11.4 Failure paths

- **Engine process death mid-request:** client's `DeathRecipient` fires; SDK fails in-flight
  requests with `EngineDisconnected` (retryable) and reconnects with backoff. The SDK **never
  auto-resubmits generative requests** (duplicate side effects, changed context); retry is the
  app's decision.
- **Sessions survive by reconstruction:** the client SDK retains the session transcript; on
  reconnect it recreates the session and the engine re-prefills (prefix caching mitigates cost).
  The engine holds no durable per-session state — design-for-death (§4.5).
- **Native runtime crash:** confined to the engine process (never the IME); the crashed runtime's
  instances are quarantined (circuit breaker: N crashes → runtime disabled for the boot session,
  router falls back to alternate plans).

---

## 12. Runtime Architecture (SPI)

```kotlin
// runtime-api — mandatory core (deliberately small)
interface InferenceRuntime {
    val id: RuntimeId
    fun probe(device: DeviceProfile): RuntimeAvailability     // native libs ok? accelerators?
    fun loadModel(pack: ResolvedModelPack, config: LoadConfig): ModelInstance   // slow, off-main
}

interface ModelInstance : AutoCloseable {
    val info: ModelInstanceInfo                                // mem footprint, ctx max
    fun createSession(config: SessionConfig): InferenceSession
}

interface InferenceSession : AutoCloseable {
    fun prefill(tokens: TokenSeq, cancel: CancelSignal): PrefillResult
    fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink)     // streaming
}

// Optional typed features — detected, never assumed
interface SupportsPrefixCache { fun cachePrefix(tokens: TokenSeq): PrefixHandle }
interface SupportsConstrainedDecoding { fun withGrammar(g: DecodingGrammar): DecodeParams }
interface SupportsVision { fun encodeImage(img: SharedImage): ImageEmbedding }
interface SupportsLoRA { fun attachAdapter(adapter: ResolvedAdapterPack) }
interface SupportsDelegatedCapability {   // for runtime-aicore: whole-capability delegation
    fun execute(cap: CapabilityId, payload: ByteArray, cancel: CancelSignal, sink: DeltaSink)
}
```

Notes:

- **Two integration shapes, one SPI.** Token-level runtimes (llama.cpp, LiteRT-LM, ExecuTorch)
  implement the session shape; **AICore/ML Kit is different in kind** — it exposes finished
  capabilities, not token loops — so it implements `SupportsDelegatedCapability` and the router
  treats it as a plan that bypasses the pipeline's prompt stage (the pipeline still validates and
  structures output). This keeps "delegate to the OS when it's better" honest rather than
  shoehorned.
- **Threading contract:** all SPI calls arrive on the engine's inference executor, never the main
  thread; an instance is single-owner (one session executing at a time unless the instance
  declares concurrency). Cancellation is **step-bounded**, not wall-clock: honored within one
  decode step (poll per token) and one batch chunk during prefill; adapters SHOULD hook backend
  abort callbacks to interrupt inside a step. *(Refined per measured spike evidence — cancel
  latency equals one in-flight step, 65 ms–1.36 s under emulator jitter; see
  docs/spikes/llamacpp-feasibility.md.)* Normative detail: docs/runtime/runtime-spi.md §6.
- **Hardware acceleration lives inside adapters** (llama.cpp OpenCL/Vulkan, LiteRT GPU delegate,
  ExecuTorch QNN/MediaTek backends). NNAPI is deprecated and is not a target; vendor acceleration
  arrives via each runtime's own delegates, which is exactly why runtimes stay pluggable.
- **Conformance kit (`runtime-tck`)** is the real interface definition: determinism under greedy
  decoding, cancellation latency, memory release after `close()` (measured RSS), stream ordering,
  UTF-8 boundary safety, crash containment. An adapter that passes the TCK is supported; one that
  doesn't, isn't — including in forks.

---

## 13. Model Management

### 13.1 Model packs (data only, signed)

```jsonc
// pack manifest (schema versioned)
{
  "id": "touvay.pack.compact-writer-q4",
  "version": "1.2.0",
  "engineMin": "1.0.0",
  "runtime": { "id": "llamacpp", "minAdapter": "1.0" },
  "capabilities": [
    { "id": "text.rewrite",   "quality": 62, "template": "templates/rewrite.tpl" },
    { "id": "text.summarize", "quality": 58, "template": "templates/summarize.tpl" }
  ],
  "resources": { "ramMb": 900, "ctxMax": 2048, "accel": ["cpu", "gpu-opencl"] },
  "deviceConstraints": { "minTier": "T1", "abis": ["arm64-v8a"] },
  "files": [ { "path": "weights.gguf", "bytes": 716800000, "sha256": "…" } ],
  "license": { "spdx": "apache-2.0", "noticePath": "LICENSE" },
  "signature": "ed25519:…"   // over canonicalized manifest incl. file digests
}
```

- The **capability registry** is the join of installed manifests: capability → candidate plans with
  quality scores and resource costs. Routing = filter by device tier & constraints, rank by
  quality within budget, prefer already-loaded (affinity).
- Templates/config in packs are **declarative data** interpreted by capability pipelines — never
  executable. A template language with logic is a code smell here; it stays substitution-only.
- **Integrity:** manifest signature verified against keys pinned in the engine build at install
  time; per-file SHA-256 verified on install and re-verified cheaply (size+mtime+spot hash) at
  load. User-imported packs (a deliberate OSS-friendly feature) are marked untrusted: extra
  warnings, and their files are treated as hostile input — model file parsers are fuzzed, since
  GGUF/graph parsers have had real CVEs.

### 13.2 Acquisition

Sources, in order of preference: OEM/preload directory → Play Asset Delivery (for small default
packs) → Touvay CDN via the downloader (consent + unmetered-by-default policy, resumable, verified)
→ user import. The downloader is the only networked component and carries no user data — requests
are for signed artifacts by content hash.

### 13.3 Lifecycle state machine

```
AVAILABLE ──install──► INSTALLING ──verify──► INSTALLED
                                                  │ load (on demand)
                                              LOADING ──► READY ⇄ ACTIVE(refcount>0)
                                                  │            │
                                                  ▼            ▼ idle timer / pressure
                                               FAILED       UNLOADING ──► INSTALLED
INSTALLED ──uninstall/evict-by-storage-policy──► AVAILABLE
```

Refcounted acquisition; idle unload timer (tier-dependent, e.g., 30–120 s); immediate unload on
`onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`; storage eviction never removes a pack required by
the only plan for an installed client's used capability without policy consent.

---

## 14. Scheduling, Threading, and Memory

### 14.1 Scheduling

- **Two classes:** `INTERACTIVE` (user is waiting; keyboard actions) and `BACKGROUND`
  (summarize-in-advance, indexing). FIFO within class; strict priority between them.
- **Preemption is cooperative at token boundaries:** an arriving interactive request causes the
  running background job to be cancelled at the next token (v1: cancel, not pause — KV snapshot/
  restore isn't worth its complexity yet; revisit when a runtime exposes cheap session state).
- **Coalescing:** requests with the same `coalesceKey` from the same client supersede one another —
  purpose-built for type-then-retry keyboard flows.
- **Admission control:** per-client concurrent + queued caps, `EngineBusy(retryAfter)` beyond;
  per-client token budgets become meaningful in the shared-engine phase.
- **Thermal integration:** `PowerManager` thermal status/headroom listener; MODERATE → shed
  BACKGROUND, reduce decode threads; SEVERE → refuse new BACKGROUND, warn on INTERACTIVE.
  Field experience across on-device LLM deployments is unambiguous that sustained decode throttles
  quickly — the scheduler treats heat as a budget like memory, and capabilities are designed for
  *short bursts* (§3.5).

### 14.2 Threading model

- Engine: Kotlin coroutines, structured concurrency. Dispatchers: `Main` (binder-entry hop only,
  immediately off), `Default` (routing/pipeline CPU work), a **dedicated inference executor** —
  one owner thread per loaded model instance (runtimes multithread internally; thread count set
  from big-core topology, never `availableProcessors()` naïvely).
- Binder calls into the service return immediately (`oneway` callbacks outbound); the engine never
  executes inference on a binder thread.
- Cancellation: coroutine cancellation → `CancelSignal` (atomic flag + condition) checked in native
  token loops via JNI callback — no thread interruption games in native code.
- SDK: suspend/Flow only; Java interop via `ListenableFuture`/callback adapters in a separate
  artifact.

### 14.3 Memory management

- **Device tiers** (evaluated at runtime, not hardcoded by device list):
  - T0 (<3.5 GB or `isLowRamDevice`): no LLM capabilities; only tiny task models (<150 MB); some
    capabilities report `DeviceNotSupported`.
  - T1 (~4 GB): one resident instance ≤1 GB incl. KV; ctx ≤1K; small-model plans preferred.
  - T2 (6–8 GB): ≤3B-class Q4; ctx 2–4K; opportunistic second small instance.
  - T3 (≥12 GB): 7–8B-class; concurrent instances; larger ctx.
- **Weights are mmap'd read-only** where the runtime supports it (llama.cpp/GGUF does): clean pages
  the kernel can evict under pressure without killing the process — the single most effective
  4 GB-device survival mechanism.
- **KV cache is the managed dynamic cost:** per-tier ctx caps, per-request budget accounting in the
  budget manager, session KV freed on idle. Budgeting is done on **resident** memory under
  full-context load — never on allocator-reported reservations or creation-time RSS: backends
  reserve compute/KV buffers virtually and Linux commits pages lazily. *(Measured: ~815 MB
  reported "allocated" vs 32–76 MB resident at context creation; docs/spikes/llamacpp-feasibility.md.)*
- `onTrimMemory` staged response: BACKGROUND → drop idle instances; CRITICAL → unload all, cancel
  BACKGROUND. Engine process death remains survivable by design regardless.

---

## 15. Plugin System

| Extension point | Kind | Mechanism | Who can extend |
|---|---|---|---|
| Inference runtimes | Code | Gradle module implementing `runtime-api`, registered at composition root; per-ABI native libs via app bundle splits | Engine maintainers; forks (must pass TCK) |
| Capability pipelines | Code | Gradle module implementing pipeline SPI + contract payload schema | Engine maintainers; forks |
| Model packs | **Data** | Signed pack installed at runtime | Anyone the signing policy admits; users (untrusted-import mode) |
| Templates/decoding configs | Data | Inside packs, declarative only | Pack authors |
| Device policy overrides | Data | Signed policy file (tier thresholds, thermal responses) | Engine maintainers; OEM partners |

Explicitly rejected: runtime-loaded third-party code (Dex/native), for security, Play-policy, and
API-freeze reasons (§3.7, ADR-006). The extension story for third parties is: contribute upstream,
or fork and pass the conformance kits — which is the honest OSS contract.

---

## 16. Security & Privacy

**Threat model and mitigations:**

| Threat | Mitigation |
|---|---|
| Malicious client app (shared-engine phase) | Binder UID auth; per-app user consent (engine-owned consent registry, IME-picker-style UX); quotas; no cross-client data or cache visibility (prefix caches keyed per client where content-derived) |
| Malicious/compromised model source | Ed25519-signed manifests, pinned keys, per-file SHA-256; packs are data-only; fuzzed parsers |
| Hostile user-imported model file | Untrusted-mode gating + warnings; parser hardening; parse/validate before runtime load; imported packs never auto-selected over signed ones |
| On-disk attacker / backup leakage | App-private storage, `allowBackup=false` for model store and any engine state; no user text at rest, ever |
| Network MITM on downloads | HTTPS + content-addressed artifacts + signature verification (integrity does not depend on TLS alone) |
| Exfiltration via the engine ("trust us" problem) | Inference path has no network code by dependency rule; StrictMode `detectNetwork` enforced in instrumentation; OSS + reproducible builds so the claim is auditable |
| Log/crash leakage of user text | Structured logging with redaction by type (user-content strings are a distinct type that cannot reach log sinks); diagnostics are local-only — redacted crash/perf incidents go to an on-device, size-capped incident log that only the user can export and share (ADR-013); no automatic upload path exists in any module |
| IPC abuse (malformed parcels/protos) | Fuzzed contract decoding; strict payload size caps; unknown-field-tolerant proto parsing |

Keyboard-specific: the IME never forwards keystrokes to the engine ambiently — only explicit
user-invoked selections/fields; password/`textNoSuggestions` input types are hard-excluded in the
SDK layer itself, not just app code.

---

## 17. Performance Strategy

**Metrics (the contract with ourselves):** time-to-first-token, decode tokens/s, prefill tokens/s,
peak RSS delta, request energy proxy, thermal delta per request class. Budgets per capability/tier
(e.g., interactive rewrite on T2: TTFT ≤ 800 ms warm, ≤ 4 s cold with UI affordance; on T1 the
router may select the small-model plan to hold the same budget).

**Techniques, in order of expected leverage:**

1. **Prefix caching of capability templates** — every capability has a fixed engine-owned preamble;
   caching its KV makes TTFT ≈ user-content prefill only. (Feature-detected; LiteRT-LM exposes
   this natively, llama.cpp via session state.)
2. **Right-size the model per task** — routing quality tiers beats kernel micro-optimization.
3. **Quantization ladder** (Q4 default; Q8/FP16 only where quality evals demand).
4. **mmap + warm-instance affinity** (avoid load/unload thrash via idle timers and plan affinity).
5. **Thread/core discipline** — decode threads = big cores, never oversubscribed.
6. **GPU/NPU delegates measured, not assumed** — on mid-range SoCs, GPU delegates sometimes lose
   to well-threaded CPU; the device profile caches per-device microbenchmark results from first
   run (with user-idle scheduling).
7. **Bursty UX contract** (§3.5) — capabilities are shaped so requests finish in seconds; thermal
   headroom is a scheduler input.
8. Later: speculative decoding, LoRA-over-shared-base to multiply capabilities without multiplying
   resident weights.

Performance regression gates run in CI on a physical device matrix (one device per tier), tracking
the metric set per commit for the benchmark scenario suite.

---

## 18. Testing Strategy

| Layer | Approach |
|---|---|
| `engine-core` | Pure-JVM unit tests (fast, no emulator): router decisions as table-driven tests, scheduler with virtual time, budget manager property tests |
| Runtime adapters | **`runtime-tck` conformance suite** (the SPI's executable spec: determinism, cancellation ≤1 token, memory release, stream ordering, UTF-8 safety, crash containment) + adapter-specific native tests; parser fuzzing (libFuzzer) for model-file ingestion |
| Capability pipelines | Golden tests with greedy decoding against pinned tiny models (deterministic output fixtures); template snapshot tests; structured-output parser fuzz/property tests |
| Quality (the tests that keep users) | Per-capability eval harness: dataset + scoring per capability (grammar F-score, summary rubric, translation chrF); **release gate for model packs**, run off-device in CI with on-device spot checks. Without this, model swaps silently degrade the product |
| Contract | AIDL frozen-file diff checks; proto compatibility checks (buf-style breaking-change detection); cross-version matrix tests (old SDK ↔ new engine, new SDK ↔ old engine) |
| SDK | Binary-compatibility-validator; `sdk-fakes` gives app developers a deterministic in-memory engine (also used by keyboard tests) |
| Integration | Instrumented tests on the tier device matrix; **chaos suite**: kill engine mid-stream, binder death, `onTrimMemory` storms, disk-full mid-download, malformed packs |
| Performance | Macrobenchmark + custom native timers, per-tier budget assertions (§17) |

Test-enabling design choices already made above: pure-JVM core, ports-and-adapters effects,
data-only packs (fixtures are trivial), single composition root (swap everything in tests).

---

## 19. Repository Structure

```
touvay-engine/
├── docs/
│   ├── ARCHITECTURE.md            (this document)
│   └── adr/                       (one file per ADR, numbered, immutable once accepted)
├── contract/
│   └── touvay-contract/           AIDL + proto schemas + frozen golden files
├── sdk/
│   ├── touvay-sdk/
│   ├── touvay-sdk-java/           (ListenableFuture/callback interop)
│   └── sdk-fakes/
├── engine/
│   ├── engine-service/            (composition root)
│   ├── engine-core/               (pure Kotlin)
│   ├── engine-models/             (+ downloader, isolated)
│   └── engine-device/
├── capabilities/
│   ├── capability-text/
│   ├── capability-vision/
│   └── capability-speech/
├── runtime/
│   ├── runtime-api/
│   ├── runtime-tck/
│   ├── runtime-llamacpp/
│   ├── runtime-litert/
│   ├── runtime-executorch/        (post-v1)
│   └── runtime-aicore/
├── apps/
│   ├── keyboard/                  (first client; may move to its own repo at 1.0)
│   ├── demo/                      (SDK reference client)
│   └── engine-app/                (standalone shared engine; promotion phase)
├── packs/                         (manifest schema, signing tools, sample tiny packs for tests)
└── build-logic/                   (convention plugins enforcing dependency rules)
```

---

## 20. Architecture Decision Records

Each of these gets a full file under `docs/adr/`; summarized here.

**ADR-001 — Process topology: out-of-process embedded engine, promotable to shared app.**
Alternatives: in-process library; central app day one. Chosen for crash isolation (IME must
survive), memory attribution, day-one distribution, and forced contract discipline. Consequence:
IPC design cost paid up front; accepted because that contract is the platform's core asset. (§5)

**ADR-002 — IPC: Binder/AIDL bound service.** Alternatives: Messenger, gRPC-over-UDS,
ContentProvider. Binder gives caller identity, lifecycle, and performance natively. Consequence:
Android-only transport — acceptable; the engine is an Android platform component.

**ADR-003 — Wire format: AIDL envelope + protobuf-lite payloads.** Alternatives: pure Parcelable
(brittle cross-version evolution), JSON (slow, schemaless). Consequence: proto toolchain in the
build; worth it for decade-scale schema evolution and language-neutral contract docs.

**ADR-004 — Capability-based public API with structured results; generic `generate` allowed but
model-agnostic by construction.** Alternative: prompt-passthrough API. Rejected: it couples every
client to model behavior and destroys replaceability (§3.6).

**ADR-005 — Runtime SPI: small mandatory core + typed optional feature interfaces.** Alternative:
lowest-common-denominator interface or per-runtime routing logic. Consequence: router complexity
grows with features, but capability pipelines stay runtime-agnostic and the TCK stays testable. (§12)

**ADR-006 — Plugins: code at compile time, data at runtime.** Alternative: dynamic code loading.
Rejected on security, Play policy, and API-freeze grounds (§3.7, §15).

**ADR-007 — Kotlin core + C++ runtime adapters via narrow JNI.** Alternative: Rust core with
UniFFI (better memory safety, future cross-platform). Deferred: Android-only scope, team velocity,
and ecosystem favor Kotlin; the pure-JVM core and narrow SPI keep a future Rust/KMP extraction
tractable. Revisit trigger: a funded iOS/desktop port or recurring memory-safety incidents in glue
code.

**ADR-008 — AICore/ML Kit GenAI is a delegating backend, not the foundation.** Rationale: it now
offers third-party APIs (summarize/proofread/rewrite/prompt/image description) on premium
Snapdragon/Dimensity/Tensor devices — but it's closed, device-limited, and Google-controlled.
Touvay uses it opportunistically via `SupportsDelegatedCapability` when present, better on budget,
and permitted by user policy ("open models only" toggle). This hedges the biggest strategic risk
(§21-R1) instead of ignoring it.

**ADR-009 — Versioning: additive AIDL evolution + runtime capability discovery + semver SDK;
capabilities individually versioned (`text.rewrite@2`).** Alternative: lockstep versioning.
Rejected: shared-engine phase makes independent client/engine update cadences inevitable.

**ADR-010 — Scheduling: two-class priority, cooperative token-boundary preemption by
cancellation, request coalescing.** Alternative: pause/resume preemption via KV snapshots.
Deferred until runtimes expose cheap session state; complexity unjustified now.

**ADR-011 — Models distributed as signed packs; engine never downloads without explicit consent;
user-import supported as untrusted.** Alternatives: APK-bundled only (too limiting), silent
download (violates privacy posture).

**ADR-012 — Engine holds no durable user state; sessions reconstructible from client-held
transcripts.** Alternative: engine-side session persistence. Rejected: process death is routine on
target devices; statelessness converts a hard reliability problem into a latency cost softened by
prefix caching.

**ADR-013 — Diagnostics are local-only with manual export; no automatic upload of any kind.**
*(Added at architecture review, 2026-07-11.)* Alternatives: opt-in scrubbed crash upload (the
original §16 draft); no diagnostics at all. Decision: redacted crash and performance incidents are
written to an on-device, size-capped incident log; the only way diagnostic data leaves the device
is the user manually exporting it (e.g., attaching it to a bug report). Consequence: field
debugging depends on user-initiated reports — accepted, because it makes "no telemetry / no hidden
network" a hard, auditable guarantee: the downloader remains the only networked module, with no
exceptions.

**ADR-014 — Capability wire schemas are owned by the contract layer, not by capability
implementation modules.** *(Accepted 2026-07-11, Task 1.)* Context: capability pipelines parse
payload protos and the SDK builds them, but §7/§8 never assigned schema ownership. Alternatives:
(a) schemas live in each `capability-*` module — rejected: the SDK would need dependencies on
implementation modules, inverting the dependency rules, and wire schemas would evolve at
implementation cadence without contract review; (b) schemas live in the contract layer — chosen:
wire schemas *are* contract, they evolve additively under the same review discipline as the AIDL
surface, and both sides of the wire reach them without new edges. Consequence: `capability-*`
modules take a dependency on the contract layer for schema classes only (the §8 allowlist gains
that edge when those modules land); if depending on the full contract artifact ever tempts
pipelines to touch transport types, the schemas split into a leaner `touvay-contract-schemas`
artifact — an anticipated, non-breaking refactor.

---

## 21. Risks and Future Evolution

### Top risks over a 10-year horizon

| # | Risk | Mitigation baked into the architecture |
|---|------|-----------------------------------------|
| R1 | **Android platform absorbs the category** (AICore/ML Kit GenAI becomes ubiquitous and free) | Differentiate on openness, model choice, low-tier device coverage; consume AICore as a backend (ADR-008); the capability SDK remains valuable as the neutral layer |
| R2 | **Runtime ecosystem churn** (llama.cpp API instability; LiteRT-LM already replaced the MediaPipe LLM API within ~2 years) | Runtimes are adapters behind a TCK; pinned versions; churn is contained to one module |
| R3 | Model license shifts (Gemma/Llama terms) | License is a manifest field enforced at registry level; packs swappable without engine releases |
| R4 | NPU fragmentation (QNN vs MediaTek vs Exynos SDKs) | Acceleration lives inside runtime adapters; never leaks above the SPI |
| R5 | Play policy shifts (16 KB pages was the 2025 example; FGS/background policy tightening) | Native build hygiene centralized in runtime modules; engine is a *bound* service, avoiding foreground-service policy exposure |
| R6 | Quality regressions on model swaps | Eval harness as release gate (§18) — this is infrastructure, not optional tooling |
| R7 | API ossification / accidental surface | Explicit API mode, compatibility validators, contract-first review, capability versioning |
| R8 | OSS sustainability (bus factor, fork fragmentation) | ADR discipline, conformance kits define "compatible", small stable surfaces reduce maintenance area; governance docs at 1.0 |
| R9 | RAM/expectation inflation (tomorrow's "small" model is 8B) | Tier policy is data (signed policy file), not code; capability quality floors re-evaluated per pack release |
| R10 | Security incident via model files or IPC | Fuzzing (parsers, contract), signing, data-only packs, crash containment + circuit breakers |

### Evolution path

1. **Phase 1 (v1):** embedded engine in Touvay Keyboard; `text.*` capabilities; llama.cpp +
   LiteRT-LM (+ AICore delegate); tiers T0–T2 validated on real 4 GB hardware.
2. **Phase 2:** `vision.describe`, `speech.transcribe` (streaming audio via SharedMemory ring
   buffer — the contract already reserves the transport); ExecuTorch adapter for NPU coverage.
3. **Phase 3 (promotion):** standalone Touvay Engine app; SDK discovery order
   (signature-pinned standalone → embedded); cross-app consent UX; per-app quotas activate.
4. **Phase 4:** LoRA adapter packs over shared bases (personalization without per-app models);
   pause/resume scheduling if runtimes mature; KMP/Rust core extraction **only** if a second
   platform is funded (trigger in ADR-007).

New capabilities that "don't exist today" arrive as: new capability id + proto schema + pipeline
module + packs that claim it — no changes to the SDK connection layer, contract envelope,
scheduler, or SPI. That is the 10-year test this architecture is built to pass.

---

## Appendix A — References consulted (July 2026)

- [LLM Inference guide for Android — Google AI Edge](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) (MediaPipe LLM API maintenance mode; LiteRT-LM migration)
- [ML Kit GenAI APIs overview](https://developers.google.com/ml-kit/genai) and [Gemini Nano on Android](https://developer.android.com/ai/gemini-nano)
- [On-device GenAI APIs via ML Kit — Android Developers Blog](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html)
- [Top AI on Android updates from Google I/O '26](https://android-developers.googleblog.com/2026/05/android-ai-intelligence-system.html) (Gemini Nano 4 preview, structured output, prefix caching)
- [Running on-device AI models on Android: MediaPipe, llama.cpp, or ExecuTorch](https://meetprajapati.com/blogs/running-on-device-ai-models-android-mediapipe-llamacpp-executorch/)
- [On-device LLMs guide 2026](https://www.buildmvpfast.com/blog/on-device-llm-mobile-llama-ios-android-2026) (thermal constraints, bursty UX)
- [Support 16 KB page sizes — Android Developers](https://developer.android.com/guide/practices/page-sizes) and [Play 16 KB deadline clarification](https://support.google.com/googleplay/android-developer/thread/368982598)

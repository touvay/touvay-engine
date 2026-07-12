# Production llama.cpp Adapter — Design

**Status:** Proposed — awaiting approval; **no implementation until Task 2 is approved.**
**Conforms to:** `runtime-spi.md` (all SPI-* requirements) and `runtime-tck.md`.
**Evidence base:** the Task 1 spike (`runtime/runtime-llamacpp-spike`,
`docs/spikes/llamacpp-feasibility.md`). The spike validated the shape; this design
carries over what was proven and fixes what the spike deliberately simplified.

## 1. Module structure

```
runtime/runtime-llamacpp/
├── build.gradle.kts               # touvay.android.library; ndkVersion; abi arm64-v8a (+x86_64 for CI)
├── src/main/cpp/
│   ├── CMakeLists.txt             # llama.cpp static; 16 KB max-page-size; curl/examples/openmp OFF
│   └── llama_jni.cpp              # thin bridge (§3); no inference logic
├── src/main/kotlin/…/llamacpp/
│   ├── LlamaCppRuntime.kt         # InferenceRuntime
│   ├── LlamaCppModelInstance.kt   # ModelInstance (owns tokenizer access)
│   ├── LlamaCppSession.kt         # InferenceSession
│   ├── Utf8StreamDecoder.kt       # promoted from the spike (proven)
│   └── internal/NativeHandles.kt  # handle wrappers + Cleaner backstops (SPI-OW-2)
├── src/androidTest/…/LlamaCppTck.kt   # the conformance claim (one class)
└── README.md                      # pin policy, documented constants (chunk size,
                                   # cancel bound, KV bytes/token per supported family)
```

Dependency edges: `runtime-api` only (unchanged §8 rule). The spike module is deleted
in the same change that lands this adapter TCK-green — one llama.cpp integration in
the tree, ever. `apps/benchmark` retargets the production adapter (one-line dependency
change) and keeps its role as the manual measurement harness.

**Upstream pin policy** (risk R2): exact tag+commit in `scripts/fetch-llamacpp.ps1`.
Bumping = reviewed PR that must show: build green, TCK green, benchmark deltas vs the
previous pin (±20% guardrail on decode/TTFT), changelog review for API/behavior notes.

## 2. Ownership rules

| Kotlin object | Native counterpart | Freed by |
|---|---|---|
| `LlamaCppModelInstance` | `llama_model*` | `close()` → `llama_model_free` |
| `LlamaCppSession` | `SessionCtx { llama_context*, atomic cancel, atomic step-epoch }` | `close()` → `llama_free` + delete |
| (none — per decode call) | `llama_sampler*` chain | end of the decode call |

- Handles are `private val` longs inside their Kotlin owner; they never escape the
  module (SPI-OW table).
- `@Volatile closed` + idempotent close (proven in spike), **plus** a
  `java.lang.ref.Cleaner` backstop per handle that frees and logs `wtf`-level if the
  engine leaks an object (SPI-OW-2). Cleaner never runs on the reference queue for
  correctly closed objects (close deregisters).
- The tokenizer is **not** a separate object: `llama_vocab` is owned by `llama_model`;
  `ModelInstance.tokenize` (new SPI method) calls through under the instance's
  liveness check. It is lock-free and safe concurrently with a running session
  (SPI-LC-7) because vocab access is read-only in llama.cpp.

## 3. JNI boundary

Carried over from the spike (validated end-to-end), with three deltas:

| # | Function | Delta vs spike |
|---|---|---|
| 1 | `nativeBackendInit()` | unchanged (once per process) |
| 2 | `nativeLoadModel(path, useMmap) → jlong` | unchanged |
| 3 | `nativeModelCtxTrain(model) → jint` | unchanged |
| 4 | `nativeTokenize(model, text, addSpecial) → jintArray` | unchanged (now backs the SPI method) |
| 5 | `nativeCreateContext(model, nCtx, nThreads, nBatch) → jlong` | **+ registers ggml abort callback** (§6) |
| 6 | `nativePrefill(ctx, tokens) → jint` | chunk size becomes a parameter (documented constant, default 256 — tightened from 512 for CX-02 headroom) |
| 7 | `nativeDecode(ctx, maxTokens, callback) → jint` | callback stays `(I[B)Z` raw-bytes (proven necessary: BPE pieces split UTF-8; `NewStringUTF` aborts under CheckJNI) |
| 8 | `nativeCancel(ctx)` | unchanged (atomic store) |
| 9 | `nativeFreeContext(ctx)` / `nativeFreeModel(model)` | unchanged |

Bridge rules (unchanged from spike, now normative): no inference logic in C++; no
allocations retained across calls except the handle structs; every JNI array pinned
region released on all paths; `env->ExceptionCheck` after every callback invocation.

## 4. Model loading

1. Kotlin-side pre-validation for clean errors (SPI-ER-1): file exists, non-empty,
   GGUF magic (`GGUF` 4 bytes) — cheap read, keeps hostile-file surface behind one gate.
2. `llama_model_params`: `use_mmap = LoadConfig.useMmap` (default true), `n_gpu_layers = 0`
   (CPU-only v1 — GPU is a separate measured decision per §17), `use_mlock = false`
   (SPI-MM-3).
3. Failure → `IllegalArgumentException("model rejected by llama.cpp: <path>")` — path
   yes, content never (SPI-ER-4).
4. Warm/cold: nothing special to do — mmap + page cache provides warm reloads for free
   (0.76 s vs 3.6 s measured); the model manager exploits it, the adapter just keeps
   `loadModel` repeatable (SPI-MM lifecycle).

## 5. Threading

- No adapter-owned persistent threads in v1: ggml spins its compute workers per call,
  bounded by `n_threads` from `LoadConfig` (SPI-TH-4 satisfied trivially).
  ggml's persistent-threadpool API is a *measured future option* if per-call spin-up
  shows up in device TTFT profiles — not before.
- All entry points tolerate arbitrary caller threads (SPI-TH-2): the native structs
  hold no thread-locals; publication safety comes from the engine's executor
  happens-before plus `@Volatile` fields.

## 6. Cancellation (tightened beyond the spike)

The spike's polling loop bounds cancellation to **one decode step** (measured
65 ms–1.36 s under emulator jitter). Production adds ggml's abort callback to
interrupt *inside* a step:

- `llama_context_params.abort_callback` is wired to the session's atomic flag at
  context creation. ggml polls it between graph nodes, so an in-flight
  `llama_decode` aborts in ~node granularity (≪ one step).
- The Kotlin loop keeps the per-token poll as the portable guarantee (SPI-CX-1);
  the abort callback is the declared tighter bound: the TCK subject declares
  `CancelBound.Tighter` after device calibration, `OneStep` until then.
- An aborted `llama_decode` returns nonzero; the adapter treats
  flag-set + nonzero-return as clean cancellation (not an error, SPI-CX-5); any other
  nonzero return is a backend failure (SPI-ER-3).
- Post-cancel the session reports CANCELLED and accepts only `close()` (SPI-LC-9).

## 7. Scheduler interaction

The adapter is deliberately passive (ARCHITECTURE.md §14 owns policy):

- Declares instance concurrency = 1 (sessions execute sequentially); the engine's
  scheduler serializes per instance and implements preemption as
  *cancel-and-discard-session* — which is exactly the SPI-LC-9 contract.
- Coalescing (`coalesceKey`) needs nothing from the adapter: superseded requests
  arrive as cancellations.
- Thread budget arrives via `LoadConfig.threads` (engine derives it from core
  topology; spike evidence — 4 > 6 threads even on 6 vCPUs — supports capping).

## 8. Streaming

Identical to the proven spike path: raw UTF-8 piece bytes per token across JNI →
`Utf8StreamDecoder` (incomplete-tail carry, malformed-replace) → `TokenSink.onToken`
on the decode thread. Final flush on natural EOG emits any pending replacement char.
EOG tokens filtered natively (SPI-ST-4).

## 9. Benchmark hooks

- The adapter records per-call timings internally when `LlamaCppDiagnostics.enabled`
  (a module-internal flag settable only by test/bench code — zero overhead otherwise):
  prefill ms, per-step decode times (histogram), abort-observed latency. This is
  local-only measurement (ADR-013 applies: never leaves the device except by explicit
  pull).
- The TCK's CX-01 and PF category read the step histogram; `apps/benchmark` swaps its
  dependency to this module and reuses the whole scenario suite unchanged (it talks to
  the SPI, not to spike types — the one spike-only seam, `createSession(config,
  threads)`, is replaced by constructing per-sweep `LoadConfig`s).

## 10. Sequence diagrams

### 10.1 Warm request (instance already loaded)

```
Engine (worker)        LlamaCppModelInstance      LlamaCppSession        native (llama.cpp)
   │  tokenize(text)          │                        │                       │
   ├─────────────────────────►│  nativeTokenize        │                       │
   │◄─────────────────────────┤◄───────────────────────┼───────────────────────┤
   │  createSession(cfg)      │                        │                       │
   ├─────────────────────────►│ nativeCreateContext(+abort cb) ───────────────►│
   │◄── session ──────────────┤                        │                       │
   │  prefill(tokens, cancel) │                        │                       │
   ├──────────────────────────┼───────────────────────►│ chunked llama_decode ►│
   │◄── PrefillResult ────────┼────────────────────────┤◄──────────────────────┤
   │  decode(params, cancel, sink)                     │  per token:           │
   ├──────────────────────────┼───────────────────────►│  sample→piece bytes ─►│
   │      sink.onToken(id, piece)  ◄── utf8 reassembly ┤◄─ callback (I[B)Z ────┤
   │  … repeat until EOG/max/cancel …                  │                       │
   │◄── decode returns ───────┼────────────────────────┤                       │
   │  session.close() ────────┼───────────────────────►│ llama_free ──────────►│
```

### 10.2 Cancellation during decode

```
Client cancels → engine coroutine cancelled
Engine (any thread)        LlamaCppSession               native
   │ cancel.set(); cancelNow()     │                       │
   ├──────────────────────────────►│ atomic store ────────►│  ggml abort cb polls flag
   │                               │                       │  between graph nodes →
   │                               │                       │  llama_decode returns ≠0
   │                               │◄── flag+≠0 = clean cancel (no exception) ──┤
   │◄── decode returns (tokens so far) ──────────────────── │
   │ engine: RequestFailure.Cancelled → binder onFailed(CANCELLED)
   │ session.close()  (only legal op post-cancel, SPI-LC-9)
```

### 10.3 Load / unload with the model manager

```
Model manager            LlamaCppRuntime            kernel page cache
   │ acquire(pack) refcount=1 │                          │
   ├── loadModel ────────────►│ mmap weights ───────────►│ (cold: ~3.6 s; warm: ~0.76 s)
   │◄─ instance ──────────────┤                          │
   │   … sessions come and go (refcount>0) …             │
   │ idle timer fires, refcount=0                        │
   ├── instance.close() ─────►│ munmap + free ──────────►│ pages stay cached (warm)
   │   (reload while warm is cheap — measured)           │
```

---

## 11. Proposed Task 2 (requires approval before any code)

**Scope**
1. `runtime-tck` module: `AbstractRuntimeTck` + fixtures + tier profiles + the
   sabotage self-test suite (FakeRuntime + 4 sabotaged fakes) running in JVM CI.
2. Additive `runtime-api` change: `ModelInstance.tokenize` (BCV additive diff only).
3. `runtime/runtime-llamacpp` per this design, including the abort-callback
   cancellation path and the Cleaner backstops.
4. `LlamaCppTck` green in emulator device-mode (functional categories); PF recorded
   informative. Benchmark app retargeted; spike module deleted.
5. Docs: adapter README (pin policy, documented constants); AGENTS.md refresh.
   **Explicitly out:** engine/router/model-manager wiring (that is Task 3, after the
   physical-device go/no-go), GPU delegates, prefix caching, session reuse.

**Risks**
- llama.cpp abort-callback behavior differs from expectation at b5199 → fallback is
  the proven one-step polling (already conformant as `CancelBound.OneStep`).
- TCK memory tolerances flaky on emulator → tolerances are device-classed; ME gates
  may need one calibration round.
- Still no physical device → Task 2 completes functionally regardless (its acceptance
  criteria avoid device-only items); the T1 go/no-go stays a separate gate.

**Acceptance criteria**
- `gradlew build` green including TCK self-test (sabotaged fakes fail as designed).
- `LlamaCppTck` all mandatory categories green on emulator; JSON report archived
  under `docs/spikes/results/`.
- BCV: only the additive `tokenize` entry changes in `.api` files.
- Benchmark parity: production adapter within ±20% of spike numbers on the same
  emulator (guards against regression during productionization).
- Zero references from engine/SDK modules to the adapter (dependency rules unchanged).
- Spike module removed; `docs/` updated.

**Estimated effort:** 3–4 focused engineering days
(TCK + self-test ≈ 1.5 d; adapter ≈ 1–1.5 d — the JNI is largely proven; SPI change,
retarget, docs ≈ 0.5–1 d).

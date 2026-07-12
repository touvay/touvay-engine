# Production llama.cpp Adapter — Design

**Status:** Implemented and production-hardened — Runtime v1.0 (Task 2.1, 2026-07-12).
**Conforms to:** `runtime-spi.md` (all SPI-* requirements) and `runtime-tck.md`.
**Evidence base:** the historical Task 1 spike (removed after productionization;
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
│   └── NativeGuard.kt             # phantom-reference handle backstops (SPI-OW-2)
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
  `PhantomReference` backstop per handle that frees and logs `wtf`-level if the engine
  leaks an object (SPI-OW-2). Guards retain only primitive handles and non-capturing
  free functions; correctly closed objects deregister before the queue can process them.
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
| 10 | `nativeDetokenize(model, ids) → byte[]` | TCK-only exact stream-parity oracle; raw UTF-8 bytes |

Task 2.1 hardening converts Java UTF-16 to standard UTF-8 explicitly (JNI modified
UTF-8 is never used for prompt/path bytes), retries dynamically sized token pieces,
and preserves embedded NUL and supplementary characters. Bridge rules: no inference logic in C++; no
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

- No persistent compute threads in v1: ggml spins its workers per call,
  bounded by `n_threads` from `LoadConfig` (SPI-TH-4 satisfied trivially).
  A single process-wide daemon waits on the native leak-guard reference queue; it owns
  no model/session state and performs no inference work.
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

Raw UTF-8 piece bytes cross JNI → `Utf8StreamDecoder` (incomplete-tail carry,
malformed-replace) → `TokenSink.onToken` on the decode thread. Kotlin delays one
callback so final flush can be appended to the last token without inventing an extra
callback. Exact concatenation is checked against backend detokenization (SPI-ST-3).
EOG tokens are filtered natively (SPI-ST-4).

## 9. Benchmark hooks

- TCK-CX derives its one-step bound from the same run's callback intervals, without a
  production diagnostics switch. TCK-PF writes a local JSON report through Gradle's
  additional-test-output directory.
- `apps/benchmark` targets this module and reuses the scenario suite through the SPI;
  thread sweeps construct a separate `LoadConfig`/instance per thread count.

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

## 11. Task 2 / 2.1 implementation record

**Implemented scope**
1. `runtime-tck` module: `AbstractRuntimeTck` + fixtures + tier profiles + the
   sabotage self-test suite (FakeRuntime + 4 sabotaged fakes) running in JVM CI.
2. Additive `runtime-api` change: `ModelInstance.tokenize` (BCV additive diff only).
3. `runtime/runtime-llamacpp` per this design, including the abort-callback
   cancellation path and phantom-reference backstops.
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

**Acceptance results (Task 2.1)**
- `gradlew build` green including TCK self-test (sabotaged fakes fail as designed).
- `LlamaCppTck` 28/28 mandatory tests green with zero skips/failures on the API 36
  emulator; evidence archived under `docs/runtime/results/`.
- BCV: only the additive `tokenize` entry changes in `.api` files.
- Benchmark parity: no production regression observed; stable warm short-prompt and
  thread-sweep decode metrics remained within the ±20% guardrail. Emulator cold/cache
  outliers improved materially and remain informative only.
- Zero references from engine/SDK modules to the adapter (dependency rules unchanged).
- Spike module removed; `docs/` updated.

Task 3 engine/router/model-manager wiring remains explicitly out of scope and requires
separate approval after representative arm64 device evidence.

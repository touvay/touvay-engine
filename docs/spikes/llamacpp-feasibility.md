# Spike: llama.cpp feasibility for Touvay Engine (Task 1 Part B)

**Status:** functional validation complete (emulator); representative measurements
**pending a physical arm64 device** — see §6.
**Scope guard:** this spike is isolated. Nothing here is registered with the engine,
reachable from the SDK, or wired to the capability router. Conversion into the
production runtime adapter requires explicit approval (Task 1 instruction).

---

## 1. Question under test

Can upstream llama.cpp, behind Touvay's runtime SPI, satisfy the baseline requirements
of ARCHITECTURE.md — streaming, ≤1-token cancellation, memory release on unload,
process-death recovery — with viable latency/memory/thermal behavior on a 4 GB (T1)
Android device?

## 2. What was built

| Piece | Location | Notes |
|---|---|---|
| Runtime adapter (spike) | `runtime/runtime-llamacpp-spike` | Implements the `runtime-api` SPI (`InferenceRuntime` → `ModelInstance` → `InferenceSession`); CPU-only; greedy decoding |
| JNI bridge | `src/main/cpp/spike_llama_jni.cpp` | ~250 lines; 1:1 mapping onto llama.cpp calls; token pieces cross as raw UTF-8 bytes (never `NewStringUTF` — BPE pieces can split code points); per-context atomic cancel flag polled every token and every 512-token prefill chunk |
| Native build | `src/main/cpp/CMakeLists.txt` | llama.cpp statically linked into one shared lib; `-Wl,-z,max-page-size=16384`; OpenMP/curl/examples off |
| Benchmark host | `apps/benchmark` | Inference in a `:spike` process behind a benchmark-local AIDL (mirrors production topology); on-device UI + instrumented runner; results as JSON in the app's external files dir |
| Fetch scripts | `scripts/fetch-llamacpp.ps1`, `scripts/fetch-model.ps1` | Hash/commit-pinned; sources and weights are never committed |

**Pinned upstream:** llama.cpp tag `b5199`, commit `ced44be34290fab450f8344efa047d8a08e723b4`.
**Test model:** Qwen2.5-0.5B-Instruct Q4_K_M (Apache-2.0, official Qwen GGUF, 468 MiB,
SHA-256 `74A4DA8C…A9DB`) — see `models/README.md` for full provenance and the rationale.

**Native binary cost (arm64-v8a, stripped release):** 3.3 MB. All ELF LOAD segments
align at 0x4000 → 16 KB page compliant (verified with `llvm-readelf -l`). x86_64 is
built only to validate functionally on the emulator.

## 3. How to reproduce

```powershell
# one-time
scripts/fetch-llamacpp.ps1
scripts/fetch-model.ps1          # hash-verified download (~468 MB)

# build + install (needs NDK 27.2.12479018 + CMake 3.22.1, installed via sdkmanager)
./gradlew :apps:benchmark:assembleDebug
adb install -r apps/benchmark/build/outputs/apk/debug/benchmark-debug.apk
adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.touvay.benchmark/files/model.gguf

# automated: quick suite (load, short prompt ×2, cancellation ×3, unload)
./gradlew :apps:benchmark:connectedDebugAndroidTest
# automated: full suite (adds medium prompt, 8-request burst, thread sweep, context probe)
./gradlew :apps:benchmark:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.quick=false
# results:
adb pull /sdcard/Android/data/com.touvay.benchmark/files/spike-smoke-result.json

# manual: launch "Touvay Spike Benchmark" for the same scenarios plus
# "Kill spike process + recover" (scenario 14: forced engine-process death).
```

## 4. Measurement methodology

- Timing: `SystemClock.elapsedRealtimeNanos` around SPI calls; TTFT reported both as
  prefill-only and prefill+first-decode-step.
- Memory: `/proc/self/status` `VmRSS` (current) and `VmHWM` (peak RSS), plus
  `Debug.getNativeHeapAllocatedSize`, snapshotted at baseline / after load / per
  context / after unload. mmap'd weights appear in RSS as page-cache-backed clean pages
  — the kernel can evict them under pressure, so "RSS after load" overstates
  *unreclaimable* pressure; native heap after unload is the leak signal.
- Cancellation: decode 256 tokens on a worker thread, flip the native cancel flag from
  another thread after 12 observed tokens, measure flag-flip → decode-return. 3 runs,
  max reported.
- Thermal: battery temperature + `PowerManager` thermal status/headroom sampled per
  generation and across the 8-request burst.
- Death recovery: kill the `:spike` process (same uid), measure auto-rebind, rerun the
  quick suite — its cold `modelLoad` time is the recovery cost (mirrors ADR-012's
  design-for-death posture).

## 5. Results — x86_64 emulator (functional validation ONLY)

> ⚠️ These numbers exist to prove the harness works end-to-end. They are **not
> representative** of ARM performance, memory pressure, or thermals: the emulator runs
> generic x86-64 kernels on a desktop CPU (Ryzen/16 GB host), has no ARM NEON/dotprod
> path, no battery, and no thermal envelope. No T1 conclusion may be drawn from them.

Test bed: `Google sdk_gphone64_x86_64` AVD, API 36, 6 vCPU, **1.97 GB RAM VM**,
swiftshader GPU (unused), full suite runtime 177 s. Raw JSON: pulled via §3; archived
in the Task 1 commit message reference.

| Measurement (Task 1 item) | Emulator value | Validity |
|---|---|---|
| 1. Native library load | 27.2 ms | behavioral ✓ |
| 2. Model load (cold) | 3,590 ms | functional only |
| 2a. Model load (warm reload, page cache) | 759 ms | behavioral ✓ (mmap benefit confirmed) |
| 3. TTFT short prompt (prefill+first token) | 2,298 ms cold ctx / 1,585 ms warm | functional only |
| 4. Prompt processing | ~13–15 tok/s (171-token medium prompt: 11.4 s) | functional only |
| 5. Decode rate | 4.6–19.6 tok/s (high emulator variance) | functional only |
| 6. Peak RSS (VmHWM) | 625 MB (baseline 101 MB; after load 583 MB) | behavioral ✓ (mmap-dominated) |
| 7. Native heap after load | 41 MB | behavioral ✓ |
| 8. Memory after unload | RSS 429 → **79 MB**; native heap 41 → **5 MB** | **behavioral ✓ — memory is released** |
| 9. Cancellation latency (3 runs) | 64.6 / 973.5 / 1,356.4 ms | bounded by one `llama_decode` step; see analysis |
| 10. Cold vs warm | cold 3.6 s vs warm 0.76 s load; TTFT 2.3 s vs 1.6 s | behavioral ✓ |
| 11. Thermal over 8-request burst | no signal (emulator has no thermal envelope); decode 3.7–19.6 tok/s noise | device required |
| 12. Thread counts (decode tok/s) | 2 → 12.9; **4 → 19.4**; 6 → 16.0 | oversubscription penalty visible even on 6 vCPU |
| 13. Context probe (RSS delta per context) | 512→+32 MB, 1024→+37 MB, 2048→+49 MB, 4096→+76 MB, all succeeded | behavioral ✓ (KV scaling) |
| 14. Process-death recovery | mechanism implemented (benchmark "kill + recover"); production-equivalent path validated in Part A's cross-process test (engine `:touvay` kill → `EngineDisconnected` → reconnect) | device click-through pending |

Correctness observations: streaming token callbacks arrived in order with valid UTF-8
after stream reassembly; EOG detection terminated generations naturally; the quick
suite's assertions (tokens produced, decode rate > 0, cancellation < 2 s) passed on
both runs; no native crashes across ~40 generations, 8 context create/destroy cycles,
and 2 model load/unload cycles per run.

Footnote on the context probe: with a context alive, the native heap allocator reports
~815 MB "allocated" while RSS rises only 32–76 MB — ggml *reserves* compute/KV buffers
up front and Linux commits pages lazily on first touch. On 64-bit this virtual
reservation is harmless, but it means resident KV cost grows during long generations
toward the reservation, so the per-tier context caps must be validated against RSS
under a full-context generation on device, not against creation-time RSS.

Raw dataset: [results/emulator-x86_64-api36-full.json](results/emulator-x86_64-api36-full.json).

## 6. Device measurements — pending hardware

No physical Android device was attached during this session (`adb devices` empty), so
items 3–7 and 10–13 of the Task 1 measurement list have **functional** but not
**representative** answers. To produce the representative dataset, connect a 4 GB
arm64 device (developer mode) and run the §3 commands; the full-suite JSON contains
every metric in the Task 1 list. The analysis section below will be updated with the
device dataset before any production-adapter decision.

Recommended test devices, in priority order: any 4 GB Snapdragon 6xx/7xx-class phone
(T1 target), then one 8 GB device (T2 reference point).

## 7. Analysis

### What the spike establishes (valid despite the emulator)

1. **The SPI shape survives contact with a real runtime.** Tokenize/prefill/decode/
   cancel/close mapped cleanly onto llama.cpp b5199 with a ~250-line JNI bridge and no
   reimplemented inference. The `ModelInstance`/`InferenceSession` split matched
   llama.cpp's model/context split exactly.
2. **Cancellation semantics hold, with a precise caveat.** Cancellation cannot
   interrupt an in-flight `llama_decode` step; latency is bounded by *one decode step*,
   not one wall-clock constant. Under emulator jitter that step reached 1.36 s; on
   target ARM hardware a decode step is the inverse decode rate (e.g. 50–100 ms at
   10–20 tok/s). The runtime-tck's "≤ 1 token" contract is therefore the right
   formulation — and must be verified per device tier.
3. **Model memory is genuinely released on unload** (RSS −350 MB, native heap −36 MB,
   back to ~baseline). No leak signal across repeated load/unload.
4. **mmap earns its keep**: warm reload at 0.76 s vs 3.6 s cold is the page cache doing
   exactly what ARCHITECTURE.md §14.3 assumes.
5. **The entire lifecycle fit in a 1.97 GB VM**, including a 4,096-token context —
   with a 468 MB model. KV cost scaled ~19 MB/1K tokens (Qwen2.5-0.5B's small KV
   geometry). This is a *positive structural signal* for the 4 GB baseline, though not
   proof.
6. **Thread oversubscription hurts even without big.LITTLE**: 4 threads beat 6 on a
   6-vCPU VM. The engine's planned big-core-derived thread count (§14.2) is justified.

### What remains open (device-blocking)

Representative TTFT, prefill/decode rates, thermal behavior under bursts, and the
maximum comfortable context on a real 4 GB ARM device. **No T1 go/no-go is declared in
this document.** The harness, model, and instructions are ready; the decision gate is
one device run away.

### Provisional limits to validate on device (not yet policy)

- Threads: `min(bigCores, 4)`, never `availableProcessors()`.
- Context: 1,024 for T1 interactive capabilities (~37 MB KV at this model's geometry);
  larger contexts are a T2+ decision.
- Residency: one model instance; rely on mmap + idle unload (§14.3) — supported by the
  unload/reload numbers above.

## 8. Risks identified

1. **Upstream API churn is real and fast.** The b5199 API (`llama_model_load_from_file`,
   `llama_vocab_*`, sampler chains) differs materially from ~6 months earlier. The
   production adapter must pin + wrap, and the runtime-tck must run on every pin bump.
   Mitigated by design (adapter + TCK, ARCHITECTURE.md R2) — confirmed, not new.
2. **Shallow-clone build metadata:** ggml warns `build version fixed at 1` on shallow
   clones — cosmetic for the spike; the production fetch should use a full-depth pin or
   inject the version.
3. **Debug-symbol stripping needs `ndkVersion` in every APK-producing module**, or a
   25 MB unstripped library ships silently. Worth a convention-plugin guard later.
4. **Emulator ≠ device.** The single biggest open risk remains unmeasured ARM
   performance/thermals on T1 hardware; no architectural conclusion is safe until §6
   is executed.
5. **Prefill cancellation granularity is 512 tokens** (chunk boundary), coarser than
   decode's 1 token. Acceptable for short keyboard prompts; the production adapter
   should shrink chunks or check the flag inside ggml's callback if long-document
   capabilities land.

## 9. Deliberate simplifications (spike-only)

Greedy decoding only; one active decode per session; CPU-only (no GPU/NPU delegates);
no KV-cache reuse across requests; no prefix caching; chat template hardcoded for the
test model. Each is a production-adapter concern with an ARCHITECTURE.md home (§12
typed feature interfaces, §17 performance strategy) — none blocks the feasibility
question.

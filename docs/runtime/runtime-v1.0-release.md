# Runtime v1.0 Foundation — Release Checkpoint

**Status:** Approved for merge (2026-07-12)
**Milestone tag:** `runtime-v1.0-foundation`

## What shipped

- The Task 0 Binder walking skeleton and asynchronous SDK, including cross-process
  streaming, cancellation, binder-death recovery, and exactly-one-terminal semantics.
- A small, Android-free Runtime SPI: runtime probe/load, model-owned tokenization,
  model instances, single-owner inference sessions, prefill/decode streaming,
  cooperative cancellation, and explicit resource ownership.
- Runtime TCK v1.0 with 28 device conformance tests plus a 10-test JVM self/sabotage
  suite that proves the kit detects leaks, cancellation failures, stream corruption,
  nondeterminism, parser failures, validation gaps, and content leakage.
- A production llama.cpp b5199 adapter with a narrow JNI boundary, CPU-only execution,
  mmap'd GGUF weights, abort-callback cancellation, exact UTF-8 streaming, defensive
  native cleanup, 16 KiB page alignment, and no engine/router wiring.
- A production-adapter benchmark harness and archived emulator conformance/parity
  evidence. The isolated Task 1 spike module was removed.

## Runtime SPI summary

`InferenceRuntime` is a stateless entry point that probes a device and loads an
integrity-verified `ResolvedModelPack`. `ModelInstance` owns weights and tokenizer
access, reports fixed resource facts, and creates sessions. `InferenceSession` owns KV
state and performs prefill/decode on engine worker threads. Cancellation is step-bounded;
once observed, the session may only be closed. Optional runtime features remain additive
typed interfaces and are not part of Runtime v1.0.

Ownership is explicit: the future Model Manager owns instance caching/refcounts and pack
file lifetime; request execution owns sessions; the adapter owns native handles. Pack
files must remain immutable until the loaded instance closes.

## Runtime TCK coverage

The 28-test device suite covers lifecycle and defensive close, cross-thread invocation,
foreign-thread cancellation, step/chunk cancellation bounds, cancel/completion races,
stream ordering and caps, exact backend detokenization parity, CJK/emoji/NUL UTF-8,
EOG filtering, throwing sinks, greedy determinism, native/RSS release, full-context KV
resident bounds, repeated load/unload, missing and hostile GGUF files, log/exception
privacy, invalid configuration, and informative performance reporting.

The API 36 x86_64 emulator result is 28/28 with zero skips/failures. JVM TCK self-tests
are 10/10. Evidence lives in `docs/runtime/results/`.

## llama.cpp adapter architecture

- Upstream is pinned to tag `b5199`, commit
  `ced44be34290fab450f8344efa047d8a08e723b4`.
- Kotlin owns lifecycle, state machines, policy constants, cancellation propagation,
  UTF-8 reassembly, and leak backstops.
- C++ is a thin JNI bridge over llama.cpp. Java strings are converted from UTF-16 to
  standard UTF-8 explicitly; token pieces cross JNI as raw bytes.
- Each model maps to `llama_model*`; each session maps to a context plus atomic cancel
  state. Samplers are call-scoped. Native handles never escape the adapter.
- CPU threads come from `LoadConfig`; GPU layers, mlock, prefix caching, and session
  reuse are deliberately absent from v1.

## Known limitations

- No runtime is registered with the engine. Model Manager, routing, scheduling, and
  capability inference integration are post-checkpoint work.
- Performance profiles are emulator-informative and not calibrated on physical T1/T2
  devices.
- The adapter is CPU-only and greedy-only; sampling policy and optional acceleration
  require measured follow-up work.
- Model fixtures are fetched/pushed out of band and are not committed.
- Model download, user import UX, pack signing tools, and runtime parser fuzz targets do
  not exist yet.

## Remaining risks

- Representative TTFT, thermals, and full-context residency on a 4 GB arm64 phone remain
  unmeasured. This is a gate before Task 3 implementation, not a reason to reopen Runtime
  v1 architecture.
- llama.cpp API churn remains contained by the exact pin, adapter boundary, TCK, and
  required benchmark comparison on pin upgrades.
- GGUF is hostile input. The malformed corpus validates clean rejection, but sustained
  libFuzzer coverage is still required before general untrusted imports.
- Android/Gradle tooling currently emits Gradle 9 deprecation warnings and has a protobuf
  plugin conflict when dotted instrumentation arguments are supplied with `-P`.

## Lessons learned

- Cancellation is step-bounded, not safely expressible as one universal wall-clock
  number; per-run step calibration avoids device-specific false failures.
- JNI modified UTF-8 is not valid model input. Prompt strings require explicit UTF-16 to
  standard UTF-8 conversion, while generated pieces must cross as raw bytes because BPE
  tokens can split code points.
- Allocator reservations are not resident cost. Memory policy and tests must measure RSS
  after touching a near-full context.
- mmap makes unload/reload cheap and reclaimable, but only if the pack owner guarantees
  file immutability for the complete instance lifetime.
- A TCK needs sabotage tests; a green suite is credible only when deliberately broken
  runtimes fail the intended checks.
- Build/test evidence must survive AGP uninstall, so instrumentation artifacts belong in
  Gradle's additional-test-output directory.

## Tasks 0–2 technical-debt review

### Must fix before Task 3 implementation

1. Run the pinned adapter/TCK/benchmark on a representative 4 GB arm64 device and
   calibrate the T1 performance/KV profiles.
2. Provision the hash-pinned model in the device-test job and make missing fixtures a CI
   failure. Local developer runs may still opt into a documented skip, but release CI
   must never report adapter conformance when all model-backed tests were skipped.
3. Close the `RequestProcessor` publication race: a concurrent `cancel()` can observe an
   `ActiveRequest` before its `lateinit job` is assigned.
4. Resolve the Task 0 callback contract mismatch: `RequestListener` documents
   `onAccepted` before every terminal callback, while validation failures currently emit
   `onFailed` directly.
5. Replace propagation of arbitrary exception messages in `RequestProcessor` with typed,
   content-free internal failures. Future pipeline/model errors must not be able to echo
   user content across Binder or into diagnostics.

Items 3–5 are existing engine-core debt. They are intentionally not changed in this
Runtime-only checkpoint and must be handled in a focused stabilization change before
Model Manager production wiring.

### Can defer

- Gradle 9 deprecation cleanup and the protobuf-plugin/dotted-`-P` tooling conflict.
- Rename the benchmark's legacy `:spike` process/AIDL symbols.
- Add persistent native thread-pool experiments only if physical TTFT profiles show
  thread startup is material.
- Expand GGUF malformed-corpus coverage into continuous native fuzzing before user import.
- Automate raw benchmark/TCK artifact publication in CI; current checked-in summaries are
  sufficient for this milestone.
- Update `ENGINE_VERSION_NAME` when the engine product, rather than the runtime
  foundation, receives its first release version.

### Future architectural consideration

- Keep the future downloader in a separate network-enabled module; Model Manager remains
  offline and accepts staged pack sources through a narrow install port.
- Decide when model-pack manifest governance warrants its own compatibility tooling. It
  is durable signed data but not an SDK/AIDL public surface.
- Add optional prefix-cache, delegated-capability, acceleration, and pause/resume
  interfaces only when a first implementation and conformance tests exist.
- Consider isolating hostile model parsing in a sacrificial process if fuzzing and crash
  containment evidence show the engine-process boundary is insufficient.

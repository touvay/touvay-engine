# Production llama.cpp adapter

Runtime v1.0 adapter for llama.cpp behind `runtime-api`. It is intentionally not
registered with the engine; composition-root/model-manager wiring is Task 3.

## Upstream pin

- llama.cpp tag `b5199`
- commit `ced44be34290fab450f8344efa047d8a08e723b4`
- fetch with `scripts/fetch-llamacpp.ps1`

A pin bump requires review of upstream API/behavior changes, a green full build,
28/28 device TCK, and benchmark comparison against the previous pin.

## Documented constants

- Prefill chunk: 256 tokens.
- Cancellation: conservatively declared `OneStep`; the ggml abort callback normally
  interrupts within a graph step, but a tighter wall-clock bound awaits physical-device
  calibration.
- Qwen2.5-0.5B KV cost: 12,288 bytes/token (24 layers × 2 KV heads × 64 head dimension
  × key/value × fp16). The TCK measures resident growth after warming fixed buffers.
- CPU-only v1; `LoadConfig.threads` owns the bounded compute thread count.
- `use_mmap` follows `LoadConfig`; `mlock` and GPU layers are disabled.

## Conformance

`LlamaCppTck` is the adapter's Runtime v1.0 conformance claim. On 2026-07-12 the API 36
x86_64 emulator run completed 28/28 mandatory tests with zero skips/failures. Emulator
performance is informative only; representative arm64 T1/T2 calibration remains a
separate hardware gate.

The suite covers lifecycle, defensive cleanup, cross-thread use/cancellation, step-
bounded cancellation, exact detokenization parity, UTF-8 including supplementary/NUL
input, EOG filtering, sink failure, determinism, resident/native memory release,
full-context KV bounds, hostile GGUF inputs, content-free exceptions/logs, and an
informative performance report.

# Rewrite Benchmark Suite v1

**Status:** Product-quality baseline suite  
**Capability:** `text.rewrite@1`  
**Corpus:** `benchmarks/rewrite/corpus-v1.tsv` (105 synthetic cases)

## Methodology

The suite has two mandatory runs against the same model revision, decode policy,
Engine commit, and physical-device state.

1. The production Rewrite corpus run executes through the typed SDK, Binder,
   signed pack, production prompt asset, Coordinator, and authoritative Rewrite
   result. It records wall and Engine TTFT, end-to-end latency, streaming facts,
   quality, Engine-process PSS, battery counters, thermal state, and cancellation.
2. The Runtime run uses the existing production llama.cpp adapter benchmark to
   record exact decoded tokens/second, prefill tokens/second, model load/reload,
   memory, cancellation, battery counters, thermal state, thread sweep, and
   context sweep.

Exact token counts remain a Runtime measurement. The public Rewrite response does
not expose model tokens, and this suite does not change that contract.

### Controlled device procedure

- Use a physical API 29+ arm64 device; record build fingerprint and total RAM.
- Start at 60–80% battery, unplugged, Battery Saver off, airplane mode on, screen
  fixed at 50%, and device idle at thermal status NONE or LIGHT.
- Reboot, wait five minutes, close other apps, then run three complete repetitions.
- Do not interact with the device during a run. Reject runs interrupted by charging,
  OS updates, calls, critical thermal status, or missing energy counters when energy
  is a release gate.
- Compare the median of three repetitions. Preserve every raw JSON, Batterystats
  check-in, thermal dump, device-property file, corpus hash, model digest, and run
  manifest.

### Cold and warm definitions

- **Cold:** a new bound Engine service/model lifecycle followed by capability-ready
  discovery and the first Rewrite. Wall TTFT and end-to-end latency include connect,
  signed-pack/catalog readiness, model acquisition, prefill, and generation.
- **Warm:** subsequent Rewrite requests on the retained client/model lifecycle.
- The full corpus runs warm. One balanced case per category runs cold. Quick mode
  runs two warm cases per category and is for smoke testing only.

### Battery, memory, and thermal

- Engine PSS is sampled every 50 ms during each Rewrite. Reports include before,
  peak, and after PSS.
- Battery energy and charge counters are sampled before and after the suite. When
  `BATTERY_PROPERTY_ENERGY_COUNTER` is unavailable, the report records null and the
  captured Batterystats check-in becomes the evidence source.
- Thermal status/headroom and battery temperature are captured with each result and
  at suite boundaries. Sustained-run degradation is evaluated from ordered warm
  cases, not from a single snapshot.
- Battery comparisons use energy per completed warm case and require identical screen,
  radio, charging, and ambient conditions.

### Model variants

Run every candidate quantization/model as a separate immutable variant. Runtime-only
performance variants may use any verified local GGUF with `-SkipQuality`. A variant is
eligible for quality comparison only when it is packaged as a correctly signed Rewrite
pack with its exact prompt asset and metadata. Record model ID, version, SHA-256,
quantization, Runtime adapter revision, and Engine commit. Never compare unsigned or
prompt-mismatched output as a production quality result.

## Running

From the Engine repository on Windows:

```powershell
.\scripts\run-rewrite-benchmark.ps1 `
  -ModelPath .\models\qwen2.5-0.5b-instruct-q4_k_m.gguf `
  -ModelVariant qwen2.5-0.5b-q4_k_m
```

Smoke run:

```powershell
.\scripts\run-rewrite-benchmark.ps1 `
  -ModelPath .\models\qwen2.5-0.5b-instruct-q4_k_m.gguf `
  -ModelVariant qwen2.5-0.5b-q4_k_m -Quick
```

After an approved baseline exists, add `-EnforceThresholds`. Results are written
under ignored `benchmarks/results/<timestamp>-<variant>/`.

Compare an approved baseline with a candidate:

```powershell
.\scripts\compare-rewrite-benchmark.ps1 `
  -BaselineRewrite <baseline>\rewrite-benchmark.json `
  -CandidateRewrite <candidate>\rewrite-benchmark.json `
  -BaselineRuntime <baseline>\runtime-benchmark.json `
  -CandidateRuntime <candidate>\runtime-benchmark.json
```

## Quality scoring

Each case receives five deterministic scores in `[0,1]`:

- character n-gram F-score averaged over n=1…6;
- Unicode token multiset F-score;
- required-term recall for explicit meaning anchors;
- forbidden-term compliance; and
- requested-length compliance.

The composite is:

```text
0.40 × character F-score
+ 0.20 × token F-score
+ 0.25 × required-term recall
+ 0.15 × mean(forbidden-term compliance, length compliance)
```

Reference similarity is not treated as a complete language-quality judgment. Before
promoting a model, reviewers must manually blind-review a stratified 20-case sample for
meaning preservation, fluency, tone, harmful additions, and locale correctness. Any
meaning inversion, fabricated fact, unsafe addition, or wrong-language response is a
release blocker regardless of aggregate score.

## Absolute acceptance thresholds

| Metric | Threshold |
|---|---:|
| Mean composite quality | ≥ 0.72 |
| Per-category mean quality | ≥ 0.65 |
| Lowest individual case | ≥ 0.40 |
| Mean required-term recall | ≥ 0.95 |
| Mean forbidden-term compliance | ≥ 0.98 |
| Warm TTFT p95 | ≤ 2,500 ms |
| Cold wall TTFT p95 | ≤ 8,000 ms |
| Warm end-to-end latency p95 | ≤ 15,000 ms |
| Exact decode throughput | ≥ 8 tokens/s |
| Cancellation latency p95 / max | ≤ 150 ms / ≤ 500 ms |
| Peak Engine PSS | ≤ 900 MiB |
| Energy per warm case | ≤ 15 mWh when counter is supported |
| Maximum accepted thermal status | ≤ SEVERE; never CRITICAL |
| Sustained warm TTFT degradation | ≤ 35% between identical pre/post-corpus sentinels |

These are v1 release floors, not claimed measurements. The first three controlled Pixel
8 full runs establish the approved baseline; thresholds may tighten only through a
reviewed policy-version update, never by editing historical results.

## Regression policy

A candidate fails if it violates an absolute threshold or, against the approved baseline:

- mean quality falls by more than 0.02 absolute;
- any category mean falls by more than 0.03 absolute;
- TTFT or end-to-end p95 increases by more than 10%;
- exact decode throughput decreases by more than 8%;
- peak PSS or energy per case increases by more than 10%; or
- cancellation p95 increases by more than 50 ms.

All comparisons require the same corpus hash, device class, thermal starting condition,
decode policy, and run count. A model, prompt asset, capability implementation revision,
Runtime revision, or decode-policy change requires a new candidate report. Baselines are
promoted only from three reproducible full runs plus manual quality review. Quick runs
never establish or replace a baseline.

# Runtime TCK — Technology Compatibility Kit Design

**Status:** Approved and implemented — Runtime v1.0 (Task 2.1, 2026-07-12)
**Implements:** `docs/runtime/runtime-spi.md` as executable tests.
**Goal:** any runtime — llama.cpp, LiteRT-LM, ExecuTorch, an AICore delegate, a fork's
adapter — passes the same kit **without engine changes**. "Conformant" has exactly one
definition: the TCK is green.

The device suite deliberately remains 28 JUnit methods. Requirements that share one
execution path are verified together: TH-02 by CX-01, CX-05 by CX-06, ST-03 by the
ST-02 method's exact backend-detokenization sub-check, ME-03 by ME-02's full-context
resident-growth sub-check, and informative PF report creation by LC-02.

## 1. Shape

A Gradle module `runtime/runtime-tck` published as a test library:

```
runtime-tck/
├── src/main/kotlin/…/tck/
│   ├── RuntimeTckSubject.kt      # what an adapter hands the kit (see §2)
│   ├── AbstractRuntimeTck.kt     # JUnit4 base class: all TCK-* tests
│   ├── TckReport.kt              # JSON report writer (same schema family as the spike)
│   └── profiles/                 # tier performance profiles (data, not code)
└── src/test/kotlin/…             # TCK self-test against FakeRuntime (the kit is
                                  # itself tested: FakeRuntime passes; sabotaged
                                  # variants — leaky, out-of-order, cancel-ignoring —
                                  # must FAIL the corresponding test)
```

An adapter conforms by adding one androidTest class:

```kotlin
class LlamaCppTck : AbstractRuntimeTck() {
    override fun subject() = RuntimeTckSubject(
        runtime = LlamaCppRuntime(),
        conformancePack = /* smallest real pack for this format */,
        determinismPack = /* may be the same pack */,
        documentedPrefillChunk = 512,
        declaredCancelBound = CancelBound.OneStep, // or Tighter(ms) if abort-hooked
    )
}
```

Two execution modes, same tests:
- **JVM mode** — runs for pure-JVM runtimes (FakeRuntime; validates the kit itself in
  ordinary CI).
- **Device mode** (androidTest) — real adapters; functional tests run on emulator CI,
  performance gates evaluate only on physical device tiers (emulator results are
  recorded as informative — the spike proved emulator numbers are not representative).

## 2. Adapter-supplied fixtures

| Fixture | Requirement |
|---|---|
| `conformancePack` | Smallest *real* model for the runtime's format that exercises the full path (tokenize→prefill→decode→EOG). Kept small so the TCK stays runnable in minutes; fetched by hash-pinned script, never committed (models/ policy). |
| `determinismPack` | A pack for which greedy decoding is deterministic. Usually the same pack. |
| Documented constants | Prefill chunk size (SPI-CX-2), declared cancel bound (SPI-CX-3/4), per-token KV estimate (SPI-ME-2). The TCK *verifies documentation against behavior*. |
| Malformed-file corpus | Provided by the TCK (truncations, bit-flips, oversized headers of the adapter's own conformance pack, generated at runtime) — adapter doesn't supply, adapter must survive (§7). |

Delegated-capability runtimes (e.g. AICore) run a reduced profile: lifecycle, error,
cancellation, and streaming categories apply; token-level categories (determinism,
tokenize, KV memory) are N/A by declared capability. The subject declares
`tokenLevel = false` and the kit selects the applicable test set — one kit, two
profiles, still zero engine changes.

## 3. Test categories → requirements

Every test cites the SPI requirement it enforces. Mandatory unless marked (I)nformative.

### TCK-LC — Lifecycle
| Test | Verifies |
|---|---|
| LC-01 probe is cheap, side-effect-free, repeatable | SPI-LC-1 |
| LC-02 load → close → load ×5, no state bleed between instances | SPI-LC-2/3, SPI-OW-1 |
| LC-03 double-close instance & session are no-ops | SPI-LC-4/10 |
| LC-04 every method after close throws `IllegalStateException` | SPI-LC-5 |
| LC-05 close instance with a live (idle) session: defensive release, no crash | SPI-LC-6 |
| LC-06 `tokenize` concurrent with an active decode on another thread | SPI-LC-7 |
| LC-07 load failure (missing file) leaves no native allocation behind | SPI-LC-2, ME-4 |

### TCK-TH — Threading
| Test | Verifies |
|---|---|
| TH-01 sequential session calls from different threads | SPI-TH-2 |
| TH-02 cancel from a foreign thread during decode | SPI-TH-3, CX-7 |
| TH-03 sink invoked on the decoding thread, never the main looper | SPI-TH-1/5 |
| TH-04 after close, no adapter threads remain (thread enumeration diff) | SPI-TH-4 |

TH-02 is enforced by CX-01: decode runs on the test thread while cancellation is
signalled by the watcher thread, with termination and latency assertions.

### TCK-CX — Cancellation
| Test | Verifies |
|---|---|
| CX-01 cancel mid-decode: returns ≤ declared bound; measured against the same run's per-step time histogram (bound = max(observed step) × 1.5 + 50 ms for `OneStep` subjects) | SPI-CX-1/3/4 |
| CX-02 cancel mid-prefill on a long prompt: returns ≤ one documented chunk | SPI-CX-2 |
| CX-03 pre-set signal: immediate return, zero tokens/chunks | SPI-CX-6 |
| CX-04 cancel racing natural completion ×20: no crash/hang, ≤ 1-step overshoot | SPI-CX-7 |
| CX-05 no exception from cancellation itself | SPI-CX-5 |
| CX-06 post-cancel: only close() is exercised (kit never reuses; asserts close works) | SPI-LC-9 |

CX-06 also asserts SPI-CX-5: cancellation itself propagates no exception.

The per-step histogram approach makes CX-01 device-independent: the spike showed
wall-clock varies 20× with load (65 ms–1.36 s) while the *step-bounded* property held —
so the kit measures the property, and per-tier wall-clock budgets live in §6 profiles.

### TCK-ST — Streaming
| Test | Verifies |
|---|---|
| ST-01 pieces arrive in order, one per token, count ≤ maxTokens | SPI-ST-1/5 |
| ST-02 UTF-8 validity of every piece; multi-byte content prompt (emoji/CJK) reassembles exactly | SPI-ST-2/3 |
| ST-03 concatenated pieces equal detokenized emitted ids | SPI-ST-3 |
| ST-04 EOG never surfaced to the sink | SPI-ST-4 |
| ST-05 throwing sink: generation stops, exception propagates, session closes cleanly | SPI-ST-6 |

ST-03 executes inside the ST-02 JUnit method and compares concatenated streamed pieces
with adapter-supplied backend detokenization of the emitted token ids. The same check
round-trips CJK, a supplementary emoji, and embedded NUL through tokenization.

### TCK-DT — Determinism (token-level subjects)
| Test | Verifies |
|---|---|
| DT-01 greedy decode, fixed prompt: identical token-id sequence across 3 runs, fresh sessions | golden-test foundation (§18 of ARCHITECTURE.md) |
| DT-02 identical across instance reload | no hidden per-load state |

### TCK-ME — Memory
| Test | Verifies |
|---|---|
| ME-01 native heap after final close within tolerance of pre-load baseline (default ±16 MB, allocator caching allowance; evidence shows ≈0 achievable) | SPI-ME-4 |
| ME-02 RSS after close ≤ baseline + 10% of model size | SPI-MM-4 |
| ME-03 resident growth during a full-context generation ≤ declared KV estimate × 1.5 (measures **resident**, not reservations) | SPI-ME-2/3 |
| ME-04 load/close ×5: no monotonic native-heap growth (leak detector) | SPI-ME-4 |

ME-03 executes inside ME-02 after warming fixed compute buffers; incremental resident
growth at near-full context must remain within the documented KV bytes/token ×1.5 plus
a 4 MiB `/proc` measurement allowance.

### TCK-ER — Errors
| Test | Verifies |
|---|---|
| ER-01 missing/empty pack file → `IllegalArgumentException`, no native alloc left | SPI-ER-1 |
| ER-02 malformed-file corpus (truncated / bit-flipped / header-oversized conformance pack): every sample → clean exception, zero native crashes | SPI-ER-5 |
| ER-03 sentinel privacy test: prompt contains `TCK-SENTINEL-7f3a`; assert the sentinel appears in **no** exception message and no logcat line emitted by the adapter | SPI-ER-4 |
| ER-04 absurd config (ctx=0, threads=0/negative) → IAE, not crash | SPI-ER-1 |

### TCK-PF — Performance (device tiers; (I) on emulator)
Runs the spike's scenario shapes (short/medium prompt, burst, thread sanity) through
the SPI and writes a `TckReport` JSON. Gates evaluate against `profiles/` data:

| Profile | TTFT short prompt (warm instance) | Decode floor | Sustained burst degradation |
|---|---|---|---|
| `t1-4gb.json` | ≤ 1,500 ms | ≥ 8 tok/s | ≤ 40% over 8 requests |
| `t2-8gb.json` | ≤ 800 ms | ≥ 15 tok/s | ≤ 30% |

Profile numbers are **initial hypotheses** — they gate nothing until the first
physical-device dataset calibrates them (explicitly revisited then; the kit refuses to
"pass" PF on an emulator, it only records).

`TckReport` is written during LC-02. Android connected tests use Gradle's
`additionalTestOutputDir`, so reports are copied to build outputs before AGP uninstalls
the test APK; JVM self-tests write to their temporary fixture directory.

## 4. Pass criteria

An adapter is **conformant** when all mandatory categories are green in device mode on
at least one physical device per supported tier, and PF meets the tier profile on each.
Until physical devices are in CI, the interim bar for merging an adapter is: all
mandatory categories green on emulator + PF recorded (informative) + no open TCK
waivers. The llama.cpp adapter met this bar at 28/28 with zero skips/failures on
2026-07-12; evidence is archived under `docs/runtime/results/`. Waivers (documented,
time-boxed) require explicit approval and live in the adapter's README.

## 5. Versioning

The TCK is versioned with `runtime-api` (same repo, same review). New tests are minor
bumps; changing an existing test's semantics is a major bump and requires re-running
all adapters. A conformance claim always names the TCK version.

## 6. Why this kit is trustworthy (self-test)

`runtime-tck`'s own test suite runs the kit against:
- `FakeRuntime` (correct) — must pass everything;
- sabotaged fakes — `LeakyFake` (skips frees) must fail ME-01/04, `ReorderingFake`
  must fail ST-03, `CancelIgnoringFake` must fail CX-01, `CrashyParserFake` must fail
  ER-02, etc.

The implemented sabotage suite additionally proves EOG leakage, nondeterminism,
configuration-validation gaps, and user-content log leakage are detected.

A kit that cannot detect the bugs it exists to catch is decoration; the sabotage suite
is therefore mandatory in ordinary JVM CI.

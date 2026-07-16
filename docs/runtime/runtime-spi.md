# Runtime SPI Specification (v1)

**Status:** Stable — Runtime v1.0 (implemented 2026-07-12; frozen 2026-07-15)
**Normative for:** `runtime/runtime-api` and every runtime adapter.
**Executable form:** `docs/runtime/runtime-tck.md` (each requirement here maps to TCK tests).
**Evidence base:** llama.cpp spike, `docs/spikes/llamacpp-feasibility.md` (cited as *[spike]*).

RFC-2119 keywords (MUST/SHOULD/MAY) are used normatively. Requirements are numbered
`SPI-<area>-<n>` for TCK traceability.

---

## 1. Roles and terms

| Term | Meaning |
|---|---|
| **Engine** | The caller: model manager + scheduler in `engine-core`/`engine-service`. The only client of the SPI. |
| **Adapter** | A runtime implementation (`runtime-llamacpp`, `runtime-litert`, …). |
| **Runtime** | `InferenceRuntime`: stateless entry point (probe, load). |
| **Instance** | `ModelInstance`: one loaded model (weights + tokenizer). |
| **Session** | `InferenceSession`: one generation context (KV cache) borrowed from an instance. |

Trust model: adapters are engine-internal code (compiled in, ADR-006). The SPI contract
exists for *correctness and replaceability*, not for defending against malicious
adapters. Model **files**, however, are untrusted input (§16) — parsing them safely is
the adapter's duty (SPI-ER-5).

## 2. Surface (v1)

The v1 surface is the existing `runtime-api` plus one **additive** change motivated by
the spike:

> **SPI change: `ModelInstance.tokenize(text: String): TokenSequence`.**
> Rationale [spike]: capability pipelines produce text; `InferenceSession.prefill`
> consumes tokens; tokenization is model-owned (vocab lives in the model), so the spike
> had to add it on the concrete class. Every token-level runtime needs it; promoting it
> to the SPI keeps pipelines runtime-agnostic. Delegated-capability runtimes (AICore)
> never see tokens and are unaffected (they implement `SupportsDelegatedCapability`
> when that feature interface lands).

Optional typed feature interfaces (prefix cache, constrained decoding, vision, LoRA,
delegated capabilities — ARCHITECTURE.md §12) are added additively when the first
supporting runtime lands; they are out of scope for this v1 spec except where noted.

## 3. Lifecycle and state machines

### 3.1 Runtime

```
        probe(device)                    loadModel(pack, config)
REGISTERED ────────► (Available|Unavailable)      │
   ▲                                              ▼
   └── stateless; no state machine ────► ModelInstance (new, independent)
```

- **SPI-LC-1** `probe` MUST be cheap (<50 ms), allocation-light, and side-effect-free.
  It MUST NOT touch model files.
- **SPI-LC-2** `loadModel` MUST either return a usable instance or throw; no
  half-loaded state may leak (all natively allocated resources freed on the failure path).
- **SPI-LC-3** A runtime MUST support multiple concurrent instances *structurally*
  (no global mutable state keyed by "the" model); the engine's budget manager decides
  whether concurrency is actually exercised per tier.

### 3.2 ModelInstance

```
             createSession()*
   ┌────────────────────────────┐
   ▼                            │
LOADED ──── close() ────► CLOSED (terminal)
```

- **SPI-LC-4** `close()` MUST be idempotent and MUST NOT throw.
- **SPI-LC-5** Any call other than `close()` on a closed instance MUST throw
  `IllegalStateException`.
- **SPI-LC-6** The engine guarantees it will close all sessions before closing their
  instance. Adapters MUST NOT rely on it for *correctness*: `close()` on an instance
  with live sessions MUST defensively release those sessions too (log-and-free, never
  undefined behavior).
- **SPI-LC-7** `tokenize` MUST be pure (no session/KV state touched) and callable
  concurrently with an active session on another thread.

### 3.3 InferenceSession

```
        prefill()          decode()
READY ───────────► PRIMED ───────────► READY   (further prefill/decode legal)
  │                  │                   │
  │     cancel observed during either    │
  │                  ▼                   │
  └──────────► CANCELLED ── close() only ┘──── close() ────► CLOSED (terminal)
```

- **SPI-LC-8** Sessions are single-owner: the engine serializes prefill/decode; at most
  one is executing at any time. Adapters MAY assert this, MUST NOT deadlock if violated.
- **SPI-LC-9** After a cancellation has been *observed* (prefill returned early or
  decode stopped due to the signal), the only operation the engine may invoke is
  `close()`. Rationale: partial KV state has no defined semantics in v1; request
  coalescing discards the session anyway. Adapters MUST NOT be required to support
  post-cancel reuse. *Caller corollary (found by TCK-CX-04 against the real adapter):
  once a session's signal is set, the caller cannot know whether an in-flight call
  observed it — so after any call returns with the signal set, the caller MUST issue
  only `close()`. A pre-set signal on a READY session remains a legal no-op (SPI-CX-6).*
- **SPI-LC-10** `close()` on a session: idempotent, non-throwing, frees the KV cache.

## 4. Threading model

- **SPI-TH-1** All SPI calls arrive on engine worker threads — never the main thread.
  Adapters MUST NOT dispatch work to the Android main looper.
- **SPI-TH-2** Sequential calls on one session MAY arrive on *different* threads. The
  engine provides happens-before ordering between them; adapters MUST NOT require
  thread affinity (no thread-local native state keyed to first-use thread).
- **SPI-TH-3** `CancelSignal.isCancelled` reads and `cancel()`-style writers are
  cross-thread by design; adapter-side polling MUST be tear-free (atomic).
- **SPI-TH-4** Internal worker pools (e.g. ggml compute threads) are adapter-owned,
  MUST be bounded by `LoadConfig.threads`, and MUST NOT outlive the owning
  instance/session `close()`.
- **SPI-TH-5** `TokenSink.onToken` is invoked on the decoding thread. The engine keeps
  it non-blocking using the fixed-capacity per-quantum accumulator from ADR-018;
  adapters MUST NOT assume the sink is instantaneous but MAY assume it is not I/O-bound.

## 5. Ownership and memory model

### 5.1 Object ownership

| Resource | Owner | Notes |
|---|---|---|
| `ModelInstance` lifecycle | Engine model manager | refcounting, idle unload, eviction — never the adapter |
| Session lifecycle | Engine request execution | one session per request in v1 |
| Native handles | Adapter (private) | MUST never escape the adapter module |
| `CancelSignal` | Engine | adapter polls only |
| Token arrays / prompt text | Caller, until the call returns | adapter MUST copy anything it retains |
| Piece strings from sink | Adapter emits, engine consumes | adapter MUST NOT reuse/mutate emitted buffers |

- **SPI-OW-1** Adapters MUST NOT cache instances/sessions across `loadModel` calls or
  share state between instances (the model manager is the only cache).
- **SPI-OW-2** Adapters SHOULD attach a leak backstop (e.g. `java.lang.ref.Cleaner`)
  to native handles that frees and logs if the engine leaks — triggering it is an
  engine bug, not a recovery path.

### 5.2 Memory accounting (two-component model — evidence-refined)

*[spike]* measured: allocator-reported "native heap" reached ~815 MB with a live
context while resident memory rose only 32–76 MB — backends *reserve* compute/KV
buffers virtually and Linux commits pages lazily.

- **SPI-ME-1** `ModelInstanceInfo.estimatedRamBytes` = weights + fixed per-instance
  buffers (excludes per-session KV). For mmap'd weights this approximates the mapped
  file size.
- **SPI-ME-2** Adapters SHOULD document per-token KV cost (bytes/token) per model
  geometry so the budget manager can cap contexts (ARCHITECTURE.md §14.3).
- **SPI-ME-3** Budgeting and TCK verification are done on **resident** memory under
  full-context load, never on allocator-reported reservations and never on
  creation-time RSS.
- **SPI-ME-4** After `instance.close()` returns (all sessions closed), the adapter's
  native allocations MUST be released: native-heap delta vs pre-load baseline within
  the TCK tolerance (evidence it's achievable: 41 MB → 5 MB, ≈ baseline [spike]).

### 5.3 mmap lifecycle

- **SPI-MM-1** Weights MAY be mmap'd read-only. The engine guarantees pack files are
  present and immutable from `loadModel` until `close()` returns (pack refcount).
- **SPI-MM-2** Adapters MUST tolerate page-cache eviction at any moment (transparent
  refault; no correctness dependence on residency).
- **SPI-MM-3** Adapters MUST NOT `mlock` model memory (defeats §14.3's reclaimability;
  a 4 GB device relies on evictable weights).
- **SPI-MM-4** All mappings are removed by `close()` (TCK verifies RSS return toward
  baseline; evidence: 429 MB → 79 MB [spike]).
- Warm vs cold consequence (informative): reload with a hot page cache measured 0.76 s
  vs 3.6 s cold [spike] — the model manager's idle-unload strategy is cheap to reverse
  while the cache stays warm; adapters MUST keep `loadModel` safely repeatable.

## 6. Cancellation semantics (evidence-refined)

- **SPI-CX-1** Decode: the adapter MUST poll the signal at least once per generated
  token and stop before computing the next.
- **SPI-CX-2** Prefill: the adapter MUST poll at least once per internal batch chunk
  (chunk size is an adapter constant it MUST document; spike used 512).
- **SPI-CX-3** Latency bound is **step-bounded, not wall-clock**: after the signal is
  set, `decode` MUST return within one decode step plus O(1) bookkeeping; `prefill`
  within one chunk. *[spike]* measured 65 ms–1.36 s on a jittery emulator — exactly
  one in-flight step; wall-clock budgets are per-tier TCK profiles, not spec constants.
- **SPI-CX-4** Adapters SHOULD additionally hook their backend's abort mechanism where
  one exists (e.g. ggml abort callback) to interrupt *inside* a step and tighten the
  worst case; when they do, the TCK measures against the tighter documented bound.
- **SPI-CX-5** Cancellation is not an error at the SPI level: no exception is thrown
  for it; decode simply returns having emitted what it emitted. (The engine layers its
  own `Cancelled` failure semantics above.)
- **SPI-CX-6** A signal set *before* the call MUST cause an immediate return
  (prefill: zero chunks; decode: zero tokens).
- **SPI-CX-7** `cancel` signalling MUST be callable from any thread and MUST be safe
  against races with normal completion (at-most-one-step overshoot, no crash, no hang).

## 7. Streaming semantics

- **SPI-ST-1** `onToken(tokenId, piece)` MUST be called exactly once per generated
  token, in generation order, before the token after it is computed.
- **SPI-ST-2** `piece` MUST be valid UTF-8 text; adapters whose backends produce raw
  bytes MUST reassemble across token boundaries (BPE pieces can split code points —
  proven necessary in the spike) and MAY emit an empty string while bytes are pending.
- **SPI-ST-3** Concatenation of all pieces (plus a final flush, if the adapter buffers)
  MUST equal the decoded text of the emitted token ids.
- **SPI-ST-4** End-of-generation tokens (EOG/EOS) MUST NOT be emitted to the sink.
- **SPI-ST-5** `DecodeParams.maxTokens` is a hard cap.
- **SPI-ST-6** If the sink throws, the adapter MUST stop generation, restore its
  invariants (session becomes CANCELLED-equivalent), and propagate the exception.

## 8. Error model

- **SPI-ER-1** Input/contract violations → `IllegalArgumentException`
  (bad pack contents, empty required file, invalid config values).
- **SPI-ER-2** Lifecycle misuse → `IllegalStateException` (use after close, etc.).
- **SPI-ER-3** Backend failures (allocation failure, corrupt state, decode error) →
  any `RuntimeException`; the engine maps them to `RequestFailure.Internal`. Adapters
  SHOULD prefer descriptive dedicated types but MUST NOT require the engine to know them.
- **SPI-ER-4** Exception messages MUST NOT contain user content (prompt text, token
  pieces) — messages may cross into logs (§16). Model *file paths* are permitted.
- **SPI-ER-5** Malformed/hostile model files MUST produce a clean exception, never a
  native abort: the file parser is the adapter's untrusted-input boundary and MUST be
  fuzz-tested (§16, TCK-ER).
- **SPI-ER-6** Adapters MUST NOT call `abort()`/`exit()` or install signal handlers.

## 9. Design rationale (review items resolved, 2026-07-12)

**Tokenization lives in the SPI, not the pipeline.** The tokenizer is part of the model
artifact (vocab/merges/special tokens ship inside GGUF/bundles) and must match the
consuming runtime bit-exactly; a pipeline-owned tokenizer would mean reimplementing
SentencePiece/BPE variants per model family and maintaining eternal parity with each
runtime's quirks — silent-quality-bug risk with no benefit. Pipeline needs are met
without ownership: exact truncation = tokenize-then-truncate the `TokenSequence`
(model is loaded before prefill anyway; tokenize is microseconds); pre-load size
rejection uses a conservative character heuristic where precision is irrelevant.
Delegated runtimes never see tokens and are unaffected.

**Post-cancel-only-close stands (SPI-LC-9).** No supported runtime offers safe
post-cancel reuse cheaply: llama.cpp's abort can fire mid-graph leaving in-flight-batch
KV writes in an undocumented partial state (rollback would need per-batch bookkeeping
against semantics upstream doesn't guarantee across versions); LiteRT-LM session
internals are opaque; AICore has no sessions. The only benefit of reuse — keeping a KV
prefix to skip re-prefill — is delivered properly by the planned `SupportsPrefixCache`
feature interface (explicit, validated prefix handles created outside the cancel path).
Reuse would also roughly double the determinism test surface. Complexity unjustified.

## 10. Resource cleanup summary (normative checklist)

On `session.close()`: KV cache and per-session buffers freed.
On `instance.close()`: remaining sessions defensively freed → weights unmapped →
fixed buffers freed → tokenizer freed. Native heap ≈ pre-load baseline (SPI-ME-4);
RSS falls toward baseline (SPI-MM-4). No files, locks, or persistent state left behind
— process death at any instant must be equivalent to cleanup (ADR-012 discipline
applies to adapters too).

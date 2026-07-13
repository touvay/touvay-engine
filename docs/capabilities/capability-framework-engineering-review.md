# Capability Framework v1 Engineering Review

**Date:** 2026-07-13

**Decision:** Ready for merge and architecture review

## Delivered scope

- Production `CapabilityDefinition`/`PreparedCapability`/`CapabilityAttempt` SPI with
  bounded payloads, immutable contracts, typed failures, and fresh-attempt ownership.
- Exact `CapabilityKey` registration, deterministic discovery, and exact-version lookup
  through the existing Execution Coordinator.
- `CapabilityExecutionPlan` with one to eight ordered semantic steps, prior-output-only
  references, bounded outputs, and `PromptExecutionStep` as the v1 step kind.
- Versioned `PromptRecipe` with reproducible source digest, typed semantic elements, and
  bounded data slots.
- Bounded protobuf-lite prompt-format asset parser, exact size/SHA-256 verification,
  recipe/revision/feature validation, substitution-only rendering, and final tokenization.
- Generic Capability-to-Execution adapter; no capability-specific Coordinator branch.
- Exact-revision asset access through the loaded Model Manager lease. The capability
  sees bytes only; storage paths, enumeration, deletion, and lease ownership remain in
  Model Manager.
- Reusable pure-JVM Capability TCK and test harness.

No rewrite, grammar, translation, summarization, production prompt, SDK facade, AIDL,
keyboard, UI, downloader, network, Scheduler policy, Runtime SPI, inference algorithm,
or engine routing policy was added.

## Validation evidence

| Check | Result |
|---|---|
| Full build, unit tests, Android lint, native adapter assembly | PASS |
| API compatibility (`apiCheck`) | PASS |
| Module dependency rules (`checkDependencyRules`) | PASS |
| Capability TCK mandatory checks | 20/20 PASS |
| Capability TCK sabotage self-tests | 2/2 PASS |
| Prompt golden/digest/compatibility/security/fuzz-style checks | PASS |
| Model lease bounded asset lifecycle test | PASS |
| Execution reserved-output token-budget regression | PASS |

Final command:

```text
gradlew build apiCheck checkDependencyRules
BUILD SUCCESSFUL
699 actionable tasks: 45 executed, 654 up-to-date
```

The prior clean full run executed 699 tasks from build outputs rather than relying only
on incrementality. No device test is required for this pure-JVM framework slice; the
unchanged Runtime device TCK remains the `foundation-v0.5` baseline.

## Architecture review

### Architecture and ownership

The implementation follows ADR-017, ADR-020, and ADR-021. The semantic
`CapabilityExecutionPlan` is deliberately separate from the routed execution plan:
capabilities declare intent and ordered dataflow; routing freezes model/profile/asset
bindings; each attempt receives a fresh binding. Initial orchestration explicitly
rejects more than one prompt step, while the Capability SPI and plan representation can
add multi-step orchestration later without changing capability contracts.

The bounded asset path preserves Model Manager ownership. `RuntimeModelLease` pins the
exact immutable revision, validates logical membership and byte ceilings, performs a
bounded read, and becomes unusable after release. The framework independently verifies
the frozen size and digest before protobuf parsing.

### API compatibility

No published API surface changed in `touvay-contract`, `touvay-sdk`, or `runtime-api`.
The Runtime SPI is untouched. New framework types are engine-internal and the new TCK is
excluded from BCV by design. Exact schema-version lookup is additive inside engine-core.

### Dependency review

The new `capability-tck` module depends only on `engine-core`, `runtime-api`, JUnit, and
coroutines. Engine-core reuses the repository's existing protobuf-lite version; no new
external library or version was introduced. Dependency rules include the new module and
pass. No network or Android dependency enters the framework or TCK.

### Security and privacy

- Payload and prompt-asset byte arrays are defensively copied and never represented by
  Kotlin data classes.
- Content-bearing wrappers use content-free `toString` behavior.
- Framework exceptions contain only stable enum codes.
- Parser input is capped at 512 KiB, recursion is bounded, counts and UTF-8 expansion
  are validated, and hostile corpus cases return typed failures rather than arbitrary
  exceptions or errors.
- Prompt assets are exact-revision, exact-digest data. The format is substitution-only;
  there is no script execution, file include, environment access, or network access.
- User values are inserted only through declared typed slots and are checked before
  tokenization.

### Performance and memory

Registration and plans are immutable snapshots. Preparation performs no model load,
session creation, Binder call, or main-thread work. Prompt assets are capped at 512 KiB
and read once per attempt; rendering is linear in bounded literal plus slot bytes.
Tokenization remains serialized on the acquired model's inference lane. No new process,
background thread, cache, telemetry, or startup I/O was introduced.

## Known limitations and deferred risks

- Multi-step semantic plans are modeled and validated, but v1 execution accepts exactly
  one prompt step. Multi-step scheduling is a future execution-engine enhancement, not
  a Capability SPI redesign.
- Exact prompt-config asset binding is additive and deferred until a concrete capability
  demonstrates a config requirement. Prompt-format assets are complete in this slice.
- The raw `AttemptProgram.prompt()` compatibility hook remains for `dev.echo` and legacy
  execution tests. Production capabilities use `buildModelInput` exclusively.
- No production capability is registered, so real-model quality, model-family prompt
  assets, structured public schemas, and representative 4 GB arm64 calibration remain
  future approval gates.

These are explicit scope limits, not P1/P2 defects. No unresolved must-fix finding,
architectural violation, public API break, dependency violation, or security blocker was
found in the final review.

## Recommendation

Merge Capability Framework v1 as the completed framework milestone. Stop here for
architecture review. Do not begin a user-facing capability until its contract, prompt
recipe, model eligibility, quality plan, and privacy review receive separate approval.

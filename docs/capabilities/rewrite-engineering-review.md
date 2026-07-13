# Rewrite Capability v1 Engineering Review

**Status:** Ready for merge

**Reviewed:** 2026-07-13

**Capability:** `text.rewrite@1`

## Outcome

The authorized Capability 1 scope is complete. Rewrite is implemented as a data-driven
Capability Framework plugin and uses the existing Execution Coordinator, Scheduler,
Model Manager boundary, prompt-asset loader, Runtime session orchestration, streaming
credit protocol, cancellation path, retry rules, and terminal arbitration. No Runtime
SPI, Model Manager architecture, Execution Engine architecture, Scheduler policy, or
engine routing contract was changed.

No open P1 or P2 implementation finding remains. The implementation is ready for merge
as the first production capability. This is not approval for keyboard integration or an
end-user model release.

## Delivered scope

- additive protobuf-lite request, delta, and authoritative response schemas in
  `touvay-contract`, including frozen tone, length, and disposition enums;
- bounded parsing and validation for explicit source text, options, locale, and payload
  sizes;
- one-step semantic `ExecutionPlan` and typed `PromptRecipe` without model, Runtime, or
  filesystem identity;
- deterministic reference prompt-format asset for signed model-pack publishers and
  golden tests, with production loading restricted to the exact authenticated asset;
- JSON-string encoding of user source so model-family special-token syntax and prompt
  control markup remain data;
- bounded provisional protobuf deltas and deterministic authoritative final
  post-processing;
- typed `INVALID_OUTPUT` handling with content-free errors;
- generic Execution Engine registration at the engine-service composition root; and
- Capability TCK, golden, parser-negative/fuzz-style, streaming, output, cancellation,
  Coordinator integration, contract, and composition tests.

## Validation evidence

The final combined gate completed successfully:

```text
gradlew build apiCheck checkDependencyRules \
  :runtime:runtime-tck:test \
  :capabilities:capability-tck:test \
  :capabilities:capability-rewrite:testDebugUnitTest
```

| Gate | Result |
|---|---|
| Full build, unit tests, lint, and assembly | Pass |
| Binary/API compatibility (`apiCheck`) | Pass |
| Repository dependency rules | Pass |
| Capability Framework TCK self/sabotage suite | 22/22 |
| Rewrite suite, debug variant | 30/30 |
| Rewrite suite, release variant through full build | 30/30 |
| Runtime TCK JVM self/sabotage suite | 10/10 |
| Existing llama.cpp device conformance baseline | 28/28, unchanged |

The adapter device suite was not rerun because no Android device was connected during
this review. Runtime SPI and llama.cpp sources were not modified; the accepted API 36
x86_64 28/28 report remains the applicable adapter baseline. A connected device rerun
is still required for a Runtime change or release-candidate model/adapter change.

## Architecture and dependency review

- Clean Architecture boundaries remain intact. `capability-rewrite` depends only on
  `touvay-contract` and `engine-core`; engine-service is the only composition root.
- The capability module uses the Android library variant because the contract owner is
  an Android library. Its production Kotlin code remains Android-free.
- No external dependency or network capability was added.
- Public API evolution is additive: the versioned Rewrite protobuf types, capability
  key, and typed invalid-output error code. The BCV dump was reviewed and contains no
  removal or signature mutation.
- Context provenance remains internal to the approved Context architecture. Rewrite
  consumes only explicit request text and does not synthesize or expose Context
  provenance through its public schema.
- No new ADR is required; the implementation is an instance of ADR-014 and ADR-017–023.

## Security, privacy, and resource review

- User source, prompts, generated text, model identity, and paths are absent from logs
  and exception messages.
- Raw source cannot create prompt control tokens in the reference format; injection
  cases are covered by golden tests.
- Request, source, asset, generated output, delta, and final payloads are bounded.
- Deltas split only at Unicode scalar boundaries; malformed/control-bearing Runtime
  output is rejected before transport.
- Cancellation reaches the existing Runtime `CancelSignal`, and attempt-owned buffers
  and sessions are closed by existing Coordinator ownership rules.
- There is no storage, telemetry, downloader, networking, retrieval, memory, keyboard,
  or UI behavior in this slice.

## Known limitations and remaining risks

1. A signed, installed, schema-compatible model pack with the exact prompt asset is
   required before Rewrite can execute outside the host integration harness.
2. Production Binder ingress still has the documented diagnostic compatibility path;
   enabling end-user Coordinator routing requires the already-approved Router/model
   composition to be constructible. This capability does not fake that availability.
3. Host tests validate contracts and orchestration, not rewrite quality. A frozen
   evaluation set and representative 4 GB arm64 latency, RSS, and thermal calibration
   remain release gates.
4. Provisional streaming text may differ from the normalized authoritative final by
   outer whitespace or line endings; schema v1 explicitly requires clients to replace
   the preview with the final response.
5. The current Coordinator supports the approved single prompt step. Multi-step plan
   execution remains a separately gated framework extension.

## Principal-engineering conclusion

The implementation matches the frozen Capability, Context, Execution, Model Manager,
and Runtime contracts. Its public surface is versioned and additive, its inputs and
outputs are bounded, cancellation and streaming preserve existing ownership semantics,
and capability-specific logic does not leak into orchestration. Capability 1 Rewrite is
ready for merge. Do not begin keyboard integration or another capability without the
next explicit authorization.

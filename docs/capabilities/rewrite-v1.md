# Rewrite Capability v1

**Status:** Implemented; engineering review complete

**Capability key:** `text.rewrite@1`

**Implementation revision:** 1

**Normative baseline:** Architecture v1.0, Capability Framework v1.0, Context
Architecture v1.0, ADR-004, ADR-014, ADR-017–023

## Contract

Public protobuf schemas are owned by `touvay-contract` in `rewrite_v1.proto`.

`RewriteRequest` contains:

- required explicit source text;
- tone: neutral/default, formal, or casual;
- length target: preserve/default, shorter, or longer; and
- optional BCP 47 output locale.

The source is explicit request content. Rewrite never reads editor, keyboard, clipboard,
app screen, conversation, retrieval, memory, file, or network state. Omitted tone and
length use stable neutral/preserve defaults. Locale tags are bounded, validated, and
canonicalized; private-use-only and malformed tags are rejected.

Limits:

| Item | Limit |
|---|---:|
| Request payload | 12 KiB |
| Source text | 8 KiB UTF-8 |
| Generated text retained | 12 KiB UTF-8 |
| Serialized delta | 2 KiB |
| Serialized final | 16 KiB |
| Requested output | 256 model tokens |
| Minimum routed context | 1,024 tokens |

Source text is mandatory and semantically non-truncatable. If the exact routed
tokenizer cannot fit the complete rendered input after reserving output tokens, the
Coordinator returns a typed invalid-request/context-limit failure rather than rewriting
only part of the selection.

## Semantic ExecutionPlan

Rewrite declares exactly one `PromptExecutionStep`, `rewrite.generate`, with prepared
inputs for source, tone, length, and locale. It declares semantic requirements and
resource demand only; it does not name a model, pack, Runtime, device, or filesystem
path. The central Router freezes the exact model/profile/prompt-asset binding under
ADR-017/021.

Retry receives a fresh `RewriteAttempt` and the same prepared semantic input. Retry is
controlled solely by the frozen routed plan and is forbidden after the first public
delta.

## PromptRecipe and prompt asset

The code-owned recipe `text.rewrite.v1`, revision 1, declares:

- trusted rewrite instruction identity;
- typed source, tone, length, and locale data slots;
- a plain-text output constraint; and
- required asset feature `prompt.typed-slots`.

`RewriteReferencePromptAsset` produces deterministic protobuf-lite reference bytes for
pack publishers and golden tests. Production execution does not fall back to those
compiled bytes: it reads the exact template asset authenticated by the selected signed
model-pack manifest and frozen routed digest.

User source is encoded as one JSON string before slot substitution. Quotes, backslashes,
control characters, `<`, `>`, `&`, and line-separator characters are escaped. This is
required because Runtime tokenizers may recognize model-family special-token markup;
raw source is never allowed to create prompt control tokens. Tone, length, and locale
are finite validated values.

The reference asset instructs the model to preserve meaning, apply the structured
options, treat the JSON string as data, and return only rewritten text. Model-family
formatting remains signed pack data and must pass asset/recipe compatibility checks.

## Streaming and structured output

`RewriteDelta` is an append-only provisional preview. The Coordinator provides transport
sequence numbers; clients concatenate delta text in order. Deltas are coalesced per
bounded decode quantum, split only at Unicode scalar boundaries, and capped at 2 KiB
serialized.

The final `RewriteResponse` is authoritative. Clients replace provisional preview text
with its `text` field. `disposition` is `REWRITTEN` or `UNCHANGED` after deterministic
post-processing.

Post-processing:

1. joins ordered Runtime text pieces;
2. rejects malformed Unicode, NUL, disallowed control characters, and size overflow;
3. normalizes CRLF/CR to LF;
4. trims outer whitespace;
5. rejects empty output; and
6. emits the versioned protobuf response.

Raw token IDs and raw Runtime events never cross the capability contract. Provisional
deltas may differ in outer whitespace/line endings from the authoritative normalized
final. That relation is part of schema v1 and does not weaken the first-delta retry
commit point.

## Errors and cancellation

- malformed protobuf, invalid enums/options, invalid locale, blank source, or oversized
  source → `INVALID_REQUEST`;
- missing/invalid exact prompt asset → existing model/internal failure mapping;
- malformed, empty, control-bearing, or oversized model output → `INVALID_OUTPUT`;
- cancellation/deadline/preemption/runtime failure → existing Execution Coordinator
  semantics.

All errors are content-free. Parser, output, and asset exception messages never include
source text, prompt text, generated text, model identity, or paths. Preparation,
rendering, consumption, and finalization are cancellable; Coordinator cancellation
continues through the existing Runtime `CancelSignal` without a Runtime SPI change.

## Composition and availability

The engine-service composition root registers `RewriteCapabilityDefinition` in both the
exact Capability Framework registry and `ExecutionProgramRegistry` through the generic
`CapabilityExecutionProgramFactory`. `ExecutionEngineComponent` uses that production
registry by default. No capability-specific branch was added to the Coordinator,
Scheduler, Model Manager, or Runtime SPI.

Actual readiness still depends on the central Router finding an installed, verified,
schema-compatible model revision and signed prompt asset. The diagnostic Binder
`RequestProcessor` remains a compatibility path; activation of the production
Coordinator at service ingress requires the existing model/router composition to be
made constructible and is not faked by this capability.

## Testing

Rewrite inherits all 20 mandatory Capability TCK checks and adds:

- contract round-trip and frozen-enum tests;
- exact golden prompt tests for option/locale normalization;
- special-token/prompt-injection encoding tests;
- malformed and fuzz-style request corpus tests;
- provisional streaming reconstruction and Unicode boundary tests;
- structured final normalization/disposition tests;
- invalid/oversized model-output tests;
- cancellation through the production Coordinator and Runtime `CancelSignal`;
- successful Coordinator streaming/final integration; and
- composition-root exact-registration tests.

Real-model language quality is intentionally not claimed by host-side tests. A signed
eligible pack must pass the future Rewrite evaluation dataset and representative 4 GB
arm64 latency/RSS/thermal calibration before an end-user release.

Validation evidence and the final risk assessment are recorded in
[`rewrite-engineering-review.md`](rewrite-engineering-review.md).

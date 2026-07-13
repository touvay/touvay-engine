# ADR-023 — Deterministic context merge and exact token budgeting

**Status:** Accepted for Context Architecture v1.0 (2026-07-13)

## Context

Multiple bounded Context Providers may complete concurrently and may supply overlapping
facts. Prompt construction must preserve instruction/data separation, and model
tokenizers differ enough that byte or character counts are not exact token budgets.
Completion-order merging, raw string concatenation, or rerunning providers after a
candidate is selected would make outputs and retry behavior non-deterministic.

The model context window must reserve trusted control, model-format overhead, and output
tokens before optional context. A final fit decision is only authoritative after the
exact routed model and tokenizer are acquired.

## Decision

The Context Resolver validates typed contributions, then merges them using capability
policy rank, registered provider rank, kind, and request-local item identity. Completion
order never affects the result. Exclusive conflicts fail or resolve through explicit
typed precedence; optional absence is represented, not replaced with invented content.
Different context items are never silently concatenated.

Trusted instructions and output constraints remain typed control elements. Selection,
conversation, clipboard, retrieval, memory, and other user-bearing values remain
untrusted data through recipe rendering regardless of their text.

Budgeting is two-phase:

1. Before routing, hard item/byte/work limits provide conservative admission bounds.
2. After exact candidate acquisition, the capability projects the frozen context using
   the selected Runtime tokenizer and a versioned deterministic priority/truncation
   policy.

The final fully rendered model input is tokenized as one sequence; separately tokenized
fragment counts are estimates only. If it does not fit, policy-approved items are
removed or truncated, then the input is rerendered and retokenized within a hard
iteration bound. Trusted control is never silently removed. Mandatory content that
cannot fit produces a typed content-limit failure. Retry may produce a different valid
token projection for another tokenizer but cannot rerun providers or change semantic
priority.

## Consequences

- Equal frozen context, policy, asset, tokenizer, and limit produce equal model input.
- Provider concurrency improves latency without introducing merge nondeterminism.
- Exact token accounting prevents context-window overflow across runtime adapters.
- Prompt-injection separation is structural rather than phrase filtering.
- Capability TCKs must cover merge permutations, exact final tokenization, Unicode-safe
  truncation, mandatory overflow, and fallback tokenizers.
- Exact fit may require bounded rerender/retokenize iterations; that cost is accepted
  and measured outside typing threads.

## Alternatives considered

### Completion-order or last-writer-wins merge

Rejected. Scheduling timing would change semantic input and make tests flaky.

### Raw string concatenation

Rejected. It destroys provenance, trust roles, item boundaries, and deterministic
truncation.

### Estimate tokens from bytes or characters

Rejected. Tokenizers and model-family formatting differ, so estimates cannot enforce
the hard context limit.

### Model-generated context summarization

Rejected as an implicit policy. It adds hidden inference, latency, semantic drift, and
new failure/retry behavior. A future explicit multi-step capability may define it.

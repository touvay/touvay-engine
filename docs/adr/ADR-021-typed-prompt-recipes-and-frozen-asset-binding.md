# ADR-021 — Typed prompt recipes and frozen signed-asset binding

**Status:** Accepted for Capability Framework Architecture v1.0 (2026-07-13)

## Context

The Milestone 5 attempt port returns a raw prompt string. That keeps the execution
foundation small, but a raw string cannot prove instruction/data separation, bind a
prompt to an exact signed model-pack asset, enforce deterministic token truncation, or
support compatibility tests across model formats.

Architecture v1 requires engine-owned prompts and signed, declarative, data-only pack
templates. The first production capability must not accidentally turn a string template
or configuration map into an executable or model-coupled public contract.

## Decision

### Typed prompt recipe

A prompt step contains an immutable `PromptRecipe`, not a rendered string. The recipe
has a stable ID, monotonic revision, reproducible digest, ordered trusted instruction
elements, typed untrusted data/context slots, output constraints, and bounded modality
placeholders.

User and context values are supplied separately from the recipe. They can fill data
slots only; they cannot become trusted instructions or template structure.

### Signed prompt/config assets

Model packs may bind `FILE_ROLE_TEMPLATE` and `FILE_ROLE_CONFIG` assets. Those assets use
bounded, versioned protobuf-lite schemas owned by the Capability Framework. They are
substitution-only data: no loops, conditions, reflection, arbitrary functions, external
includes, environment access, or code execution.

The pack manifest authenticates exact asset path, role, size, and SHA-256 digest but does
not interpret the asset. Framework verification checks the digest and size again at
load, bounded-parses the asset, rejects unknown required features, and proves
compatibility with the exact capability key and prompt recipe revision.

### Frozen binding

Every routed prompt step freezes the exact recipe identity, model revision,
template/config references and digests, structured-output strategy, limits, and required
features. Retry uses only the primary or fallback bindings already in the frozen request
plan. Activation, rollback, or prompt asset changes do not affect an in-flight request.

### Asset and tokenizer ports

Capability code receives a bounded exact-revision asset reader and candidate tokenizer
through an attempt environment. It never receives model-store paths or opens arbitrary
files. The asset reader becomes invalid when the attempt lease ends.

The renderer preserves typed roles, applies deterministic capability-owned truncation,
reserves output tokens, tokenizes the final model input exactly, and rejects overflow.
Trusted control content is never silently truncated.

### Version fingerprint

The local reproducibility fingerprint includes capability key, implementation revision,
recipe ID/revision/digest, exact template/config digests, output-strategy revision, and
exact model revision. It contains no user content and is not exposed as model identity to
clients.

## Consequences

- Prompt injection boundaries are structural rather than naming conventions.
- Prompt/model compatibility and rollback are reproducible by exact digest.
- Pack authors need protobuf tooling and cannot hand-author arbitrary logic templates.
- `engine-core` gains a reviewed protobuf-lite dependency for internal prompt asset
  schemas; public contract and Runtime SPI surfaces remain unchanged.
- Rendering and token-budget logic become independently testable with synthetic assets
  and fake tokenizers.
- Existing `AttemptProgram.prompt(): String` remains a foundation compatibility method
  until the internal execution adapter migrates; production capabilities cannot use it
  directly.

## Alternatives considered

### Raw prompt strings

Rejected. They erase trust roles, asset compatibility, deterministic truncation, and
prompt revision identity.

### Mustache/Jinja-style templates

Rejected. Logic and expansion behavior create an executable semantic surface and make
resource bounds difficult to prove.

### JSON configuration bags

Rejected. They invite stringly typed behavior and require duplicate-key and semantic
validation rules without providing typed role/slot structure.

### Compiled-only prompts

Rejected as the only mechanism. Model-family formatting and prompt/config rollback must
be bindable to exact signed pack revisions without an engine release.

### Capability access to pack paths

Rejected. It bypasses Model Manager ownership, permits unrelated file enumeration, and
risks retaining files beyond the mmap/storage lease.

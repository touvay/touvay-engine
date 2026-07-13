# ADR-020 — Production Capability SPI and exact-key registry

**Status:** Accepted for Capability Framework Architecture v1.0 (2026-07-13)

## Context

The walking skeleton exposes `CapabilityPipeline.execute(payload, emit)` and registers
pipelines by capability ID. That was sufficient for `dev.echo`, but production
capabilities require bounded preparation, immutable plans, fresh retry attempts, prompt
asset binding, structured output validation, and side-by-side wire schema versions.

ADR-009 explicitly permits versions such as `text.rewrite@1` and `text.rewrite@2` to
coexist. An ID-only registry cannot represent that state. Letting capability modules
depend directly on Model Manager types would also leak catalog, storage, trust, and mmap
ownership into the semantic layer.

Multi-step capabilities must be possible later without replacing the SPI introduced for
the first single-prompt capability.

## Decision

### SPI ownership and lifecycle

`engine-core` owns an internal production Capability SPI. Concrete compile-time
`capability-*` modules implement it and are registered only at the composition root.
The SPI separates:

1. immutable process-lived `CapabilityDefinition`;
2. bounded request-scoped `PreparedCapability`;
3. immutable request-scoped `CapabilityExecutionPlan` with ordered semantic steps; and
4. fresh mutable `CapabilityAttempt` state for one routed step candidate.

The Capability SPI never owns Scheduler permits, model leases, Runtime sessions,
CancelSignals, Binder callbacks, credits, or retries. The Execution Coordinator remains
the sole infrastructure owner under ADR-017.

### Execution plan refinement

A prepared capability declares a `CapabilityExecutionPlan` containing one to eight
ordered semantic steps. V1 implements `PromptExecutionStep`; each prompt step contains a
typed `PromptRecipe`, step-local routing requirements, demand, input bindings, and an
output binding. A step may reference only prepared input, explicit context, or outputs
of earlier steps.

The Router freezes that semantic plan into ADR-017's request `ExecutionPlan`, binding
exact candidates, prompt/config assets, limits, and execution profiles per step. These
are two phases of one plan lifecycle. Initial orchestration executes exactly one prompt
step; multi-step requests fail with a typed unsupported-framework-feature result until
the Coordinator's multi-step state machine is separately authorized.

This structural support avoids changing the Capability SPI when sequential multi-step
execution is implemented later.

### Registry and discovery

The production registry key is the exact `(capabilityId, wireSchemaVersion)` pair.
Exactly one definition may own a key. The registry is immutable after composition,
rejects duplicates, and supplies the single source of truth for internal discovery and
execution lookup.

The ID-only `CapabilityRegistry` and `CapabilityPipeline` remain a diagnostic
compatibility surface for `dev.echo` during migration. They are not used for production
capabilities and are not silently widened into the new SPI.

### Dependency boundary

Capability modules depend on the core-owned SPI and approved contract schemas. They
receive core-owned plan, tokenizer, asset-reader, and immutable model-fact projections.
They do not receive Model Manager concrete types, model-store paths, or concrete Runtime
registrations. The composition adapter performs projection at the ownership boundary.

## Consequences

- Wire schema versions can coexist without synthetic capability IDs.
- Parsing, planning, attempt state, retry, and cleanup have explicit owners.
- Future multi-step execution extends Coordinator behavior without replacing the
  Capability SPI or prompt contracts.
- Discovery and execution cannot disagree because they share one exact-key registry.
- Capability TCKs run without Android, storage, Binder, or real runtime adapters.
- A mapping layer is required between Model Manager/execution data and core-owned
  capability projections; the extra DTOs are accepted to preserve ownership.
- The walking skeleton and production framework coexist temporarily, so composition
  tests must prevent a production key from being registered in both paths.

## Alternatives considered

### Extend `CapabilityPipeline.execute`

Rejected. It combines preparation, execution, streaming, and finalization and provides
no stable home for prompt assets, routing demand, fresh retry state, or multiple steps.

### Register by capability ID only

Rejected. It contradicts ADR-009 side-by-side schema evolution.

### Encode the version in the ID

Rejected. IDs describe intent; schema version is a separate compatibility dimension.

### Give capabilities Model Manager interfaces or resolved paths

Rejected. It leaks storage/lifecycle ownership and makes capability implementations
harder to test and secure.

### Implement only a single prompt operation with no plan layer

Rejected. The first composite or staged capability would require replacing the SPI and
rerouting ownership model rather than extending the existing ordered-step plan.

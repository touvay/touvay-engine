# ADR-015 — Model Manager resolution and loaded-instance identity

**Status:** Accepted for Model Manager Architecture v1.0 (2026-07-12)

## Context

Runtime v1.0 deliberately exposes a small SPI: an `InferenceRuntime` probes and loads a
verified `ResolvedModelPack`, returning a usable `ModelInstance`. The initial Model
Manager proposal placed runtime registrations directly in the manager and keyed loaded
instances with a tuple of pack/version/digest/runtime/`LoadConfig`.

That shape works for llama.cpp, but it would make the manager own compile-time plugin
registration and would force its key to grow whenever execution gains an accelerator,
delegate, adapter binding, or other load-affecting option. Expanding
`ResolvedModelPack` with catalog, trust, and policy metadata would also leak
orchestration concerns into the public Runtime SPI.

The refinement also considered a `PREPARING` state between an idle loaded instance and
an active one for GPU upload, prefix-cache creation, runtime warmup, or deferred backend
initialization.

## Decision

### Runtime resolution

The Model Manager depends on an internal `RuntimeRegistry` resolution port. The registry
owns immutable compile-time registrations, adapter versions, supported-feature metadata,
runtime-id uniqueness, device probing, and availability caching. `engine-service`
constructs the registry at the composition root.

The registry exposes two internal operations. `compatibility(requirement, device)`
answers whether a compiled-in binding can satisfy a manifest without selecting load
policy. `resolve(requirement, device, executionProfileRequest)` returns one compatible
`RuntimeBinding` plus a canonical `ExecutionProfile`, or a typed unavailable result. The
final profile includes the chosen binding identity, so resolution does not depend on an
identity it has not yet selected. The manager does not own, enumerate, or duplicate
runtime registrations. This introduces no new Runtime SPI API and does not permit
dynamic code loading.

### Resolved model boundary

`ResolvedModelPack` remains unchanged as the narrow Runtime SPI DTO containing pack id,
version, and resolved file paths.

The manager owns a richer internal `ResolvedModelRevision` containing exact revision
identity, manifest digest, trust, runtime requirement, resource constraints, capability
metadata, and immutable file descriptors. During a cold load, the manager acquires a
`PackRevisionHold` and projects the revision into `ResolvedModelPack`. The loaded entry
retains the hold until `ModelInstance.close()` returns.

### Loaded-instance lifecycle

Runtime v1 does not add `PREPARING`. SPI-LC-2 requires `loadModel` to return a usable
instance, so mandatory GPU/backend initialization belongs to `LOADING`. Prefix caches or
reusable warmup require a concrete typed optional SPI, scheduler admission, ownership,
and TCK coverage before a preparation transition can be added. The future additive
transition `READY_IDLE → PREPARING → ACTIVE` remains possible.

### Instance identity

Loaded instances use:

```text
InstanceKey = ModelRevisionIdentity + ExecutionProfile
```

`ModelRevisionIdentity` is the exact `(packId, packVersion, manifestDigest)` revision,
not only a logical model or pack id. Callers supply an `ExecutionProfileRequest`; Runtime
Registry resolution returns the canonical `ExecutionProfile` containing the selected
binding identity and every load-affecting option. Runtime v1 includes normalized
`LoadConfig` (`threads`, `useMmap`); future accelerator, delegate, or typed load features
extend the profile rather than the manager API.

Session-only facts such as context length, prompt, decode parameters, and prefix data do
not belong in the instance key. Keys are process-local and never durable. Typed equality,
not a hash alone, controls reuse.

## Consequences

- The Model Manager remains independent of concrete adapters and plugin registration.
- Runtime probing has one owner and one device-snapshot cache.
- Runtime v1 public APIs and adapter implementations remain unchanged.
- Catalog/trust/policy data can evolve without widening the Runtime SPI.
- Side-by-side versions and rollback cannot accidentally reuse an instance backed by
  different bytes.
- Execution profiles can grow additively without reconstructing a field-by-field cache
  key across callers.
- A future preparation feature must justify its lifecycle and conformance surface rather
  than occupying a speculative state today.

## Alternatives considered

### Manager-owned runtime registrations

Rejected. It duplicates composition-root responsibility, couples lifecycle code to
plugin inventory, and makes probing/cache semantics ambiguous.

### Expand `ResolvedModelPack` into the manager's rich resolved model

Rejected. Signature, trust, activation, catalog, and storage-reference facts are not
runtime concerns. The change would widen a frozen public SPI without benefiting adapters.

### Pack + version + digest + runtime + `LoadConfig` key

Rejected as the long-term vocabulary. It is equivalent to the chosen key for Runtime v1
but does not provide a stable home for future load-affecting semantics.

### Add `PREPARING` immediately

Rejected. There is no Runtime v1 operation that can implement it, and mandatory setup is
already covered by `loadModel`. Adding an unused state would create policy and race
semantics without executable conformance.

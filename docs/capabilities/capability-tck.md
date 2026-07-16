# Capability TCK v1

**Status:** Stable — Capability TCK v1 executable contract (frozen 2026-07-15)

**Normative architecture:** `CAPABILITY_SPEC.md`, ADR-020, ADR-021

The pure-JVM `capabilities/capability-tck` module is inherited by every production
capability implementation. Each exact `CapabilityKey` supplies synthetic request,
prompt-asset, token, delta, and final fixtures through `CapabilityTckSubject`.

## Mandatory checks

| Group | Checks | Requirement |
|---|---:|---|
| Identity/discovery | 4 | Exact schema key, duplicate rejection, immutable descriptor, stable availability |
| Semantic plan | 5 | Valid/deterministic plan, prior-output ordering, payload ceiling, bounded attempt policy |
| Attempt lifecycle | 2 | Fresh retry state and idempotent close |
| Prompt assets | 5 | Golden rendering, exact digest, recipe compatibility, hostile parser corpus, slot bounds |
| Structured output | 1 | Ordered deterministic delta/final assembly |
| Cancellation | 1 | Long-running attempt work observes coroutine cancellation |
| Failure safety | 2 | Typed malformed input and content-free errors |
| **Total** | **20** | All are mandatory for a production capability key |

The self-test suite adds two negative sabotage tests. One reuses mutable attempt state;
the other leaks malformed payload text in an exception. Both must be detected by the
TCK, so a green self-test is evidence that these checks are not vacuous.

## Usage

```kotlin
class MyCapabilityTck : AbstractCapabilityTck() {
    override fun subject(): CapabilityTckSubject = mySyntheticSubject()
}
```

Run the framework self-test with:

```text
gradlew :capabilities:capability-tck:test
```

The TCK uses no Android, Binder, Model Manager implementation, concrete Runtime, model
file, network, or production prompt. Real-model quality and golden evaluation remains
capability-specific evidence in addition to this deterministic framework contract.

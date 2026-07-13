# Rewrite Capability

Production `text.rewrite@1` plugin for the Touvay Capability Framework.

The module owns bounded request parsing, semantic planning, the typed Rewrite recipe,
reference prompt-format asset bytes, deterministic output assembly, provisional
structured deltas, and the authoritative structured final response. Public wire schemas
remain in `touvay-contract` under ADR-014.

Runtime, model, storage, scheduling, retry, credits, and terminal ownership remain in
their existing platform layers. The module has no Android API calls, network access,
filesystem access, model identity, or concrete Runtime dependency.

Validation:

```text
gradlew :capabilities:capability-rewrite:testDebugUnitTest
```

See `docs/capabilities/rewrite-v1.md` for the normative capability contract.

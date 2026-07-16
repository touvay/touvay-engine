# Touvay Engine Specification Index

**Status:** Stable — canonical specification index

**Last updated:** 2026-07-15

This index is the entry point for normative Touvay Engine architecture and implementation
contracts. `docs/ARCHITECTURE.md` defines the platform-wide source of truth. Accepted
ADRs override explanatory text where a later decision refines an earlier document.
Task-specific specifications are normative within their stated scope.

## Authority order

When documents disagree, use this order:

1. accepted ADR governing the exact decision;
2. task-specific normative specification;
3. `docs/ARCHITECTURE.md` platform architecture;
4. engineering review/release notes; and
5. implementation comments and examples.

Conflicts are never resolved silently. Record the conflict, proposed trade-offs, and
recommended correction for architecture review.

## Platform freeze policy

The Platform Foundation specifications listed as Stable are frozen at
`platform-v1-validated`. Every future semantic architectural change requires an ADR
that is reviewed and accepted before implementation. This includes changes to behavior,
ownership, lifecycle, public contracts, security/privacy guarantees, dependency rules,
or cross-module responsibilities. Additive implementation within an existing contract
does not require an ADR unless it changes one of those decisions. Editorial fixes may
land without an ADR but must not alter meaning.

## Normative specifications

| Area | Specification | Status |
|---|---|---|
| Platform | [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md) | Stable v1.0, amended through ADR-023 |
| Runtime SPI | [`docs/runtime/runtime-spi.md`](../runtime/runtime-spi.md) | Stable Runtime v1.0 normative contract |
| Runtime TCK | [`docs/runtime/runtime-tck.md`](../runtime/runtime-tck.md) | Stable Runtime v1.0 executable contract |
| llama.cpp adapter | [`docs/runtime/runtime-llamacpp-design.md`](../runtime/runtime-llamacpp-design.md) | Stable production adapter design |
| Model Manager | [`docs/model-manager/model-manager.md`](../model-manager/model-manager.md) | Stable v1.0 implementation contract |
| Execution | [`docs/execution/execution-architecture.md`](../execution/execution-architecture.md) | Stable v1.0 implementation contract |
| Capability Framework | [`docs/capabilities/CAPABILITY_SPEC.md`](../capabilities/CAPABILITY_SPEC.md) | Stable v1.0 implementation contract |
| Capability TCK | [`docs/capabilities/capability-tck.md`](../capabilities/capability-tck.md) | Stable v1 executable contract |
| Context | [`docs/context/CONTEXT_SPEC.md`](../context/CONTEXT_SPEC.md) | Stable v1.0 implementation contract |
| Rewrite Capability | [`docs/capabilities/rewrite-v1.md`](../capabilities/rewrite-v1.md) | Stable `text.rewrite@1` contract |
| Keyboard | [`docs/keyboard/KEYBOARD_ARCHITECTURE.md`](../keyboard/KEYBOARD_ARCHITECTURE.md) | Stable v1.0; approved 2026-07-15 |
| SDK consumer integration | [`docs/integration/SDK_CONSUMER_INTEGRATION.md`](../integration/SDK_CONSUMER_INTEGRATION.md) | External-client integration handoff |
| Signed demo pack | [`docs/demo/demo-pack.md`](../demo/demo-pack.md) | Offline validation trust workflow |
| Android SDK | [`sdk/touvay-sdk/README.md`](../../sdk/touvay-sdk/README.md) | Typed capability quick start |

## Accepted ADRs

ADR-001 through ADR-014 are preserved in
[`docs/ARCHITECTURE.md` section 20](../ARCHITECTURE.md#20-architecture-decision-records).
Later full records are:

| ADR | Decision |
|---|---|
| [ADR-015](../adr/ADR-015-model-manager-resolution-and-instance-identity.md) | Runtime Registry resolution, rich internal model boundary, and instance identity |
| [ADR-016](../adr/ADR-016-model-pack-manifest-and-signature-envelope.md) | Protobuf manifest and detached Ed25519 envelope |
| [ADR-017](../adr/ADR-017-execution-coordinator-and-terminal-semantics.md) | Execution ownership, immutable context/plan, and terminal semantics |
| [ADR-018](../adr/ADR-018-credit-based-streaming.md) | Count-and-byte credits and bounded decode quanta |
| [ADR-019](../adr/ADR-019-logical-conversations-and-runtime-sessions.md) | Client-owned conversations and request-attempt Runtime sessions |
| [ADR-020](../adr/ADR-020-production-capability-spi-and-exact-key-registry.md) | Production Capability SPI, ordered semantic plan, and exact-key registry |
| [ADR-021](../adr/ADR-021-typed-prompt-recipes-and-frozen-asset-binding.md) | Typed prompt recipes and frozen signed-asset binding |
| [ADR-022](../adr/ADR-022-explicit-context-providers-and-provenance.md) | Explicit request-scoped providers and internal context provenance |
| [ADR-023](../adr/ADR-023-deterministic-context-merge-and-token-budgeting.md) | Deterministic context merging and exact tokenizer budgeting |

## Milestone reviews and evidence

Engineering reviews and release notes record validation evidence but do not override the
normative contracts:

- [`docs/runtime/runtime-v1.0-release.md`](../runtime/runtime-v1.0-release.md)
- [`docs/model-manager/slice-1-engineering-review.md`](../model-manager/slice-1-engineering-review.md)
- [`docs/model-manager/slice-2-engineering-review.md`](../model-manager/slice-2-engineering-review.md)
- [`docs/model-manager/slice-3-engineering-review.md`](../model-manager/slice-3-engineering-review.md)
- [`docs/model-manager/slice-4-engineering-review.md`](../model-manager/slice-4-engineering-review.md)
- [`docs/execution/milestone-5-engineering-review.md`](../execution/milestone-5-engineering-review.md)
- [`docs/capabilities/capability-framework-engineering-review.md`](../capabilities/capability-framework-engineering-review.md)
- [`docs/capabilities/rewrite-engineering-review.md`](../capabilities/rewrite-engineering-review.md)
- [`docs/demo/platform-validation-engineering-review.md`](../demo/platform-validation-engineering-review.md)

Current delivery state and approval gates are summarized in
[`PROJECT_STATUS.md`](../../PROJECT_STATUS.md).

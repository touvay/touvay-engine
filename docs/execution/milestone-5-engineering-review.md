# Milestone 5 — Execution Engine Engineering Review

**Status:** Complete; ready for merge review

**Date:** 2026-07-13

**Architecture baseline:** Execution Architecture v1.0, ADR-017, ADR-018, ADR-019

## Outcome

Milestone 5 implements the production execution foundation that connects Runtime v1 and
the Model Manager behind approved ports. It does not register a user-facing capability,
execute keyboard requests, add networking, or wire llama.cpp to an app route.

## Implemented scope

- immutable, content-free `ExecutionContext` and defensively copied request input;
- immutable dispatch-time `ExecutionPlan` with primary plus one ordered fallback;
- resource-owning sequential attempts without rerouting;
- bounded admission, two-class priority, FIFO, per-principal isolation, coalescing, and
  cancellation preemption, including fixed-RAM/KV/load-cost reservations;
- bounded pre-admission ingress before capability preparation begins;
- exact-once terminal arbitration, admitted-only acceptance, and terminal publication
  after reverse-order request cleanup;
- deadline coverage across queue, acquisition, prefill, decode, credit wait, and retry;
- Model Manager acquisition adapter and per-instance-key single-thread inference lane;
- tokenize → context check → create session → prefill → bounded decode quanta → close
  session → close model lease ownership;
- `AtomicCancelSignal` propagation into in-flight Runtime calls;
- pre-first-delta fallback retry only, with fresh session/program state;
- contract-v2 negotiated count-and-byte credits and strict grant sequencing;
- bounded SDK callback storage with replenishment after downstream Flow consumption;
- append-only AIDL evolution with frozen v1 Binder transaction ids; and
- static, content-free public failure mapping.

## Architecture and dependency review

- Approved architecture changed: **no**. This implements ADR-017–019.
- Runtime SPI changed: **no**. Only SPI-TH-5's obsolete unbounded-channel explanation
  was aligned with ADR-018.
- Public API changed: **yes, additive and reviewed**. Contract v2 appends transport
  feature discovery, credit submit/grant methods, `StreamCreditWindow`, and typed error
  codes. SDK and Runtime public surfaces are otherwise unchanged.
- Dependency graph changed: **one approved composition edge**,
  `engine-service → engine-models`; `checkDependencyRules` enforces it. `engine-core`
  uses only `runtime-api` plus coroutines.
- New external dependencies: **none**.
- Module boundaries: preserved. Scheduler and orchestration are in `engine-core`; model
  caching/file lifetime remain in `engine-models`; Binder and concrete adaptation remain
  in `engine-service`.

## Correctness and security review

- Request cancellation is scoped by authenticated principal plus request id.
- Cancellation from Model Manager acquisition remains coroutine cancellation and is not
  misreported as model unavailability.
- Cancellation, success, and failure reserve the terminal outcome through one atomic
  arbiter; a cancellation recorded before success cannot lose the terminal race.
- Same-principal coalescing replacement is atomic; rejected work does not cancel useful
  predecessors. One bounded ingress replacement slot permits saturation-time
  replacement, and ingress order prevents a slower older preparation from superseding
  newer admitted work.
- Duplicate/out-of-order/over-cap credit grants cannot inflate the window.
- SDK rejects sequence, request-id, count, and byte-window violations without unbounded
  buffering.
- Runtime token sinks never perform Binder I/O or suspend; overflow cancels the attempt.
- Session close precedes model-lease release on success, failure, retry, cancellation,
  timeout, and shutdown.
- Arbitrary downstream exception messages no longer enter public errors.
- User payload, prompt, token pieces, and prepared state remain memory-only.

## Performance and memory review

- Binder entry performs only authentication, bounded envelope/window validation, copy,
  and enqueue.
- Streaming is bounded by both delta count and bytes end-to-end.
- Preparation jobs, retained inline payloads, request counts, fixed RAM, KV estimates,
  and cold-load cost are bounded before or during atomic admission.
- Decode quanta create preemption/cancellation/credit safe points and cap token buffering
  at one quantum and 1 MiB.
- Credit waits release Scheduler compute permission while retaining only explicitly
  admitted session/model resources.
- Active references to one model instance share one dedicated inference lane; Runtime
  internal threads remain bounded by the execution profile.

## Validation

- `gradlew build apiCheck checkDependencyRules`: **green**, 688 tasks.
- Recorded JVM/Robolectric unit results: **221 tests, 0 failures, 0 skipped** across
  debug/release and pure-JVM modules.
- `engine-core`: Coordinator lifecycle, cancellation signal, retry commit, deadline,
  credit wait, Scheduler priority/coalescing, legacy race closure, and terminal tests.
- Contract/SDK/service: parcel round trips, frozen AIDL transaction ids, v1 fallback,
  consumption-ordered grants, invalid grant rejection, Binder cancellation, and access
  control.
- Existing Model Manager suite: **96/96 green**.
- Runtime v1 remains the previously approved **28/28 device-TCK green** baseline; Runtime
  SPI and adapters were not modified by Milestone 5.

## Known limitations and remaining risks

1. No production `ExecutionProgramFactory` or Router is registered. The Coordinator is
   intentionally unreachable from app capabilities until the next architecture gate.
2. Contract-v1 streaming remains limited to bounded diagnostic/unary behavior. Model
   streaming requires negotiated contract-v2 credits.
3. The service composition adapter receives an existing `RuntimeInstanceManager`; final
   Android bootstrap policy, installed-pack selection, and concrete runtime registration
   remain part of the future routing/capability composition milestone.
4. Scheduler v1 intentionally uses cancellation rather than pause/resume and may starve
   background work under sustained interactive load, as accepted by ADR-010.
5. The default Scheduler RAM/KV/load ceilings are conservative safety limits; device
   policy must supply calibrated limits. Representative 4 GB arm64 calibration and
   real-adapter end-to-end execution remain required before a model capability ships.
6. Public logical conversations remain deferred; Runtime sessions are request-attempt
   scoped as required by ADR-019.

## Recommendation

Milestone 5 is ready for merge as the execution-engine foundation. Do not begin a
user-facing capability, Router policy, keyboard integration, or networking milestone
without explicit authorization and its required design review.

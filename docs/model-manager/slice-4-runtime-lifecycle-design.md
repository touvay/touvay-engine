# Task 3 Slice 4 — Runtime lifecycle design note

**Status:** Approved architecture applied to authorized Slice 4 implementation

This slice implements ADR-015 inside `engine-models`. It does not change the Runtime
SPI, create inference sessions, execute inference, or add scheduler/routing policy.

## Lease ↔ `ModelInstance` ownership

`RuntimeInstanceManager` acquires one exact-revision `PackRevisionLease` before a cold
load and projects that held revision to the existing Runtime SPI `ResolvedModelPack`.
Each loaded cache entry exclusively owns the resulting `ModelInstance` and that storage
lease. A `RuntimeModelLease` only borrows the instance and contributes one process-local
instance reference; it never owns or closes the instance directly. Unload always closes
the instance first and releases the storage lease only after `ModelInstance.close()`
returns, so mmap-backed files cannot be deleted while the runtime may still reference
them. Slice 4 exposes no session operation.

## Cache invalidation

The cache key is the exact `ModelRevisionIdentity` plus the canonical
`ExecutionProfile` returned by `RuntimeRegistry`. Activation, rollback, manifest digest,
binding, thread-count, or mmap changes therefore cannot reuse an incompatible instance.
Zero-reference entries remain `READY_IDLE` until explicit idle release or manager
shutdown; timers, memory-pressure policy, and eviction ranking belong to later slices.
Runtime registrations are immutable. Probe results are cached per binding and complete
`DeviceProfile` and can be explicitly invalidated when the composition root observes a
device/backend change.

## Concurrent acquisition

Acquisition is suspending and loading runs on an injected worker dispatcher. The first
acquirer installs one `LOADING` entry per `InstanceKey`; concurrent acquirers await the
same deferred load. Each successful waiter increments the reference count under the
manager lock before receiving a lease. Cancelling one waiter does not cancel the shared
load. A completed load with no remaining waiters is retained as `READY_IDLE`, bounded by
the explicit release/shutdown operations available in this slice.

## Runtime failure handling

The Registry validates immutable registrations, required adapter version/features, and
cached `probe` availability before load. A probe or load exception becomes a typed,
content-free Model Manager failure; adapter messages are not copied into user-visible
text. Failed loads are removed from the single-flight map so a later acquisition can
retry. Every failure after storage acquisition releases the storage lease, and every
failure after a runtime returns an instance closes that instance before releasing the
lease. Manager shutdown rejects new acquisitions, waits for in-flight loads, and then
closes all entries in ownership order.

## Reference-count invariants

- Every delivered, open `RuntimeModelLease` corresponds to exactly one positive entry
  reference acquired before delivery.
- Lease close is atomic and idempotent; counts never underflow.
- `ACTIVE` means reference count greater than zero; `READY_IDLE` means exactly zero.
- A loaded entry owns exactly one instance and one exact-revision storage lease.
- `LOADING` owns no published instance; load failure leaves no cache entry or storage
  lease.
- An entry can unload only with zero references. It is removed from the cache before
  close begins, preventing reacquisition during teardown.
- Runtime consistency verification checks key/profile/binding identity, lifecycle state,
  reference count, and one-entry-per-key invariants without invoking the Runtime SPI.

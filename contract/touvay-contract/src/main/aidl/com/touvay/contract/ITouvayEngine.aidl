package com.touvay.contract;

import com.touvay.contract.ClientHello;
import com.touvay.contract.EngineHello;
import com.touvay.contract.CapabilityInfo;
import com.touvay.contract.RequestEnvelope;
import com.touvay.contract.ITouvayResponseCallback;

/**
 * The Touvay Engine binder contract (contract version 1).
 *
 * Evolution rules (ARCHITECTURE.md ADR-009): methods are added, never changed or removed.
 * Clients must call negotiate() first and honor the version window it returns.
 */
interface ITouvayEngine {
    /** Exchanges contract versions. Must be the first call on a fresh connection. */
    EngineHello negotiate(in ClientHello hello);

    /** Lists capabilities known to this engine build, with their status on this device. */
    List<CapabilityInfo> listCapabilities();

    /**
     * Submits a request. All results, including failures, are delivered through the
     * callback; this method only enqueues. Never blocks on inference.
     */
    void submit(in RequestEnvelope request, ITouvayResponseCallback callback);

    /** Requests cooperative cancellation of an in-flight or queued request. */
    oneway void cancel(String requestId);
}

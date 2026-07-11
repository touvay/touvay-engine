package com.touvay.contract;

import com.touvay.contract.ResponseDelta;
import com.touvay.contract.ResponseFinal;
import com.touvay.contract.EngineError;

/**
 * Client-side callback for one submitted request.
 *
 * Declared oneway so the engine never blocks on a slow or dead client. Exactly one of
 * onCompleted/onFailed terminates the request; onDelta may be called zero or more times
 * before that, in sequence order.
 */
oneway interface ITouvayResponseCallback {
    void onAccepted(String requestId);
    void onDelta(in ResponseDelta delta);
    void onCompleted(in ResponseFinal result);
    void onFailed(in EngineError error);
}

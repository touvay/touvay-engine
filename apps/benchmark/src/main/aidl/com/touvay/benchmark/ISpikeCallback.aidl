package com.touvay.benchmark;

/** Progress/result callbacks from the :spike process back to the UI/test process. */
oneway interface ISpikeCallback {
    void onProgress(String line);
    void onFinished(String resultJson);
    void onError(String message);
}

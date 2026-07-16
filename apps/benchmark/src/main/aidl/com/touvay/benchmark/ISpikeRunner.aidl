package com.touvay.benchmark;

import com.touvay.benchmark.ISpikeCallback;

/**
 * Benchmark-local control surface for the :spike process. Mirrors the production
 * topology (inference isolated from the client process) without touching the
 * production contract.
 */
interface ISpikeRunner {
    int getPid();
    oneway void runSuite(String modelPath, String modelVariant, boolean quick, ISpikeCallback callback);
    oneway void cancelActive();
}

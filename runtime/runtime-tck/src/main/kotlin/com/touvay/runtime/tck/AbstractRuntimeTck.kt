package com.touvay.runtime.tck

import org.junit.Before
import org.junit.Test

/**
 * The Runtime TCK as a JUnit4 test class. An adapter's conformance claim is one
 * subclass providing its [RuntimeTckSubject]; run it as a device (androidTest) class
 * for real adapters, or as a plain JVM test for pure-JVM runtimes.
 *
 * Test-to-requirement traceability lives in TckChecks / docs/runtime/runtime-tck.md.
 * Probe-dependent tests (memory, thread counts) self-skip via JUnit assumptions when
 * the environment can't measure — skipped is visible in reports, never a silent pass.
 */
public abstract class AbstractRuntimeTck {

    protected abstract fun subject(): RuntimeTckSubject

    private lateinit var checks: TckChecks

    @Before
    public fun setUpTck() {
        checks = TckChecks(subject())
    }

    // Lifecycle
    @Test public fun lc01_probeCheapAndRepeatable(): Unit = checks.lc01ProbeCheapAndRepeatable()
    @Test public fun lc02_repeatedLoadCloseCycles(): Unit = checks.lc02RepeatedLoadCloseCycles()
    @Test public fun lc03_doubleCloseIdempotent(): Unit = checks.lc03DoubleCloseIdempotent()
    @Test public fun lc04_useAfterCloseThrows(): Unit = checks.lc04UseAfterCloseThrows()
    @Test public fun lc05_closeInstanceWithLiveSession(): Unit = checks.lc05CloseInstanceWithLiveSession()
    @Test public fun lc06_tokenizeConcurrentWithDecode(): Unit = checks.lc06TokenizeConcurrentWithDecode()
    @Test public fun lc07_failedLoadLeaksNothing(): Unit = checks.lc07FailedLoadLeaksNothing()

    // Threading
    @Test public fun th01_callsFromDifferentThreads(): Unit = checks.th01CallsFromDifferentThreads()
    @Test public fun th03_sinkOnDecodingThread(): Unit = checks.th03SinkOnDecodingThread()
    @Test public fun th04_noThreadsSurviveClose(): Unit = checks.th04NoThreadsSurviveClose()

    // Cancellation
    @Test public fun cx01_cancelMidDecodeWithinBound(): Unit = checks.cx01CancelMidDecodeWithinBound()
    @Test public fun cx02_cancelMidPrefillWithinChunk(): Unit = checks.cx02CancelMidPrefillWithinChunk()
    @Test public fun cx03_preSetSignalReturnsImmediately(): Unit = checks.cx03PreSetSignalReturnsImmediately()
    @Test public fun cx04_cancelRacesCompletion(): Unit = checks.cx04CancelRacesCompletion()
    @Test public fun cx06_postCancelCloseIsClean(): Unit = checks.cx06PostCancelCloseIsClean()

    // Streaming
    @Test public fun st01_orderAndCap(): Unit = checks.st01OrderAndCap()
    @Test public fun st02_utf8Reassembly(): Unit = checks.st02Utf8Reassembly()
    @Test public fun st04_noEogMarkersInOutput(): Unit = checks.st04NoEogMarkersInOutput()
    @Test public fun st05_throwingSinkPropagates(): Unit = checks.st05ThrowingSinkPropagates()

    // Determinism
    @Test public fun dt01_deterministicAcrossSessions(): Unit = checks.dt01DeterministicAcrossSessions()
    @Test public fun dt02_deterministicAcrossReloads(): Unit = checks.dt02DeterministicAcrossReloads()

    // Memory
    @Test public fun me01_nativeHeapReleasedOnClose(): Unit = checks.me01NativeHeapReleasedOnClose()
    @Test public fun me02_rssReleasedOnClose(): Unit = checks.me02RssReleasedOnClose()
    @Test public fun me04_noLeakAcrossCycles(): Unit = checks.me04NoLeakAcrossCycles()

    // Errors
    @Test public fun er01_missingFileCleanError(): Unit = checks.er01MissingFileCleanError()
    @Test public fun er02_malformedCorpusSurvived(): Unit = checks.er02MalformedCorpusSurvived()
    @Test public fun er03_noUserContentInErrors(): Unit = checks.er03NoUserContentInErrors()
    @Test public fun er04_absurdConfigRejected(): Unit = checks.er04AbsurdConfigRejected()
}

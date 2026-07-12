package com.touvay.runtime.tck

import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.SessionConfig
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertFailsWith

/**
 * The kit's own test: a conformant fake passes every check, and each sabotaged fake
 * FAILS exactly the check written to catch its violation. A TCK that cannot detect
 * the bugs it exists for is decoration (docs/runtime/runtime-tck.md §6).
 */
class TckSelfTest {

    private companion object {
        const val CHUNK = 64
    }

    private fun checks(behavior: FakeBehavior = FakeBehavior()): TckChecks {
        val tracker = FakeAllocTracker()
        val workDir = Files.createTempDirectory("tck-selftest")
        val modelFile = workDir.resolve("model.bin")
        Files.write(modelFile, FakeRuntime.MAGIC.toByteArray() + ByteArray(4096) { (it * 7).toByte() })
        val subject = RuntimeTckSubject(
            runtimeFactory = { FakeRuntime(behavior, tracker, CHUNK) },
            conformancePack = ResolvedModelPack("tck.fake", "1", mapOf("weights.gguf" to modelFile)),
            loadConfig = LoadConfig(threads = 2),
            sessionConfig = SessionConfig(contextLength = 512),
            documentedPrefillChunkTokens = CHUNK,
            cancelBound = CancelBound.OneStep,
            probe = FakeProbe(tracker),
            deviceProfile = DeviceProfile(4L * 1024 * 1024 * 1024, false, listOf("x86_64")),
            workDir = workDir,
            declaredKvBytesPerToken = 1_024,
            detokenize = { _, tokenIds -> fakeDetokenize(tokenIds) },
            reportDir = workDir.resolve("reports"),
        )
        return TckChecks(subject)
    }

    // -- the conformant fake passes everything ------------------------------------------

    @Test
    fun conformantFake_passesEveryMandatoryCheck() {
        val c = checks()
        c.lc01ProbeCheapAndRepeatable()
        c.lc02RepeatedLoadCloseCycles()
        c.lc03DoubleCloseIdempotent()
        c.lc04UseAfterCloseThrows()
        c.lc05CloseInstanceWithLiveSession()
        c.lc06TokenizeConcurrentWithDecode()
        c.lc07FailedLoadLeaksNothing()
        c.th01CallsFromDifferentThreads()
        c.th03SinkOnDecodingThread()
        c.th04NoThreadsSurviveClose()
        c.cx01CancelMidDecodeWithinBound()
        c.cx02CancelMidPrefillWithinChunk()
        c.cx03PreSetSignalReturnsImmediately()
        c.cx04CancelRacesCompletion()
        c.cx06PostCancelCloseIsClean()
        c.st01OrderAndCap()
        c.st02Utf8Reassembly()
        c.st04NoEogMarkersInOutput()
        c.st05ThrowingSinkPropagates()
        c.dt01DeterministicAcrossSessions()
        c.dt02DeterministicAcrossReloads()
        c.me01NativeHeapReleasedOnClose()
        c.me02RssReleasedOnClose()
        c.me04NoLeakAcrossCycles()
        c.er01MissingFileCleanError()
        c.er02MalformedCorpusSurvived()
        c.er03NoUserContentInErrors()
        c.er04AbsurdConfigRejected()
    }

    // -- each sabotage fails its check ----------------------------------------------------

    @Test
    fun leakyFake_failsNativeHeapCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(leakOnClose = true)).me01NativeHeapReleasedOnClose()
        }
    }

    @Test
    fun leakyFake_failsLeakAcrossCyclesCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(leakOnClose = true)).me04NoLeakAcrossCycles()
        }
    }

    @Test
    fun cancelIgnoringFake_failsDecodeCancelBound() {
        // Long generation + slow steps: ignoring cancel overshoots the bound by ~2 s.
        val behavior = FakeBehavior(ignoreCancel = true, stepDelayMillis = 10, eogAtToken = 200)
        assertFailsWith<AssertionError> {
            checks(behavior).cx01CancelMidDecodeWithinBound()
        }
    }

    @Test
    fun eogEmittingFake_failsMarkerCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(emitEogMarker = true)).st04NoEogMarkersInOutput()
        }
    }

    @Test
    fun reorderingFake_failsDetokenizationParityCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(reorderStreaming = true)).st03BackendDetokenizationParity()
        }
    }

    @Test
    fun privacyLeakingFake_failsLogPrivacyCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(leakUserContentToLog = true)).er03NoUserContentInErrors()
        }
    }

    @Test
    fun nondeterministicFake_failsDeterminismCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(nondeterministic = true)).dt01DeterministicAcrossSessions()
        }
    }

    @Test
    fun errorThrowingParserFake_failsCorpusCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(throwErrorOnMalformed = true)).er02MalformedCorpusSurvived()
        }
    }

    @Test
    fun validationSkippingFake_failsAbsurdConfigCheck() {
        assertFailsWith<AssertionError> {
            checks(FakeBehavior(skipConfigValidation = true)).er04AbsurdConfigRejected()
        }
    }
}

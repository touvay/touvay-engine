package com.touvay.runtime.tck

import com.touvay.runtime.api.AtomicCancelSignal
import com.touvay.runtime.api.CancelSignal
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.InferenceSession
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.api.TokenSequence
import com.touvay.runtime.api.TokenSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.max

/**
 * The executable requirements of docs/runtime/runtime-spi.md. Each public method is
 * one TCK test; AbstractRuntimeTck exposes them as JUnit tests, and the self-test
 * suite calls them directly against sabotaged fakes to prove they detect violations.
 */
public class TckChecks(private val subject: RuntimeTckSubject) {

    // ---------------------------------------------------------------- helpers

    private fun load(pack: ResolvedModelPack = subject.conformancePack): ModelInstance =
        subject.runtimeFactory().loadModel(pack, subject.loadConfig)

    private inline fun <R> withInstance(block: (ModelInstance) -> R): R {
        val instance = load()
        try {
            return block(instance)
        } finally {
            instance.close()
        }
    }

    private inline fun <R> withSession(block: (ModelInstance, InferenceSession) -> R): R =
        withInstance { instance ->
            val session = instance.createSession(subject.sessionConfig)
            try {
                block(instance, session)
            } finally {
                session.close()
            }
        }

    private class Generation {
        val tokenIds = mutableListOf<Int>()
        val pieces = mutableListOf<String>()
        val stepNanos = mutableListOf<Long>()
        var prefillNanos: Long = 0
        var decodeNanos: Long = 0
    }

    private fun generate(
        instance: ModelInstance,
        session: InferenceSession,
        prompt: String = subject.shortPrompt,
        maxTokens: Int = 16,
        cancel: CancelSignal = CancelSignal.NONE,
        onEachToken: (Int) -> Unit = {},
    ): Generation {
        val generation = Generation()
        val prefillStart = System.nanoTime()
        session.prefill(instance.tokenize(prompt), cancel)
        generation.prefillNanos = System.nanoTime() - prefillStart
        var lastNanos = System.nanoTime()
        val decodeStart = lastNanos
        session.decode(
            DecodeParams(maxTokens = maxTokens),
            cancel,
            TokenSink { tokenId, piece ->
                val now = System.nanoTime()
                generation.stepNanos += now - lastNanos
                lastNanos = now
                generation.tokenIds += tokenId
                generation.pieces += piece
                onEachToken(generation.tokenIds.size)
            },
        )
        generation.decodeNanos = System.nanoTime() - decodeStart
        return generation
    }

    private fun awaitTrue(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }

    private fun requireProbe(value: Long?, what: String): Long {
        assumeTrue("probe cannot measure $what in this environment; test skipped", value != null)
        return value!!
    }

    // ---------------------------------------------------------------- TCK-LC

    /** SPI-LC-1: probe is cheap, repeatable, side-effect-free. */
    public fun lc01ProbeCheapAndRepeatable() {
        val runtime = subject.runtimeFactory()
        val first = runtime.probe(subject.deviceProfile)
        var bestNanos = Long.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            val result = runtime.probe(subject.deviceProfile)
            bestNanos = minOf(bestNanos, System.nanoTime() - start)
            assertEquals("probe result changed between calls", first, result)
        }
        assertTrue("probe too slow: best of 5 was ${bestNanos / 1_000_000} ms (limit 50)", bestNanos < 50_000_000)
    }

    /** SPI-LC-2/3, SPI-OW-1: repeated load/close cycles, no state bleed. */
    public fun lc02RepeatedLoadCloseCycles() {
        var reportGeneration: Generation? = null
        var reportLoadNanos = 0L
        var runtimeId = "unknown"
        repeat(5) {
            val loadStart = System.nanoTime()
            val instance = load()
            reportLoadNanos = System.nanoTime() - loadStart
            runtimeId = subject.runtimeFactory().id.value
            try {
                val session = instance.createSession(subject.sessionConfig)
                try {
                    val generation = generate(instance, session, maxTokens = 4)
                    assertTrue("cycle produced no tokens", generation.tokenIds.isNotEmpty())
                    reportGeneration = generation
                } finally {
                    session.close()
                }
            } finally {
                instance.close()
            }
        }
        val generation = requireNotNull(reportGeneration)
        val report = TckReport.write(
            directory = subject.reportDir,
            runtimeId = runtimeId,
            totalRamBytes = subject.deviceProfile.totalRamBytes,
            modelLoadMillis = reportLoadNanos / 1e6,
            prefillMillis = generation.prefillNanos / 1e6,
            firstTokenMillis = (generation.stepNanos.firstOrNull() ?: -1L) / 1e6,
            decodeTokensPerSecond = generation.tokenIds.size /
                (generation.decodeNanos.coerceAtLeast(1L) / 1e9),
        )
        assertTrue("TCK-PF report was not written", Files.isRegularFile(report))
    }

    /** SPI-LC-4/10: double close is a no-op. */
    public fun lc03DoubleCloseIdempotent() {
        val instance = load()
        val session = instance.createSession(subject.sessionConfig)
        session.close()
        session.close()
        instance.close()
        instance.close()
    }

    /** SPI-LC-5: use after close throws IllegalStateException. */
    public fun lc04UseAfterCloseThrows() {
        val instance = load()
        val session = instance.createSession(subject.sessionConfig)
        session.close()
        instance.close()
        assertThrowsIse("createSession on closed instance") {
            instance.createSession(subject.sessionConfig)
        }
        assertThrowsIse("tokenize on closed instance") { instance.tokenize("x") }
        assertThrowsIse("prefill on closed session") {
            session.prefill(com.touvay.runtime.api.TokenSequence(intArrayOf(1)), CancelSignal.NONE)
        }
        assertThrowsIse("decode on closed session") {
            session.decode(DecodeParams(1), CancelSignal.NONE) { _, _ -> }
        }
    }

    /** SPI-LC-6: closing an instance with a live session releases defensively. */
    public fun lc05CloseInstanceWithLiveSession() {
        val baseline = requireProbe(subject.probe.nativeBytes(), "native heap")
        val instance = load()
        val session = instance.createSession(subject.sessionConfig)
        instance.close()   // engine wouldn't do this; adapter must survive it
        session.close()    // still idempotent afterwards
        val after = requireProbe(subject.probe.nativeBytes(), "native heap")
        assertTrue(
            "defensive instance close retained ${(after - baseline) / (1024 * 1024)} MB",
            after - baseline <= 16L * 1024 * 1024,
        )
    }

    /** SPI-LC-7: tokenize is safe concurrently with an active decode. */
    public fun lc06TokenizeConcurrentWithDecode() {
        withSession { instance, session ->
            val cancel = AtomicCancelSignal()
            val failure = AtomicReference<Throwable>()
            val decodeStarted = CountDownLatch(1)
            val decoder = thread(name = "tck-lc06-decode") {
                try {
                    generate(
                        instance,
                        session,
                        maxTokens = 24,
                        cancel = cancel,
                        onEachToken = { decodeStarted.countDown() },
                    )
                } catch (t: Throwable) {
                    failure.set(t)
                    decodeStarted.countDown()
                }
            }
            assertTrue("decode never started", decodeStarted.await(30, TimeUnit.SECONDS))
            repeat(20) {
                assertTrue("tokenize returned nothing", instance.tokenize(subject.shortPrompt).ids.isNotEmpty())
            }
            awaitCancellableThreadTermination(
                worker = decoder,
                operation = "LC06 decode",
                cancel = cancel::cancel,
            )
            failure.get()?.let { throw AssertionError("decode failed alongside tokenize", it) }
        }
    }

    /** SPI-LC-2 + SPI-ME-4: failed load leaves no native allocation behind. */
    public fun lc07FailedLoadLeaksNothing() {
        val baseline = requireProbe(subject.probe.nativeBytes(), "native heap")
        val missing = ResolvedModelPack(
            id = "tck.missing",
            version = "1",
            files = mapOf("weights.gguf" to subject.workDir.resolve("does-not-exist.gguf")),
        )
        try {
            subject.runtimeFactory().loadModel(missing, subject.loadConfig)
            fail("load of a missing file succeeded")
        } catch (expected: Exception) {
            // expected
        }
        val after = requireProbe(subject.probe.nativeBytes(), "native heap")
        assertTrue(
            "failed load leaked ${(after - baseline) / 1024} KB of native heap",
            after - baseline <= 4 * 1024 * 1024,
        )
    }

    // ---------------------------------------------------------------- TCK-TH

    /** SPI-TH-2: sequential session calls from different threads. */
    public fun th01CallsFromDifferentThreads() {
        withSession { instance, session ->
            val failure = AtomicReference<Throwable>()
            val prefiller = thread(name = "tck-th01-prefill") {
                try {
                    session.prefill(instance.tokenize(subject.shortPrompt), CancelSignal.NONE)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            prefiller.join(60_000)
            failure.get()?.let { throw AssertionError("prefill on foreign thread failed", it) }

            val pieces = mutableListOf<String>()
            val decoder = thread(name = "tck-th01-decode") {
                try {
                    session.decode(DecodeParams(8), CancelSignal.NONE) { _, piece ->
                        pieces += piece
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            decoder.join(60_000)
            failure.get()?.let { throw AssertionError("decode on foreign thread failed", it) }
            assertTrue("no tokens decoded across threads", pieces.isNotEmpty())
        }
    }

    /** SPI-TH-1/5: the sink runs on the decoding thread. */
    public fun th03SinkOnDecodingThread() {
        withSession { instance, session ->
            session.prefill(instance.tokenize(subject.shortPrompt), CancelSignal.NONE)
            val caller = Thread.currentThread()
            val sinkThreads = mutableSetOf<Thread>()
            session.decode(DecodeParams(4), CancelSignal.NONE) { _, _ ->
                sinkThreads += Thread.currentThread()
            }
            assumeTrue("no tokens generated; cannot verify sink thread", sinkThreads.isNotEmpty())
            assertEquals("sink ran off the decoding thread", setOf(caller), sinkThreads)
        }
    }

    /** SPI-TH-4: no adapter threads survive close. */
    public fun th04NoThreadsSurviveClose() {
        val before = requireProbe(subject.probe.threadCount()?.toLong(), "thread count")
        withSession { instance, session -> generate(instance, session, maxTokens = 8) }
        val settled = awaitTrue(5_000) {
            val now = subject.probe.threadCount() ?: return@awaitTrue true
            now <= before + 2
        }
        assertTrue(
            "adapter threads outlived close: ${subject.probe.threadCount()} vs baseline $before",
            settled,
        )
    }

    // ---------------------------------------------------------------- TCK-CX

    /** SPI-CX-1/3/4: mid-decode cancel returns within the declared bound. */
    public fun cx01CancelMidDecodeWithinBound() {
        // Calibration run: per-step time histogram from this environment, this model.
        val maxStepNanos = withSession { instance, session ->
            val calibration = generate(instance, session, maxTokens = 20)
            assumeTrue("calibration produced <6 tokens; cannot bound a step", calibration.stepNanos.size >= 6)
            calibration.stepNanos.max()
        }
        val boundNanos = when (val bound = subject.cancelBound) {
            is CancelBound.OneStep -> (maxStepNanos * 1.5).toLong() + 50_000_000
            is CancelBound.TighterMillis -> bound.millis * 1_000_000 + 25_000_000
        }

        withSession { instance, session ->
            val cancel = AtomicCancelSignal()
            val seen = AtomicInteger(0)
            val cancelSetAt = AtomicLong(0)
            val watcher = thread(name = "tck-cx01-watcher") {
                if (awaitTrue(60_000) { seen.get() >= 5 }) {
                    cancelSetAt.set(System.nanoTime())
                    cancel.cancel()
                }
            }
            generate(instance, session, maxTokens = 512, cancel = cancel, onEachToken = { seen.set(it) })
            val returnedAt = System.nanoTime()
            watcher.join(60_000)

            assertTrue("cancel watcher did not terminate", !watcher.isAlive)
            assumeTrue(
                "generation ended before cancel fired",
                cancelSetAt.get() in 1 until returnedAt,
            )
            val latency = returnedAt - cancelSetAt.get()
            assertTrue(
                "cancel-to-return ${latency / 1_000_000} ms exceeds declared bound " +
                    "${boundNanos / 1_000_000} ms (max observed step ${maxStepNanos / 1_000_000} ms)",
                latency <= boundNanos,
            )
        }
    }

    /** SPI-CX-2: mid-prefill cancel returns within one documented chunk. */
    public fun cx02CancelMidPrefillWithinChunk() {
        val chunk = subject.documentedPrefillChunkTokens
        val longPrompt = buildString {
            while (true) {
                append("the quick brown fox jumps over the lazy dog ")
                if (length > chunk * 12) break // > 2x chunk tokens for any realistic tokenizer
            }
        }
        // Calibration: uncancelled prefill of the same prompt to estimate chunk time.
        val (promptTokens, totalNanos) = withSession { instance, session ->
            val tokens = instance.tokenize(longPrompt)
            assumeTrue(
                "long prompt only ${tokens.ids.size} tokens; need > 2x chunk ($chunk)",
                tokens.ids.size > 2 * chunk,
            )
            val start = System.nanoTime()
            session.prefill(tokens, CancelSignal.NONE)
            tokens.ids.size to System.nanoTime() - start
        }
        val chunks = ceil(promptTokens / chunk.toDouble()).toLong()
        val perChunkNanos = totalNanos / max(1, chunks)
        val boundNanos = perChunkNanos * 2 + 50_000_000

        withSession { instance, session ->
            val tokens = instance.tokenize(longPrompt)
            val cancel = AtomicCancelSignal()
            val cancelSetAt = AtomicLong(0)
            val watcher = thread(name = "tck-cx02-watcher") {
                Thread.sleep(max(1, perChunkNanos / 2_000_000)) // mid-first-chunks
                cancelSetAt.set(System.nanoTime())
                cancel.cancel()
            }
            session.prefill(tokens, cancel)
            val returnedAt = System.nanoTime()
            watcher.join(10_000)

            assumeTrue("prefill finished before cancel fired", cancelSetAt.get() in 1 until returnedAt)
            val latency = returnedAt - cancelSetAt.get()
            assertTrue(
                "prefill cancel-to-return ${latency / 1_000_000} ms exceeds one-chunk bound " +
                    "${boundNanos / 1_000_000} ms",
                latency <= boundNanos,
            )
        }
    }

    /** SPI-CX-6: a pre-set signal returns immediately with no work. */
    public fun cx03PreSetSignalReturnsImmediately() {
        withSession { instance, session ->
            val cancelled = AtomicCancelSignal().apply { cancel() }
            val pieces = mutableListOf<String>()
            session.prefill(instance.tokenize(subject.shortPrompt), cancelled)
            session.decode(DecodeParams(64), cancelled) { _, piece -> pieces += piece }
            assertEquals("pre-cancelled decode still emitted tokens", emptyList<String>(), pieces)
        }
    }

    /** SPI-CX-7: cancel racing natural completion never crashes or hangs. */
    public fun cx04CancelRacesCompletion() {
        repeat(20) { iteration ->
            withSession { instance, session ->
                val cancel = AtomicCancelSignal()
                session.prefill(instance.tokenize(subject.shortPrompt), CancelSignal.NONE)
                val failure = AtomicReference<Throwable>()
                val decoder = thread(name = "tck-cx04-decode-$iteration") {
                    try {
                        session.decode(DecodeParams(3), cancel) { _, _ -> }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
                val racer = thread(name = "tck-cx04-$iteration") {
                    Thread.sleep((iteration % 10 * 10).toLong())
                    cancel.cancel()
                }
                decoder.join(60_000)
                racer.join(10_000)
                assertTrue("decode hung while racing cancellation", !decoder.isAlive)
                assertTrue("cancel racer did not terminate", !racer.isAlive)
                failure.get()?.let { throw AssertionError("cancel/completion race threw", it) }
            }
        }
    }

    /** SPI-CX-5 + SPI-LC-9: cancellation throws nothing; close afterwards works. */
    public fun cx06PostCancelCloseIsClean() {
        val instance = load()
        val session = instance.createSession(subject.sessionConfig)
        val cancel = AtomicCancelSignal()
        session.prefill(instance.tokenize(subject.shortPrompt), CancelSignal.NONE)
        val seen = AtomicInteger(0)
        val watcher = thread { awaitTrue(60_000) { seen.get() >= 2 }; cancel.cancel() }
        session.decode(DecodeParams(256), cancel) { _, _ -> seen.incrementAndGet() }
        watcher.join(60_000)
        session.close()
        instance.close()
    }

    // ---------------------------------------------------------------- TCK-ST

    /** SPI-ST-1/5: one piece per token, order preserved, maxTokens is a hard cap. */
    public fun st01OrderAndCap() {
        withSession { instance, session ->
            val generation = generate(instance, session, maxTokens = 12)
            assertTrue("no tokens generated", generation.pieces.isNotEmpty())
            assertTrue(
                "emitted ${generation.pieces.size} pieces > maxTokens 12",
                generation.pieces.size <= 12,
            )
            assertEquals(
                "tokenIds and pieces diverged",
                generation.tokenIds.size,
                generation.pieces.size,
            )
        }
    }

    /** SPI-ST-2/3: valid UTF-8 across piece boundaries (multi-byte prompt). */
    public fun st02Utf8Reassembly() {
        withSession { instance, session ->
            val generation = generate(instance, session, maxTokens = 24)
            val whole = generation.pieces.joinToString("")
            // Strict re-decode: any adapter-side reassembly failure surfaces as U+FFFD.
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(whole.toByteArray(Charsets.UTF_8)))
            if (subject.expectNoReplacementChars) {
                assertTrue(
                    "output contains U+FFFD — broken UTF-8 reassembly across pieces",
                    '�' !in whole,
                )
            }
        }
        st03BackendDetokenizationParity()
    }

    /** SPI-ST-3: streamed pieces equal backend detokenization exactly. */
    public fun st03BackendDetokenizationParity() {
        withSession { instance, session ->
            val generation = generate(instance, session, maxTokens = 24)
            val streamed = generation.pieces.joinToString("")
            val backendText = subject.detokenize(instance, generation.tokenIds.toIntArray())
            assertEquals(
                "streamed pieces differ from backend detokenization",
                backendText,
                streamed,
            )

            val unicode = "JNI UTF-8 round trip: \u4f60\u597d \ud83c\udf0d \u0000 end"
            val roundTrip = subject.detokenize(instance, instance.tokenize(unicode).ids)
            assertTrue(
                "tokenization did not preserve standard UTF-8 input",
                roundTrip.endsWith(unicode),
            )
        }
    }

    /** SPI-ST-4 (heuristic): EOG/special markers never surface in pieces. */
    public fun st04NoEogMarkersInOutput() {
        withSession { instance, session ->
            val generation = generate(instance, session, maxTokens = 48)
            generation.pieces.forEach { piece ->
                subject.eogMarkers.forEach { marker ->
                    assertTrue("piece '$piece' contains EOG marker '$marker'", marker !in piece)
                }
            }
        }
    }

    /** SPI-ST-6: a throwing sink stops generation and propagates. */
    public fun st05ThrowingSinkPropagates() {
        withSession { instance, session ->
            session.prefill(instance.tokenize(subject.shortPrompt), CancelSignal.NONE)
            val emitted = AtomicInteger(0)
            try {
                session.decode(DecodeParams(64), CancelSignal.NONE) { _, _ ->
                    if (emitted.incrementAndGet() == 3) throw IllegalStateException("TCK-SINK-THROW")
                }
                fail("sink exception was swallowed")
            } catch (e: Exception) {
                var cause: Throwable? = e
                var found = false
                while (cause != null) {
                    if (cause.message?.contains("TCK-SINK-THROW") == true) found = true
                    cause = cause.cause
                }
                assertTrue("propagated exception lost the sink's failure: $e", found)
            }
            assertTrue("generation continued after sink threw", emitted.get() <= 4)
        }
    }

    // ---------------------------------------------------------------- TCK-DT

    /** Greedy determinism across fresh sessions. */
    public fun dt01DeterministicAcrossSessions() {
        withInstance { instance ->
            val runs = (1..3).map {
                val session = instance.createSession(subject.sessionConfig)
                try {
                    generate(instance, session, maxTokens = 12).tokenIds.toList()
                } finally {
                    session.close()
                }
            }
            assertEquals("run 2 diverged from run 1", runs[0], runs[1])
            assertEquals("run 3 diverged from run 1", runs[0], runs[2])
        }
    }

    /** Greedy determinism across instance reloads. */
    public fun dt02DeterministicAcrossReloads() {
        val first = withSession { instance, session ->
            generate(instance, session, maxTokens = 12).tokenIds.toList()
        }
        val second = withSession { instance, session ->
            generate(instance, session, maxTokens = 12).tokenIds.toList()
        }
        assertEquals("generation differs across reloads", first, second)
    }

    // ---------------------------------------------------------------- TCK-ME

    /** SPI-ME-4: native heap returns to baseline after close. */
    public fun me01NativeHeapReleasedOnClose() {
        val baseline = requireProbe(subject.probe.nativeBytes(), "native heap")
        withSession { instance, session -> generate(instance, session, maxTokens = 8) }
        val after = requireProbe(subject.probe.nativeBytes(), "native heap")
        assertTrue(
            "native heap grew ${(after - baseline) / (1024 * 1024)} MB past close (tolerance 16 MB)",
            after - baseline <= 16L * 1024 * 1024,
        )
    }

    /** SPI-ME-2/3 + SPI-MM-4: full-context cost is bounded and close releases RSS. */
    public fun me02RssReleasedOnClose() {
        val baseline = requireProbe(subject.probe.residentBytes(), "RSS")
        val modelBytes = subject.conformancePack.files.values.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        withSession { instance, session ->
            val longPrompt = "context budget measurement ".repeat(subject.sessionConfig.contextLength)
            val allTokens = instance.tokenize(longPrompt).ids
            val tokenCount = minOf(allTokens.size, subject.sessionConfig.contextLength - 2)
            assumeTrue("context fixture produced too few tokens", tokenCount > 16)

            // A single-token prefill does not fault in every page used by a full native batch.
            // Warm one documented chunk so the delta measures KV residency, not lazy buffer commitment.
            val warmTokenCount = minOf(subject.documentedPrefillChunkTokens, tokenCount / 2)
            session.prefill(TokenSequence(allTokens.copyOfRange(0, warmTokenCount)), CancelSignal.NONE)
            val warmed = requireProbe(subject.probe.residentBytes(), "RSS")
            session.prefill(TokenSequence(allTokens.copyOfRange(warmTokenCount, tokenCount)), CancelSignal.NONE)
            session.decode(DecodeParams(1), CancelSignal.NONE) { _, _ -> }
            val full = requireProbe(subject.probe.residentBytes(), "RSS")
            val residentGrowth = (full - warmed).coerceAtLeast(0)
            val declared = subject.declaredKvBytesPerToken * (tokenCount - warmTokenCount)
            val bound = (declared * 3 / 2) + 4L * 1024 * 1024
            assertTrue(
                "full-context resident growth ${residentGrowth / (1024 * 1024)} MB " +
                    "exceeds declared KV bound ${bound / (1024 * 1024)} MB",
                residentGrowth <= bound,
            )
        }
        val settled = awaitTrue(5_000) {
            val now = subject.probe.residentBytes() ?: return@awaitTrue true
            now - baseline <= modelBytes / 10 + 32L * 1024 * 1024
        }
        assertTrue(
            "RSS did not return toward baseline after close " +
                "(now ${(subject.probe.residentBytes() ?: -1) / (1024 * 1024)} MB, " +
                "baseline ${baseline / (1024 * 1024)} MB)",
            settled,
        )
    }

    /** SPI-ME-4: repeated cycles show no monotonic native growth. */
    public fun me04NoLeakAcrossCycles() {
        val first = requireProbe(subject.probe.nativeBytes(), "native heap")
        repeat(5) {
            withSession { instance, session -> generate(instance, session, maxTokens = 4) }
        }
        val last = requireProbe(subject.probe.nativeBytes(), "native heap")
        assertTrue(
            "native heap grew ${(last - first) / (1024 * 1024)} MB across 5 load/close cycles",
            last - first <= 8L * 1024 * 1024,
        )
    }

    // ---------------------------------------------------------------- TCK-ER

    /** SPI-ER-1: missing pack file → IllegalArgumentException, promptly. */
    public fun er01MissingFileCleanError() {
        val missing = ResolvedModelPack(
            id = "tck.er01",
            version = "1",
            files = mapOf("weights.gguf" to subject.workDir.resolve("nope.gguf")),
        )
        try {
            subject.runtimeFactory().loadModel(missing, subject.loadConfig)
            fail("load of missing file succeeded")
        } catch (e: IllegalArgumentException) {
            // required type
        } catch (e: Exception) {
            fail("expected IllegalArgumentException, got ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** SPI-ER-5: the malformed corpus never causes an Error/abort/hang. */
    public fun er02MalformedCorpusSurvived() {
        val original = subject.conformancePack.files.values.first()
        MalformedCorpus.forEachVariant(original, subject.workDir.resolve("er02")) { variant ->
            val pack = ResolvedModelPack("tck.er02", "1", mapOf("weights.gguf" to variant))
            try {
                subject.runtimeFactory().loadModel(pack, subject.loadConfig).close()
                // A structurally-valid variant may load; that is acceptable.
            } catch (e: Exception) {
                // Clean rejection is the expected path.
            } catch (e: Error) {
                fail("corpus file ${variant.fileName} caused ${e.javaClass.simpleName} — SPI-ER-5 violation")
            }
        }
    }

    /** SPI-ER-4: user content never appears in exception messages. */
    public fun er03NoUserContentInErrors() {
        val sentinel = "TCK-SENTINEL-7f3a"
        val collected = mutableListOf<String>()
        subject.probe.beginLogCapture()
        fun harvest(t: Throwable) {
            var cause: Throwable? = t
            while (cause != null) {
                cause.message?.let { collected += it }
                cause = cause.cause
            }
        }
        // Generate with the sentinel in the prompt, then drive every error path.
        val instance = load()
        val session = instance.createSession(subject.sessionConfig)
        try {
            session.prefill(instance.tokenize("Please rewrite: $sentinel"), CancelSignal.NONE)
            session.decode(DecodeParams(4), CancelSignal.NONE) { _, _ -> }
        } finally {
            session.close()
        }
        runCatching { session.prefill(instance.tokenize("x"), CancelSignal.NONE) }
            .exceptionOrNull()?.let { harvest(it) }
        runCatching {
            session.decode(DecodeParams(1), CancelSignal.NONE) { _, _ -> }
        }.exceptionOrNull()?.let { harvest(it) }
        instance.close()
        runCatching { instance.tokenize(sentinel) }.exceptionOrNull()?.let { harvest(it) }
        runCatching {
            subject.runtimeFactory().loadModel(
                ResolvedModelPack("tck.er03", "1", mapOf("weights.gguf" to subject.workDir.resolve("er03-missing.gguf"))),
                subject.loadConfig,
            )
        }.exceptionOrNull()?.let { harvest(it) }

        collected += subject.probe.endLogCapture()

        collected.forEach { message ->
            assertTrue("exception message leaked user content: \"$message\"", sentinel !in message)
        }
    }

    /** SPI-ER-1: absurd configs are rejected with IllegalArgumentException. */
    public fun er04AbsurdConfigRejected() {
        withInstance { instance ->
            try {
                instance.createSession(SessionConfig(contextLength = 0))
                fail("contextLength=0 was accepted")
            } catch (e: IllegalArgumentException) {
                // required
            } catch (e: Exception) {
                fail("expected IllegalArgumentException for ctx=0, got ${e.javaClass.simpleName}")
            }
        }
        try {
            subject.runtimeFactory().loadModel(subject.conformancePack, LoadConfig(threads = 0))
            fail("threads=0 was accepted")
        } catch (e: IllegalArgumentException) {
            // required
        } catch (e: Exception) {
            fail("expected IllegalArgumentException for threads=0, got ${e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- helpers

    private inline fun assertThrowsIse(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what did not throw")
        } catch (e: IllegalStateException) {
            // required
        } catch (e: Exception) {
            fail("$what threw ${e.javaClass.simpleName} instead of IllegalStateException")
        }
    }
}

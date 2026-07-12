package com.touvay.engine.models

import java.util.Random
import kotlin.test.Test
import kotlin.test.fail

internal class VerifierFuzzStyleTest {
    @Test
    fun randomBoundedManifestCorpusNeverEscapesTypedFailure() {
        val random = Random(0x544f55564159L)
        val parser = ManifestParser()
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(2_049))
            random.nextBytes(bytes)
            try {
                parser.parse(bytes)
            } catch (_: ModelPackVerificationException) {
                // Expected: hostile bytes must fail through the typed boundary.
            } catch (unexpected: Throwable) {
                fail("unexpected parser failure type: ${unexpected::class.qualifiedName}")
            }
        }
    }

    @Test
    fun randomBoundedEnvelopeCorpusNeverEscapesTypedFailure() {
        val random = Random(0x5349474e41545552L)
        val codec = SignatureEnvelopeCodec()
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(4_098))
            random.nextBytes(bytes)
            try {
                codec.decode(bytes)
            } catch (_: ModelPackVerificationException) {
                // Expected for arbitrary input.
            } catch (unexpected: Throwable) {
                fail("unexpected envelope failure type: ${unexpected::class.qualifiedName}")
            }
        }
    }

    @Test
    fun deterministicMutationsOfValidSignedPackNeverCrashVerifier() {
        val random = Random(0x4d4f44454c504143L)
        val originalManifest = TestFixtures.manifest().toByteArray()
        val originalEnvelope = TestFixtures.envelope(
            TestFixtures.KEY_ID,
            TestFixtures.sign(originalManifest),
        )
        val verifier = BoundedModelPackVerifier(TestFixtures.trustStore())

        repeat(1_000) { iteration ->
            val mutateManifest = iteration % 2 == 0
            val manifest = if (mutateManifest) mutate(originalManifest, random) else originalManifest
            val envelope = if (mutateManifest) originalEnvelope else mutate(originalEnvelope, random)
            try {
                verifier.verify(manifest, envelope, TestFixtures.environment())
            } catch (_: ModelPackVerificationException) {
                // Every rejection remains typed and content-free.
            } catch (unexpected: Throwable) {
                fail("unexpected verifier failure type: ${unexpected::class.qualifiedName}")
            }
        }
    }

    private fun mutate(source: ByteArray, random: Random): ByteArray = when (random.nextInt(3)) {
        0 -> source.copyOf().also { bytes ->
            repeat(1 + random.nextInt(4)) {
                val index = random.nextInt(bytes.size)
                bytes[index] = (bytes[index].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
        }
        1 -> source.copyOf(random.nextInt(source.size + 1))
        else -> source + ByteArray(1 + random.nextInt(8)).also(random::nextBytes)
    }
}

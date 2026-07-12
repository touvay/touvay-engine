package com.touvay.runtime.tck

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generates hostile variants of the adapter's own conformance model file (TCK-ER-02,
 * SPI-ER-5). Every variant must produce a clean `Exception` or a successful load —
 * never an `Error`, native abort, or hang.
 *
 * Streaming by design: conformance packs can be hundreds of MB; variants are created
 * one at a time, handed to [test], and deleted before the next — bounded heap and disk.
 */
internal object MalformedCorpus {

    fun forEachVariant(original: Path, workDir: Path, test: (Path) -> Unit) {
        Files.createDirectories(workDir)
        val size = Files.size(original)

        fun runVariant(name: String, create: (Path) -> Unit) {
            val path = workDir.resolve("corpus-$name.bin")
            try {
                create(path)
                test(path)
            } finally {
                Files.deleteIfExists(path)
            }
        }

        runVariant("empty") { Files.write(it, ByteArray(0)) }
        runVariant("header-only") { copyPrefix(original, it, minOf(16, size)) }
        runVariant("garbage") { Files.write(it, ByteArray(4096) { i -> (i * 31).toByte() }) }
        runVariant("oversized-header-counts") { path ->
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .put(byteArrayOf(0x47, 0x47, 0x55, 0x46)) // GGUF
                .putInt(3)
                .putLong(Long.MAX_VALUE)
                .putLong(Long.MAX_VALUE)
                .array()
            Files.write(path, header)
        }
        runVariant("truncated-10pct") { copyPrefix(original, it, size / 10) }
        runVariant("truncated-60pct") { copyPrefix(original, it, size * 6 / 10) }
        // One structural-header bit flip on a full copy: corruption must be detected,
        // not crash. (Deep-tensor flips may legitimately load; header flips must not abort.)
        runVariant("bitflip-4") { path ->
            Files.copy(original, path, StandardCopyOption.REPLACE_EXISTING)
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.seek(4)
                val b = file.read()
                file.seek(4)
                file.write(b xor 0xFF)
            }
        }
    }

    private fun copyPrefix(source: Path, destination: Path, bytes: Long) {
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(destination).use { output ->
                val buffer = ByteArray(1 shl 16)
                var remaining = bytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
}

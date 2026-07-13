package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.touvay.engine.models.store.proto.CachedRevisionVerification as CachedRevisionRecord
import com.touvay.engine.models.store.proto.CatalogMetadataCache as CatalogCacheRecord
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

internal enum class CatalogCacheCheckpoint {
    TEMP_DURABLE,
    REPLACED,
}

internal fun interface CatalogCacheFaultInjector {
    fun at(checkpoint: CatalogCacheCheckpoint)

    companion object {
        val NONE: CatalogCacheFaultInjector = CatalogCacheFaultInjector { }
    }
}

internal class CatalogCacheLoad(
    val verifications: Map<ModelRevisionIdentity, CachedRevisionVerification>,
    val cacheWasValid: Boolean,
)

internal class CatalogMetadataCacheStore(
    private val root: Path,
    private val durability: StorageDurability,
    private val faultInjector: CatalogCacheFaultInjector = CatalogCacheFaultInjector.NONE,
) {
    fun load(): CatalogCacheLoad {
        cleanTemporaryFiles()
        val cache = root.resolve(CACHE_FILE)
        if (!Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) {
            return CatalogCacheLoad(emptyMap(), cacheWasValid = false)
        }
        return try {
            val bytes = readBounded(cache)
            val input = CodedInputStream.newInstance(bytes).apply {
                setSizeLimit(MAX_CACHE_BYTES)
                setRecursionLimit(MAX_RECURSION)
            }
            val record = CatalogCacheRecord.parseFrom(input)
            if (!input.isAtEnd ||
                record.schemaVersion != SCHEMA_VERSION ||
                record.revisionsCount > MAX_REVISIONS
            ) {
                throw IllegalArgumentException()
            }
            val values = linkedMapOf<ModelRevisionIdentity, CachedRevisionVerification>()
            record.revisionsList.forEach { cached ->
                val identity = cached.identity()
                if (cached.payloadMetadataFingerprint.size() != SHA256_BYTES ||
                    cached.fullyVerifiedAtEpochMillis < 0 ||
                    values.put(
                        identity,
                        CachedRevisionVerification(
                            identity = identity,
                            payloadMetadataFingerprint = cached.payloadMetadataFingerprint
                                .toByteArray().toHex(),
                            fullyVerifiedAtEpochMillis = cached.fullyVerifiedAtEpochMillis,
                        ),
                    ) != null
                ) {
                    throw IllegalArgumentException()
                }
            }
            CatalogCacheLoad(values.toMap(), cacheWasValid = true)
        } catch (_: Exception) {
            Files.deleteIfExists(cache)
            CatalogCacheLoad(emptyMap(), cacheWasValid = false)
        }
    }

    fun write(verifications: Collection<CachedRevisionVerification>) {
        if (verifications.size > MAX_REVISIONS) return
        Files.createDirectories(root)
        val record = CatalogCacheRecord.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .addAllRevisions(
                verifications.sortedWith(
                    compareBy(
                        { it.identity.packId },
                        { it.identity.packVersion },
                        { it.identity.manifestSha256 },
                    ),
                ).map { it.toRecord() },
            )
            .build()
        val temporary = root.resolve("catalog-cache.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, record.toByteArray())
            durability.forceFile(temporary)
            faultInjector.at(CatalogCacheCheckpoint.TEMP_DURABLE)
            durability.replaceFile(temporary, root.resolve(CACHE_FILE))
            faultInjector.at(CatalogCacheCheckpoint.REPLACED)
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    private fun cleanTemporaryFiles() {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(root).use { entries ->
            entries.filter {
                val name = it.fileName.toString()
                name.startsWith("catalog-cache.") && name.endsWith(".tmp")
            }.forEach(Files::deleteIfExists)
        }
    }

    private fun readBounded(path: Path): ByteArray {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IllegalArgumentException()
        }
        val size = Files.size(path)
        if (size < 0 || size > MAX_CACHE_BYTES) throw IllegalArgumentException()
        return Files.newInputStream(path).use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(size.toInt(), 64 * 1024))
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    total++
                    if (total > MAX_CACHE_BYTES) throw IllegalArgumentException()
                    output.write(byte)
                } else {
                    total += read
                    if (total > MAX_CACHE_BYTES) throw IllegalArgumentException()
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    private fun CachedRevisionRecord.identity(): ModelRevisionIdentity {
        if (!Identifiers.isGeneral(packId, 128) ||
            SemanticVersion.parse(packVersion) == null ||
            manifestSha256.size() != SHA256_BYTES
        ) {
            throw IllegalArgumentException()
        }
        return ModelRevisionIdentity(packId, packVersion, manifestSha256.toByteArray().toHex())
    }

    private fun CachedRevisionVerification.toRecord(): CachedRevisionRecord =
        CachedRevisionRecord.newBuilder()
            .setPackId(identity.packId)
            .setPackVersion(identity.packVersion)
            .setManifestSha256(ByteString.copyFrom(identity.manifestSha256.hexBytes()))
            .setPayloadMetadataFingerprint(
                ByteString.copyFrom(payloadMetadataFingerprint.hexBytes()),
            )
            .setFullyVerifiedAtEpochMillis(fullyVerifiedAtEpochMillis)
            .build()

    private fun String.hexBytes(): ByteArray {
        if (length != SHA256_BYTES * 2) throw IllegalArgumentException()
        return ByteArray(SHA256_BYTES) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CACHE_FILE = "catalog-cache.pb"
        private const val SCHEMA_VERSION = 1
        private const val SHA256_BYTES = 32
        private const val MAX_CACHE_BYTES = 1024 * 1024
        private const val MAX_REVISIONS = 4096
        private const val MAX_RECURSION = 16
    }
}

package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.touvay.engine.models.proto.ModelPackManifest
import com.touvay.engine.models.store.proto.ActivePointer
import com.touvay.engine.models.store.proto.InstallMarker
import com.touvay.engine.models.store.proto.PackIdentityRecord
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TransactionalModelStore(
    private val root: Path,
    private val verifier: BoundedModelPackVerifier,
    private val environment: CompatibilityEnvironment,
    private val durability: StorageDurability = NioStorageDurability,
    private val faultInjector: StoreFaultInjector = StoreFaultInjector.NONE,
) {
    private val lock = ReentrantLock()

    fun install(source: ModelPackSource): InstalledRevision = lock.withLock {
        translateIo {
            ensureRoot()
            val manifestBytes = source.openManifest().use {
                readBounded(it, MAX_MANIFEST_BYTES, ModelStoreFailure.SOURCE_LAYOUT_INVALID)
            }
            val signatureBytes = source.openSignatureEnvelope().use {
                readBounded(it, MAX_SIGNATURE_BYTES, ModelStoreFailure.SOURCE_LAYOUT_INVALID)
            }
            val verified = verifier.verify(manifestBytes, signatureBytes, environment)
            val identity = verified.identity()
            validateSourceLayout(source.entries(), verified.manifest)
            rejectVersionConflict(identity)

            val finalDirectory = revisionDirectory(identity)
            if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
                verifyRevision(finalDirectory, expectedIdentity = identity)
                return@translateIo InstalledRevision(identity, InstallDisposition.ALREADY_INSTALLED)
            }

            val stagingDirectory = stagingRoot.resolve(UUID.randomUUID().toString())
            Files.createDirectory(stagingDirectory)
            faultInjector.at(StoreCheckpoint.STAGING_CREATED)
            try {
                writeDurable(stagingDirectory.resolve(MANIFEST_FILE), manifestBytes)
                writeDurable(stagingDirectory.resolve(SIGNATURE_FILE), signatureBytes)
                durability.forceDirectory(stagingDirectory)
                faultInjector.at(StoreCheckpoint.MANIFESTS_DURABLE)

                val filesDirectory = stagingDirectory.resolve(FILES_DIRECTORY)
                Files.createDirectory(filesDirectory)
                copyDeclaredFiles(source, verified.manifest, filesDirectory)
                forceDirectoryTree(filesDirectory)
                faultInjector.at(StoreCheckpoint.FILES_DURABLE)

                val marker = installMarker(identity).toByteArray()
                writeDurable(stagingDirectory.resolve(INSTALL_MARKER_FILE), marker)
                durability.forceDirectory(stagingDirectory)
                faultInjector.at(StoreCheckpoint.MARKER_DURABLE)

                ensurePackDirectory(identity.packId)
                Files.createDirectories(finalDirectory.parent)
                durability.forceDirectory(finalDirectory.parent)
                durability.commitDirectory(stagingDirectory, finalDirectory)
                faultInjector.at(StoreCheckpoint.REVISION_COMMITTED)
                InstalledRevision(identity, InstallDisposition.INSTALLED)
            } catch (failure: Exception) {
                deleteTreeIfPresent(stagingDirectory)
                throw failure
            }
        }
    }

    fun activate(identity: ModelRevisionIdentity) = lock.withLock {
        translateIo {
            ensureRoot()
            val revision = revisionDirectory(identity)
            val verified = verifyRevision(revision, expectedIdentity = identity)
            compatibilityVerifier().verify(verified.manifest, environment)
            ensurePackDirectory(identity.packId)
            val packDirectory = packDirectory(identity.packId)
            val temporary = packDirectory.resolve("active.${UUID.randomUUID()}.tmp")
            try {
                writeDurable(temporary, activePointer(identity).toByteArray())
                faultInjector.at(StoreCheckpoint.ACTIVE_TEMP_DURABLE)
                durability.replaceFile(temporary, packDirectory.resolve(ACTIVE_POINTER_FILE))
                faultInjector.at(StoreCheckpoint.ACTIVE_REPLACED)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    fun rollback(identity: ModelRevisionIdentity) {
        activate(identity)
    }

    fun activeRevision(packId: String): ModelRevisionIdentity? = lock.withLock {
        translateIo {
            ensureRoot()
            val pointerPath = packDirectory(packId).resolve(ACTIVE_POINTER_FILE)
            if (!Files.isRegularFile(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
                return@translateIo null
            }
            val identity = parseActivePointer(readBounded(pointerPath, MAX_RECORD_BYTES)).toIdentity()
            verifyRevision(revisionDirectory(identity), identity)
            identity
        }
    }

    fun deleteInactive(identity: ModelRevisionIdentity) = lock.withLock {
        translateIo {
            ensureRoot()
            if (activeRevisionUnsafe(identity.packId) == identity) {
                modelStoreFailure(ModelStoreFailure.ACTIVE_REVISION_DELETE_FORBIDDEN)
            }
            val revision = revisionDirectory(identity)
            verifyRevision(revision, expectedIdentity = identity)
            val trashEntry = trashRoot.resolve(UUID.randomUUID().toString())
            durability.commitDirectory(revision, trashEntry)
            faultInjector.at(StoreCheckpoint.REVISION_MOVED_TO_TRASH)
            deleteTreeNoFollow(trashEntry)
            durability.forceDirectory(trashRoot)
        }
    }

    fun recover(): RecoveryReport = lock.withLock {
        translateIo {
            ensureRoot()
            var stagingRemoved = 0
            var trashRemoved = 0
            var invalidRemoved = 0
            var pointersCleared = 0

            childDirectories(stagingRoot).forEach {
                deleteTreeNoFollow(it)
                stagingRemoved++
            }
            childDirectories(trashRoot).forEach {
                deleteTreeNoFollow(it)
                trashRemoved++
            }

            childDirectories(packsRoot).forEach { packDirectory ->
                if (!Files.isDirectory(packDirectory, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(packDirectory)
                ) {
                    quarantineAndDelete(packDirectory)
                    invalidRemoved++
                    return@forEach
                }
                val versions = packDirectory.resolve(VERSIONS_DIRECTORY)
                if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(versions)
                ) {
                    quarantineAndDelete(packDirectory)
                    invalidRemoved++
                    return@forEach
                }
                val validRevisions = mutableListOf<VerifiedManifest>()
                childDirectories(versions).forEach { revision ->
                    val verified = try {
                        val verified = verifyRevision(revision)
                        if (revision.fileName.toString() != revisionDirectoryName(verified.identity())) {
                            null
                        } else {
                            verified
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (verified == null) {
                        quarantineAndDelete(revision)
                        invalidRemoved++
                    } else {
                        validRevisions += verified
                    }
                }
                val directoryName = packDirectory.fileName.toString()
                val recordedIdentity = readPackIdentityOrNull(packDirectory)
                    ?.takeIf { packDirectoryName(it) == directoryName }
                val derivedIdentities = validRevisions.map { it.manifest.packId }
                    .filter { packDirectoryName(it) == directoryName }
                    .distinct()
                val identity = recordedIdentity ?: derivedIdentities.singleOrNull()
                if (identity == null) {
                    quarantineAndDelete(packDirectory)
                    invalidRemoved++
                    return@forEach
                }
                if (recordedIdentity == null) writePackIdentity(packDirectory, identity)
                validRevisions.toList().forEach { verified ->
                    if (verified.manifest.packId != identity) {
                        quarantineAndDelete(
                            versions.resolve(revisionDirectoryName(verified.identity())),
                        )
                        validRevisions -= verified
                        invalidRemoved++
                    }
                }
                val pointer = packDirectory.resolve(ACTIVE_POINTER_FILE)
                if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
                    val valid = try {
                        val active = parseActivePointer(readBounded(pointer, MAX_RECORD_BYTES)).toIdentity()
                        val verified = verifyRevision(revisionDirectory(active), active)
                        compatibilityVerifier().verify(verified.manifest, environment)
                        active.packId == identity
                    } catch (_: Exception) {
                        false
                    }
                    if (!valid) {
                        repairOrClearActivePointer(packDirectory, validRevisions)
                        pointersCleared++
                    }
                }
            }
            durability.forceDirectory(stagingRoot)
            durability.forceDirectory(trashRoot)
            RecoveryReport(stagingRemoved, trashRemoved, invalidRemoved, pointersCleared)
        }
    }

    private fun repairOrClearActivePointer(
        packDirectory: Path,
        revisions: List<VerifiedManifest>,
    ) {
        val compatible = revisions.filter { verified ->
            try {
                compatibilityVerifier().verify(verified.manifest, environment)
                true
            } catch (_: ModelPackVerificationException) {
                false
            }
        }.maxWithOrNull { left, right ->
            val leftVersion = SemanticVersion.parse(left.manifest.packVersion)!!
            val rightVersion = SemanticVersion.parse(right.manifest.packVersion)!!
            leftVersion.compareTo(rightVersion).takeIf { it != 0 }
                ?: left.manifestSha256.compareTo(right.manifestSha256)
        }
        val pointer = packDirectory.resolve(ACTIVE_POINTER_FILE)
        if (compatible == null) {
            Files.deleteIfExists(pointer)
            durability.forceDirectory(packDirectory)
            return
        }
        val temporary = packDirectory.resolve("active.${UUID.randomUUID()}.tmp")
        try {
            writeDurable(temporary, activePointer(compatible.identity()).toByteArray())
            durability.replaceFile(temporary, pointer)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyDeclaredFiles(
        source: ModelPackSource,
        manifest: ModelPackManifest,
        filesDirectory: Path,
    ) {
        manifest.filesList.forEach { declared ->
            val target = safeTarget(filesDirectory, declared.logicalPath)
            Files.createDirectories(target.parent)
            assertNoLinks(filesDirectory, target.parent)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            source.open(declared.logicalPath).use { input ->
                Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (count < declared.byteSize) {
                        val requested = minOf(buffer.size.toLong(), declared.byteSize - count).toInt()
                        val read = input.read(buffer, 0, requested)
                        if (read < 0) modelStoreFailure(ModelStoreFailure.FILE_INTEGRITY_MISMATCH)
                        if (read == 0) {
                            val byte = input.read()
                            if (byte < 0) modelStoreFailure(ModelStoreFailure.FILE_INTEGRITY_MISMATCH)
                            output.write(byte)
                            digest.update(byte.toByte())
                            count++
                        } else {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            count += read
                        }
                    }
                    if (input.read() != -1) {
                        modelStoreFailure(ModelStoreFailure.FILE_INTEGRITY_MISMATCH)
                    }
                }
            }
            if (count != declared.byteSize ||
                !MessageDigest.isEqual(digest.digest(), declared.sha256.toByteArray())
            ) {
                modelStoreFailure(ModelStoreFailure.FILE_INTEGRITY_MISMATCH)
            }
            durability.forceFile(target)
            durability.forceDirectory(target.parent)
        }
    }

    private fun validateSourceLayout(entries: List<ModelPackSourceEntry>, manifest: ModelPackManifest) {
        val declared = manifest.filesList.mapTo(linkedSetOf()) { it.logicalPath }
        val seen = linkedSetOf<String>()
        entries.forEach { entry ->
            if (entry.kind != ModelPackSourceEntryKind.REGULAR_FILE || entry.linkCount != 1) {
                modelStoreFailure(ModelStoreFailure.SOURCE_ENTRY_UNSAFE)
            }
            if (!seen.add(entry.logicalPath)) {
                modelStoreFailure(ModelStoreFailure.SOURCE_LAYOUT_INVALID)
            }
        }
        if (seen != declared) modelStoreFailure(ModelStoreFailure.SOURCE_LAYOUT_INVALID)
    }

    private fun verifyRevision(
        directory: Path,
        expectedIdentity: ModelRevisionIdentity? = null,
    ): VerifiedManifest {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            modelStoreFailure(ModelStoreFailure.REVISION_NOT_FOUND)
        }
        val manifestBytes = readBounded(directory.resolve(MANIFEST_FILE), MAX_MANIFEST_BYTES)
        val signatureBytes = readBounded(directory.resolve(SIGNATURE_FILE), MAX_SIGNATURE_BYTES)
        val verified = verifier.verifyAuthenticity(manifestBytes, signatureBytes)
        val identity = verified.identity()
        if (expectedIdentity != null && identity != expectedIdentity) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
        val marker = parseInstallMarker(readBounded(directory.resolve(INSTALL_MARKER_FILE), MAX_RECORD_BYTES))
        if (marker.toIdentity() != identity) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)

        val expected = verified.manifest.filesList.associateBy { it.logicalPath }
        val filesRoot = directory.resolve(FILES_DIRECTORY)
        val seen = linkedSetOf<String>()
        if (!Files.isDirectory(filesRoot, LinkOption.NOFOLLOW_LINKS)) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
        Files.walkFileTree(filesRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || attrs.isSymbolicLink) {
                    modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
                }
                val logical = filesRoot.relativize(file).joinToString("/") { it.toString() }
                val declared = expected[logical] ?: modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
                verifyFile(file, declared.byteSize, declared.sha256)
                seen += logical
                return FileVisitResult.CONTINUE
            }
        })
        if (seen != expected.keys) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        return verified
    }

    private fun verifyFile(path: Path, expectedSize: Long, expectedDigest: ByteString) {
        if (Files.size(path) != expectedSize) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        if (!MessageDigest.isEqual(digest.digest(), expectedDigest.toByteArray())) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
    }

    private fun rejectVersionConflict(identity: ModelRevisionIdentity) {
        val pack = packDirectory(identity.packId)
        val versions = pack.resolve(VERSIONS_DIRECTORY)
        childDirectories(versions).forEach { revision ->
            val marker = try {
                parseInstallMarker(readBounded(revision.resolve(INSTALL_MARKER_FILE), MAX_RECORD_BYTES))
            } catch (_: Exception) {
                return@forEach
            }
            if (marker.packId == identity.packId &&
                marker.packVersion == identity.packVersion &&
                marker.manifestSha256.toByteArray().toHex() != identity.manifestSha256
            ) {
                modelStoreFailure(ModelStoreFailure.INSTALL_CONFLICT)
            }
        }
    }

    private fun ensureRoot() {
        Files.createDirectories(root)
        val version = root.resolve(ROOT_VERSION_FILE)
        if (Files.exists(version, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(version, LinkOption.NOFOLLOW_LINKS) ||
                !MessageDigest.isEqual(readBounded(version, ROOT_VERSION_BYTES.size), ROOT_VERSION_BYTES)
            ) {
                modelStoreFailure(ModelStoreFailure.UNSUPPORTED_ROOT)
            }
        } else {
            if (Files.newDirectoryStream(root).use { it.iterator().hasNext() }) {
                modelStoreFailure(ModelStoreFailure.UNSUPPORTED_ROOT)
            }
            writeDurable(version, ROOT_VERSION_BYTES)
            durability.forceDirectory(root)
        }
        Files.createDirectories(stagingRoot)
        Files.createDirectories(packsRoot)
        Files.createDirectories(trashRoot)
        durability.forceDirectory(root)
    }

    private fun ensurePackDirectory(packId: String) {
        val pack = packDirectory(packId)
        val versions = pack.resolve(VERSIONS_DIRECTORY)
        Files.createDirectories(versions)
        val identityPath = pack.resolve(IDENTITY_FILE)
        if (Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS)) {
            if (parsePackIdentity(readBounded(identityPath, MAX_RECORD_BYTES)).packId != packId) {
                modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
            }
        } else {
            writePackIdentity(pack, packId)
        }
        durability.forceDirectory(versions)
        durability.forceDirectory(pack)
        durability.forceDirectory(packsRoot)
    }

    private fun activeRevisionUnsafe(packId: String): ModelRevisionIdentity? {
        val pointer = packDirectory(packId).resolve(ACTIVE_POINTER_FILE)
        return if (Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) {
            parseActivePointer(readBounded(pointer, MAX_RECORD_BYTES)).toIdentity()
        } else {
            null
        }
    }

    private fun readPackIdentityOrNull(pack: Path): String? = try {
        parsePackIdentity(readBounded(pack.resolve(IDENTITY_FILE), MAX_RECORD_BYTES)).packId
    } catch (_: Exception) {
        null
    }

    private fun writePackIdentity(pack: Path, packId: String) {
        val target = pack.resolve(IDENTITY_FILE)
        val temporary = pack.resolve("identity.${UUID.randomUUID()}.tmp")
        val record = PackIdentityRecord.newBuilder()
            .setSchemaVersion(RECORD_SCHEMA_VERSION)
            .setPackId(packId)
            .build()
        try {
            writeDurable(temporary, record.toByteArray())
            durability.replaceFile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun quarantineAndDelete(path: Path) {
        val target = trashRoot.resolve(UUID.randomUUID().toString())
        durability.commitDirectory(path, target)
        deleteTreeNoFollow(target)
    }

    private fun safeTarget(base: Path, logicalPath: String): Path {
        val target = base.resolve(logicalPath).normalize()
        if (!target.startsWith(base)) modelStoreFailure(ModelStoreFailure.SOURCE_ENTRY_UNSAFE)
        return target
    }

    private fun assertNoLinks(base: Path, directory: Path) {
        var current: Path? = directory
        while (current != null && current.startsWith(base)) {
            if (Files.isSymbolicLink(current)) modelStoreFailure(ModelStoreFailure.SOURCE_ENTRY_UNSAFE)
            if (current == base) return
            current = current.parent
        }
        modelStoreFailure(ModelStoreFailure.SOURCE_ENTRY_UNSAFE)
    }

    private fun childDirectories(path: Path): List<Path> {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.newDirectoryStream(path).use { stream ->
            stream.map { it }.toList()
        }
    }

    private fun deleteTreeIfPresent(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) deleteTreeNoFollow(path)
    }

    private fun deleteTreeNoFollow(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun writeDurable(path: Path, bytes: ByteArray) {
        Files.write(path, bytes)
        durability.forceFile(path)
    }

    private fun forceDirectoryTree(path: Path) {
        Files.walk(path).use { paths ->
            paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach(durability::forceDirectory)
        }
    }

    private fun readBounded(path: Path, maximumBytes: Int): ByteArray {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
        val size = Files.size(path)
        if (size < 0 || size > maximumBytes) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        return Files.newInputStream(path).use {
            readBounded(it, maximumBytes, ModelStoreFailure.STORE_CORRUPT)
        }
    }

    private fun readBounded(
        input: InputStream,
        maximumBytes: Int,
        failure: ModelStoreFailure,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, COPY_BUFFER_BYTES))
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0
        while (true) {
            val requested = minOf(buffer.size, maximumBytes + 1 - total)
            if (requested <= 0) modelStoreFailure(failure)
            val read = input.read(buffer, 0, requested)
            if (read < 0) return output.toByteArray()
            if (read == 0) {
                val byte = input.read()
                if (byte < 0) return output.toByteArray()
                total++
                if (total > maximumBytes) modelStoreFailure(failure)
                output.write(byte)
            } else {
                total += read
                if (total > maximumBytes) modelStoreFailure(failure)
                output.write(buffer, 0, read)
            }
        }
    }

    private fun parsePackIdentity(bytes: ByteArray): PackIdentityRecord = parseRecord(bytes) {
        PackIdentityRecord.parseFrom(it)
    }.also {
        if (it.schemaVersion != RECORD_SCHEMA_VERSION || !Identifiers.isGeneral(it.packId, 128)) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
    }

    private fun parseInstallMarker(bytes: ByteArray): InstallMarker = parseRecord(bytes) {
        InstallMarker.parseFrom(it)
    }.also { validateRecord(it.schemaVersion, it.packId, it.packVersion, it.manifestSha256) }

    private fun parseActivePointer(bytes: ByteArray): ActivePointer = parseRecord(bytes) {
        ActivePointer.parseFrom(it)
    }.also { validateRecord(it.schemaVersion, it.packId, it.packVersion, it.manifestSha256) }

    private fun <T> parseRecord(bytes: ByteArray, parse: (CodedInputStream) -> T): T = try {
        val input = CodedInputStream.newInstance(bytes)
        input.setSizeLimit(MAX_RECORD_BYTES)
        input.setRecursionLimit(8)
        parse(input).also {
            if (!input.isAtEnd) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
    } catch (_: InvalidProtocolBufferException) {
        modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
    }

    private fun validateRecord(schema: Int, packId: String, version: String, digest: ByteString) {
        if (schema != RECORD_SCHEMA_VERSION ||
            !Identifiers.isGeneral(packId, 128) ||
            SemanticVersion.parse(version) == null ||
            digest.size() != SHA256_BYTES
        ) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
    }

    private fun installMarker(identity: ModelRevisionIdentity): InstallMarker =
        InstallMarker.newBuilder()
            .setSchemaVersion(RECORD_SCHEMA_VERSION)
            .setPackId(identity.packId)
            .setPackVersion(identity.packVersion)
            .setManifestSha256(ByteString.copyFrom(identity.manifestSha256.hexBytes()))
            .build()

    private fun activePointer(identity: ModelRevisionIdentity): ActivePointer =
        ActivePointer.newBuilder()
            .setSchemaVersion(RECORD_SCHEMA_VERSION)
            .setPackId(identity.packId)
            .setPackVersion(identity.packVersion)
            .setManifestSha256(ByteString.copyFrom(identity.manifestSha256.hexBytes()))
            .build()

    private fun InstallMarker.toIdentity(): ModelRevisionIdentity = ModelRevisionIdentity(
        packId,
        packVersion,
        manifestSha256.toByteArray().toHex(),
    )

    private fun ActivePointer.toIdentity(): ModelRevisionIdentity = ModelRevisionIdentity(
        packId,
        packVersion,
        manifestSha256.toByteArray().toHex(),
    )

    private fun VerifiedManifest.identity(): ModelRevisionIdentity = ModelRevisionIdentity(
        manifest.packId,
        manifest.packVersion,
        manifestSha256,
    )

    private fun compatibilityVerifier(): CompatibilityVerifier = CompatibilityVerifier()

    private fun packDirectory(packId: String): Path = packsRoot.resolve(packDirectoryName(packId))

    private fun revisionDirectory(identity: ModelRevisionIdentity): Path =
        packDirectory(identity.packId)
            .resolve(VERSIONS_DIRECTORY)
            .resolve(revisionDirectoryName(identity))

    private fun packDirectoryName(packId: String): String =
        MessageDigest.getInstance("SHA-256").digest(packId.toByteArray(StandardCharsets.UTF_8)).toHex()

    private fun revisionDirectoryName(identity: ModelRevisionIdentity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(VERSION_DIRECTORY_DOMAIN)
        digest.update(0.toByte())
        digest.update(identity.packVersion.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(identity.manifestSha256.hexBytes())
        return digest.digest().toHex()
    }

    private val stagingRoot: Path get() = root.resolve(STAGING_DIRECTORY)
    private val packsRoot: Path get() = root.resolve(PACKS_DIRECTORY)
    private val trashRoot: Path get() = root.resolve(TRASH_DIRECTORY)

    private inline fun <T> translateIo(block: () -> T): T = try {
        block()
    } catch (failure: ModelPackVerificationException) {
        throw failure
    } catch (failure: ModelStoreException) {
        throw failure
    } catch (_: Exception) {
        modelStoreFailure(ModelStoreFailure.IO_FAILURE)
    }

    private fun String.hexBytes(): ByteArray {
        if (length != SHA256_BYTES * 2) modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        return try {
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            modelStoreFailure(ModelStoreFailure.STORE_CORRUPT)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val RECORD_SCHEMA_VERSION = 1
        private const val SHA256_BYTES = 32
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_RECORD_BYTES = 4 * 1024
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_SIGNATURE_BYTES = 4 * 1024
        private const val ROOT_VERSION_FILE = "root.version"
        private const val STAGING_DIRECTORY = "staging"
        private const val PACKS_DIRECTORY = "packs"
        private const val TRASH_DIRECTORY = "trash"
        private const val VERSIONS_DIRECTORY = "versions"
        private const val IDENTITY_FILE = "identity.pb"
        private const val ACTIVE_POINTER_FILE = "active.pb"
        private const val MANIFEST_FILE = "manifest.pb"
        private const val SIGNATURE_FILE = "manifest.sig"
        private const val INSTALL_MARKER_FILE = "installed.ok"
        private const val FILES_DIRECTORY = "files"
        private val ROOT_VERSION_BYTES = "TOUVAY_MODEL_STORE_V1\n".toByteArray(StandardCharsets.US_ASCII)
        private val VERSION_DIRECTORY_DOMAIN =
            "TOUVAY_MODEL_VERSION_DIR_V1".toByteArray(StandardCharsets.US_ASCII)
    }
}

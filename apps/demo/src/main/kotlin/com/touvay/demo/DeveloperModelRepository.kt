package com.touvay.demo

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Base64
import com.touvay.engine.models.ModelManagerDeviceTier
import com.touvay.engine.models.ModelManagerPlatform
import com.touvay.engine.models.ModelManagerPlatformConfiguration
import com.touvay.engine.models.ModelManagerRemovalResult
import com.touvay.engine.models.ModelManagerRevisionInfo
import com.touvay.engine.models.ModelManagerRuntimeRegistration
import com.touvay.engine.models.ModelManagerTrustedKey
import com.touvay.engine.models.ModelRevisionIdentity
import com.touvay.runtime.llamacpp.LlamaCppRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Developer-only administrator over the existing signed Model Manager pipeline. */
internal class DeveloperModelRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val sourceIndex = appContext.getSharedPreferences("console_model_sources", Context.MODE_PRIVATE)
    private val storeRoot = appContext.noBackupFilesDir.toPath().resolve("touvay-models")
    private val activeSourceRoot = requireNotNull(appContext.getExternalFilesDir(null))
        .toPath().resolve(DemoPackProvisioner.DIRECTORY)
    private val libraryRoot = requireNotNull(appContext.getExternalFilesDir(null))
        .toPath().resolve("console-model-library")

    suspend fun prepareBuiltIn(): DemoPackState = withContext(Dispatchers.IO) {
        DemoPackProvisioner.prepare(appContext)
    }

    fun acquisitionSources(): List<ModelAcquisitionSource> = listOf(
        ModelAcquisitionSource("local_directory", "Import signed model pack", true),
        ModelAcquisitionSource("official_catalog", "Official Model Catalog — future", false),
    )

    suspend fun importDirectory(tree: Uri): Path = withContext(Dispatchers.IO) {
        val pending = libraryRoot.resolve("import-${UUID.randomUUID()}.pending")
        val committed = libraryRoot.resolve("import-${UUID.randomUUID()}")
        Files.createDirectories(libraryRoot)
        try {
            DocumentTreeCopier(appContext).copy(tree, pending)
            try {
                Files.move(pending, committed, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(pending, committed)
            }
            committed
        } catch (failure: Throwable) {
            deleteTree(pending)
            throw failure
        }
    }

    /** Full verification uses an isolated temporary Model Manager store and commits nothing. */
    suspend fun verify(source: Path): ModelRevisionIdentity = withContext(Dispatchers.IO) {
        val verificationRoot = appContext.cacheDir.toPath().resolve("verify-${UUID.randomUUID()}")
        try {
            withPlatform(verificationRoot) { platform -> platform.installAndActivate(source) }
                .also { rememberSource(it, source) }
        } finally {
            deleteTree(verificationRoot)
        }
    }

    suspend fun install(source: Path): ModelRevisionIdentity = withContext(Dispatchers.IO) {
        withPlatform(storeRoot) { platform -> platform.install(source) }
            .also { rememberSource(it, source) }
    }

    suspend fun revisions(): List<ModelManagerRevisionInfo> = withContext(Dispatchers.IO) {
        withPlatform(storeRoot, ModelManagerPlatform::revisions).also { revisions ->
            revisions.singleOrNull {
                it.state == com.touvay.engine.models.ModelManagerRevisionState.ACTIVE
            }?.takeIf { sourceIndex.getString(it.identity.key(), null) == null }
                ?.let { rememberSource(it.identity, activeSourceRoot) }
        }
    }

    suspend fun activate(identity: ModelRevisionIdentity): Unit = withContext(Dispatchers.IO) {
        activateOrRollback(identity, rollback = false)
    }

    suspend fun rollback(identity: ModelRevisionIdentity): Unit = withContext(Dispatchers.IO) {
        activateOrRollback(identity, rollback = true)
    }

    suspend fun deleteInactive(identity: ModelRevisionIdentity): ModelManagerRemovalResult =
        withContext(Dispatchers.IO) {
            withPlatform(storeRoot) { platform -> platform.deleteInactive(identity) }
        }

    fun trustedKeySummary(): String = "$KEY_ID\nEd25519 • Engine pinned"

    private suspend fun activateOrRollback(identity: ModelRevisionIdentity, rollback: Boolean) {
        val source = sourceFor(identity)
            ?: error("No imported source is available for this revision")
        withPlatform(storeRoot) { platform ->
            val current = platform.revisions().singleOrNull {
                it.state == com.touvay.engine.models.ModelManagerRevisionState.ACTIVE
            }
            val swap = publishSource(source, current?.identity)
            try {
                if (rollback) platform.rollback(identity) else platform.activate(identity)
                swap.commit()
            } catch (failure: Throwable) {
                swap.restore()
                throw failure
            }
        }
    }

    private fun publishSource(source: Path, current: ModelRevisionIdentity?): SourceSwap {
        if (source.normalize() == activeSourceRoot.normalize()) return SourceSwap.NONE
        val pending = activeSourceRoot.resolveSibling("${activeSourceRoot.fileName}.pending")
        val backup = activeSourceRoot.resolveSibling("${activeSourceRoot.fileName}.backup")
        deleteTree(pending)
        deleteTree(backup)
        copyTree(source, pending)
        if (Files.exists(activeSourceRoot)) Files.move(activeSourceRoot, backup)
        try {
            Files.move(pending, activeSourceRoot)
        } catch (failure: Throwable) {
            if (Files.exists(backup)) Files.move(backup, activeSourceRoot)
            throw failure
        }
        return SourceSwap(
            commitAction = {
                if (current != null && Files.exists(backup)) {
                    val archive = libraryRoot.resolve("revision-${current.manifestSha256}")
                    if (!Files.exists(archive)) Files.move(backup, archive) else deleteTree(backup)
                    rememberSource(current, archive)
                } else {
                    deleteTree(backup)
                }
            },
            restoreAction = {
                deleteTree(activeSourceRoot)
                if (Files.exists(backup)) Files.move(backup, activeSourceRoot)
            },
        )
    }

    private fun rememberSource(identity: ModelRevisionIdentity, source: Path) {
        sourceIndex.edit().putString(identity.key(), source.toAbsolutePath().toString()).apply()
    }

    private fun sourceFor(identity: ModelRevisionIdentity): Path? =
        sourceIndex.getString(identity.key(), null)?.let(Paths::get)?.takeIf(Files::isDirectory)

    private suspend fun <T> withPlatform(
        root: Path,
        block: (ModelManagerPlatform) -> T,
    ): T {
        val platform = createPlatform(root)
        return try {
            block(platform)
        } finally {
            platform.shutdown()
        }
    }

    private fun createPlatform(root: Path): ModelManagerPlatform {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val tier = when {
            activityManager.isLowRamDevice || memory.totalMem < 3_500_000_000L -> ModelManagerDeviceTier.T0
            memory.totalMem < 6_000_000_000L -> ModelManagerDeviceTier.T1
            memory.totalMem < 12_000_000_000L -> ModelManagerDeviceTier.T2
            else -> ModelManagerDeviceTier.T3
        }
        return ModelManagerPlatform(
            root = root,
            configuration = ModelManagerPlatformConfiguration(
                engineVersion = ENGINE_VERSION,
                androidApi = Build.VERSION.SDK_INT,
                deviceTier = tier,
                totalRamBytes = memory.totalMem,
                isLowRamDevice = activityManager.isLowRamDevice,
                supportedAbis = Build.SUPPORTED_ABIS.toSet(),
                trustedKeys = listOf(
                    ModelManagerTrustedKey(KEY_ID, Base64.decode(PUBLIC_KEY_BASE64, Base64.NO_WRAP)),
                ),
            ),
            runtimeRegistrations = listOf(
                ModelManagerRuntimeRegistration(
                    bindingIdentity = "llamacpp.b5199",
                    runtime = LlamaCppRuntime(),
                    adapterVersion = "1.0.0",
                ),
            ),
        )
    }

    private fun ModelRevisionIdentity.key(): String = "$packId|$packVersion|$manifestSha256"

    private class SourceSwap(
        private val commitAction: () -> Unit,
        private val restoreAction: () -> Unit,
    ) {
        fun commit() = commitAction()
        fun restore() = restoreAction()

        companion object {
            val NONE = SourceSwap({}, {})
        }
    }

    private companion object {
        const val ENGINE_VERSION = "0.1.0"
        const val KEY_ID = "d086218cf3b91da80208646e6a4e0ded99da431e8848d74417d08fb09851edb6"
        const val PUBLIC_KEY_BASE64 = "2SmylYadUGTDCKocs5z03VH0J8jE9QBV2RTaux9pWW8="
    }
}

internal data class ModelAcquisitionSource(
    val id: String,
    val label: String,
    val available: Boolean,
)

private class DocumentTreeCopier(private val context: Context) {
    private var fileCount = 0
    private var byteCount = 0L

    fun copy(tree: Uri, destination: Path) {
        Files.createDirectories(destination)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        copyChildren(tree, rootId, destination, depth = 0)
        require(Files.isRegularFile(destination.resolve("manifest.pb")))
        require(Files.isRegularFile(destination.resolve("manifest.sig")))
        require(Files.isDirectory(destination.resolve("files")))
    }

    private fun copyChildren(tree: Uri, documentId: String, destination: Path, depth: Int) {
        require(depth <= MAX_DEPTH)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        context.contentResolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                require(SAFE_NAME.matches(name))
                val target = destination.resolve(name).normalize()
                require(target.startsWith(destination))
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    Files.createDirectory(target)
                    copyChildren(tree, id, target, depth + 1)
                } else {
                    require(++fileCount <= MAX_FILES)
                    val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    context.contentResolver.openInputStream(document).use { input ->
                        requireNotNull(input)
                        Files.newOutputStream(target).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                byteCount += read
                                require(byteCount <= MAX_BYTES)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        } ?: error("Selected model-pack directory cannot be read")
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_FILES = 256
        const val MAX_BYTES = 8L * 1024L * 1024L * 1024L
        const val COPY_BUFFER_BYTES = 64 * 1024
        val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,240}")
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}

private fun copyTree(source: Path, destination: Path) {
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val target = destination.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path)) {
                Files.createDirectories(target)
            } else {
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

package com.touvay.engine.models

import com.touvay.engine.models.proto.ModelPackManifest
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal enum class CatalogRevisionState {
    ACTIVE,
    INSTALLED_INACTIVE,
    PENDING_REMOVAL,
}

internal enum class CatalogCompatibility {
    COMPATIBLE,
    INCOMPATIBLE,
}

internal enum class CatalogLoadHealth {
    NEVER_LOADED,
}

internal class ResolvedModelFile(
    val logicalPath: String,
    val absolutePath: Path,
    val byteSize: Long,
    val sha256: String,
)

internal class ResolvedModelRevision(
    val identity: ModelRevisionIdentity,
    val manifest: ModelPackManifest,
    val trust: SigningKeyTrust,
    val revisionDirectory: Path,
    files: List<ResolvedModelFile>,
    val installBytes: Long,
) {
    val files: List<ResolvedModelFile> = Collections.unmodifiableList(files.toList())
}

internal class CatalogRevision(
    val resolved: ResolvedModelRevision,
    val state: CatalogRevisionState,
    val compatibility: CatalogCompatibility,
    val compatibilityFailure: VerificationFailure?,
    val storageReferenceCount: Int,
    val fullyVerifiedAtEpochMillis: Long,
    val loadHealth: CatalogLoadHealth = CatalogLoadHealth.NEVER_LOADED,
)

internal class CatalogSnapshot(
    val generation: Long,
    revisions: List<CatalogRevision>,
) {
    val revisions: List<CatalogRevision> = Collections.unmodifiableList(revisions.toList())

    companion object {
        val EMPTY: CatalogSnapshot = CatalogSnapshot(0, emptyList())
    }
}

internal class CatalogRebuildReport(
    val recovery: RecoveryReport,
    val revisionCount: Int,
    val metadataCacheUsed: Boolean,
)

internal sealed interface VersionSelection {
    class Active(val packId: String) : VersionSelection

    class Exact(val identity: ModelRevisionIdentity) : VersionSelection

    class HighestCompatible(val packId: String) : VersionSelection
}

internal enum class RemovalDisposition {
    REMOVED,
    DEFERRED_UNTIL_RELEASE,
}

internal class PackRevisionLease internal constructor(
    val revision: ResolvedModelRevision,
    private val releaseAction: (ModelRevisionIdentity) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction(revision.identity)
    }
}

internal fun interface CatalogClock {
    fun epochMillis(): Long

    companion object {
        val SYSTEM: CatalogClock = CatalogClock(System::currentTimeMillis)
    }
}

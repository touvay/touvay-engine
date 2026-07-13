package com.touvay.engine.models

/** Exact immutable installed revision identity exposed to the composition root. */
public class ModelRevisionIdentity(
    public val packId: String,
    public val packVersion: String,
    public val manifestSha256: String,
) {
    override fun equals(other: Any?): Boolean = other is ModelRevisionIdentity &&
        packId == other.packId &&
        packVersion == other.packVersion &&
        manifestSha256 == other.manifestSha256

    override fun hashCode(): Int = 31 * (31 * packId.hashCode() + packVersion.hashCode()) +
        manifestSha256.hashCode()

    override fun toString(): String = "ModelRevisionIdentity(redacted)"
}

internal enum class InstallDisposition {
    INSTALLED,
    ALREADY_INSTALLED,
}

internal class InstalledRevision(
    val identity: ModelRevisionIdentity,
    val disposition: InstallDisposition,
)

internal class RecoveryReport(
    val removedStagingEntries: Int,
    val completedTrashDeletes: Int,
    val removedInvalidRevisions: Int,
    val repairedOrClearedActivePointers: Int,
)

internal class CachedRevisionVerification(
    val identity: ModelRevisionIdentity,
    val payloadMetadataFingerprint: String,
    val fullyVerifiedAtEpochMillis: Long,
)

internal class StoredCatalogRevision(
    val verifiedManifest: VerifiedManifest,
    val directory: java.nio.file.Path,
    val payloadMetadataFingerprint: String,
    val payloadFullyVerified: Boolean,
)

internal class StoreCatalogRecovery(
    val report: RecoveryReport,
    val revisions: List<StoredCatalogRevision>,
    val activeRevisions: Set<ModelRevisionIdentity>,
)

internal enum class StoreCheckpoint {
    STAGING_CREATED,
    MANIFESTS_DURABLE,
    FILES_DURABLE,
    MARKER_DURABLE,
    REVISION_COMMITTED,
    ACTIVE_TEMP_DURABLE,
    ACTIVE_REPLACED,
    REVISION_MOVED_TO_TRASH,
}

internal fun interface StoreFaultInjector {
    fun at(checkpoint: StoreCheckpoint)

    companion object {
        val NONE: StoreFaultInjector = StoreFaultInjector { }
    }
}

package com.touvay.engine.models

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class ModelCatalogManager(
    root: Path,
    private val store: TransactionalModelStore,
    private val environment: CompatibilityEnvironment,
    durability: StorageDurability = NioStorageDurability,
    private val clock: CatalogClock = CatalogClock.SYSTEM,
    cacheFaultInjector: CatalogCacheFaultInjector = CatalogCacheFaultInjector.NONE,
) {
    private val lock = ReentrantLock()
    private val snapshot = AtomicReference(CatalogSnapshot.EMPTY)
    private val referenceCounts = mutableMapOf<ModelRevisionIdentity, Int>()
    private val pendingRemoval = mutableSetOf<ModelRevisionIdentity>()
    private val cache = CatalogMetadataCacheStore(root, durability, cacheFaultInjector)
    private val compatibilityVerifier = CompatibilityVerifier()
    private val consistencyVerifier = CatalogConsistencyVerifier()
    private var generation = 0L

    fun snapshot(): CatalogSnapshot = snapshot.get()

    fun rebuild(): CatalogRebuildReport = lock.withLock { rebuildLocked() }

    fun install(source: ModelPackSource): InstalledRevision = lock.withLock {
        val installed = store.install(source)
        rebuildLocked()
        installed
    }

    fun activate(identity: ModelRevisionIdentity) = lock.withLock {
        val revision = findExact(snapshot.get(), identity)
        ensureAcquirable(revision)
        store.activate(identity)
        rebuildLocked()
    }

    fun rollback(identity: ModelRevisionIdentity) {
        activate(identity)
    }

    fun select(selection: VersionSelection): CatalogRevision = lock.withLock {
        selectFrom(snapshot.get(), selection).also(::ensureAcquirable)
    }

    fun acquire(selection: VersionSelection): PackRevisionLease = lock.withLock {
        val selected = selectFrom(snapshot.get(), selection)
        ensureAcquirable(selected)
        val identity = selected.resolved.identity
        val current = referenceCounts[identity] ?: 0
        if (current == Int.MAX_VALUE) {
            modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
        }
        referenceCounts[identity] = current + 1
        publishReferenceState()
        PackRevisionLease(selected.resolved, ::release)
    }

    fun requestRemoval(identity: ModelRevisionIdentity): RemovalDisposition = lock.withLock {
        val revision = findExact(snapshot.get(), identity)
        if (revision.state == CatalogRevisionState.ACTIVE) {
            modelCatalogFailure(ModelCatalogFailure.ACTIVE_REVISION_REMOVE_FORBIDDEN)
        }
        if ((referenceCounts[identity] ?: 0) > 0) {
            pendingRemoval += identity
            publishReferenceState()
            RemovalDisposition.DEFERRED_UNTIL_RELEASE
        } else {
            store.deleteInactive(identity)
            pendingRemoval -= identity
            rebuildLocked()
            RemovalDisposition.REMOVED
        }
    }

    private fun release(identity: ModelRevisionIdentity) = lock.withLock {
        val current = referenceCounts[identity]
            ?: modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
        if (current <= 0) modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
        if (current == 1) referenceCounts.remove(identity) else referenceCounts[identity] = current - 1

        if (identity in pendingRemoval && current == 1) {
            try {
                store.deleteInactive(identity)
                pendingRemoval -= identity
                rebuildLocked()
            } catch (_: Exception) {
                publishReferenceState()
            }
        } else {
            publishReferenceState()
        }
    }

    private fun rebuildLocked(): CatalogRebuildReport {
        retryUnreferencedPendingRemovals()
        val now = clock.epochMillis()
        if (now < 0) modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
        val cached = try {
            cache.load()
        } catch (_: Exception) {
            CatalogCacheLoad(emptyMap(), cacheWasValid = false)
        }
        val cachedVerifications = cached.verifications.filterValues {
            it.fullyVerifiedAtEpochMillis <= now
        }
        val recovered = store.recoverForCatalog(cachedVerifications)
        val installedIdentities = recovered.revisions
            .mapTo(linkedSetOf()) { it.verifiedManifest.identity() }
        if (referenceCounts.any { (identity, count) -> count <= 0 || identity !in installedIdentities }) {
            modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
        }
        pendingRemoval.retainAll(installedIdentities)

        val revisions = recovered.revisions.map { stored ->
            val verified = stored.verifiedManifest
            val identity = verified.identity()
            val cachedVerification = cachedVerifications[identity]
            val verifiedAt = if (stored.payloadFullyVerified) {
                now
            } else {
                cachedVerification?.fullyVerifiedAtEpochMillis
                    ?: modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
            }
            catalogRevision(
                stored = stored,
                active = identity in recovered.activeRevisions,
                referenceCount = referenceCounts[identity] ?: 0,
                pending = identity in pendingRemoval,
                verifiedAt = verifiedAt,
            )
        }
        val next = CatalogSnapshot(++generation, revisions.sortedWith(REVISION_ORDER))
        consistencyVerifier.verify(next, recovered.activeRevisions)

        try {
            cache.write(
                recovered.revisions.map { stored ->
                    val identity = stored.verifiedManifest.identity()
                    val catalogRevision = revisions.single { it.resolved.identity == identity }
                    CachedRevisionVerification(
                        identity = identity,
                        payloadMetadataFingerprint = stored.payloadMetadataFingerprint,
                        fullyVerifiedAtEpochMillis = catalogRevision.fullyVerifiedAtEpochMillis,
                    )
                },
            )
        } catch (_: Exception) {
            // The cache is disposable; the committed revision set has already been verified.
        }
        snapshot.set(next)
        return CatalogRebuildReport(
            recovery = recovered.report,
            revisionCount = revisions.size,
            metadataCacheUsed = recovered.revisions.any { !it.payloadFullyVerified },
        )
    }

    private fun retryUnreferencedPendingRemovals() {
        pendingRemoval.toList().forEach { identity ->
            if ((referenceCounts[identity] ?: 0) == 0) {
                try {
                    store.deleteInactive(identity)
                    pendingRemoval -= identity
                } catch (_: Exception) {
                    // Retain the tombstone in this process and retry on the next rebuild.
                }
            }
        }
    }

    private fun catalogRevision(
        stored: StoredCatalogRevision,
        active: Boolean,
        referenceCount: Int,
        pending: Boolean,
        verifiedAt: Long,
    ): CatalogRevision {
        val verified = stored.verifiedManifest
        val manifest = verified.manifest
        val compatibilityFailure = try {
            compatibilityVerifier.verify(manifest, environment)
            null
        } catch (failure: ModelPackVerificationException) {
            failure.failure
        }
        val filesRoot = stored.directory.resolve("files")
        val files = manifest.filesList.map { file ->
            ResolvedModelFile(
                logicalPath = file.logicalPath,
                absolutePath = filesRoot.resolve(file.logicalPath).normalize().toAbsolutePath(),
                byteSize = file.byteSize,
                sha256 = file.sha256.toByteArray().toHex(),
            )
        }
        val resolved = ResolvedModelRevision(
            identity = verified.identity(),
            manifest = manifest,
            trust = verified.trust,
            revisionDirectory = stored.directory.toAbsolutePath().normalize(),
            files = files,
            installBytes = manifest.filesList.sumOf { it.byteSize },
        )
        return CatalogRevision(
            resolved = resolved,
            state = when {
                pending -> CatalogRevisionState.PENDING_REMOVAL
                active -> CatalogRevisionState.ACTIVE
                else -> CatalogRevisionState.INSTALLED_INACTIVE
            },
            compatibility = if (compatibilityFailure == null) {
                CatalogCompatibility.COMPATIBLE
            } else {
                CatalogCompatibility.INCOMPATIBLE
            },
            compatibilityFailure = compatibilityFailure,
            storageReferenceCount = referenceCount,
            fullyVerifiedAtEpochMillis = verifiedAt,
        )
    }

    private fun publishReferenceState() {
        val current = snapshot.get()
        val updated = current.revisions.map { revision ->
            val identity = revision.resolved.identity
            CatalogRevision(
                resolved = revision.resolved,
                state = when {
                    identity in pendingRemoval -> CatalogRevisionState.PENDING_REMOVAL
                    revision.state == CatalogRevisionState.ACTIVE -> CatalogRevisionState.ACTIVE
                    else -> CatalogRevisionState.INSTALLED_INACTIVE
                },
                compatibility = revision.compatibility,
                compatibilityFailure = revision.compatibilityFailure,
                storageReferenceCount = referenceCounts[identity] ?: 0,
                fullyVerifiedAtEpochMillis = revision.fullyVerifiedAtEpochMillis,
                loadHealth = revision.loadHealth,
            )
        }
        val next = CatalogSnapshot(++generation, updated)
        consistencyVerifier.verify(
            next,
            next.revisions.filter { it.state == CatalogRevisionState.ACTIVE }
                .mapTo(linkedSetOf()) { it.resolved.identity },
        )
        snapshot.set(next)
    }

    private fun selectFrom(snapshot: CatalogSnapshot, selection: VersionSelection): CatalogRevision =
        when (selection) {
            is VersionSelection.Active -> snapshot.revisions.singleOrNull {
                it.resolved.identity.packId == selection.packId &&
                    it.state == CatalogRevisionState.ACTIVE
            }
            is VersionSelection.Exact -> snapshot.revisions.singleOrNull {
                it.resolved.identity == selection.identity
            }
            is VersionSelection.HighestCompatible -> snapshot.revisions
                .filter {
                    it.resolved.identity.packId == selection.packId &&
                        it.compatibility == CatalogCompatibility.COMPATIBLE &&
                        it.state != CatalogRevisionState.PENDING_REMOVAL
                }
                .maxWithOrNull(VERSION_ORDER)
        } ?: modelCatalogFailure(ModelCatalogFailure.REVISION_NOT_FOUND)

    private fun findExact(snapshot: CatalogSnapshot, identity: ModelRevisionIdentity): CatalogRevision =
        snapshot.revisions.singleOrNull { it.resolved.identity == identity }
            ?: modelCatalogFailure(ModelCatalogFailure.REVISION_NOT_FOUND)

    private fun ensureAcquirable(revision: CatalogRevision) {
        if (revision.state == CatalogRevisionState.PENDING_REMOVAL) {
            modelCatalogFailure(ModelCatalogFailure.REVISION_PENDING_REMOVAL)
        }
        if (revision.compatibility != CatalogCompatibility.COMPATIBLE) {
            modelCatalogFailure(ModelCatalogFailure.REVISION_INCOMPATIBLE)
        }
    }

    private fun VerifiedManifest.identity(): ModelRevisionIdentity = ModelRevisionIdentity(
        manifest.packId,
        manifest.packVersion,
        manifestSha256,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val VERSION_ORDER = Comparator<CatalogRevision> { left, right ->
            val version = SemanticVersion.parse(left.resolved.identity.packVersion)!!
                .compareTo(SemanticVersion.parse(right.resolved.identity.packVersion)!!)
            version.takeIf { it != 0 }
                ?: left.resolved.identity.manifestSha256
                    .compareTo(right.resolved.identity.manifestSha256)
        }
        private val REVISION_ORDER = compareBy<CatalogRevision>(
            { it.resolved.identity.packId },
            { SemanticVersion.parse(it.resolved.identity.packVersion) },
            { it.resolved.identity.manifestSha256 },
        )
    }
}

internal class CatalogConsistencyVerifier {
    fun verify(snapshot: CatalogSnapshot, durableActive: Set<ModelRevisionIdentity>) {
        val identities = mutableSetOf<ModelRevisionIdentity>()
        val active = mutableSetOf<ModelRevisionIdentity>()
        snapshot.revisions.forEach { revision ->
            val resolved = revision.resolved
            if (!identities.add(resolved.identity) ||
                resolved.identity.packId != resolved.manifest.packId ||
                resolved.identity.packVersion != resolved.manifest.packVersion ||
                revision.storageReferenceCount < 0 ||
                (revision.state == CatalogRevisionState.ACTIVE &&
                    revision.compatibility != CatalogCompatibility.COMPATIBLE) ||
                (revision.compatibility == CatalogCompatibility.COMPATIBLE &&
                    revision.compatibilityFailure != null) ||
                (revision.compatibility == CatalogCompatibility.INCOMPATIBLE &&
                    revision.compatibilityFailure == null)
            ) {
                modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
            }
            if (revision.state == CatalogRevisionState.ACTIVE) active += resolved.identity
            if (resolved.files.map { it.logicalPath }.toSet() !=
                resolved.manifest.filesList.map { it.logicalPath }.toSet()
            ) {
                modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
            }
            val filesRoot = resolved.revisionDirectory.resolve("files").normalize()
            val declaredFiles = resolved.manifest.filesList.associateBy { it.logicalPath }
            resolved.files.forEach { file ->
                val expected = filesRoot.resolve(file.logicalPath).normalize()
                val declared = declaredFiles[file.logicalPath]
                    ?: modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
                if (!expected.startsWith(filesRoot) ||
                    file.absolutePath.normalize() != expected ||
                    file.byteSize != declared.byteSize ||
                    file.sha256 != declared.sha256.toByteArray().toHex()
                ) {
                    modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
                }
            }
        }
        if (active != durableActive ||
            snapshot.revisions.filter { it.state == CatalogRevisionState.ACTIVE }
                .groupBy { it.resolved.identity.packId }
                .any { it.value.size != 1 }
        ) {
            modelCatalogFailure(ModelCatalogFailure.CATALOG_INCONSISTENT)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

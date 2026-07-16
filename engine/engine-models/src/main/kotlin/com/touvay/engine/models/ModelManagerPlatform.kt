package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.touvay.engine.models.proto.DeviceTier
import com.touvay.runtime.api.InferenceRuntime
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import kotlinx.coroutines.Dispatchers

/** Device tier supplied by the Android composition root. */
public enum class ModelManagerDeviceTier {
    T0,
    T1,
    T2,
    T3,
}

/** One engine-pinned Ed25519 verification key. Private keys never enter the engine. */
public class ModelManagerTrustedKey(
    public val keyId: String,
    publicKey: ByteArray,
) {
    private val publicKeySnapshot: ByteArray = publicKey.copyOf()

    public val publicKey: ByteArray get() = publicKeySnapshot.copyOf()

    init {
        require(publicKeySnapshot.size == TrustedSigningKey.ED25519_PUBLIC_KEY_BYTES)
        require(Identifiers.isSigningKey(keyId))
    }
}

/** One concrete Runtime registration owned by the engine-service composition root. */
public class ModelManagerRuntimeRegistration(
    public val bindingIdentity: String,
    public val runtime: InferenceRuntime,
    public val adapterVersion: String,
    features: Set<String> = emptySet(),
) {
    public val features: Set<String> = Collections.unmodifiableSet(features.toSet())
}

/** Immutable compatibility and trust configuration for one engine process. */
public class ModelManagerPlatformConfiguration(
    public val engineVersion: String,
    public val androidApi: Int,
    public val deviceTier: ModelManagerDeviceTier,
    public val totalRamBytes: Long,
    public val isLowRamDevice: Boolean,
    supportedAbis: Set<String>,
    trustedKeys: List<ModelManagerTrustedKey>,
    supportedManifestFeatures: Set<String> = emptySet(),
) {
    public val supportedAbis: Set<String> = Collections.unmodifiableSet(supportedAbis.toSet())
    public val trustedKeys: List<ModelManagerTrustedKey> =
        Collections.unmodifiableList(trustedKeys.toList())
    public val supportedManifestFeatures: Set<String> =
        Collections.unmodifiableSet(supportedManifestFeatures.toSet())

    init {
        require(this.supportedAbis.isNotEmpty())
        require(this.trustedKeys.isNotEmpty())
        require(totalRamBytes > 0)
    }
}

/** Exact signed asset identity used to freeze a routed prompt binding. */
public data class ModelCapabilityAsset(
    public val logicalPath: String,
    public val sha256: String,
    public val byteSize: Int,
)

/** Read-only route facts projected from one active, verified catalog revision. */
public class ModelCapabilityRoute(
    public val revision: ModelRevisionIdentity,
    public val runtimeId: String,
    public val qualityScore: Int,
    public val maximumContextLength: Int,
    public val estimatedInstanceRamBytes: Long,
    public val promptAsset: ModelCapabilityAsset,
)

/** Developer-tool projection of a verified installed model revision. */
public class ModelManagerRevisionInfo(
    public val identity: ModelRevisionIdentity,
    public val state: ModelManagerRevisionState,
    public val compatibility: ModelManagerRevisionCompatibility,
    public val installBytes: Long,
    public val signingKeyId: String,
    public val fullyVerifiedAtEpochMillis: Long,
)

/** Durable revision state already owned by the Model Manager catalog. */
public enum class ModelManagerRevisionState {
    ACTIVE,
    INSTALLED_INACTIVE,
    PENDING_REMOVAL,
}

/** Compatibility result for an installed, signed revision. */
public enum class ModelManagerRevisionCompatibility {
    COMPATIBLE,
    INCOMPATIBLE,
}

/** Result of deleting an inactive revision. */
public enum class ModelManagerRemovalResult {
    REMOVED,
    DEFERRED_UNTIL_RELEASE,
}

/**
 * Unpublished composition facade over the approved Model Manager internals.
 *
 * It exposes only the operations needed by engine-service: offline install/activation,
 * active capability route projection, and the existing Runtime instance acquisition
 * boundary. Storage, manifests, trust-store types, and catalog internals remain hidden.
 */
public class ModelManagerPlatform private constructor(
    root: Path,
    configuration: ModelManagerPlatformConfiguration,
    runtimeRegistrations: List<ModelManagerRuntimeRegistration>,
    durability: StorageDurability,
) {
    private val registry: DefaultRuntimeRegistry
    private val catalog: ModelCatalogManager

    /** Existing Model Manager-to-Execution acquisition boundary. */
    public val runtimeInstances: RuntimeInstanceManager

    init {
        val bindings = runtimeRegistrations.map { registration ->
            RuntimeBinding(
                identity = registration.bindingIdentity,
                runtime = registration.runtime,
                adapterVersion = registration.adapterVersion,
                features = registration.features,
            )
        }
        registry = DefaultRuntimeRegistry(bindings)
        val environment = CompatibilityEnvironment(
            engineVersion = configuration.engineVersion,
            androidApi = configuration.androidApi,
            deviceTier = configuration.deviceTier.toWire(),
            supportedAbis = configuration.supportedAbis,
            runtimes = runtimeRegistrations.map { registration ->
                RuntimeCompatibility(
                    runtimeId = registration.runtime.id.value,
                    adapterVersion = registration.adapterVersion,
                    features = registration.features,
                )
            },
            supportedManifestFeatures = configuration.supportedManifestFeatures,
        )
        val trustStore = ImmutableModelPackTrustStore(
            configuration.trustedKeys.map { key ->
                TrustedSigningKey(
                    keyId = key.keyId,
                    publicKey = ByteString.copyFrom(key.publicKey),
                    trust = SigningKeyTrust.ENGINE_PINNED,
                )
            },
        )
        val verifier = BoundedModelPackVerifier(trustStore)
        val runtimeCompatibility = RegistryRuntimeRequirementCompatibility(
            registry,
            com.touvay.runtime.api.DeviceProfile(
                totalRamBytes = configuration.totalRamBytes,
                isLowRamDevice = configuration.isLowRamDevice,
                supportedAbis = configuration.supportedAbis.toList(),
            ),
        )
        val store = TransactionalModelStore(
            root = root,
            verifier = verifier,
            environment = environment,
            runtimeCompatibility = runtimeCompatibility,
            durability = durability,
        )
        catalog = ModelCatalogManager(
            root = root,
            store = store,
            environment = environment,
            runtimeCompatibility = runtimeCompatibility,
            durability = durability,
        )
        catalog.rebuild()
        runtimeInstances = RuntimeInstanceManager(catalog, registry, Dispatchers.IO)
    }

    public constructor(
        root: Path,
        configuration: ModelManagerPlatformConfiguration,
        runtimeRegistrations: List<ModelManagerRuntimeRegistration>,
    ) : this(root, configuration, runtimeRegistrations, NioStorageDurability)

    internal constructor(
        root: Path,
        configuration: ModelManagerPlatformConfiguration,
        runtimeRegistrations: List<ModelManagerRuntimeRegistration>,
        durability: StorageDurability,
        @Suppress("UNUSED_PARAMETER") testComposition: Unit = Unit,
    ) : this(root, configuration, runtimeRegistrations, durability)

    /** Installs, verifies, catalogs, and atomically activates one offline directory pack. */
    public fun installAndActivate(sourceRoot: Path): ModelRevisionIdentity {
        val installed = catalog.install(DirectoryModelPackSource(sourceRoot))
        catalog.activate(installed.identity)
        return installed.identity
    }

    /** Verifies and transactionally installs one offline directory pack without activating it. */
    public fun install(sourceRoot: Path): ModelRevisionIdentity =
        catalog.install(DirectoryModelPackSource(sourceRoot)).identity

    /** Returns a content-free immutable snapshot for first-party developer tooling. */
    public fun revisions(): List<ModelManagerRevisionInfo> =
        catalog.snapshot().revisions.map { revision ->
            ModelManagerRevisionInfo(
                identity = revision.resolved.identity,
                state = when (revision.state) {
                    CatalogRevisionState.ACTIVE -> ModelManagerRevisionState.ACTIVE
                    CatalogRevisionState.INSTALLED_INACTIVE ->
                        ModelManagerRevisionState.INSTALLED_INACTIVE
                    CatalogRevisionState.PENDING_REMOVAL ->
                        ModelManagerRevisionState.PENDING_REMOVAL
                },
                compatibility = when (revision.compatibility) {
                    CatalogCompatibility.COMPATIBLE ->
                        ModelManagerRevisionCompatibility.COMPATIBLE
                    CatalogCompatibility.INCOMPATIBLE ->
                        ModelManagerRevisionCompatibility.INCOMPATIBLE
                },
                installBytes = revision.resolved.installBytes,
                signingKeyId = revision.resolved.manifest.signingKeyId,
                fullyVerifiedAtEpochMillis = revision.fullyVerifiedAtEpochMillis,
            )
        }

    /** Atomically activates an exact compatible installed revision. */
    public fun activate(identity: ModelRevisionIdentity): Unit {
        catalog.activate(identity)
    }

    /** Rolls back by atomically activating an exact earlier installed revision. */
    public fun rollback(identity: ModelRevisionIdentity): Unit = catalog.rollback(identity)

    /** Deletes only an inactive revision, preserving catalog lease semantics. */
    public fun deleteInactive(identity: ModelRevisionIdentity): ModelManagerRemovalResult =
        when (catalog.requestRemoval(identity)) {
            RemovalDisposition.REMOVED -> ModelManagerRemovalResult.REMOVED
            RemovalDisposition.DEFERRED_UNTIL_RELEASE ->
                ModelManagerRemovalResult.DEFERRED_UNTIL_RELEASE
        }

    /** Returns deterministic active routes advertising the exact capability schema. */
    public fun activeRoutes(capabilityId: String, schemaVersion: Int): List<ModelCapabilityRoute> {
        require(capabilityId.isNotBlank() && schemaVersion > 0)
        return catalog.snapshot().revisions.asSequence()
            .filter { revision ->
                revision.state == CatalogRevisionState.ACTIVE &&
                    revision.compatibility == CatalogCompatibility.COMPATIBLE
            }
            .mapNotNull { revision ->
                val capability = revision.resolved.manifest.capabilitiesList.singleOrNull {
                    it.id == capabilityId && it.schemaVersion == schemaVersion
                } ?: return@mapNotNull null
                if (!capability.hasTemplatePath()) return@mapNotNull null
                val asset = revision.resolved.files.singleOrNull {
                    it.logicalPath == capability.templatePath
                } ?: return@mapNotNull null
                if (asset.byteSize !in 1..RuntimeModelLease.MAX_ASSET_BYTES.toLong()) {
                    return@mapNotNull null
                }
                ModelCapabilityRoute(
                    revision = revision.resolved.identity,
                    runtimeId = revision.resolved.manifest.runtime.id,
                    qualityScore = capability.qualityScore,
                    maximumContextLength = revision.resolved.manifest.resources.maxContextLength,
                    estimatedInstanceRamBytes =
                        revision.resolved.manifest.resources.estimatedInstanceRamBytes,
                    promptAsset = ModelCapabilityAsset(
                        logicalPath = asset.logicalPath,
                        sha256 = asset.sha256,
                        byteSize = asset.byteSize.toInt(),
                    ),
                )
            }
            .sortedWith(
                compareByDescending<ModelCapabilityRoute> { it.qualityScore }
                    .thenBy { it.revision.packId }
                    .thenByDescending { it.revision.packVersion }
                    .thenBy { it.revision.manifestSha256 },
            )
            .toList()
    }

    /** Releases loaded Runtime instances after Execution has stopped acquiring them. */
    public suspend fun shutdown() {
        runtimeInstances.shutdown()
    }
}

private class DirectoryModelPackSource(sourceRoot: Path) : ModelPackSource {
    private val root = sourceRoot.toAbsolutePath().normalize()
    private val filesRoot = root.resolve("files")

    override fun openManifest(): InputStream = openFixed(root.resolve("manifest.pb"))

    override fun openSignatureEnvelope(): InputStream = openFixed(root.resolve("manifest.sig"))

    override fun entries(): List<ModelPackSourceEntry> {
        if (!Files.isDirectory(filesRoot, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(filesRoot)
        ) {
            return emptyList()
        }
        return Files.walk(filesRoot).use { paths ->
            paths.filter { it != filesRoot }
                .filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .map { path ->
                    val logical = filesRoot.relativize(path).joinToString("/") { it.toString() }
                    ModelPackSourceEntry(
                        logicalPath = logical,
                        kind = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(path)
                        ) {
                            ModelPackSourceEntryKind.REGULAR_FILE
                        } else if (Files.isSymbolicLink(path)) {
                            ModelPackSourceEntryKind.SYMBOLIC_LINK
                        } else {
                            ModelPackSourceEntryKind.OTHER
                        },
                    )
                }
                .toList()
        }
    }

    override fun open(logicalPath: String): InputStream = openFixed(safeFile(logicalPath))

    private fun safeFile(logicalPath: String): Path {
        val target = filesRoot.resolve(logicalPath).normalize()
        require(target.startsWith(filesRoot))
        return target
    }

    private fun openFixed(path: Path): InputStream {
        require(path.startsWith(root))
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        return Files.newInputStream(path)
    }
}

private fun ModelManagerDeviceTier.toWire(): DeviceTier = when (this) {
    ModelManagerDeviceTier.T0 -> DeviceTier.DEVICE_TIER_T0
    ModelManagerDeviceTier.T1 -> DeviceTier.DEVICE_TIER_T1
    ModelManagerDeviceTier.T2 -> DeviceTier.DEVICE_TIER_T2
    ModelManagerDeviceTier.T3 -> DeviceTier.DEVICE_TIER_T3
}

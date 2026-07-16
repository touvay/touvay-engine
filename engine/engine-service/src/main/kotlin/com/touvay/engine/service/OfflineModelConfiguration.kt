package com.touvay.engine.service

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import com.touvay.engine.models.ModelManagerDeviceTier
import com.touvay.runtime.api.DeviceProfile
import java.nio.file.Path
import java.security.MessageDigest

/** Explicit embedded-host opt-in for one signed, engine-pinned offline pack. */
internal class OfflineModelConfiguration(
    val packId: String,
    val keyId: String,
    val publicKey: ByteArray,
    val sourceRoot: Path,
    val storeRoot: Path,
    val device: DeviceProfile,
    val tier: ModelManagerDeviceTier,
) {
    companion object {
        const val HOST_ENABLED = "com.touvay.engine.host.ENABLED"
        const val HOST_PACK_ID = "com.touvay.engine.host.PACK_ID"
        const val HOST_KEY_ID = "com.touvay.engine.host.KEY_ID"
        const val HOST_PUBLIC_KEY_BASE64 = "com.touvay.engine.host.PUBLIC_KEY_BASE64"
        const val HOST_SOURCE_DIRECTORY = "com.touvay.engine.host.SOURCE_DIRECTORY"

        const val DEMO_ENABLED = "com.touvay.engine.demo.ENABLED"
        const val DEMO_PACK_ID = "com.touvay.engine.demo.PACK_ID"
        const val DEMO_KEY_ID = "com.touvay.engine.demo.KEY_ID"
        const val DEMO_PUBLIC_KEY_BASE64 = "com.touvay.engine.demo.PUBLIC_KEY_BASE64"
        const val DEMO_SOURCE_DIRECTORY = "com.touvay.engine.demo.SOURCE_DIRECTORY"

        fun read(context: Context): OfflineModelConfiguration? {
            val application = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            val metadata = application.metaData ?: return null
            val noBackupRoot = context.noBackupFilesDir.toPath()
            val parsed = if (metadata.getBoolean(HOST_ENABLED, false)) {
                parse(
                    metadata = metadata,
                    keys = PRODUCTION_KEYS,
                    sourceBase = noBackupRoot.resolve(PRODUCTION_SOURCE_PARENT),
                    requireHashedKeyId = true,
                )
            } else {
                context.getExternalFilesDir(null)?.toPath()?.let { external ->
                    parse(
                        metadata = metadata,
                        keys = DEMO_KEYS,
                        sourceBase = external,
                        requireHashedKeyId = true,
                    )
                }
            } ?: return null
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val device = DeviceProfile(
                totalRamBytes = memory.totalMem,
                isLowRamDevice = activityManager.isLowRamDevice,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            return OfflineModelConfiguration(
                packId = parsed.packId,
                keyId = parsed.keyId,
                publicKey = parsed.publicKey,
                sourceRoot = parsed.sourceRoot,
                storeRoot = noBackupRoot.resolve("touvay-models"),
                device = device,
                tier = deviceTier(device),
            )
        }

        internal fun parse(
            metadata: Bundle,
            keys: MetadataKeys,
            sourceBase: Path,
            requireHashedKeyId: Boolean,
        ): ParsedMetadata? {
            if (!metadata.getBoolean(keys.enabled, false)) return null
            val packId = metadata.getString(keys.packId)
                ?.takeIf { GENERAL_IDENTIFIER.matches(it) }
                ?: return null
            val keyId = metadata.getString(keys.keyId)
                ?.takeIf { SIGNING_KEY_IDENTIFIER.matches(it) }
                ?: return null
            val encodedPublicKey = metadata.getString(keys.publicKeyBase64)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val publicKey = try {
                Base64.decode(encodedPublicKey, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (publicKey.size != ED25519_PUBLIC_KEY_BYTES) return null
            if (requireHashedKeyId && keyId != publicKey.sha256Hex()) return null
            val directory = metadata.getString(keys.sourceDirectory)
                ?.takeIf { SAFE_DIRECTORY.matches(it) }
                ?: return null
            val normalizedBase = sourceBase.toAbsolutePath().normalize()
            val source = normalizedBase.resolve(directory).normalize()
            if (!source.startsWith(normalizedBase)) return null
            return ParsedMetadata(packId, keyId, publicKey, source)
        }

        private fun deviceTier(device: DeviceProfile): ModelManagerDeviceTier = when {
            device.isLowRamDevice || device.totalRamBytes < 3_500_000_000L ->
                ModelManagerDeviceTier.T0
            device.totalRamBytes < 6_000_000_000L -> ModelManagerDeviceTier.T1
            device.totalRamBytes < 12_000_000_000L -> ModelManagerDeviceTier.T2
            else -> ModelManagerDeviceTier.T3
        }

        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val PRODUCTION_SOURCE_PARENT = "touvay-pack-sources"
        private val GENERAL_IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        private val SIGNING_KEY_IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val SAFE_DIRECTORY = Regex("[A-Za-z0-9._-]{1,64}")

        internal val PRODUCTION_KEYS = MetadataKeys(
            HOST_ENABLED,
            HOST_PACK_ID,
            HOST_KEY_ID,
            HOST_PUBLIC_KEY_BASE64,
            HOST_SOURCE_DIRECTORY,
        )
        internal val DEMO_KEYS = MetadataKeys(
            DEMO_ENABLED,
            DEMO_PACK_ID,
            DEMO_KEY_ID,
            DEMO_PUBLIC_KEY_BASE64,
            DEMO_SOURCE_DIRECTORY,
        )
    }
}

internal data class MetadataKeys(
    val enabled: String,
    val packId: String,
    val keyId: String,
    val publicKeyBase64: String,
    val sourceDirectory: String,
)

internal data class ParsedMetadata(
    val packId: String,
    val keyId: String,
    val publicKey: ByteArray,
    val sourceRoot: Path,
)

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

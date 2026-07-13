package com.touvay.engine.service

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.touvay.engine.models.ModelManagerDeviceTier
import com.touvay.runtime.api.DeviceProfile
import java.nio.file.Path

/** Explicit host opt-in for one offline, engine-pinned validation pack. */
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
        const val ENABLED = "com.touvay.engine.demo.ENABLED"
        const val PACK_ID = "com.touvay.engine.demo.PACK_ID"
        const val KEY_ID = "com.touvay.engine.demo.KEY_ID"
        const val PUBLIC_KEY_BASE64 = "com.touvay.engine.demo.PUBLIC_KEY_BASE64"
        const val SOURCE_DIRECTORY = "com.touvay.engine.demo.SOURCE_DIRECTORY"

        fun read(context: Context): OfflineModelConfiguration? {
            val application = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
            val metadata = application.metaData ?: return null
            if (!metadata.getBoolean(ENABLED, false)) return null
            val packId = metadata.getString(PACK_ID)?.takeIf { it.isNotBlank() } ?: return null
            val keyId = metadata.getString(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null
            val publicKey = try {
                Base64.decode(metadata.getString(PUBLIC_KEY_BASE64), Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (publicKey.size != ED25519_PUBLIC_KEY_BYTES) return null
            val directory = metadata.getString(SOURCE_DIRECTORY)
                ?.takeIf { SAFE_DIRECTORY.matches(it) }
                ?: return null
            val external = context.getExternalFilesDir(null)?.toPath() ?: return null
            val source = external.resolve(directory).normalize()
            if (!source.startsWith(external)) return null
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val device = DeviceProfile(
                totalRamBytes = memory.totalMem,
                isLowRamDevice = activityManager.isLowRamDevice,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            return OfflineModelConfiguration(
                packId = packId,
                keyId = keyId,
                publicKey = publicKey,
                sourceRoot = source,
                storeRoot = context.noBackupFilesDir.toPath().resolve("touvay-models"),
                device = device,
                tier = deviceTier(device),
            )
        }

        private fun deviceTier(device: DeviceProfile): ModelManagerDeviceTier = when {
            device.isLowRamDevice || device.totalRamBytes < 3_500_000_000L ->
                ModelManagerDeviceTier.T0
            device.totalRamBytes < 6_000_000_000L -> ModelManagerDeviceTier.T1
            device.totalRamBytes < 12_000_000_000L -> ModelManagerDeviceTier.T2
            else -> ModelManagerDeviceTier.T3
        }

        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private val SAFE_DIRECTORY = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

package com.touvay.engine.service

import android.os.Bundle
import android.util.Base64
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineModelConfigurationTest {

    @Test
    fun productionHost_usesPrivateSourceRootAndPinnedHashedKey() {
        val publicKey = ByteArray(32) { index -> index.toByte() }
        val keyId = publicKey.sha256Hex()
        val metadata = hostMetadata(publicKey, keyId)
        val sourceBase = Paths.get("private", "no_backup", "touvay-pack-sources")

        val parsed = OfflineModelConfiguration.parse(
            metadata,
            OfflineModelConfiguration.PRODUCTION_KEYS,
            sourceBase,
            requireHashedKeyId = true,
        )

        requireNotNull(parsed)
        assertEquals("touvay.rewrite.alpha", parsed.packId)
        assertEquals(keyId, parsed.keyId)
        assertContentEquals(publicKey, parsed.publicKey)
        assertEquals(
            sourceBase.toAbsolutePath().normalize().resolve("rewrite-a1b2c3"),
            parsed.sourceRoot,
        )
    }

    @Test
    fun productionHost_rejectsKeyIdThatIsNotPublicKeyDigest() {
        val publicKey = ByteArray(32) { index -> index.toByte() }
        val metadata = hostMetadata(publicKey, "wrong.key")

        assertNull(
            OfflineModelConfiguration.parse(
                metadata,
                OfflineModelConfiguration.PRODUCTION_KEYS,
                Paths.get("private"),
                requireHashedKeyId = true,
            ),
        )
    }

    @Test
    fun productionHost_rejectsUnsafeSourceDirectory() {
        val publicKey = ByteArray(32) { index -> index.toByte() }
        val metadata = hostMetadata(publicKey, publicKey.sha256Hex()).apply {
            putString(OfflineModelConfiguration.HOST_SOURCE_DIRECTORY, "../console-store")
        }

        assertNull(
            OfflineModelConfiguration.parse(
                metadata,
                OfflineModelConfiguration.PRODUCTION_KEYS,
                Paths.get("private"),
                requireHashedKeyId = true,
            ),
        )
    }

    @Test
    fun disabledProductionHost_hasNoConfiguration() {
        val metadata = Bundle().apply {
            putBoolean(OfflineModelConfiguration.HOST_ENABLED, false)
        }

        assertNull(
            OfflineModelConfiguration.parse(
                metadata,
                OfflineModelConfiguration.PRODUCTION_KEYS,
                Paths.get("private"),
                requireHashedKeyId = true,
            ),
        )
    }

    @Test
    fun legacyDemoHost_remainsBoundToItsOwnExternalSourceBase() {
        val publicKey = ByteArray(32) { index -> (31 - index).toByte() }
        val keyId = publicKey.sha256Hex()
        val metadata = Bundle().apply {
            putBoolean(OfflineModelConfiguration.DEMO_ENABLED, true)
            putString(OfflineModelConfiguration.DEMO_PACK_ID, "touvay.demo.rewrite")
            putString(OfflineModelConfiguration.DEMO_KEY_ID, keyId)
            putString(
                OfflineModelConfiguration.DEMO_PUBLIC_KEY_BASE64,
                Base64.encodeToString(publicKey, Base64.NO_WRAP),
            )
            putString(OfflineModelConfiguration.DEMO_SOURCE_DIRECTORY, "touvay-demo-pack")
        }
        val external = Paths.get("console", "external-files")

        val parsed = OfflineModelConfiguration.parse(
            metadata,
            OfflineModelConfiguration.DEMO_KEYS,
            external,
            requireHashedKeyId = true,
        )

        requireNotNull(parsed)
        assertEquals(
            external.toAbsolutePath().normalize().resolve("touvay-demo-pack"),
            parsed.sourceRoot,
        )
    }

    private fun hostMetadata(publicKey: ByteArray, keyId: String): Bundle = Bundle().apply {
        putBoolean(OfflineModelConfiguration.HOST_ENABLED, true)
        putString(OfflineModelConfiguration.HOST_PACK_ID, "touvay.rewrite.alpha")
        putString(OfflineModelConfiguration.HOST_KEY_ID, keyId)
        putString(
            OfflineModelConfiguration.HOST_PUBLIC_KEY_BASE64,
            Base64.encodeToString(publicKey, Base64.NO_WRAP),
        )
        putString(OfflineModelConfiguration.HOST_SOURCE_DIRECTORY, "rewrite-a1b2c3")
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .also { digest -> assertTrue(digest.matches(Regex("[0-9a-f]{64}"))) }
}

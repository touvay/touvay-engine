package com.touvay.demo

import com.google.crypto.tink.subtle.Ed25519Verify
import com.touvay.capability.rewrite.RewriteReferencePromptAsset
import com.touvay.engine.models.proto.ModelPackManifest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DemoPackAssetsTest {
    @Test
    fun signedMetadata_matchesReferencePromptAndPinnedModel() {
        val root = findPackRoot()
        val publicKey = decode(root, "demo-public-key.b64")
        val prompt = decode(root, "prompt.pb.b64")
        val manifestBytes = decode(root, "manifest.pb.b64")
        val envelope = decode(root, "manifest.sig.b64")

        assertContentEquals(RewriteReferencePromptAsset.bytes(), prompt)
        assertEquals(
            "53ae93adc1d0a53df5ceaab4771eb916b849b20e44ae6bc09857f9292a489cb8",
            manifestBytes.sha256(),
        )
        val manifest = ModelPackManifest.parseFrom(manifestBytes)
        assertEquals("touvay.demo.qwen2.5-0.5b-rewrite", manifest.packId)
        assertEquals("llamacpp", manifest.runtime.id)
        assertEquals("text.rewrite", manifest.capabilitiesList.single().id)
        assertEquals(491_400_032L, manifest.filesList.first().byteSize)
        assertEquals(
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            manifest.filesList.first().sha256.toByteArray().hex(),
        )
        assertEquals(prompt.sha256(), manifest.filesList.last().sha256.toByteArray().hex())

        val buffer = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(8).also(buffer::get)
        assertContentEquals(byteArrayOf(84, 86, 77, 80, 83, 73, 71, 0), magic)
        assertEquals(1, buffer.get().toInt())
        assertEquals(1, buffer.get().toInt())
        val keyIdBytes = ByteArray(buffer.short.toInt() and 0xffff).also(buffer::get)
        val signature = ByteArray(64).also(buffer::get)
        val keyId = keyIdBytes.toString(Charsets.UTF_8)
        assertEquals(publicKey.sha256(), keyId)
        assertEquals(keyId, manifest.signingKeyId)
        Ed25519Verify(publicKey).verify(
            signature,
            "TOUVAY_MODEL_PACK_V1\u0000".toByteArray() + manifestBytes,
        )
        assertTrue(!buffer.hasRemaining())
    }

    private fun findPackRoot(): Path {
        var current: Path? = Paths.get("").toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve("docs/demo/pack")
            if (Files.isDirectory(candidate)) return candidate
            current = current.parent
        }
        error("demo pack assets not found")
    }

    private fun decode(root: Path, name: String): ByteArray =
        Base64.getDecoder().decode(
            Files.readAllBytes(root.resolve(name)).toString(Charsets.US_ASCII).trim(),
        )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

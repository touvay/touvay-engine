package com.touvay.demo

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class DemoPackProvisionerTest {
    @Test
    fun completeExternalMetadata_isPreserved() {
        val root = Files.createTempDirectory("external-signed-pack").toFile()
        try {
            val expected = metadataFiles(root).onEachIndexed { index, file ->
                requireNotNull(file.parentFile).mkdirs()
                file.writeBytes(byteArrayOf(index.toByte()))
            }

            DemoPackProvisioner.prepareMetadata(root) { _, _ ->
                error("complete external metadata must not be replaced")
            }

            expected.forEachIndexed { index, file ->
                assertContentEquals(byteArrayOf(index.toByte()), file.readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun partialExternalMetadata_failsWithoutMixingTrustDomains() {
        val root = Files.createTempDirectory("partial-signed-pack").toFile()
        try {
            val files = metadataFiles(root)
            files.first().apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1))
            }

            assertFailsWith<IllegalStateException> {
                DemoPackProvisioner.prepareMetadata(root) { _, destination ->
                    destination.writeBytes(byteArrayOf(2))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absentExternalMetadata_installsDeveloperFixtureAssetsTogether() {
        val root = Files.createTempDirectory("developer-signed-pack").toFile()
        try {
            val decoded = mutableListOf<String>()

            DemoPackProvisioner.prepareMetadata(root) { asset, destination ->
                decoded += asset
                destination.writeText(asset)
            }

            assertContentEquals(
                listOf("manifest.pb.b64", "manifest.sig.b64", "prompt.pb.b64"),
                decoded,
            )
            val files = metadataFiles(root)
            assertContentEquals("manifest.pb.b64".toByteArray(), files[0].readBytes())
            assertContentEquals("manifest.sig.b64".toByteArray(), files[1].readBytes())
            assertContentEquals("prompt.pb.b64".toByteArray(), files[2].readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun metadataFiles(root: File): List<File> = listOf(
        File(root, "manifest.pb"),
        File(root, "manifest.sig"),
        File(root, "files/prompts/text-rewrite-v1.pb"),
    )
}

package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.touvay.engine.models.proto.CapabilityDescriptor
import com.touvay.engine.models.proto.ModelFile
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ManifestParserTest {
    private val parser = ManifestParser()

    @Test
    fun parsesValidV1Manifest() {
        val parsed = parser.parse(TestFixtures.manifest().toByteArray())
        assertEquals("touvay.pack.compact-writer-q4", parsed.packId)
        assertEquals(4, parsed.filesCount)
    }

    @Test
    fun enforcesRawAndStructuralBounds() {
        assertVerificationFailure(VerificationFailure.MANIFEST_TOO_LARGE) {
            parser.parse(ByteArray(1024 * 1024 + 1))
        }
        assertVerificationFailure(VerificationFailure.MALFORMED_MANIFEST) {
            parser.parse(byteArrayOf(0x0a, 0x7f))
        }

        val tooManyFiles = TestFixtures.manifest().toBuilder().apply {
            repeat(253) { addFiles(filesList.first()) }
        }.build()
        assertManifestFailure(VerificationFailure.MANIFEST_LIMIT_EXCEEDED, tooManyFiles)

        val tooManyCapabilities = TestFixtures.manifest().toBuilder().apply {
            repeat(128) { index ->
                addCapabilities(
                    CapabilityDescriptor.newBuilder()
                        .setId("test.capability-$index")
                        .setSchemaVersion(1),
                )
            }
        }.build()
        assertManifestFailure(VerificationFailure.MANIFEST_LIMIT_EXCEEDED, tooManyCapabilities)
    }

    @Test
    fun rejectsUnsupportedSchemaAndInvalidSemver() {
        assertManifestFailure(
            VerificationFailure.UNSUPPORTED_MANIFEST_SCHEMA,
            TestFixtures.manifest().toBuilder().setSchemaVersion(2).build(),
        )
        listOf("1", "01.0.0", "1.0.0-01", "1.0.0+", "1.0.0+bad..id").forEach { version ->
            assertManifestFailure(
                VerificationFailure.INVALID_MANIFEST_FIELD,
                TestFixtures.manifest().toBuilder().setPackVersion(version).build(),
            )
        }
        assertManifestFailure(
            VerificationFailure.INVALID_MANIFEST_FIELD,
            TestFixtures.manifest().toBuilder()
                .setEngineMinVersion("2.0.0")
                .setEngineMaxVersion("1.0.0")
                .build(),
        )
    }

    @Test
    fun rejectsTraversalNonNormalizedAndPlatformPaths() {
        val invalidPaths = listOf(
            "/weights.gguf",
            "C:/weights.gguf",
            "folder\\weights.gguf",
            "folder/../weights.gguf",
            "folder/./weights.gguf",
            "folder//weights.gguf",
            "weights.gguf/",
            "NUL",
            "COM1.bin",
            "bad\u0000name.gguf",
            "cafe\u0301.gguf",
        )
        invalidPaths.forEach { path ->
            assertManifestFailure(
                VerificationFailure.INVALID_LOGICAL_PATH,
                withFirstFile(path = path),
            )
        }
    }

    @Test
    fun rejectsDuplicatePathsBadDigestsAndOversizedDeclaredFiles() {
        val duplicate = TestFixtures.manifest().toBuilder().apply {
            addFiles(filesList.first())
        }.build()
        assertManifestFailure(VerificationFailure.DUPLICATE_MANIFEST_ENTRY, duplicate)

        val badDigest = TestFixtures.manifest().toBuilder().apply {
            setFiles(0, filesList[0].toBuilder().setSha256(ByteString.copyFrom(ByteArray(31))))
        }.build()
        assertManifestFailure(VerificationFailure.INVALID_FILE_DIGEST, badDigest)

        val tooLarge = TestFixtures.manifest().toBuilder().apply {
            setFiles(0, filesList[0].toBuilder().setByteSize(8L * 1024 * 1024 * 1024 + 1))
        }.build()
        assertManifestFailure(VerificationFailure.DECLARED_SIZE_EXCEEDED, tooLarge)
    }

    @Test
    fun requiresReferencesToExistWithExpectedRoles() {
        val wrongTemplateRole = TestFixtures.manifest().toBuilder().apply {
            setCapabilities(0, capabilitiesList[0].toBuilder().setTemplatePath("weights.gguf"))
        }.build()
        assertManifestFailure(VerificationFailure.INVALID_FILE_REFERENCE, wrongTemplateRole)

        val missingLicense = TestFixtures.manifest().toBuilder().apply {
            setLicense(license.toBuilder().setNoticePath("missing.txt"))
        }.build()
        assertManifestFailure(VerificationFailure.INVALID_FILE_REFERENCE, missingLicense)
    }

    @Test
    fun rejectsDuplicateIdsAndUnspecifiedEnums() {
        val duplicateCapability = TestFixtures.manifest().toBuilder().apply {
            addCapabilities(capabilitiesList.first())
        }.build()
        assertManifestFailure(VerificationFailure.DUPLICATE_MANIFEST_ENTRY, duplicateCapability)

        val unspecifiedRole = TestFixtures.manifest().toBuilder().apply {
            setFiles(0, filesList[0].toBuilder().clearRole())
        }.build()
        assertManifestFailure(VerificationFailure.INVALID_MANIFEST_FIELD, unspecifiedRole)

        val wrappedUint32 = TestFixtures.manifest().toBuilder().apply {
            setDeviceConstraints(deviceConstraints.toBuilder().setMinAndroidApi(-1))
        }.build()
        assertManifestFailure(VerificationFailure.INVALID_MANIFEST_FIELD, wrappedUint32)
    }

    private fun withFirstFile(path: String): com.touvay.engine.models.proto.ModelPackManifest =
        TestFixtures.manifest().toBuilder().apply {
            setFiles(0, ModelFile.newBuilder(filesList[0]).setLogicalPath(path))
        }.build()

    private fun assertManifestFailure(
        failure: VerificationFailure,
        manifest: com.touvay.engine.models.proto.ModelPackManifest,
    ) {
        assertVerificationFailure(failure) { parser.parse(manifest.toByteArray()) }
    }
}

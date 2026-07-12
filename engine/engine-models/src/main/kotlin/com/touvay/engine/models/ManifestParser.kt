package com.touvay.engine.models

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.touvay.engine.models.proto.DeviceTier
import com.touvay.engine.models.proto.FileRole
import com.touvay.engine.models.proto.ModelPackManifest
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

internal class ManifestParser(
    private val limits: VerificationLimits = VerificationLimits(),
) {
    fun parse(bytes: ByteArray): ModelPackManifest {
        if (bytes.size > limits.maxManifestBytes) {
            verificationFailure(VerificationFailure.MANIFEST_TOO_LARGE)
        }
        val manifest = try {
            val input = CodedInputStream.newInstance(bytes)
            input.setSizeLimit(limits.maxManifestBytes)
            input.setRecursionLimit(limits.protobufRecursionLimit)
            ModelPackManifest.parseFrom(input).also {
                if (!input.isAtEnd) verificationFailure(VerificationFailure.MALFORMED_MANIFEST)
            }
        } catch (_: InvalidProtocolBufferException) {
            verificationFailure(VerificationFailure.MALFORMED_MANIFEST)
        } catch (failure: ModelPackVerificationException) {
            throw failure
        } catch (_: Exception) {
            verificationFailure(VerificationFailure.MALFORMED_MANIFEST)
        }
        validate(manifest)
        return manifest
    }

    private fun validate(manifest: ModelPackManifest) {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            verificationFailure(VerificationFailure.UNSUPPORTED_MANIFEST_SCHEMA)
        }
        requireIdentifier(manifest.packId)
        requireSemanticVersion(manifest.packVersion)
        val engineMinimum = requireSemanticVersion(manifest.engineMinVersion)
        if (manifest.hasEngineMaxVersion()) {
            val engineMaximum = requireSemanticVersion(manifest.engineMaxVersion)
            if (engineMaximum < engineMinimum) {
                verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
            }
        }
        if (manifest.createdAtEpochSeconds < 0) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
        if (!Identifiers.isSigningKey(manifest.signingKeyId)) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
        validateRuntime(manifest)
        validateResources(manifest)
        validateDeviceConstraints(manifest)
        val fileRoles = validateFiles(manifest)
        validateCapabilities(manifest, fileRoles)
        validateLicense(manifest, fileRoles)
        requireUniqueIdentifiers(manifest.requiredManifestFeaturesList)
    }

    private fun validateRuntime(manifest: ModelPackManifest) {
        if (!manifest.hasRuntime()) verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        requireIdentifier(manifest.runtime.id)
        requireSemanticVersion(manifest.runtime.minAdapterVersion)
        requireUniqueIdentifiers(manifest.runtime.requiredFeaturesList)
    }

    private fun validateResources(manifest: ModelPackManifest) {
        if (!manifest.hasResources() || manifest.resources.maxContextLength <= 0) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
        requireUniqueIdentifiers(manifest.resources.acceleratorsList)
        if (manifest.resources.estimatedInstanceRamBytes < 0 ||
            (manifest.resources.hasKvBytesPerToken() && manifest.resources.kvBytesPerToken < 0)
        ) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
    }

    private fun validateDeviceConstraints(manifest: ModelPackManifest) {
        if (!manifest.hasDeviceConstraints() ||
            manifest.deviceConstraints.minTier == DeviceTier.DEVICE_TIER_UNSPECIFIED ||
            manifest.deviceConstraints.minTier == DeviceTier.UNRECOGNIZED ||
            manifest.deviceConstraints.supportedAbisCount == 0
        ) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
        if (manifest.deviceConstraints.hasMinAndroidApi() &&
            manifest.deviceConstraints.minAndroidApi <= 0
        ) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
        requireUniqueIdentifiers(manifest.deviceConstraints.supportedAbisList)
    }

    private fun validateFiles(manifest: ModelPackManifest): Map<String, FileRole> {
        if (manifest.filesCount > limits.maxFiles) {
            verificationFailure(VerificationFailure.MANIFEST_LIMIT_EXCEEDED)
        }
        val roles = linkedMapOf<String, FileRole>()
        var declaredBytes = 0L
        manifest.filesList.forEach { file ->
            requireLogicalPath(file.logicalPath)
            if (roles.put(file.logicalPath, file.role) != null) {
                verificationFailure(VerificationFailure.DUPLICATE_MANIFEST_ENTRY)
            }
            if (file.sha256.size() != SHA256_BYTES) {
                verificationFailure(VerificationFailure.INVALID_FILE_DIGEST)
            }
            if (file.byteSize < 0 ||
                file.role == FileRole.FILE_ROLE_UNSPECIFIED ||
                file.role == FileRole.UNRECOGNIZED
            ) {
                verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
            }
            declaredBytes = try {
                Math.addExact(declaredBytes, file.byteSize)
            } catch (_: ArithmeticException) {
                verificationFailure(VerificationFailure.DECLARED_SIZE_EXCEEDED)
            }
            if (declaredBytes > limits.maxTotalDeclaredFileBytes) {
                verificationFailure(VerificationFailure.DECLARED_SIZE_EXCEEDED)
            }
        }
        return roles
    }

    private fun validateCapabilities(
        manifest: ModelPackManifest,
        fileRoles: Map<String, FileRole>,
    ) {
        if (manifest.capabilitiesCount > limits.maxCapabilities) {
            verificationFailure(VerificationFailure.MANIFEST_LIMIT_EXCEEDED)
        }
        val ids = mutableSetOf<String>()
        manifest.capabilitiesList.forEach { capability ->
            requireIdentifier(capability.id)
            if (!ids.add(capability.id)) {
                verificationFailure(VerificationFailure.DUPLICATE_MANIFEST_ENTRY)
            }
            if (capability.schemaVersion <= 0 || capability.qualityScore < 0) {
                verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
            }
            if (capability.hasTemplatePath()) {
                requireReference(capability.templatePath, FileRole.FILE_ROLE_TEMPLATE, fileRoles)
            }
            if (capability.hasConfigPath()) {
                requireReference(capability.configPath, FileRole.FILE_ROLE_CONFIG, fileRoles)
            }
        }
    }

    private fun validateLicense(
        manifest: ModelPackManifest,
        fileRoles: Map<String, FileRole>,
    ) {
        if (!manifest.hasLicense()) return
        val spdx = manifest.license.spdxId
        if (spdx.isBlank() ||
            spdx.toByteArray(StandardCharsets.UTF_8).size > limits.maxIdentifierUtf8Bytes ||
            spdx.any(Char::isISOControl)
        ) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
        if (manifest.license.hasNoticePath()) {
            requireReference(manifest.license.noticePath, FileRole.FILE_ROLE_LICENSE, fileRoles)
        }
    }

    private fun requireReference(
        path: String,
        expectedRole: FileRole,
        fileRoles: Map<String, FileRole>,
    ) {
        requireLogicalPath(path)
        if (fileRoles[path] != expectedRole) {
            verificationFailure(VerificationFailure.INVALID_FILE_REFERENCE)
        }
    }

    private fun requireUniqueIdentifiers(values: List<String>) {
        val unique = mutableSetOf<String>()
        values.forEach { value ->
            requireIdentifier(value)
            if (!unique.add(value)) {
                verificationFailure(VerificationFailure.DUPLICATE_MANIFEST_ENTRY)
            }
        }
    }

    private fun requireIdentifier(value: String) {
        if (!Identifiers.isGeneral(value, limits.maxIdentifierUtf8Bytes)) {
            verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)
        }
    }

    private fun requireSemanticVersion(value: String): SemanticVersion =
        SemanticVersion.parse(value)
            ?: verificationFailure(VerificationFailure.INVALID_MANIFEST_FIELD)

    private fun requireLogicalPath(path: String) {
        if (path.isEmpty() ||
            path.toByteArray(StandardCharsets.UTF_8).size > limits.maxLogicalPathUtf8Bytes ||
            path.startsWith('/') ||
            WINDOWS_DRIVE_PREFIX.containsMatchIn(path) ||
            '\\' in path ||
            Normalizer.normalize(path, Normalizer.Form.NFC) != path
        ) {
            verificationFailure(VerificationFailure.INVALID_LOGICAL_PATH)
        }
        val segments = path.split('/')
        if (segments.any { segment ->
                segment.isEmpty() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.any(Char::isISOControl) ||
                    isWindowsDeviceName(segment)
            }
        ) {
            verificationFailure(VerificationFailure.INVALID_LOGICAL_PATH)
        }
    }

    private fun isWindowsDeviceName(segment: String): Boolean {
        val base = segment.substringBefore('.').uppercase(Locale.ROOT)
        return base in WINDOWS_DEVICE_NAMES ||
            (base.length == 4 &&
                (base.startsWith("COM") || base.startsWith("LPT")) &&
                base.last() in '1'..'9')
    }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION: Int = 1
        private const val SHA256_BYTES: Int = 32
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
        private val WINDOWS_DEVICE_NAMES = setOf("CON", "PRN", "AUX", "NUL")
    }
}

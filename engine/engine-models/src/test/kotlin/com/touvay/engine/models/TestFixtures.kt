package com.touvay.engine.models

import com.google.protobuf.ByteString
import com.touvay.engine.models.proto.CapabilityDescriptor
import com.touvay.engine.models.proto.DeviceConstraints
import com.touvay.engine.models.proto.DeviceTier
import com.touvay.engine.models.proto.FileRole
import com.touvay.engine.models.proto.LicenseMetadata
import com.touvay.engine.models.proto.ModelFile
import com.touvay.engine.models.proto.ModelPackManifest
import com.touvay.engine.models.proto.ResourceRequirements
import com.touvay.engine.models.proto.RuntimeRequirement
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

internal object TestFixtures {
    const val KEY_ID: String = "release.test"
    val PUBLIC_KEY: ByteArray = hex(
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
    )
    private val privateSeed = hex(
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
    )

    fun manifest(signingKeyId: String = KEY_ID): ModelPackManifest =
        ModelPackManifest.newBuilder()
            .setSchemaVersion(1)
            .setPackId("touvay.pack.compact-writer-q4")
            .setPackVersion("1.2.0")
            .setEngineMinVersion("1.0.0")
            .setEngineMaxVersion("2.0.0")
            .setCreatedAtEpochSeconds(1_752_278_400)
            .setSigningKeyId(signingKeyId)
            .setRuntime(
                RuntimeRequirement.newBuilder()
                    .setId("llamacpp")
                    .setMinAdapterVersion("1.0.0"),
            )
            .addCapabilities(
                CapabilityDescriptor.newBuilder()
                    .setId("text.rewrite")
                    .setSchemaVersion(1)
                    .setQualityScore(62)
                    .setTemplatePath("templates/rewrite.tpl")
                    .setConfigPath("config/rewrite.pb"),
            )
            .setResources(
                ResourceRequirements.newBuilder()
                    .setEstimatedInstanceRamBytes(900L * 1024 * 1024)
                    .setMaxContextLength(1024)
                    .setKvBytesPerToken(12_288)
                    .addAccelerators("cpu"),
            )
            .setDeviceConstraints(
                DeviceConstraints.newBuilder()
                    .setMinTier(DeviceTier.DEVICE_TIER_T1)
                    .addSupportedAbis("arm64-v8a")
                    .addSupportedAbis("x86_64")
                    .setMinAndroidApi(29),
            )
            .addFiles(file("weights.gguf", 491_400_032, FileRole.FILE_ROLE_WEIGHTS, 1))
            .addFiles(file("templates/rewrite.tpl", 128, FileRole.FILE_ROLE_TEMPLATE, 2))
            .addFiles(file("config/rewrite.pb", 64, FileRole.FILE_ROLE_CONFIG, 3))
            .addFiles(file("LICENSE", 11_358, FileRole.FILE_ROLE_LICENSE, 4))
            .setLicense(
                LicenseMetadata.newBuilder()
                    .setSpdxId("Apache-2.0")
                    .setNoticePath("LICENSE"),
            )
            .build()

    fun environment(
        engineVersion: String = "1.0.0",
        androidApi: Int = 34,
        tier: DeviceTier = DeviceTier.DEVICE_TIER_T1,
        abis: Set<String> = setOf("x86_64"),
        runtimes: Collection<RuntimeCompatibility> = listOf(
            RuntimeCompatibility("llamacpp", "1.0.0"),
        ),
        manifestFeatures: Set<String> = emptySet(),
    ): CompatibilityEnvironment = CompatibilityEnvironment(
        engineVersion = engineVersion,
        androidApi = androidApi,
        deviceTier = tier,
        supportedAbis = abis,
        runtimes = runtimes,
        supportedManifestFeatures = manifestFeatures,
    )

    fun trustStore(
        status: SigningKeyStatus = SigningKeyStatus.ACTIVE,
    ): ModelPackTrustStore = ImmutableModelPackTrustStore(
        listOf(
            TrustedSigningKey(
                keyId = KEY_ID,
                publicKey = ByteString.copyFrom(PUBLIC_KEY),
                trust = SigningKeyTrust.ENGINE_PINNED,
                status = status,
            ),
        ),
    )

    fun sign(manifestBytes: ByteArray): ByteArray =
        signMessage(ModelPackSignature.message(manifestBytes))

    fun signMessage(message: ByteArray): ByteArray {
        val pkcs8Prefix = hex("302e020100300506032b657004220420")
        val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
            PKCS8EncodedKeySpec(pkcs8Prefix + privateSeed),
        )
        return Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(message)
            sign()
        }
    }

    fun envelope(keyId: String, signature: ByteArray): ByteArray {
        val key = keyId.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(8 + 1 + 1 + 2 + key.size + signature.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf('T'.code.toByte(), 'V'.code.toByte(), 'M'.code.toByte(),
                'P'.code.toByte(), 'S'.code.toByte(), 'I'.code.toByte(),
                'G'.code.toByte(), 0))
            .put(1)
            .put(1)
            .putShort(key.size.toShort())
            .put(key)
            .put(signature)
            .array()
    }

    private fun file(path: String, size: Long, role: FileRole, digestByte: Int): ModelFile =
        ModelFile.newBuilder()
            .setLogicalPath(path)
            .setByteSize(size)
            .setSha256(ByteString.copyFrom(ByteArray(32) { digestByte.toByte() }))
            .setRole(role)
            .build()

    fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

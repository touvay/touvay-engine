package com.touvay.engine.core

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.touvay.engine.core.proto.PromptFormatAsset
import com.touvay.engine.core.proto.PromptRole as WirePromptRole
import java.security.MessageDigest
import java.util.Collections

/** One typed substitution point declared by capability code. */
public data class PromptSlot(
    public val id: String,
    public val required: Boolean,
    public val maxUtf8Bytes: Int,
) {
    init {
        require(ID_PATTERN.matches(id))
        require(maxUtf8Bytes in 1..PromptRecipe.MAX_SLOT_BYTES)
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

/** Typed, versioned prompt intent owned by capability code, not by a model pack. */
public sealed interface PromptRecipeElement {
    /** Code-owned semantic instruction referenced by stable identifier. */
    public data class TrustedInstruction(public val id: String) : PromptRecipeElement {
        init { require(ELEMENT_ID_PATTERN.matches(id)) }
    }

    /** Untrusted data insertion point declared by [PromptSlot]. */
    public data class DataSlot(public val slotId: String) : PromptRecipeElement {
        init { require(SLOT_ID_PATTERN.matches(slotId)) }
    }

    /** Code-owned output constraint referenced by stable identifier. */
    public data class OutputConstraint(public val id: String) : PromptRecipeElement {
        init { require(ELEMENT_ID_PATTERN.matches(id)) }
    }

    private companion object {
        val ELEMENT_ID_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
        val SLOT_ID_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

/** Typed, versioned prompt intent owned by capability code, not by a model pack. */
public class PromptRecipe(
    public val capabilityKey: CapabilityKey,
    public val id: String,
    public val revision: Int,
    public val sourceSha256: String,
    elements: List<PromptRecipeElement>,
    slots: List<PromptSlot>,
    requiredAssetFeatures: Set<String> = emptySet(),
) {
    public val elements: List<PromptRecipeElement> =
        Collections.unmodifiableList(elements.toList())
    public val slots: List<PromptSlot> = Collections.unmodifiableList(slots.toList())
    public val requiredAssetFeatures: Set<String> =
        Collections.unmodifiableSet(requiredAssetFeatures.toSet())

    init {
        require(ID_PATTERN.matches(id))
        require(revision > 0)
        require(SHA256_PATTERN.matches(sourceSha256))
        require(this.elements.isNotEmpty() && this.elements.size <= MAX_ELEMENTS)
        require(this.slots.size <= MAX_SLOTS)
        require(this.slots.map { it.id }.distinct().size == this.slots.size)
        require(this.requiredAssetFeatures.all { FEATURE_PATTERN.matches(it) })
        val slotIds = this.slots.map { it.id }.toSet()
        require(this.elements.filterIsInstance<PromptRecipeElement.DataSlot>()
            .all { it.slotId in slotIds })
        require(this.slots.filter { it.required }.all { required ->
            this.elements.any { it is PromptRecipeElement.DataSlot && it.slotId == required.id }
        })
    }

    /** Resolves one declared slot. */
    public fun findSlot(id: String): PromptSlot? = slots.firstOrNull { it.id == id }

    public companion object {
        public const val MAX_SLOTS: Int = 32
        public const val MAX_ELEMENTS: Int = 64
        public const val MAX_SLOT_BYTES: Int = 256 * 1024
        private val ID_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
        private val FEATURE_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** Exact signed-pack asset binding frozen into a routed execution candidate. */
public data class PromptAssetRef(
    public val logicalPath: String,
    public val byteSize: Int,
    public val sha256: String,
) {
    init {
        require(logicalPath.isNotBlank() && logicalPath.length <= 240)
        require(!logicalPath.startsWith('/') && !logicalPath.startsWith('\\'))
        require(logicalPath.split('/', '\\').none { it.isEmpty() || it == "." || it == ".." })
        require(byteSize in 1..MAX_ASSET_BYTES)
        require(SHA256_PATTERN.matches(sha256))
    }

    public companion object {
        public const val MAX_ASSET_BYTES: Int = 512 * 1024
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** Exact-revision asset reader. Implementations must never expose filesystem paths. */
public fun interface PromptAssetSource {
    /** Reads exactly [ref] with a hard size bound and returns caller-owned bytes. */
    public suspend fun read(ref: PromptAssetRef): ByteArray

    public companion object {
        /** Default for model providers that have no capability asset binding. */
        public val UNAVAILABLE: PromptAssetSource = PromptAssetSource {
            capabilityFailure(CapabilityFailureCode.PROMPT_ASSET_UNAVAILABLE)
        }
    }
}

/** Semantic role attached to an ordered prompt block. */
public enum class PromptBlockRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

/** One safe prompt AST segment: trusted literal or typed data slot. */
public sealed interface PromptAssetSegment {
    /** Trusted signed-pack literal. */
    public class Literal(public val value: String) : PromptAssetSegment {
        override fun toString(): String = "PromptAssetLiteral(bytes=${value.toByteArray().size})"
    }

    /** Capability-declared data insertion point. */
    public data class Slot(public val id: String) : PromptAssetSegment
}

/** One ordered prompt-format block. */
public class PromptAssetBlock(
    public val role: PromptBlockRole,
    segments: List<PromptAssetSegment>,
) {
    public val segments: List<PromptAssetSegment> =
        Collections.unmodifiableList(segments.toList())
}

/** Parsed and fully verified prompt-format asset. */
public class VerifiedPromptAsset internal constructor(
    public val id: String,
    public val capabilityKey: CapabilityKey,
    public val recipeId: String,
    public val minRecipeRevision: Int,
    public val maxRecipeRevision: Int?,
    blocks: List<PromptAssetBlock>,
    requiredFeatures: Set<String>,
    public val maxExpandedBytes: Int,
) {
    public val blocks: List<PromptAssetBlock> = Collections.unmodifiableList(blocks.toList())
    public val requiredFeatures: Set<String> =
        Collections.unmodifiableSet(requiredFeatures.toSet())
}

/** Bounded protobuf-lite parser plus digest and recipe compatibility verifier. */
public class PromptAssetLoader(
    supportedFeatures: Set<String>,
) {
    private val supportedFeatures: Set<String> = supportedFeatures.toSet()

    init {
        require(this.supportedFeatures.all { FEATURE_PATTERN.matches(it) })
    }

    /** Reads, authenticates by frozen digest, parses, and validates one exact asset. */
    public suspend fun load(
        source: PromptAssetSource,
        ref: PromptAssetRef,
        recipe: PromptRecipe,
    ): VerifiedPromptAsset {
        val bytes = try {
            source.read(ref)
        } catch (failure: CapabilityFrameworkException) {
            throw failure
        } catch (_: Exception) {
            capabilityFailure(CapabilityFailureCode.PROMPT_ASSET_UNAVAILABLE)
        }
        if (bytes.size != ref.byteSize || !digest(bytes).contentEquals(hexToBytes(ref.sha256))) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }
        return parse(bytes, recipe)
    }

    /** Parses already authenticated bounded bytes; exposed for deterministic tests. */
    public fun parse(bytes: ByteArray, recipe: PromptRecipe): VerifiedPromptAsset {
        if (bytes.isEmpty() || bytes.size > PromptAssetRef.MAX_ASSET_BYTES) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }
        val wire = try {
            val input = CodedInputStream.newInstance(bytes)
            input.setSizeLimit(PromptAssetRef.MAX_ASSET_BYTES)
            input.setRecursionLimit(MAX_RECURSION_DEPTH)
            PromptFormatAsset.parseFrom(input)
        } catch (_: InvalidProtocolBufferException) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        } catch (_: IllegalArgumentException) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }
        return validate(wire, recipe)
    }

    private fun validate(wire: PromptFormatAsset, recipe: PromptRecipe): VerifiedPromptAsset {
        val key = runCatching {
            CapabilityKey(wire.capabilityId, wire.capabilitySchemaVersion)
        }.getOrElse { capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET) }
        val maxRevision = if (wire.hasMaxRecipeRevision()) wire.maxRecipeRevision else null
        if (wire.schemaVersion != SCHEMA_VERSION ||
            !ID_PATTERN.matches(wire.assetId) ||
            key != recipe.capabilityKey ||
            wire.recipeId != recipe.id ||
            wire.minRecipeRevision == 0 ||
            recipe.revision < wire.minRecipeRevision ||
            maxRevision != null && (maxRevision < wire.minRecipeRevision || recipe.revision > maxRevision) ||
            wire.blocksCount !in 1..MAX_BLOCKS ||
            wire.requiredFeaturesCount > MAX_FEATURES ||
            wire.maxExpandedBytes !in 1..MAX_EXPANDED_BYTES
        ) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }
        val features = wire.requiredFeaturesList.toSet()
        if (features.size != wire.requiredFeaturesCount ||
            features.any { !FEATURE_PATTERN.matches(it) } ||
            !supportedFeatures.containsAll(features) ||
            !features.containsAll(recipe.requiredAssetFeatures)
        ) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }

        var segmentCount = 0
        var literalBytes = 0
        val usedSlots = mutableSetOf<String>()
        val blocks = wire.blocksList.map { block ->
            val role = when (block.role) {
                WirePromptRole.PROMPT_ROLE_SYSTEM -> PromptBlockRole.SYSTEM
                WirePromptRole.PROMPT_ROLE_USER -> PromptBlockRole.USER
                WirePromptRole.PROMPT_ROLE_ASSISTANT -> PromptBlockRole.ASSISTANT
                else -> capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
            }
            if (block.segmentsCount !in 1..MAX_SEGMENTS_PER_BLOCK) {
                capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
            }
            val segments = block.segmentsList.map { segment ->
                segmentCount += 1
                when (segment.valueCase) {
                    com.touvay.engine.core.proto.PromptSegment.ValueCase.LITERAL -> {
                        val bytes = segment.literal.toByteArray(Charsets.UTF_8).size
                        if (bytes == 0) capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
                        literalBytes += bytes
                        PromptAssetSegment.Literal(segment.literal)
                    }
                    com.touvay.engine.core.proto.PromptSegment.ValueCase.SLOT_ID -> {
                        val slot = recipe.findSlot(segment.slotId)
                            ?: capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
                        usedSlots += slot.id
                        PromptAssetSegment.Slot(slot.id)
                    }
                    else -> capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
                }
            }
            PromptAssetBlock(role, segments)
        }
        if (segmentCount > MAX_SEGMENTS || literalBytes > wire.maxExpandedBytes ||
            recipe.slots.filter { it.required }.any { it.id !in usedSlots }
        ) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }
        return VerifiedPromptAsset(
            id = wire.assetId,
            capabilityKey = key,
            recipeId = wire.recipeId,
            minRecipeRevision = wire.minRecipeRevision,
            maxRecipeRevision = maxRevision,
            blocks = blocks,
            requiredFeatures = features,
            maxExpandedBytes = wire.maxExpandedBytes,
        )
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hexToBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_RECURSION_DEPTH = 16
        const val MAX_BLOCKS = 16
        const val MAX_SEGMENTS_PER_BLOCK = 64
        const val MAX_SEGMENTS = 256
        const val MAX_FEATURES = 32
        const val MAX_EXPANDED_BYTES = 512 * 1024
        val ID_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
        val FEATURE_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
    }
}

/** Values for recipe slots; contents are intentionally excluded from [toString]. */
public class PromptValues private constructor(values: Map<String, String>) {
    private val values: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(values))

    internal fun get(id: String): String? = values[id]
    internal fun keys(): Set<String> = values.keys

    override fun toString(): String = "PromptValues(count=${values.size})"

    public companion object {
        /** Copies typed values; recipe-specific limits are enforced during rendering. */
        public fun of(values: Map<String, String>): PromptValues = PromptValues(values)
    }
}

/** Substitution-only renderer for a verified prompt AST. */
public object PromptAssetRenderer {
    /** Renders exact literals and typed slots, then tokenizes through the acquired Runtime. */
    public suspend fun render(
        recipe: PromptRecipe,
        asset: VerifiedPromptAsset,
        values: PromptValues,
        environment: CapabilityAttemptEnvironment,
    ): PreparedModelInput {
        if (asset.capabilityKey != recipe.capabilityKey || asset.recipeId != recipe.id ||
            recipe.revision < asset.minRecipeRevision ||
            asset.maxRecipeRevision?.let { recipe.revision > it } == true ||
            values.keys().any { recipe.findSlot(it) == null }
        ) {
            capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
        }
        val rendered = StringBuilder()
        var expandedBytes = 0
        asset.blocks.forEach { block ->
            block.segments.forEach { segment ->
                val value = when (segment) {
                    is PromptAssetSegment.Literal -> segment.value
                    is PromptAssetSegment.Slot -> {
                        val slot = recipe.findSlot(segment.id)
                            ?: capabilityFailure(CapabilityFailureCode.INVALID_PROMPT_ASSET)
                        val supplied = values.get(slot.id)
                        if (supplied == null && slot.required) {
                            capabilityFailure(CapabilityFailureCode.INVALID_PAYLOAD)
                        }
                        supplied.orEmpty().also {
                            if (it.toByteArray(Charsets.UTF_8).size > slot.maxUtf8Bytes) {
                                capabilityFailure(CapabilityFailureCode.INPUT_TOO_LARGE)
                            }
                        }
                    }
                }
                expandedBytes += value.toByteArray(Charsets.UTF_8).size
                if (expandedBytes > asset.maxExpandedBytes) {
                    capabilityFailure(CapabilityFailureCode.PROMPT_EXPANSION_TOO_LARGE)
                }
                rendered.append(value)
            }
        }
        val text = rendered.toString()
        return PreparedModelInput(text, environment.tokenize(text))
    }
}

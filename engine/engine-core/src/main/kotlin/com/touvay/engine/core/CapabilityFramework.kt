package com.touvay.engine.core

import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.SessionConfig
import java.util.Collections

/** Stable capability identity. Schema versions coexist and are resolved exactly. */
public data class CapabilityKey(
    public val id: String,
    public val schemaVersion: Int,
) {
    init {
        require(ID_PATTERN.matches(id))
        require(schemaVersion > 0)
    }

    public companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
    }
}

/** Input kinds understood by the framework without interpreting their contents. */
public enum class CapabilityInputModality {
    TEXT,
    STRUCTURED,
    IMAGE,
    AUDIO,
}

/** Streaming behavior promised by a capability contract. */
public enum class CapabilityStreamingMode {
    NONE,
    DELTAS,
}

/** How a capability obtains model-facing input. */
public enum class CapabilityPromptStrategy {
    NONE,
    TYPED_RECIPE,
    DELEGATED,
}

/** Structured output mechanism a capability can validate. */
public enum class CapabilityOutputStrategy {
    TEXT,
    PROTOBUF,
    CONSTRAINED_DECODING,
}

/** Immutable, discoverable metadata for one production capability implementation. */
public class CapabilityContract(
    public val key: CapabilityKey,
    public val implementationRevision: Int,
    inputModalities: Set<CapabilityInputModality>,
    outputModalities: Set<CapabilityInputModality>,
    public val streamingMode: CapabilityStreamingMode,
    public val allowsCoalescing: Boolean,
    public val maxInlinePayloadBytes: Int,
    public val maxBulkInputBytes: Long,
    public val maxOutputBytes: Int,
    public val maxDeltaBytes: Int,
    public val promptStrategy: CapabilityPromptStrategy,
    outputStrategies: Set<CapabilityOutputStrategy>,
    requiredFrameworkFeatures: Set<String>,
    public val conformanceProfile: String,
) {
    public val inputModalities: Set<CapabilityInputModality> =
        Collections.unmodifiableSet(inputModalities.toSet())
    public val outputModalities: Set<CapabilityInputModality> =
        Collections.unmodifiableSet(outputModalities.toSet())
    public val outputStrategies: Set<CapabilityOutputStrategy> =
        Collections.unmodifiableSet(outputStrategies.toSet())
    public val requiredFrameworkFeatures: Set<String> =
        Collections.unmodifiableSet(requiredFrameworkFeatures.toSet())

    init {
        require(implementationRevision > 0)
        require(this.inputModalities.isNotEmpty())
        require(this.outputModalities.isNotEmpty())
        require(maxInlinePayloadBytes in 1..ExecutionRequest.MAX_INLINE_PAYLOAD_BYTES)
        require(maxBulkInputBytes >= 0)
        require(maxOutputBytes in 1..ExecutionRequest.MAX_INLINE_PAYLOAD_BYTES)
        require(maxDeltaBytes in 1..maxOutputBytes)
        require(this.outputStrategies.isNotEmpty())
        require(this.requiredFrameworkFeatures.all { FEATURE_PATTERN.matches(it) })
        require(FEATURE_PATTERN.matches(conformanceProfile))
    }

    private companion object {
        val FEATURE_PATTERN = Regex("[a-z][a-z0-9_.-]{0,127}")
    }
}

/** Bounded request bytes with defensive copying and content-free string representation. */
public class CapabilityPayload private constructor(private val bytes: ByteArray) {
    public val size: Int get() = bytes.size

    /** Returns a caller-owned copy for the capability's schema parser. */
    public fun copyBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String = "CapabilityPayload(size=$size)"

    public companion object {
        /** Creates a snapshot or rejects the request before capability code runs. */
        public fun copyOf(bytes: ByteArray, maxBytes: Int): CapabilityPayload {
            require(maxBytes > 0)
            if (bytes.size > maxBytes) capabilityFailure(CapabilityFailureCode.INPUT_TOO_LARGE)
            return CapabilityPayload(bytes.copyOf())
        }
    }
}

/** Typed, immutable context fragments supplied by trusted engine policy. */
public class CapabilityContext private constructor(fragments: Map<String, String>) {
    private val fragments: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(fragments))

    init {
        require(this.fragments.keys.all { it.isNotBlank() && it.length <= 128 })
        require(this.fragments.values.sumOf { it.toByteArray(Charsets.UTF_8).size } <= MAX_BYTES)
    }

    /** Returns one caller-authorized context value, or null when absent. */
    public fun value(key: String): String? = fragments[key]

    /** Returns content-free fragment identifiers. */
    public fun keys(): Set<String> = fragments.keys

    override fun toString(): String = "CapabilityContext(count=${fragments.size})"

    public companion object {
        public const val MAX_BYTES: Int = 64 * 1024
        public val EMPTY: CapabilityContext = CapabilityContext(emptyMap())

        /** Copies bounded policy-provided fragments. Values are user content. */
        public fun of(fragments: Map<String, String>): CapabilityContext =
            CapabilityContext(fragments)
    }
}

/** Content-free metadata available during deterministic capability preparation. */
public class CapabilityPreparationContext(
    public val execution: ExecutionContextCandidate,
)

/** Production capability SPI. Implementations parse only their own payload schema. */
public interface CapabilityDefinition {
    public val contract: CapabilityContract

    /** Creates request-scoped semantic state without acquiring a model or Runtime resource. */
    public suspend fun prepare(
        context: CapabilityPreparationContext,
        payload: CapabilityPayload,
        injectedContext: CapabilityContext,
    ): PreparedCapability
}

/** Prepared semantic request retained across independent execution attempts. */
public interface PreparedCapability : AutoCloseable {
    public val executionPlan: CapabilityExecutionPlan
    public val executionDemand: ExecutionDemand

    /** Returns fresh mutable state for one routed step attempt. */
    public fun newAttempt(binding: CapabilityAttemptBinding): CapabilityAttempt
}

/** Capability-owned attempt behavior behind the generic execution adapter. */
public interface CapabilityAttempt : AutoCloseable {
    public suspend fun buildModelInput(environment: CapabilityAttemptEnvironment): PreparedModelInput
    public fun sessionConfig(model: ModelInstanceInfo): SessionConfig
    public fun decodeParams(maxTokens: Int): DecodeParams
    public suspend fun consume(tokens: List<GeneratedToken>): List<ByteArray>
    public suspend fun finish(): ByteArray
}

/** Exact routed binding supplied when a semantic step becomes an attempt. */
public data class CapabilityAttemptBinding(
    public val stepId: String,
    public val candidateId: String,
    public val promptAsset: PromptAssetRef,
    public val contextLength: Int,
    public val maxOutputTokens: Int,
)

/** Services exposed to capability attempt code after exact model acquisition. */
public class CapabilityAttemptEnvironment(
    public val model: ModelInstanceInfo,
    public val promptAssets: PromptAssetSource,
    private val tokenizeAction: suspend (String) -> com.touvay.runtime.api.TokenSequence,
) {
    /** Tokenizes exact rendered input through the acquired Runtime instance. */
    public suspend fun tokenize(text: String): com.touvay.runtime.api.TokenSequence =
        tokenizeAction(text)
}

/** Required model/runtime features for a semantic step before route freezing. */
public class CapabilityRoutingRequirements(
    runtimeFeatures: Set<String> = emptySet(),
    public val minContextLength: Int,
    public val maxOutputTokens: Int,
) {
    public val runtimeFeatures: Set<String> =
        Collections.unmodifiableSet(runtimeFeatures.toSet())

    init {
        require(minContextLength > 0)
        require(maxOutputTokens > 0)
        require(this.runtimeFeatures.all { it.isNotBlank() && it.length <= 128 })
    }
}

/** Where a step obtains one named input value. */
public sealed interface CapabilityStepInput {
    public val slotId: String

    /** A value parsed from the request payload. */
    public data class PreparedInput(
        override val slotId: String,
        public val inputId: String,
    ) : CapabilityStepInput {
        init { require(slotId.isNotBlank() && inputId.isNotBlank()) }
    }

    /** A value injected by trusted engine context policy. */
    public data class ContextInput(
        override val slotId: String,
        public val contextKey: String,
    ) : CapabilityStepInput {
        init { require(slotId.isNotBlank() && contextKey.isNotBlank()) }
    }

    /** Output from an earlier step in the same immutable plan. */
    public data class PriorStepOutput(
        override val slotId: String,
        public val stepId: String,
        public val outputId: String,
    ) : CapabilityStepInput {
        init { require(slotId.isNotBlank() && stepId.isNotBlank() && outputId.isNotBlank()) }
    }
}

/** Bounded named semantic output produced by one capability step. */
public data class CapabilityStepOutput(
    public val id: String,
    public val maxBytes: Int,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(maxBytes in 1..ExecutionRequest.MAX_INLINE_PAYLOAD_BYTES)
    }
}

/** One ordered semantic operation in a [CapabilityExecutionPlan]. */
public sealed interface CapabilityExecutionStep {
    public val id: String
    public val inputs: List<CapabilityStepInput>
    public val routing: CapabilityRoutingRequirements
    public val demand: ExecutionDemand
    public val output: CapabilityStepOutput
}

/** A model-backed step assembled from one typed [PromptRecipe]. */
public class PromptExecutionStep(
    override val id: String,
    public val recipe: PromptRecipe,
    inputs: List<CapabilityStepInput>,
    override val routing: CapabilityRoutingRequirements,
    override val demand: ExecutionDemand,
    override val output: CapabilityStepOutput,
) : CapabilityExecutionStep {
    override val inputs: List<CapabilityStepInput> =
        Collections.unmodifiableList(inputs.toList())

    init {
        require(id.isNotBlank() && id.length <= 128)
        require(this.inputs.map { it.slotId }.distinct().size == this.inputs.size)
    }
}

/**
 * Immutable capability-semantic plan. It deliberately precedes the routed ADR-017
 * [ExecutionPlan], which freezes concrete model/runtime bindings for each attempt.
 */
public class CapabilityExecutionPlan(steps: List<CapabilityExecutionStep>) {
    public val steps: List<CapabilityExecutionStep> =
        Collections.unmodifiableList(steps.toList())

    init {
        require(this.steps.size in 1..MAX_STEPS)
        require(this.steps.map { it.id }.distinct().size == this.steps.size)
        val preceding = mutableMapOf<String, CapabilityStepOutput>()
        this.steps.forEach { step ->
            step.inputs.filterIsInstance<CapabilityStepInput.PriorStepOutput>().forEach { input ->
                require(preceding[input.stepId]?.id == input.outputId) {
                    "A plan may reference only declared outputs from preceding steps"
                }
            }
            preceding[step.id] = step.output
        }
    }

    /** Finds one step by exact stable id. */
    public fun findStep(id: String): CapabilityExecutionStep? = steps.firstOrNull { it.id == id }

    public companion object {
        public const val MAX_STEPS: Int = 8
    }
}

/** Stable availability reported by discovery without exposing routing internals. */
public enum class CapabilityAvailability {
    AVAILABLE,
    MODEL_NOT_INSTALLED,
    DEVICE_INCOMPATIBLE,
    DISABLED_BY_POLICY,
}

/** Resolves current availability while registration itself remains immutable. */
public fun interface CapabilityAvailabilityResolver {
    public fun resolve(definition: CapabilityDefinition): CapabilityAvailability
}

/** One immutable discovery record. */
public data class DiscoveredCapability(
    public val contract: CapabilityContract,
    public val availability: CapabilityAvailability,
)

/** Exact-key immutable registry for production capability implementations (ADR-020). */
public class CapabilityFrameworkRegistry private constructor(
    private val definitions: Map<CapabilityKey, CapabilityDefinition>,
) {
    /** Resolves one exact capability id and schema version. */
    public fun find(key: CapabilityKey): CapabilityDefinition? = definitions[key]

    /** Returns a stable registration-order snapshot. */
    public fun all(): List<CapabilityDefinition> = definitions.values.toList()

    public companion object {
        /** Creates an immutable registry and rejects duplicate exact keys. */
        public fun of(vararg definitions: CapabilityDefinition): CapabilityFrameworkRegistry {
            val byKey = LinkedHashMap<CapabilityKey, CapabilityDefinition>(definitions.size)
            definitions.forEach { definition ->
                require(byKey.put(definition.contract.key, definition) == null) {
                    "Duplicate capability key"
                }
            }
            return CapabilityFrameworkRegistry(Collections.unmodifiableMap(byKey))
        }
    }
}

/** Read-only capability discovery over immutable registrations and current policy state. */
public class CapabilityDiscovery(
    private val registry: CapabilityFrameworkRegistry,
    private val availability: CapabilityAvailabilityResolver,
) {
    /** Returns deterministically ordered discovery records. */
    public fun discover(): List<DiscoveredCapability> = registry.all()
        .sortedWith(compareBy({ it.contract.key.id }, { it.contract.key.schemaVersion }))
        .map { DiscoveredCapability(it.contract, availability.resolve(it)) }
}

/** Content-free framework failure categories. */
public enum class CapabilityFailureCode {
    INVALID_PAYLOAD,
    INPUT_TOO_LARGE,
    UNSUPPORTED_PLAN,
    MISSING_PLAN_BINDING,
    INVALID_PROMPT_ASSET,
    PROMPT_ASSET_UNAVAILABLE,
    PROMPT_EXPANSION_TOO_LARGE,
    INTERNAL,
}

/** Typed framework exception whose message never contains request or prompt content. */
public class CapabilityFrameworkException(
    public val code: CapabilityFailureCode,
) : RuntimeException(code.name)

internal fun capabilityFailure(code: CapabilityFailureCode): Nothing =
    throw CapabilityFrameworkException(code)

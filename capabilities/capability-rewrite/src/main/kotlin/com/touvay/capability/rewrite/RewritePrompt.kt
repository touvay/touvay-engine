package com.touvay.capability.rewrite

import com.touvay.engine.core.PromptRecipe
import com.touvay.engine.core.PromptRecipeElement
import com.touvay.engine.core.PromptSlot

/** Stable semantic prompt contract interpreted by signed model-pack format assets. */
public object RewritePrompt {
    public val REQUIRED_ASSET_FEATURES: Set<String> = setOf("prompt.typed-slots")

    public val RECIPE: PromptRecipe = PromptRecipe(
        capabilityKey = RewriteCapabilityDefinition.KEY,
        id = "text.rewrite.v1",
        revision = 1,
        sourceSha256 = "1da06c8bc29395410776ed238f63ac83f725386cb5ca4b885f2cf652b21c86ac",
        elements = listOf(
            PromptRecipeElement.TrustedInstruction("rewrite.instruction"),
            PromptRecipeElement.DataSlot("tone"),
            PromptRecipeElement.DataSlot("length"),
            PromptRecipeElement.DataSlot("locale"),
            PromptRecipeElement.DataSlot("source"),
            PromptRecipeElement.OutputConstraint("rewrite.output.plain_text"),
        ),
        slots = listOf(
            PromptSlot(
                "source",
                required = true,
                maxUtf8Bytes = MAX_ENCODED_SOURCE_UTF8_BYTES,
            ),
            PromptSlot("tone", required = true, maxUtf8Bytes = 16),
            PromptSlot("length", required = true, maxUtf8Bytes = 16),
            PromptSlot("locale", required = true, maxUtf8Bytes = 35),
        ),
        requiredAssetFeatures = REQUIRED_ASSET_FEATURES,
    )

    internal const val MAX_ENCODED_SOURCE_UTF8_BYTES: Int =
        RewriteCapabilityDefinition.MAX_SOURCE_UTF8_BYTES * 6 + 2
}

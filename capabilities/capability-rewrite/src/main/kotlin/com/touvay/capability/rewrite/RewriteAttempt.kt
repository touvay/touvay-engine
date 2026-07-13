package com.touvay.capability.rewrite

import com.touvay.contract.rewrite.v1.RewriteDelta
import com.touvay.contract.rewrite.v1.RewriteDisposition
import com.touvay.contract.rewrite.v1.RewriteResponse
import com.touvay.engine.core.CapabilityAttempt
import com.touvay.engine.core.CapabilityAttemptBinding
import com.touvay.engine.core.CapabilityAttemptEnvironment
import com.touvay.engine.core.GeneratedToken
import com.touvay.engine.core.PreparedModelInput
import com.touvay.engine.core.PromptAssetLoader
import com.touvay.engine.core.PromptAssetRenderer
import com.touvay.engine.core.PromptValues
import com.touvay.runtime.api.DecodeParams
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.SessionConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RewriteAttempt(
    input: RewriteInput,
    private val binding: CapabilityAttemptBinding,
) : CapabilityAttempt {
    private val closed = AtomicBoolean()
    private var input: RewriteInput? = input
    private val output = StringBuilder()
    private var outputUtf8Bytes = 0

    override suspend fun buildModelInput(
        environment: CapabilityAttemptEnvironment,
    ): PreparedModelInput {
        ensureOpenAndActive()
        val request = checkNotNull(input)
        val asset = PromptAssetLoader(RewritePrompt.REQUIRED_ASSET_FEATURES)
            .load(environment.promptAssets, binding.promptAsset, RewritePrompt.RECIPE)
        return PromptAssetRenderer.render(
            RewritePrompt.RECIPE,
            asset,
            PromptValues.of(
                mapOf(
                    "source" to encodePromptSource(request.source),
                    "tone" to request.tone,
                    "length" to request.length,
                    "locale" to request.locale,
                ),
            ),
            environment,
        )
    }

    override fun sessionConfig(model: ModelInstanceInfo): SessionConfig {
        ensureOpen()
        val contextLength = minOf(binding.contextLength, model.maxContextLength)
        if (contextLength <= binding.maxOutputTokens) internalFailure()
        return SessionConfig(contextLength)
    }

    override fun decodeParams(maxTokens: Int): DecodeParams {
        ensureOpen()
        if (maxTokens !in 1..binding.maxOutputTokens) internalFailure()
        return DecodeParams(maxTokens = maxTokens, temperature = 0.0f, topP = 1.0f)
    }

    override suspend fun consume(tokens: List<GeneratedToken>): List<ByteArray> {
        ensureOpenAndActive()
        if (tokens.isEmpty()) return emptyList()
        val coroutine = currentCoroutineContext()
        val batch = buildString {
            for (token in tokens) {
                coroutine.ensureActive()
                if (!isValidModelText(token.piece)) invalidOutput()
                append(token.piece)
            }
        }
        if (batch.isEmpty()) return emptyList()
        val batchBytes = batch.toByteArray(Charsets.UTF_8).size
        if (batchBytes > RewriteCapabilityDefinition.MAX_RESULT_UTF8_BYTES - outputUtf8Bytes) {
            invalidOutput()
        }
        output.append(batch)
        outputUtf8Bytes += batchBytes
        return splitUtf8(batch, MAX_DELTA_TEXT_BYTES).map { text ->
            RewriteDelta.newBuilder()
                .setText(text)
                .setProvisional(true)
                .build()
                .toByteArray()
                .also { bytes ->
                    if (bytes.size > RewriteCapabilityDefinition.MAX_DELTA_BYTES) internalFailure()
                }
        }
    }

    override suspend fun finish(): ByteArray {
        ensureOpenAndActive()
        val request = checkNotNull(input)
        val finalText = normalizeAndValidate(output.toString())
        val response = RewriteResponse.newBuilder()
            .setText(finalText)
            .setDisposition(
                if (finalText == request.source) {
                    RewriteDisposition.REWRITE_DISPOSITION_UNCHANGED
                } else {
                    RewriteDisposition.REWRITE_DISPOSITION_REWRITTEN
                },
            )
            .build()
            .toByteArray()
        if (response.size > 16 * 1024) invalidOutput()
        return response
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            output.setLength(0)
            outputUtf8Bytes = 0
            input = null
        }
    }

    private suspend fun ensureOpenAndActive() {
        ensureOpen()
        currentCoroutineContext().ensureActive()
    }

    private fun ensureOpen() {
        if (closed.get()) internalFailure()
    }

    private fun normalizeAndValidate(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty() || !isValidModelText(normalized) ||
            normalized.toByteArray(Charsets.UTF_8).size > RewriteCapabilityDefinition.MAX_RESULT_UTF8_BYTES
        ) {
            invalidOutput()
        }
        return normalized
    }

    private fun isValidModelText(value: String): Boolean {
        if (!isValidUnicode(value)) return false
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (codePoint == 0 || Character.isISOControl(codePoint) &&
                codePoint != '\n'.code && codePoint != '\t'.code && codePoint != '\r'.code
            ) {
                return false
            }
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun splitUtf8(value: String, maxBytes: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val charCount = Character.charCount(codePoint)
            val codePointBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes > 0 && bytes + codePointBytes > maxBytes) {
                result += value.substring(start, index)
                start = index
                bytes = 0
            }
            bytes += codePointBytes
            index += charCount
        }
        if (start < value.length) result += value.substring(start)
        return result
    }

    private companion object {
        const val MAX_DELTA_TEXT_BYTES = 1_024
    }
}

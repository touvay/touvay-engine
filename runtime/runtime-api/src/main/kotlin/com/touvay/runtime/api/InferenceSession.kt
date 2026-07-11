package com.touvay.runtime.api

/**
 * One inference session over a loaded model. Single-owner: exactly one prefill/decode may
 * execute at a time. Closing frees the session's KV cache.
 *
 * Cancellation contract: implementations must check [CancelSignal] at least once per
 * generated token and return promptly when it fires — the conformance kit enforces a
 * latency bound of one token (ARCHITECTURE.md §11.3).
 */
public interface InferenceSession : AutoCloseable {
    /** Processes prompt tokens into the session state. */
    public fun prefill(tokens: TokenSequence, cancel: CancelSignal): PrefillResult

    /** Generates tokens, streaming each one to [sink] until done or cancelled. */
    public fun decode(params: DecodeParams, cancel: CancelSignal, sink: TokenSink)
}

/** Token ids in model vocabulary space. */
@JvmInline
public value class TokenSequence(public val ids: IntArray)

/** Receives generated tokens on the inference thread; implementations must not block. */
public fun interface TokenSink {
    public fun onToken(tokenId: Int, piece: String)
}

public data class PrefillResult(
    val promptTokenCount: Int,
)

public data class DecodeParams(
    val maxTokens: Int,
    /** 0.0 selects greedy decoding — required for deterministic golden tests (§18). */
    val temperature: Float = 0.0f,
    val topP: Float = 1.0f,
)

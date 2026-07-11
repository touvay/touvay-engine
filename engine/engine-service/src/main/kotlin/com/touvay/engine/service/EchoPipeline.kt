package com.touvay.engine.service

import com.touvay.contract.TouvayContract
import com.touvay.contract.proto.EchoDelta
import com.touvay.contract.proto.EchoRequest
import com.touvay.contract.proto.EchoResponse
import com.touvay.engine.core.CapabilityDescriptor
import com.touvay.engine.core.CapabilityPipeline
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * The permanent `dev.echo` diagnostic capability: streams the request text back in
 * chunks, optionally delayed. Exercises the full request path — routing, streaming,
 * cancellation, stats — without loading any model, which keeps integration tests and
 * on-device health checks free.
 *
 * Chunking splits on UTF-16 units, which may split a surrogate pair across deltas.
 * Acceptable here because echo output is diagnostic, reassembled by concatenation; real
 * text capabilities own proper text segmentation in their pipelines.
 */
internal class EchoPipeline : CapabilityPipeline {

    override val descriptor = CapabilityDescriptor(
        id = TouvayContract.CAPABILITY_DIAGNOSTICS_ECHO,
        schemaVersion = 1,
    )

    override suspend fun execute(
        payload: ByteArray,
        emit: suspend (ByteArray) -> Unit,
    ): ByteArray {
        val request = try {
            EchoRequest.parseFrom(payload)
        } catch (e: InvalidProtocolBufferException) {
            // Deliberately content-free message (§16): parse errors surface as internal
            // failures without echoing any payload bytes.
            throw IllegalArgumentException("malformed dev.echo payload", e)
        }

        val text = request.text
        val requestedChunks = request.chunkCount.coerceAtLeast(1)
        val pieces = if (requestedChunks == 1 || text.length <= 1) {
            listOf(text)
        } else {
            text.chunked(ceil(text.length / requestedChunks.toDouble()).toInt())
        }

        val delayMillis = request.interChunkDelayMillis.toLong()
        pieces.forEach { piece ->
            if (delayMillis > 0) delay(delayMillis) else coroutineContext.ensureActive()
            emit(EchoDelta.newBuilder().setText(piece).build().toByteArray())
        }

        return EchoResponse.newBuilder().setText(text).build().toByteArray()
    }
}

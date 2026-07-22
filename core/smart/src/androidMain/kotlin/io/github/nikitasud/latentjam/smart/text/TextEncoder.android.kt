/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.SmartEngineException
import java.io.InputStream
import java.nio.LongBuffer
import kotlin.math.sqrt
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [TextEncoder]: ONNX Runtime over the int8 MiniLM export, with the tokenizer's vocabulary
 * read from assets.
 *
 * The tokenizer and pooling live in common code; this class is the tensor plumbing.
 */
internal class OnnxTextEncoder(
    private val openModel: () -> InputStream,
    private val openVocab: () -> InputStream,
) : TextEncoder {

    constructor(context: Context) : this(
        openModel = { context.assets.open(MODEL_ASSET) },
        openVocab = { context.assets.open(VOCAB_ASSET) },
    )

    private var session: OrtSession? = null
    private var tokenizer: BertWordPieceTokenizer? = null

    override suspend fun load(): Result<Unit> {
        if (session != null && tokenizer != null) return Result.success(Unit)
        return try {
            tokenizer = openVocab().bufferedReader(Charsets.UTF_8).useLines {
                BertWordPieceTokenizer(BertWordPieceTokenizer.parseVocab(it))
            }
            session = OrtSession.SessionOptions().use { options ->
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                OrtEnvironment.getEnvironment()
                    .createSession(openModel().use { it.readBytes() }, options)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            close()
            Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure("Failed to load text encoder: ${t.message}", t),
                ),
            )
        }
    }

    override fun encode(metadata: String): FloatArray? {
        if (metadata.isBlank()) return null
        val activeSession = session ?: return null
        val ids = (tokenizer ?: return null).encode(metadata)
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1L, ids.size.toLong())

        val idTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape,
        )
        // Batch of one, no padding, so every token attends and every segment is 0.
        val maskTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape,
        )
        val typeTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(LongArray(ids.size) { 0L }), shape,
        )
        return try {
            activeSession.run(
                mapOf(
                    "input_ids" to idTensor,
                    "attention_mask" to maskTensor,
                    "token_type_ids" to typeTensor,
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                meanPoolNormalize(((result[0].value) as Array<Array<FloatArray>>)[0])
            }
        } catch (t: Throwable) {
            null
        } finally {
            idTensor.close(); maskTensor.close(); typeTensor.close()
        }
    }

    /** Mean-pool over tokens, then L2-normalise. */
    private fun meanPoolNormalize(tokens: Array<FloatArray>): FloatArray {
        val pooled = FloatArray(TextEncoder.TEXT_DIM)
        for (token in tokens) {
            for (d in 0 until TextEncoder.TEXT_DIM) pooled[d] += token[d]
        }
        val inverse = 1f / tokens.size
        var sumSq = 0.0
        for (d in 0 until TextEncoder.TEXT_DIM) {
            pooled[d] *= inverse
            sumSq += pooled[d].toDouble() * pooled[d]
        }
        val norm = sqrt(sumSq).toFloat()
        if (norm > 1e-12f) {
            for (d in 0 until TextEncoder.TEXT_DIM) pooled[d] /= norm
        }
        return pooled
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
    }

    companion object {
        const val MODEL_ASSET = "ml/text_encoder_minilm.onnx"
        const val VOCAB_ASSET = "ml/text_vocab.txt"
    }
}

public actual fun smartTextEncoderModule(): Module = module {
    single<TextEncoder> { OnnxTextEncoder(get<Context>()) }
    single {
        val context = get<Context>()
        MusicEntityResolver {
            runCatching { context.assets.open(MUSIC_ENTITY_ASSET).use { it.readBytes() } }.getOrNull()
        }
    }
}

private const val MUSIC_ENTITY_ASSET = "ml/music_entities_250k.bin"

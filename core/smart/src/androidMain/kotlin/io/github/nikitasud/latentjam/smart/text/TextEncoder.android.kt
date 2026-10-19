/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import io.github.nikitasud.latentjam.smart.offlineOrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.SmartEngineException
import io.github.nikitasud.latentjam.smart.createOrtSession
import java.io.InputStream
import java.nio.LongBuffer
import kotlin.math.sqrt
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [TextEncoder]: ONNX Runtime over the INT8 text encoder, with the tokenizer's vocabulary
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
                BertWordPieceTokenizer(BertWordPieceTokenizer.parseVocab(it), maxLen = TextEncoder.MAX_TOKENS)
            }
            // Loaded at every launch beside the restored indexes: one copy of the model, not two.
            session = createOrtSession(openModel().use { it.readExactly() })
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
        val environment = offlineOrtEnvironment()
        val shape = longArrayOf(1L, ids.size.toLong())

        // Every tensor is created inside the try: a failure half-way through the list must not leave the
        // ones already created to the garbage collector's mercy.
        val created = ArrayList<OnnxTensor>(3)
        return try {
            created += OnnxTensor.createTensor(
                environment, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape,
            )
            // Batch of one, no padding, so every token attends and every segment is 0.
            created += OnnxTensor.createTensor(
                environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape,
            )
            created += OnnxTensor.createTensor(
                environment, LongBuffer.wrap(LongArray(ids.size) { 0L }), shape,
            )
            activeSession.run(
                mapOf(
                    "input_ids" to created[0],
                    "attention_mask" to created[1],
                    "token_type_ids" to created[2],
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                meanPoolNormalize(((result[0].value) as Array<Array<FloatArray>>)[0])
            }
        } catch (t: Throwable) {
            null
        } finally {
            for (tensor in created) tensor.close()
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
        const val MODEL_ASSET = "ml/text_encoder.onnx"
        const val VOCAB_ASSET = "ml/text_vocab.txt"
    }
}

public actual fun smartTextEncoderModule(): Module = module {
    single<TextEncoder> { OnnxTextEncoder(get<Context>()) }
    // The two packs are mapped from the APK, not copied onto the heap (see assetBytes).
    single {
        val context = get<Context>()
        MusicEntityResolver { context.assets.assetBytes(MUSIC_ENTITY_ASSET) }
    }
    single {
        val context = get<Context>()
        ArtistKnowledge(
            get(),
            loadPack = { context.assets.assetBytes(ARTIST_KNOWLEDGE_ASSET) },
            loadAdapter = {
                runCatching { context.assets.open(ARTIST_ADAPTER_ASSET).use { it.readBytes() } }.getOrNull()
            },
        )
    }
    single {
        val context = get<Context>()
        val koin = getKoin()
        io.github.nikitasud.latentjam.smart.ranker.SmartRankerAssets(
            knowledge = get(),
            read = { name -> context.assets.assetBytes("ml/$name") },
            audio = io.github.nikitasud.latentjam.smart.ranker.EngineAudioSource { koin.getOrNull() },
        )
    }
}

internal const val MUSIC_ENTITY_ASSET = "ml/music_entities_250k.bin"
internal const val ARTIST_KNOWLEDGE_ASSET = "ml/artist_knowledge.bin"
internal const val ARTIST_ADAPTER_ASSET = "ml/artist_adapter.bin"

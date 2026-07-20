/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
import java.io.File
import java.nio.FloatBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [EmbeddingBackend]: ONNX Runtime over LatentJam's MNv4 audio
 * encoder (`mnv4-conv-m-distill-mw`, an EfficientAT-family student).
 *
 * ### Model contract (fixed by the research-side export; do not drift)
 * - Input `waveform`: `[1, 320000]` float32 — 10 s of mono audio in `[-1, 1]`
 *   at 32 kHz. The mel frontend (Conv1d STFT) is INSIDE the graph, so this
 *   backend feeds raw waveform — no DSP to keep in sync with training.
 * - Output `embedding`: `[1, 960]` float32, L2-normalized in-graph.
 *
 * ### Track pooling
 * [embed] runs up to three deterministic windows (20 % / 50 % / 80 % of the
 * track), sums the window embeddings and L2-normalizes the sum (identical
 * direction to mean-then-normalize). Deterministic windows make embeddings
 * reproducible across re-indexing runs.
 *
 * The last computed embedding is dumped to `files/debug_last_embedding.txt`
 * for the Mac-side equivalence gate (adb `run-as` pull in debug builds).
 *
 * Threading: the engine serializes all calls on its single-parallelism
 * dispatcher, so one ORT session with default options is safe.
 */
internal class OnnxEmbeddingBackend(
    private val context: Context,
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    private val decoder = AndroidAudioDecoder(context)
    private var session: OrtSession? = null

    override suspend fun loadModel(): Result<Unit> {
        if (session != null) return Result.success(Unit)
        return try {
            val assetPath = config.modelLocator ?: DEFAULT_ASSET_PATH
            val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
            session = OrtEnvironment.getEnvironment().createSession(modelBytes)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure("Failed to load similarity model: ${t.message}", t),
                ),
            )
        }
    }

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
        val activeSession = session
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val audioUri = descriptor.audioUri
            ?: return backendFailure("No audioUri for ${descriptor.id.value}")

        return try {
            val uri = Uri.parse(audioUri)
            val pooled = FloatArray(config.embeddingDim)
            var windows = 0
            for (startMs in windowStartsMs(descriptor.durationMs)) {
                currentCoroutineContext().ensureActive()
                val waveform = decoder.decodeWindowMono(
                    uri = uri,
                    startMs = startMs,
                    targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES,
                ) ?: continue
                val embedding = runWindow(activeSession, waveform)
                if (embedding.size != config.embeddingDim) {
                    return backendFailure(
                        "Model produced ${embedding.size}-dim embedding, " +
                            "expected ${config.embeddingDim}",
                    )
                }
                for (i in pooled.indices) pooled[i] += embedding[i]
                windows++
            }
            if (windows == 0) {
                return backendFailure("Could not decode any audio window for ${descriptor.id.value}")
            }
            if (!l2NormalizeInPlace(pooled)) {
                return backendFailure("Model produced a non-finite or zero-norm embedding")
            }
            dumpDebugEmbedding(descriptor, pooled)
            Result.success(pooled)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure("Embedding failed for ${descriptor.id.value}: ${t.message}", t),
                ),
            )
        }
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        // The process-global OrtEnvironment is deliberately left open.
    }

    private fun runWindow(session: OrtSession, waveform: FloatArray): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(waveform),
            longArrayOf(1, WINDOW_SAMPLES.toLong()),
        ).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val result = output[0].value as Array<FloatArray>
                return result[0]
            }
        }
    }

    private fun windowStartsMs(durationMs: Long?): List<Long> {
        if (durationMs == null || durationMs <= WINDOW_MS) return listOf(0L)
        val span = durationMs - WINDOW_MS
        return WINDOW_POSITIONS.map { fraction -> (span * fraction).toLong() }
    }

    private fun l2NormalizeInPlace(vector: FloatArray): Boolean {
        var sumOfSquares = 0f
        for (component in vector) {
            if (!component.isFinite()) return false
            sumOfSquares += component * component
        }
        val norm = sqrt(sumOfSquares)
        if (!norm.isFinite() || norm <= 0f) return false
        for (i in vector.indices) vector[i] /= norm
        return true
    }

    private fun dumpDebugEmbedding(descriptor: TrackDescriptor, embedding: FloatArray) {
        runCatching {
            File(context.filesDir, DEBUG_DUMP_FILE)
                .writeText("${descriptor.id.value}\n${embedding.joinToString(",")}\n")
        }
    }

    private fun backendFailure(message: String): Result<FloatArray> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))

    private companion object {
        // Contract constants from the research-side export
        // (mnv4-conv-m-distill-mw): see scripts/distill/README.md there.
        const val SAMPLE_RATE = 32_000
        const val WINDOW_SAMPLES = 320_000
        const val WINDOW_MS = WINDOW_SAMPLES * 1000L / SAMPLE_RATE
        const val INPUT_NAME = "waveform"
        const val DEFAULT_ASSET_PATH = "ml/mnv4_audio.onnx"
        const val DEBUG_DUMP_FILE = "debug_last_embedding.txt"
        val WINDOW_POSITIONS = listOf(0.2, 0.5, 0.8)
    }
}

public actual fun smartEngineBackendModule(): Module = module {
    single<EmbeddingBackend> { OnnxEmbeddingBackend(context = get(), config = get()) }
    // Overrides the common NoopIndexStore (this module is listed after
    // smartEngineModule; Koin last-definition-wins).
    single<IndexStore> { FileIndexStore(context = get()) }

    single<IndexStore>(smartTextIndexQualifier) {
        FileIndexStore(context = get(), fileName = "smart_text_index.bin")
    }
}

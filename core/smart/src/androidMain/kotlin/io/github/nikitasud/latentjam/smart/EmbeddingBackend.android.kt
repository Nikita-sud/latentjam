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
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
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
 * [embed] runs the deterministic windows [AudioWindows] places across the
 * track, sums the window embeddings and L2-normalizes the sum (identical
 * direction to mean-then-normalize). Deterministic windows make embeddings
 * reproducible across re-indexing runs.
 *
 * Threading: the engine serializes calls on a low-priority worker and the ORT session is explicitly
 * single-threaded. ORT's default native pool otherwise occupies several cores even though the
 * calling coroutine has parallelism one, starving UI rendering during first-run indexing.
 */
internal class OnnxEmbeddingBackend(
    private val context: Context,
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    private val decoder = AndroidAudioDecoder(context)
    private var session: OrtSession? = null
    private var semanticSession: OrtSession? = null

    override suspend fun loadModel(): Result<Unit> {
        if (session != null) return Result.success(Unit)
        return try {
            val assetPath = config.modelLocator ?: DEFAULT_ASSET_PATH
            val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
            session = OrtSession.SessionOptions().use { options ->
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                OrtEnvironment.getEnvironment().createSession(modelBytes, options)
            }
            // Semantic routing improves My Mixes but is not required for audio similarity. Keep
            // startup usable if an older/debug package is missing the optional head.
            runCatching {
                val semanticBytes = context.assets.open(SEMANTIC_ASSET_PATH).use { it.readBytes() }
                semanticSession = OrtSession.SessionOptions().use { options ->
                    options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    OrtEnvironment.getEnvironment().createSession(semanticBytes, options)
                }
            }.onFailure { failure ->
                println("SMART: semantic head unavailable (${failure.message})")
            }
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
            var lastModelFailure: String? = null

            fun inferStable(waveform: FloatArray, startMs: Long): FloatArray? {
                var appliedGain = 1f
                for (targetGain in INFERENCE_GAINS) {
                    val scale = targetGain / appliedGain
                    if (scale != 1f) {
                        for (index in waveform.indices) waveform[index] *= scale
                    }
                    appliedGain = targetGain
                    val embedding = runWindow(activeSession, waveform)
                    if (embedding.size != config.embeddingDim) {
                        lastModelFailure =
                            "Model produced ${embedding.size}-dim embedding, expected " +
                            config.embeddingDim
                        return null
                    }
                    if (isUsableEmbedding(embedding)) return embedding
                }
                lastModelFailure =
                    "Model produced a non-finite or zero-norm embedding at ${startMs}ms " +
                    waveformSummary(waveform, appliedGain)
                return null
            }

            for (startMs in AudioWindows.startsMs(descriptor.durationMs, WINDOW_MS)) {
                currentCoroutineContext().ensureActive()
                val waveform = decoder.decodeWindowMono(
                    uri = uri,
                    startMs = startMs,
                    targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES,
                ) ?: continue
                val embedding = inferStable(waveform, startMs) ?: continue
                for (i in pooled.indices) pooled[i] += embedding[i]
                windows++
            }
            // Several otherwise playable MP3/M4A files on real devices reject random access even
            // though decoding from the head works. Retry the beginning only after every preferred
            // crop failed; successful tracks keep the [AudioWindows] contract and pay no extra
            // inference cost.
            if (windows == 0 && descriptor.durationMs?.let { it > WINDOW_MS } == true) {
                decoder.decodeWindowMono(
                    uri = uri,
                    startMs = 0L,
                    targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES,
                )?.let { waveform ->
                    val embedding = inferStable(waveform, 0L)
                    if (embedding != null) {
                        for (i in pooled.indices) pooled[i] += embedding[i]
                        windows++
                    }
                }
            }
            if (windows == 0) {
                if (lastModelFailure != null) return backendFailure(lastModelFailure)
                return Result.failure(
                    SmartEngineException(
                        EngineError.AudioUnavailable(decoder.lastFailure),
                    ),
                )
            }
            if (!l2NormalizeInPlace(pooled)) {
                return backendFailure("Model produced a non-finite or zero-norm embedding")
            }
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

    override suspend fun classify(embeddings: List<FloatArray>): Result<List<FloatArray>> {
        if (embeddings.isEmpty()) return Result.success(emptyList())
        val activeSession = semanticSession
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        if (embeddings.any { it.size != config.embeddingDim || !it.all(Float::isFinite) }) {
            return backendSemanticFailure("Semantic head received an invalid embedding batch")
        }
        return try {
            val flattened = FloatArray(embeddings.size * config.embeddingDim)
            for (row in embeddings.indices) {
                embeddings[row].copyInto(
                    destination = flattened,
                    destinationOffset = row * config.embeddingDim,
                )
            }
            val environment = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(flattened),
                longArrayOf(embeddings.size.toLong(), config.embeddingDim.toLong()),
            ).use { tensor ->
                activeSession.run(mapOf(SEMANTIC_INPUT_NAME to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val result = output[0].value as Array<FloatArray>
                    if (
                        result.size != embeddings.size ||
                        result.any { it.size != TrackSemantics.OUTPUT_SIZE }
                    ) {
                        return backendSemanticFailure(
                            "Semantic head produced an unexpected output shape",
                        )
                    }
                    Result.success(result.map { it.copyOf() })
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            backendSemanticFailure("Semantic classification failed: ${failure.message}", failure)
        }
    }

    override fun close() {
        runCatching { session?.close() }
        runCatching { semanticSession?.close() }
        session = null
        semanticSession = null
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

    private fun isUsableEmbedding(vector: FloatArray): Boolean {
        var sumOfSquares = 0f
        for (component in vector) {
            if (!component.isFinite()) return false
            sumOfSquares += component * component
        }
        return sumOfSquares.isFinite() && sumOfSquares > 0f
    }

    private fun waveformSummary(waveform: FloatArray, appliedGain: Float): String {
        var peak = 0f
        var sumOfSquares = 0.0
        var nonZero = 0
        for (sample in waveform) {
            val absolute = kotlin.math.abs(sample)
            if (absolute > peak) peak = absolute
            sumOfSquares += sample.toDouble() * sample
            if (absolute > 1e-6f) nonZero++
        }
        val rms = sqrt(sumOfSquares / waveform.size).toFloat()
        // Report the original-scale signal even though the array currently holds the last retry.
        val inverse = 1f / appliedGain
        return "(peak=${peak * inverse}, rms=${rms * inverse}, nonZero=$nonZero)"
    }

    private fun backendFailure(message: String): Result<FloatArray> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))

    private fun backendSemanticFailure(
        message: String,
        cause: Throwable? = null,
    ): Result<List<FloatArray>> = Result.failure(
        SmartEngineException(EngineError.BackendFailure(message, cause)),
    )

    private companion object {
        // Contract constants from the research-side export
        // (mnv4-conv-m-distill-mw): see scripts/distill/README.md there.
        const val SAMPLE_RATE = 32_000
        const val WINDOW_SAMPLES = 320_000
        const val WINDOW_MS = WINDOW_SAMPLES * 1000L / SAMPLE_RATE
        const val INPUT_NAME = "waveform"
        const val DEFAULT_ASSET_PATH = "ml/mnv4_audio.onnx"
        const val SEMANTIC_ASSET_PATH = "ml/universal_semantic_head.onnx"
        const val SEMANTIC_INPUT_NAME = "embedding"
        val INFERENCE_GAINS = listOf(1f, 0.5f, 0.25f)
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

    single<IndexStore>(smartLayoutQualifier) {
        FileIndexStore(context = get(), fileName = "map_layout.bin")
    }
}

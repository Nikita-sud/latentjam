/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.AndroidBatchDotProducts
import io.github.nikitasud.latentjam.smart.chain.BatchDotProducts
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
 * Threading: the engine serializes calls on a low-priority worker and the ORT session is explicitly
 * single-threaded. ORT's default native pool otherwise occupies several cores even though the
 * calling coroutine has parallelism one, starving UI rendering during first-run indexing.
 * [embedEach] decodes the next tracks on [decodeWorkers] background-priority threads while the
 * current one runs inference; decoding is mostly waiting on the platform codec, so a few in flight
 * multiply indexing speed. Inference stays one track at a time, in order.
 */
internal class OnnxEmbeddingBackend(
    private val context: Context,
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    private val decoder = AndroidAudioDecoder(context)
    private var session: OrtSession? = null
    private var semanticSession: OrtSession? = null

    /**
     * Tracks decoding at once while inference runs: one on a low-RAM device, two with four cores or
     * fewer, three otherwise. Measured on 40 tracks (AudioDecodeBenchmarkDeviceTest, identical audio
     * in every setting): an 8-core S24 Ultra decodes a track in 1.8 s alone, 0.52 s with two, 0.34 s
     * with three and 0.39 s with four; a 4-core emulator in 0.72, 0.52, 0.33 and 0.22 s.
     */
    private val decodeWorkers: Int = run {
        val cores = Runtime.getRuntime().availableProcessors()
        val lowRam = runCatching { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice }
            .getOrNull() == true
        when {
            lowRam -> 1
            cores <= 4 -> 2
            else -> 3
        }
    }
    private var decodePool: ExecutorService? = null

    override suspend fun loadModel(): Result<Unit> {
        if (session != null) return Result.success(Unit)
        return try {
            val assetPath = config.modelLocator ?: DEFAULT_ASSET_PATH
            val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
            // The shipped encoder runs its pointwise convolutions on LatentJam's own 4-bit operator.
            session = createOrtSession(modelBytes, operators = true)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure("Failed to load similarity model: ${t.message}", t),
                ),
            )
        }
    }

    override suspend fun loadSemanticModel(): Result<Unit> {
        if (semanticSession != null) return Result.success(Unit)
        return try {
            val semanticBytes = context.assets.open(SEMANTIC_ASSET_PATH).use { it.readBytes() }
            semanticSession = createOrtSession(semanticBytes)
            Result.success(Unit)
        } catch (failure: Throwable) {
            Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure(
                        "Failed to load semantic model: ${failure.message}",
                        failure,
                    ),
                ),
            )
        }
    }

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> = embedDecoded(descriptor, null)

    override suspend fun embedEach(
        tracks: List<TrackDescriptor>,
        onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
    ) {
        if (tracks.size < 2 || session == null) {
            for (track in tracks) onResult(track, embed(track))
            return
        }
        val pool = decodePool ?: Executors.newFixedThreadPool(decodeWorkers) { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "smart-decode").apply { isDaemon = true }
        }.also { decodePool = it }
        val dispatcher = pool.asCoroutineDispatcher()
        coroutineScope {
            // Decoding runs ahead by the workers plus one ready track; inference takes them in order.
            val ahead = ArrayDeque<Pair<TrackDescriptor, Deferred<Map<Long, AudioDecodeResult>?>>>()
            var next = 0
            fun launchNext() {
                val track = tracks[next++]
                ahead.addLast(track to async(dispatcher) { prefetch(track) })
            }
            while (next < tracks.size && ahead.size <= decodeWorkers) launchNext()
            while (ahead.isNotEmpty()) {
                val (track, decoding) = ahead.removeFirst()
                val decoded = decoding.await()
                if (next < tracks.size) launchNext()
                onResult(track, embedDecoded(track, decoded))
            }
        }
    }

    /** The planned windows of [descriptor], decoded; null when there is nothing to decode ahead. */
    private suspend fun prefetch(descriptor: TrackDescriptor): Map<Long, AudioDecodeResult>? {
        val audioUri = descriptor.audioUri ?: return null
        val decodeContext = currentCoroutineContext()
        return try {
            val uri = Uri.parse(audioUri)
            windowStartsMs(descriptor.durationMs).associateWith { startMs ->
                decoder.decodeWindowMono(
                    uri = uri,
                    startMs = startMs,
                    targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES,
                    isCancelled = { !decodeContext.isActive },
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null // embedDecoded decodes the track itself, exactly as embed would.
        }
    }

    /**
     * [embed], with the windows [prefetched] already decoded where they succeeded or failed for good.
     * A window that failed for a reason that may pass (a codec not allocated while others ran) is
     * decoded again here, alone.
     */
    private suspend fun embedDecoded(
        descriptor: TrackDescriptor,
        prefetched: Map<Long, AudioDecodeResult>?,
    ): Result<FloatArray> {
        val activeSession = session
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val audioUri = descriptor.audioUri
            ?: return invalidAudio("No audio URI for ${descriptor.id.value}")

        return try {
            val uri = Uri.parse(audioUri)
            val decodeContext = currentCoroutineContext()
            val isDecodeCancelled = { !decodeContext.isActive }
            val pooled = FloatArray(config.embeddingDim)
            var windows = 0
            var invalidAudioFailure: String? = null
            var unavailableFailure: String? = null
            var backendContractFailure: String? = null

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
                        backendContractFailure =
                            "Model produced ${embedding.size}-dim embedding, expected " +
                            config.embeddingDim
                        return null
                    }
                    if (isUsableEmbedding(embedding)) return embedding
                }
                // Decoding and inference both completed normally, and gain retries make this
                // stable for the current bytes. Treat it like an invalid track, not a transient
                // model/session failure, so the unchanged media does not wake the model forever.
                invalidAudioFailure =
                    "Model produced a non-finite or zero-norm embedding at ${startMs}ms " +
                    waveformSummary(waveform, appliedGain)
                return null
            }

            suspend fun tryWindow(startMs: Long) {
                currentCoroutineContext().ensureActive()
                val ready = prefetched?.get(startMs)?.takeUnless { it is AudioDecodeResult.Unavailable }
                when (val decoded = ready ?: decoder.decodeWindowMono(
                    uri = uri,
                    startMs = startMs,
                    targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES,
                    isCancelled = isDecodeCancelled,
                )) {
                    is AudioDecodeResult.Success -> {
                        currentCoroutineContext().ensureActive()
                        val embedding = inferStable(decoded.waveform, startMs) ?: return
                        for (i in pooled.indices) pooled[i] += embedding[i]
                        windows++
                    }
                    is AudioDecodeResult.InvalidAudio -> {
                        if (invalidAudioFailure == null) invalidAudioFailure = decoded.detail
                    }
                    is AudioDecodeResult.Unavailable -> {
                        if (unavailableFailure == null) unavailableFailure = decoded.detail
                    }
                }
            }

            for (startMs in windowStartsMs(descriptor.durationMs)) {
                tryWindow(startMs)
            }
            // Several otherwise playable MP3/M4A files on real devices reject random access even
            // though decoding from the head works. Retry the beginning only after every preferred
            // 20/50/80% crop failed; successful tracks keep the original three-window contract and
            // pay no extra inference cost.
            if (windows == 0 && descriptor.durationMs?.let { it > WINDOW_MS } == true) {
                tryWindow(0L)
            }
            if (windows == 0) {
                return Result.failure(
                    SmartEngineException(
                        zeroSuccessfulAudioWindowsError(
                            backendContractFailure = backendContractFailure,
                            unavailableFailure = unavailableFailure,
                            invalidAudioFailure = invalidAudioFailure,
                        ),
                    ),
                )
            }
            if (!l2NormalizeInPlace(pooled)) {
                return invalidAudio("Pooled embedding was non-finite or zero-norm")
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
        decodePool?.shutdownNow()
        decodePool = null
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

    private fun invalidAudio(detail: String): Result<FloatArray> =
        Result.failure(SmartEngineException(EngineError.InvalidAudio(detail)))

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
        val WINDOW_POSITIONS = listOf(0.2, 0.5, 0.8)
        val INFERENCE_GAINS = listOf(1f, 0.5f, 0.25f)
    }
}

/**
 * Chooses the final typed failure only after every deterministic crop (including the head retry)
 * has failed. Backend contract violations win first. A single transient decoder outcome wins over
 * deterministic failures from other crops, preventing a temporary codec/storage problem from
 * becoming a durable per-track marker.
 */
internal fun zeroSuccessfulAudioWindowsError(
    backendContractFailure: String?,
    unavailableFailure: String?,
    invalidAudioFailure: String?,
): EngineError = when {
    backendContractFailure != null -> EngineError.BackendFailure(backendContractFailure)
    unavailableFailure != null -> EngineError.AudioUnavailable(unavailableFailure)
    invalidAudioFailure != null -> EngineError.InvalidAudio(invalidAudioFailure)
    else -> EngineError.AudioUnavailable("No audio decode attempt completed")
}

public actual fun smartEngineBackendModule(): Module = module {
    single<BatchDotProducts> { AndroidBatchDotProducts }
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

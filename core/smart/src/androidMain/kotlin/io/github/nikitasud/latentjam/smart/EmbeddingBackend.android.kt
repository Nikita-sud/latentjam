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
import io.github.nikitasud.latentjam.smart.di.smartEnergyQualifier
import io.github.nikitasud.latentjam.smart.di.smartImputationQualifier
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
import io.github.nikitasud.latentjam.smart.text.readExactly
import java.nio.FloatBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
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
 * multiply indexing speed. Inference stays one track at a time, in order. Every decode runs on those
 * workers, and a track gets [TRACK_ANALYSIS_BUDGET]: a platform call that never returns costs that
 * one track (failed as timed out) and one abandoned thread, never the rest of the library (#27).
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

    /** Every decode runs here, inline re-decodes included, under the per-track deadline (issue #27). */
    private val decoding = DeadlineDecoding(
        ExecutorDecodeWorkers(decodeWorkers) { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "smart-decode").apply { isDaemon = true }
        },
    )

    /**
     * A SMART press's seed decodes on a lane of its own, so it never queues behind the analysis decodes running ahead
     * on [decoding] (docs/analysis-094-plan.md 1b-0); its thread keeps the default priority.
     */
    private val seedDecoding = DeadlineDecoding(
        ExecutorDecodeWorkers(1) { task -> Thread(task, "smart-seed-decode").apply { isDaemon = true } },
    )

    /** The engine's inference lock, taken here per window (see [takeInferenceLock]). */
    private var inferenceLock: Mutex? = null

    /** Time the track in hand on each lane has waited for [inferenceLock]; its budget excuses it (withinBudget). */
    private class LockWaits {
        @Volatile var nanos = 0L
        /** When the wait in progress began, or 0. */
        @Volatile var since = 0L
        val waited: () -> Duration = {
            val open = since
            (nanos + if (open != 0L) System.nanoTime() - open else 0L).nanoseconds
        }
    }
    private val analysisWaits = LockWaits()
    private val seedWaits = LockWaits()

    override fun takeInferenceLock(lock: Mutex): Boolean {
        check(inferenceLock == null) { "One engine per backend" }
        inferenceLock = lock
        return true
    }

    override suspend fun loadModel(): Result<Unit> {
        if (session != null) return Result.success(Unit)
        return try {
            val assetPath = config.modelLocator ?: DEFAULT_ASSET_PATH
            // One copy of the model on the heap, not readBytes()' two (see readExactly).
            val modelBytes = context.assets.open(assetPath).use { it.readExactly() }
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
            // The head must be the one fitted on this encoder's vectors (audit 2026-10-20 #1).
            val fittedOn = context.assets.open(SEMANTIC_VERSION_PATH).bufferedReader().use { r ->
                r.readLines().firstOrNull { it.startsWith("encoder=") }?.removePrefix("encoder=")?.trim()
            }
            val encoder = context.assets.open(EMBEDDING_VERSION_PATH).bufferedReader().use { it.readLine()?.trim() }
            check(fittedOn == encoder) { "semantic head fitted on encoder $fittedOn, the bundled encoder is $encoder" }
            val semanticBytes = context.assets.open(SEMANTIC_ASSET_PATH).use { it.readExactly() }
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

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
        return seedDecoding.withinBudget(seedWaits.waited) { embedDecoded(descriptor, null, seedDecoding, seedWaits) }
    }

    /** [embed] for analysis: on the analysis lane. */
    private suspend fun analyse(descriptor: TrackDescriptor): Result<FloatArray> {
        return decoding.withinBudget(analysisWaits.waited) { embedDecoded(descriptor, null, decoding, analysisWaits) }
    }

    override suspend fun embedEach(
        tracks: List<TrackDescriptor>,
        onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
    ) = embedEach(tracks, emptyList(), onResult)

    override suspend fun embedEach(
        tracks: List<TrackDescriptor>,
        upcoming: List<TrackDescriptor>,
        onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
    ) {
        if ((tracks.size < 2 && upcoming.isEmpty()) || session == null) {
            decoding.dropCarried()
            for (track in tracks) onResult(track, analyse(track))
            return
        }
        // Decoding runs ahead by up to the workers plus one ready track (fewer when memory is short or Battery Saver
        // is on), on into the next chunk; inference takes them in
        // order. A prefetched track and an inline one decode the same windows the same way (embedDecoded).
        decoding.each(
            tracks = tracks,
            ahead = if (slow || systemMemoryLow()) 0 else lookAheadFor(decodeWorkers, freeHeapBytes()),
            prefetch = { track -> prefetch(track) },
            finish = { track, decoded -> embedDecoded(track, decoded, decoding, analysisWaits) },
            waited = analysisWaits.waited,
            onResult = onResult,
            upcoming = upcoming,
        )
    }

    @Volatile private var slow = false

    override fun setAnalysisPacing(slow: Boolean) {
        this.slow = slow
    }

    /** The system is short of RAM (codec buffers are native, outside the heap): decode one track at a time. */
    private fun systemMemoryLow(): Boolean = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        info.lowMemory
    }.getOrDefault(false)

    /** What the Java heap can still give before it reaches its limit. */
    private fun freeHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    }

    /** A track's windows decoded ahead, and what is left of its codec back-off allowance for the rest. */
    private class Prefetched(val windows: Map<Long, AudioDecodeResult>, val backoff: CodecBackoff)

    /** The planned windows of [descriptor], decoded; null when there is nothing to decode ahead. */
    private suspend fun prefetch(descriptor: TrackDescriptor): Prefetched? {
        val audioUri = descriptor.audioUri ?: return null
        val decodeContext = currentCoroutineContext()
        val backoff = CodecBackoff()
        return try {
            val uri = Uri.parse(audioUri)
            val windows = windowStartsMs(descriptor.durationMs).associateWith { startMs ->
                decoder.decodeWindowMono(
                    uri = uri,
                    startMs = startMs,
                    targetSampleRate = SAMPLE_RATE,
                    targetSamples = WINDOW_SAMPLES,
                    isCancelled = { !decodeContext.isActive },
                    backoff = backoff,
                )
            }
            Prefetched(windows, backoff)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // embedDecoded decodes the track itself, exactly as embed would, with what is left of the same allowance.
            Prefetched(emptyMap(), backoff)
        }
    }

    /**
     * [embed], with the windows [prefetched] already decoded where they succeeded or failed for good.
     * A window that failed for a reason that may pass (a codec not allocated while others ran) is
     * decoded again, alone, on a worker. Callers run this inside [DeadlineDecoding.withinBudget].
     */
    private suspend fun embedDecoded(
        descriptor: TrackDescriptor,
        prefetched: Prefetched?,
        lane: DeadlineDecoding,
        waits: LockWaits,
    ): Result<FloatArray> {
        val activeSession = session
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val audioUri = descriptor.audioUri
            ?: return invalidAudio("No audio URI for ${descriptor.id.value}")
        // What prefetching left of the track's codec back-off, or the whole allowance for a track decoded here.
        val backoff = prefetched?.backoff ?: CodecBackoff()

        return try {
            val uri = Uri.parse(audioUri)
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

            // One window's inference under the engine's lock, so a press waits for one window, never a chunk. The lock
            // is free between windows, when the engine may close the session: a closed session is never run.
            suspend fun inferWindow(run: () -> FloatArray?): FloatArray? {
                val lock = inferenceLock ?: return run()
                val asked = System.nanoTime()
                waits.since = asked
                fun stopWaiting() {
                    if (waits.since == 0L) return
                    waits.nanos += System.nanoTime() - asked
                    waits.since = 0L
                }
                return try {
                    lock.withLock {
                        stopWaiting()
                        if (session !== activeSession) throw SmartEngineException(EngineError.ModelUnavailable)
                        run()
                    }
                } finally {
                    // Cancelled (the budget ran out) while still waiting: the wait counts, and is over.
                    stopWaiting()
                }
            }

            suspend fun tryWindow(startMs: Long) {
                currentCoroutineContext().ensureActive()
                val ready = prefetched?.windows?.get(startMs)?.takeUnless { it is AudioDecodeResult.Unavailable }
                // On a worker, never here: a decode stuck in a platform call must not take the inference
                // thread with it (issue #27).
                when (val decoded = ready ?: lane.decode {
                    val worker = this
                    decoder.decodeWindowMono(
                        uri = uri,
                        startMs = startMs,
                        targetSampleRate = SAMPLE_RATE,
                        targetSamples = WINDOW_SAMPLES,
                        isCancelled = { !worker.isActive },
                        backoff = backoff,
                    )
                }) {
                    is AudioDecodeResult.Success -> {
                        currentCoroutineContext().ensureActive()
                        val embedding = inferWindow { inferStable(decoded.waveform, startMs) } ?: return
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
        } catch (closed: SmartEngineException) {
            Result.failure(closed)
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
            val environment = offlineOrtEnvironment()
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
        decoding.close()
        seedDecoding.close()
        // The process-global OrtEnvironment is deliberately left open.
    }

    private fun runWindow(session: OrtSession, waveform: FloatArray): FloatArray {
        val environment = offlineOrtEnvironment()
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
        const val SEMANTIC_VERSION_PATH = "ml/semantic_version.txt"
        const val EMBEDDING_VERSION_PATH = "ml/embedding_version.txt"
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
    single<RowStoreFactory> {
        val context = get<Context>()
        RowStoreFactory { dim, name -> MappedRowStore.forIndex(context, dim, name) }
    }
    single<EmbeddingBackend> { OnnxEmbeddingBackend(context = get(), config = get()) }
    // Overrides the common NoopIndexStore (this module is listed after
    // smartEngineModule; Koin last-definition-wins).
    // One audio-vector file per encoder (VersionedIndexStore): a test build on another encoder never wipes the
    // release build's vectors, and the other way round.
    single<IndexStore> { VersionedIndexStore("smart_index") { name -> FileIndexStore(context = get(), fileName = name, half = true) } }

    single<IndexStore>(smartTextIndexQualifier) {
        FileIndexStore(context = get(), fileName = "smart_text_index.bin")
    }

    // The two vector indexes keep their rows in mapped scratch files, off the Java heap (MappedRowStore); they
    // override the common heap-backed bindings the same way.
    single<VectorIndex> {
        val dim = get<SmartEngineConfig>().embeddingDim
        InMemoryVectorIndex(dim = dim, store = MappedRowStore.forIndex(get(), dim, "audio", half = true))
    }

    single<VectorIndex>(smartTextIndexQualifier) {
        val dim = io.github.nikitasud.latentjam.smart.text.TextEncoder.TEXT_DIM
        InMemoryVectorIndex(dim = dim, store = MappedRowStore.forIndex(get(), dim, "text"))
    }

    single<IndexStore>(smartLayoutQualifier) {
        FileIndexStore(context = get(), fileName = "map_layout.bin")
    }
    single<io.github.nikitasud.latentjam.smart.cluster.MapRefreshStateStore> {
        io.github.nikitasud.latentjam.smart.cluster.FileMapRefreshStateStore(get<android.content.Context>().filesDir)
    }
    single<io.github.nikitasud.latentjam.smart.cluster.RegionIdentityStore> {
        io.github.nikitasud.latentjam.smart.cluster.FileRegionIdentityStore(get<android.content.Context>().filesDir)
    }

    single<IndexStore>(smartImputationQualifier) {
        FileIndexStore(context = get(), fileName = "smart_imputation.bin")
    }
    single<IndexStore>(smartEnergyQualifier) {
        VersionedIndexStore("smart_energy") { name -> FileIndexStore(context = get(), fileName = name) }
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.IosBatchDotProducts
import io.github.nikitasud.latentjam.smart.chain.BatchDotProducts
import io.github.nikitasud.latentjam.smart.di.smartEnergyQualifier
import io.github.nikitasud.latentjam.smart.di.smartImputationQualifier
import io.github.nikitasud.latentjam.smart.di.smartLayoutQualifier
import io.github.nikitasud.latentjam.smart.di.smartTextIndexQualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS audio backend backed by the ONNX Runtime provider installed by the Swift host.
 *
 * The native call decodes with AVAudioFile, which has no deadline of its own: it runs on an I/O
 * thread and a track gets [TRACK_ANALYSIS_BUDGET], after which it fails as timed out and the
 * thread is left to finish (or never) on its own (issue #27). ORT sessions accept concurrent runs,
 * so a late return overlapping the next track is safe.
 */
internal class IosEmbeddingBackend(
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    private var lease: IosInferenceLease? = null

    private val decoding = DeadlineDecoding(elasticWorkers())

    /**
     * A SMART press's seed runs on a lane of its own: without a lock around the whole track it may run beside the
     * analysis track (docs/analysis-094-plan.md 1b-0).
     */
    private val seedDecoding = DeadlineDecoding(elasticWorkers())

    private fun elasticWorkers() = object : DecodeWorkers {
        // Elastic: a stuck thread is one of 64, and the next call simply gets another.
        override val dispatcher: CoroutineDispatcher = Dispatchers.IO
        override fun abandon() {}
    }

    override suspend fun loadModel(): Result<Unit> {
        val existingLease = lease?.takeIf { it.currentProvider() != null }
        if (existingLease == null) {
            lease?.release()
            lease = null
        }
        val activeLease = existingLease ?: IosInferenceRegistry.acquire()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val provider = activeLease.currentProvider() ?: run {
            if (existingLease == null) activeLease.release()
            return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        }
        val error = provider.loadAudio()
        return if (error == null) {
            lease = activeLease
            Result.success(Unit)
        } else {
            if (existingLease == null) activeLease.release()
            backendFailure(error)
        }
    }

    override suspend fun loadSemanticModel(): Result<Unit> {
        val existingLease = lease?.takeIf { it.currentProvider() != null }
        if (existingLease == null) {
            lease?.release()
            lease = null
        }
        val activeLease = existingLease ?: IosInferenceRegistry.acquire()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val provider = activeLease.currentProvider() ?: run {
            if (existingLease == null) activeLease.release()
            return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        }
        val error = provider.loadSemantic()
        return if (error == null) {
            lease = activeLease
            Result.success(Unit)
        } else {
            if (existingLease == null) activeLease.release()
            backendFailure(error)
        }
    }

    /** The engine's inference lock, taken by the native call per window through a [WindowGate] (see [takeInferenceLock]). */
    private var inferenceLock: Mutex? = null

    // Native code takes the lock around each window's inference, so the unit a press may wait for is one window.
    override fun takeInferenceLock(lock: Mutex): Boolean {
        inferenceLock = lock
        return true
    }

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> = embedTimed(descriptor, seedDecoding)

    override suspend fun embedEach(
        tracks: List<TrackDescriptor>,
        onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
    ) {
        for (track in tracks) onResult(track, embedTimed(track, decoding))
    }

    private suspend fun embedTimed(descriptor: TrackDescriptor, lane: DeadlineDecoding): Result<FloatArray> {
        // iOS suspends the app soon after it leaves the screen, while the deadline's clock keeps running: a
        // track timed out across that is analysed again, not remembered as timed out.
        val transitionsBefore = IosAppTransitions.count
        val gate = inferenceLock?.let(::WindowGate)
        val result = try {
            lane.withinBudget(waited = gate?.let { it::waited }) { embedNative(descriptor, gate, lane) }
        } finally {
            // A native call left running past its time stops at its next window instead of taking the lock again.
            gate?.stopped = true
        }
        val file = descriptor.id.value to descriptor.audioUri
        return forgivenLock.withLock {
            timeoutUnlessSuspended(
                result = result,
                budget = TRACK_ANALYSIS_BUDGET,
                transitionsBefore = transitionsBefore,
                transitionsAfter = IosAppTransitions.count,
                inBackgroundAfter = IosAppTransitions.inBackground,
                forgivenBefore = file in forgivenTimeouts,
            ).also { if (it.exceptionOrNull() !== result.exceptionOrNull()) forgivenTimeouts += file }
        }
    }

    /** Tracks (and files) whose timeout was let off as a suspension this run, under [forgivenLock]: both lanes use it. */
    private val forgivenTimeouts = HashSet<Pair<String, String?>>()
    private val forgivenLock = Mutex()

    private suspend fun embedNative(descriptor: TrackDescriptor, gate: WindowGate?, lane: DeadlineDecoding): Result<FloatArray> {
        // Read under the lock, which close() holds to change the lease.
        val held = if (gate != null) gate.locked { lease } else lease
        val provider = held?.currentProvider()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val uri = descriptor.audioUri
            ?: return invalidAudio("No audio URI")
        val durationMs = descriptor.durationMs ?: -1L
        // The lease the track started with: a release and re-initialisation meanwhile is not the same model.
        gate?.current = { lease === held && held.currentProvider() === provider }
        val native = lane.decode { provider.embedAudio(uri, durationMs, config.embeddingDim, gate) }
        // Closed between two windows: not this file's failure.
        if (gate?.closed == true) return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val output = when (native.status) {
            IosAudioEmbeddingStatus.SUCCESS -> native.embedding
                ?: return backendFailure("Native audio provider returned success without output")
            IosAudioEmbeddingStatus.INVALID_AUDIO ->
                return invalidAudio(native.technicalDetail ?: "Audio is not decodable")
            IosAudioEmbeddingStatus.UNAVAILABLE ->
                return audioUnavailable(native.technicalDetail ?: "Audio is temporarily unavailable")
            IosAudioEmbeddingStatus.BACKEND_FAILURE ->
                return backendFailure(native.technicalDetail ?: "Audio inference failed")
        }
        if (output.size != config.embeddingDim || !output.all(Float::isFinite)) {
            return backendFailure(
                "Model produced an invalid embedding (size=${output.size}, " +
                    "expected=${config.embeddingDim})",
            )
        }
        return Result.success(output)
    }

    override suspend fun classify(embeddings: List<FloatArray>): Result<List<FloatArray>> {
        if (embeddings.isEmpty()) return Result.success(emptyList())
        if (embeddings.any { it.size != config.embeddingDim || !it.all(Float::isFinite) }) {
            return backendFailure("Semantic head received an invalid embedding batch")
        }
        val provider = lease?.currentProvider()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        provider.loadSemantic()?.let { return backendFailure(it) }
        val flattened = FloatArray(embeddings.size * config.embeddingDim)
        for (row in embeddings.indices) {
            embeddings[row].copyInto(flattened, destinationOffset = row * config.embeddingDim)
        }
        val output = provider.classifySemantics(
            embeddings = flattened,
            batchSize = embeddings.size,
            inputDim = config.embeddingDim,
            outputDim = TrackSemantics.OUTPUT_SIZE,
        ) ?: return backendFailure("Semantic classification failed")
        if (
            output.size != embeddings.size * TrackSemantics.OUTPUT_SIZE ||
            !output.all(Float::isFinite)
        ) {
            return backendFailure("Semantic head produced an invalid output")
        }
        return Result.success(
            List(embeddings.size) { row ->
                output.copyOfRange(
                    row * TrackSemantics.OUTPUT_SIZE,
                    (row + 1) * TrackSemantics.OUTPUT_SIZE,
                )
            },
        )
    }

    override fun close(): Unit {
        lease?.release()
        lease = null
    }

    /**
     * The engine's inference lock for one native call, taken on its own (blocked) thread around each window. Refuses
     * once [stopped] (the track's result was already given) or once the provider [current] checks for was closed.
     */
    private class WindowGate(val lock: Mutex) : IosInferenceGate {
        @Volatile var stopped = false
        @Volatile var closed = false
        @Volatile var current: () -> Boolean = { true }
        @Volatile private var waitedNanos = 0L
        @Volatile private var waitingSince: TimeMark? = null

        /** Time spent waiting for the lock so far, a wait still in progress included (see [DeadlineDecoding.withinBudget]). */
        fun waited(): Duration = waitedNanos.nanoseconds + (waitingSince?.elapsedNow() ?: Duration.ZERO)

        /** [block] under the lock from a coroutine, its wait counted like a window's. */
        suspend fun <T> locked(block: () -> T): T {
            val asked = TimeSource.Monotonic.markNow()
            waitingSince = asked
            try {
                lock.lock()
            } finally {
                // Cleared first: a read in between undercounts for a moment rather than counting the wait twice.
                waitingSince = null
                waitedNanos += asked.elapsedNow().inWholeNanoseconds
            }
            return try { block() } finally { lock.unlock() }
        }

        override fun enter(): Boolean {
            if (stopped) return false
            val asked = TimeSource.Monotonic.markNow()
            waitingSince = asked
            try {
                runBlocking { lock.lock() }
            } finally {
                // Cleared first: a read in between undercounts for a moment rather than counting the wait twice.
                waitingSince = null
                waitedNanos += asked.elapsedNow().inWholeNanoseconds
            }
            val proceed = try {
                when {
                    stopped -> false
                    !current() -> {
                        closed = true
                        false
                    }
                    else -> true
                }
            } catch (failure: Throwable) {
                // Never leave the lock held, nor let a Kotlin exception cross into Swift.
                false
            }
            if (!proceed) lock.unlock()
            return proceed
        }

        override fun exit() {
            lock.unlock()
        }
    }

    private fun <T> backendFailure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))

    private fun <T> audioUnavailable(detail: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.AudioUnavailable(detail)))

    private fun <T> invalidAudio(detail: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.InvalidAudio(detail)))
}

public actual fun smartEngineBackendModule(): Module = module {
    // Observed from app start, on the main thread, so a move to the background before the first analysis counts.
    IosAppTransitions.count
    single<BatchDotProducts> { IosBatchDotProducts }
    single<EmbeddingBackend> { IosEmbeddingBackend(config = get()) }
    single<IndexStore> { VersionedIndexStore("smart_index") { name -> IosFileIndexStore(fileName = name, half = true) } }
    single<IndexStore>(smartTextIndexQualifier) {
        IosFileIndexStore(fileName = "smart_text_index.bin")
    }
    single<IndexStore>(smartLayoutQualifier) {
        IosFileIndexStore(fileName = "map_layout.bin")
    }
    single<io.github.nikitasud.latentjam.smart.cluster.MapRefreshStateStore> {
        io.github.nikitasud.latentjam.smart.cluster.IosMapRefreshStateStore()
    }
    single<io.github.nikitasud.latentjam.smart.cluster.RegionIdentityStore> {
        io.github.nikitasud.latentjam.smart.cluster.IosRegionIdentityStore()
    }
    single<IndexStore>(smartImputationQualifier) {
        IosFileIndexStore(fileName = "smart_imputation.bin")
    }
    single<IndexStore>(smartEnergyQualifier) {
        VersionedIndexStore("smart_energy") { name -> IosFileIndexStore(fileName = name) }
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long one track may take from the moment the inference side starts waiting on it to its result
 * (issue #27). Android decodes a track as up to three 10 s windows, each one bounded by the decode
 * loop's own 30 s wall budget (AndroidAudioDecoder), so 3 × 30 s = 90 s is the longest a track whose
 * windows can still succeed spends decoding them one after another. Measured tracks take 0.3–1.8 s.
 * Beyond it something sits in a platform call the loop guard never sees (opening the file, the
 * extractor, a seek, a codec start or release) and the track is given up.
 */
internal val TRACK_ANALYSIS_BUDGET: Duration = 90.seconds

/** How long decoding carried to the next chunk waits for it: the gap between chunks is about a second. */
internal val CARRY_FOR: Duration = 10.seconds

/** Threads for blocking platform decoding, so the inference thread itself never enters a call that can hang. */
internal interface DecodeWorkers {
    /** Where the next piece of decoding starts. */
    val dispatcher: CoroutineDispatcher

    /**
     * A worker is stuck in a call that ignores cancellation: send later work to fresh threads, so one
     * stuck worker never shrinks the pool for the rest of the library.
     */
    fun abandon()

    fun close() {}
}

/**
 * D1 (docs/analysis-094-plan.md §0): a timeout is transient, not remembered. Its cause may pass (a slow card, a busy
 * codec); the app's retry ledger stops a file that keeps timing out after three tries (D2).
 */
internal fun analysisTimedOut(budget: Duration): EngineError.AudioUnavailable =
    EngineError.AudioUnavailable("$ANALYSIS_TIMED_OUT ${budget.inWholeSeconds} s")

private const val ANALYSIS_TIMED_OUT = "Analysis timed out after"

/** Whether this failure is a track's analysis running out of its budget. */
public fun EngineError.isAnalysisTimeout(): Boolean =
    this is EngineError.AudioUnavailable && technicalDetail.orEmpty().startsWith(ANALYSIS_TIMED_OUT)

/**
 * [result], except that a track timed out after [budget] while the app was suspended becomes the retryable
 * [EngineError.AudioUnavailable]: its clock kept running while the track itself did not, so it is analysed again
 * by the next pass and is no attempt in the app's retry ledger. The app counts as suspended during the track when it
 * changed between foreground and background since the track started ([transitionsBefore] to [transitionsAfter]),
 * or when it is still in the background as the timeout fires ([inBackgroundAfter]). An app playing music in the
 * background is not suspended, so that is only a likely sign: a track already let off once ([forgivenBefore]) is
 * reported as timed out (an attempt in the retry ledger), so a track that really hangs costs at most one more wait. Any other result is unchanged.
 */
internal fun timeoutUnlessSuspended(
    result: Result<FloatArray>,
    budget: Duration,
    transitionsBefore: Int,
    transitionsAfter: Int,
    inBackgroundAfter: Boolean,
    forgivenBefore: Boolean = false,
): Result<FloatArray> {
    val timedOut = (result.exceptionOrNull() as? SmartEngineException)?.error == analysisTimedOut(budget)
    val suspended = transitionsAfter != transitionsBefore || inBackgroundAfter
    if (!timedOut || !suspended || forgivenBefore) return result
    return Result.failure(SmartEngineException(EngineError.AudioUnavailable("Analysis interrupted by the app being suspended")))
}

/**
 * Runs a backend's blocking decoding on [workers] and gives every track at most [budget].
 *
 * Decoding is started in a scope of its own, never as a child of the caller: a worker stuck in native
 * code is cancelled and abandoned, never joined, so it cannot hold the batch (and the engine's
 * inference lock) with it.
 */
internal class DeadlineDecoding(
    private val workers: DecodeWorkers,
    private val budget: Duration = TRACK_ANALYSIS_BUDGET,
) {
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * Decoding started for the next [each] call's tracks while the last one finished its own: the decoders keep working
     * while the engine installs and checkpoints between chunks. Dropped when the next call does not ask for them, or
     * when no call comes within [CARRY_FOR] (a wait, or the end of the pass), so decoded audio is never kept around.
     */
    private var carried: List<Pair<TrackDescriptor, Deferred<*>>> = emptyList()

    /** The pool replacements so far: carried work started on a pool that was replaced since never runs, so it is dropped. */
    @kotlin.concurrent.Volatile
    private var poolGeneration = 0
    private var carriedGeneration = 0

    /** Work was dropped that may sit in a stuck call: the next work starts on fresh workers. */
    @kotlin.concurrent.Volatile
    private var staleWorkDropped = false

    private fun abandonWorkers() {
        poolGeneration++
        staleWorkDropped = false
        workers.abandon()
    }

    /** Replaces the workers first when dropped work might still hold one, so new work never queues behind it. */
    private fun freshWorkersIfStale() {
        if (staleWorkDropped) abandonWorkers()
    }
    private var carryExpiry: Job? = null
    private val carryLock = Mutex()

    /** Starts [block] on a worker. */
    fun <T> start(block: suspend CoroutineScope.() -> T): Deferred<T> =
        scope.async(workers.dispatcher, block = block)

    /**
     * [work]'s value; when the waiting is cancelled (the track's deadline, or the pass itself), [work] is
     * cancelled too. Unfinished work may sit in a call that ignores cancellation, so when the pass itself is
     * cancelled the workers are replaced (the budget's own timeout replaces them in [withinBudget]): the next
     * pass must not queue behind a stuck thread.
     */
    suspend fun <T> await(work: Deferred<T>): T = try {
        work.await()
    } catch (cancellation: CancellationException) {
        work.cancel()
        if (!work.isCompleted && cancellation !is TimeoutCancellationException) abandonWorkers()
        throw cancellation
    }

    /** [block] on a worker, awaited. */
    suspend fun <T> decode(block: suspend CoroutineScope.() -> T): T = await(start(block))

    /**
     * [analyse]'s result, or [analysisTimedOut] once [budget] passes; the workers are then replaced. A track that
     * spent a tenth of the budget or more waiting for the inference lock ([waited], a running total that includes a
     * wait still in progress: a press's windows, a model load)
     * is not the file's fault: it fails as plainly unavailable, retried at the next pass and never a D2 attempt.
     */
    suspend fun withinBudget(
        waited: (() -> Duration)? = null,
        analyse: suspend () -> Result<FloatArray>,
    ): Result<FloatArray> {
        freshWorkersIfStale()
        // [waited] is a running total: only what this track added counts.
        val waitedBefore = waited?.invoke() ?: Duration.ZERO
        withTimeoutOrNull(budget) { analyse() }?.let { return it }
        abandonWorkers()
        val lockWait = (waited?.invoke() ?: Duration.ZERO) - waitedBefore
        if (lockWait >= budget / 10) {
            return Result.failure(
                SmartEngineException(EngineError.AudioUnavailable("Analysis waited ${lockWait.inWholeSeconds} s for the model")),
            )
        }
        return Result.failure(SmartEngineException(analysisTimedOut(budget)))
    }

    /**
     * [EmbeddingBackend.embedEach] for a backend that decodes ahead: up to [ahead] + 1 tracks are
     * [prefetch]ed on the workers while [finish] turns the oldest one into its result, in order. A
     * track that overruns [budget] fails as timed out; the tracks decoding behind it are started again
     * on fresh workers, so a pool whose only thread is stuck never strands them.
     */
    suspend fun <D> each(
        tracks: List<TrackDescriptor>,
        ahead: Int,
        prefetch: suspend (TrackDescriptor) -> D,
        finish: suspend (TrackDescriptor, D) -> Result<FloatArray>,
        upcoming: List<TrackDescriptor> = emptyList(),
        waited: (() -> Duration)? = null,
        onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
    ) {
        val queue = ArrayDeque<Pair<TrackDescriptor, Deferred<D>>>()
        // What the last call started for this one, by descriptor; anything else it started is dropped now.
        val reusable = LinkedHashMap<TrackDescriptor, Deferred<D>>()
        val (handedOver, generation) = takeCarriedWithGeneration()
        // Work started on a pool replaced since (a timeout or a cancellation) may never run: decode afresh.
        val usable = generation == poolGeneration
        for ((track, work) in handedOver) {
            @Suppress("UNCHECKED_CAST")
            if (usable && track in tracks && track !in reusable) {
                reusable[track] = work as Deferred<D>
            } else {
                if (!work.isCompleted) staleWorkDropped = true
                work.cancel()
            }
        }
        freshWorkersIfStale()
        var next = 0
        val ahead2 = ArrayDeque<Pair<TrackDescriptor, Deferred<D>>>()
        var nextUpcoming = 0
        fun refill() {
            while (next < tracks.size && queue.size <= ahead) {
                val track = tracks[next++]
                queue.addLast(track to (reusable.remove(track) ?: start { prefetch(track) }))
            }
            // The pool stays as busy as it was: once this call's tracks are all started, the next call's start.
            while (next >= tracks.size && nextUpcoming < upcoming.size && queue.size + ahead2.size <= ahead) {
                val track = upcoming[nextUpcoming++]
                if (track in tracks) continue
                ahead2.addLast(track to start { prefetch(track) })
            }
        }
        try {
            refill()
            while (queue.isNotEmpty()) {
                val (track, decoding) = queue.removeFirst()
                var finished = false
                val generationBefore = poolGeneration
                val result = withinBudget(waited) {
                    val decoded = await(decoding)
                    refill()
                    finish(track, decoded).also { finished = true }
                }
                // The workers were replaced (a timeout, also one that fired as the inference finished): restart.
                if (!finished || poolGeneration != generationBefore) {
                    // The workers were replaced: what is still decoding may sit behind the stuck one.
                    val pending = queue.toList()
                    queue.clear()
                    for ((later, work) in pending) {
                        if (work.isCompleted) {
                            queue.addLast(later to work)
                        } else {
                            work.cancel()
                            queue.addLast(later to start { prefetch(later) })
                        }
                    }
                    // So may what was started for later: started again on the fresh workers when it is reached.
                    for ((later, work) in reusable.entries.toList()) {
                        if (!work.isCompleted) {
                            work.cancel()
                            reusable.remove(later)
                        }
                    }
                    val upcomingStarted = ahead2.toList()
                    ahead2.clear()
                    for ((later, work) in upcomingStarted) {
                        if (work.isCompleted) ahead2.addLast(later to work) else work.cancel()
                    }
                    refill()
                }
                onResult(track, result)
            }
            // Finished normally: the next call's decoding carries over.
            val handOver = ahead2.toList()
            ahead2.clear()
            if (handOver.isNotEmpty()) {
                carryLock.withLock {
                    carried = handOver
                    carriedGeneration = poolGeneration
                    carryExpiry = scope.launch {
                        delay(CARRY_FOR)
                        dropCarried()
                    }
                }
            }
        } finally {
            // Decoding left behind by a cancelled pass may be stuck; later passes start on fresh workers.
            val left = queue + ahead2 + reusable.map { (track, work) -> track to work }
            if (left.any { (_, work) -> !work.isCompleted }) abandonWorkers()
            for ((_, work) in left) work.cancel()
        }
    }

    /** Cancels decoding carried for a call that did not come. */
    suspend fun dropCarried() {
        carryLock.withLock {
            carryExpiry?.cancel()
            carryExpiry = null
            for ((_, work) in carried) {
                // Not replaced here (that would race new work on the pool): the next work starts on fresh workers.
                // Marked under the lock, so a call that takes the hand-over after this sees the mark first.
                if (!work.isCompleted) staleWorkDropped = true
                work.cancel()
            }
            carried = emptyList()
        }
    }

    private suspend fun takeCarriedWithGeneration(): Pair<List<Pair<TrackDescriptor, Deferred<*>>>, Int> =
        carryLock.withLock {
            carryExpiry?.cancel()
            carryExpiry = null
            (carried to carriedGeneration).also { carried = emptyList() }
        }

    fun close() {
        workers.close()
    }
}

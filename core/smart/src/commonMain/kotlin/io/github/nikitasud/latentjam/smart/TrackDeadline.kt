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
import kotlinx.coroutines.async
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

/** The failure a timed-out track gets: remembered while the file is unchanged, listed with a Retry. */
internal fun analysisTimedOut(budget: Duration): EngineError.InvalidAudio =
    EngineError.InvalidAudio("Analysis timed out after ${budget.inWholeSeconds} s")

/**
 * [result], except that a track timed out after [budget] while the app was suspended becomes the retryable
 * [EngineError.AudioUnavailable]: its clock kept running while the track itself did not, so it is analysed again
 * by the next pass instead of being remembered as timed out. The app counts as suspended during the track when it
 * changed between foreground and background since the track started ([transitionsBefore] to [transitionsAfter]),
 * or when it is still in the background as the timeout fires ([inBackgroundAfter]). An app playing music in the
 * background is not suspended, so that is only a likely sign: a track already let off once ([forgivenBefore]) is
 * remembered as timed out, so a track that really hangs costs at most one more wait. Any other result is unchanged.
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
        if (!work.isCompleted && cancellation !is TimeoutCancellationException) workers.abandon()
        throw cancellation
    }

    /** [block] on a worker, awaited. */
    suspend fun <T> decode(block: suspend CoroutineScope.() -> T): T = await(start(block))

    /** [analyse]'s result, or [analysisTimedOut] once [budget] passes; the workers are then replaced. */
    suspend fun withinBudget(analyse: suspend () -> Result<FloatArray>): Result<FloatArray> {
        withTimeoutOrNull(budget) { analyse() }?.let { return it }
        workers.abandon()
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
        onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
    ) {
        val queue = ArrayDeque<Pair<TrackDescriptor, Deferred<D>>>()
        var next = 0
        fun refill() {
            while (next < tracks.size && queue.size <= ahead) {
                val track = tracks[next++]
                queue.addLast(track to start { prefetch(track) })
            }
        }
        try {
            refill()
            while (queue.isNotEmpty()) {
                val (track, decoding) = queue.removeFirst()
                var finished = false
                val result = withinBudget {
                    val decoded = await(decoding)
                    refill()
                    finish(track, decoded).also { finished = true }
                }
                if (!finished) {
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
                    refill()
                }
                onResult(track, result)
            }
        } finally {
            // Decoding left behind by a cancelled pass may be stuck; later passes start on fresh workers.
            if (queue.any { (_, work) -> !work.isCompleted }) workers.abandon()
            for ((_, work) in queue) work.cancel()
        }
    }

    fun close() {
        workers.close()
    }
}

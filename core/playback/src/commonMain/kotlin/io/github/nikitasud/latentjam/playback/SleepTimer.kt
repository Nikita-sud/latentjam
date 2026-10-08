/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** User-visible sleep-timer state. The countdown value is intentionally minute-granular. */
public sealed interface SleepTimerState {
    public data object Off : SleepTimerState
    public data class Countdown(public val remainingMinutes: Int) : SleepTimerState
    public data object EndOfTrack : SleepTimerState
}

/**
 * App-lifetime, local sleep timer.
 *
 * It owns no platform player and calls [PlaybackController.pause], so expiry can never accidentally
 * start an already-paused queue. Replacing or cancelling a timer is immediate and idempotent.
 */
public class SleepTimerController(
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
) {
    private val mutableState = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    public val state: StateFlow<SleepTimerState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Stops playback after [minutes] of wall-clock time. Non-positive values cancel the timer. */
    public fun startCountdown(minutes: Int) {
        if (minutes <= 0) {
            cancel()
            return
        }
        replace {
            val deadline = nowMillis() + minutes.toLong() * MILLIS_PER_MINUTE
            while (true) {
                val remainingMs = deadline - nowMillis()
                if (remainingMs <= 0L) break
                mutableState.value = SleepTimerState.Countdown(
                    remainingMinutes = ((remainingMs + MILLIS_PER_MINUTE - 1L) /
                        MILLIS_PER_MINUTE).toInt(),
                )
                delay(minOf(remainingMs, COUNTDOWN_REFRESH_MS))
            }
            playback.pause()
            mutableState.value = SleepTimerState.Off
        }
    }

    /**
     * Stops when the currently selected queue entry finishes or is skipped.
     *
     * The timer follows the queue *row*, not its position: editing the queue elsewhere (removing
     * or moving a row above the playhead) or changing the shuffle order leaves the listener on the
     * same entry and must not stop playback. Only when the queue holds the same track twice does
     * the id stop being the row's identity, and then which of the track's rows is playing — how many
     * of them come before the playhead — is the only one the snapshot exposes. Edits to other rows
     * leave that count alone, wherever they move the playhead's index. Ambiguity is read from every
     * state rather than once: Play next on the playing track queues it again after the timer was
     * set, and the playhead moving on to that copy is still the end of this row.
     *
     * Neither is moving the playhead inside the row an end of it: a manual seek back, Previous
     * restarting the track, or tapping the row again all land at the start of a row that never
     * finished, and only a repeat-one wrap means the track was played out — see the wrap check.
     */
    public fun startAtEndOfTrack() {
        val initial = playback.state.value
        val trackId = initial.track?.id ?: return
        replace {
            mutableState.value = SleepTimerState.EndOfTrack
            var rowOrdinal = initial.rowsOfTrackBeforePlayhead(trackId)
            var hasPlayed = initial.isPlaying
            var previousPositionMs = initial.positionMs
            var previousDurationMs = initial.durationMs
            var previousStart = initial.playbackStart
            playback.state.first { snapshot ->
                if (snapshot.isPlaying) hasPlayed = true
                // Only a duplicated id needs the row's place among the track's rows to tell them
                // apart. Everywhere else the id is the row's identity and its place is the first,
                // which is what a copy queued later is compared against.
                val ambiguousRow = snapshot.queue.count { it.id == trackId } > 1
                val ordinal = snapshot.rowsOfTrackBeforePlayhead(trackId)
                val movedAway = snapshot.track?.id != trackId ||
                    (ambiguousRow && ordinal != rowOrdinal)
                if (!ambiguousRow) rowOrdinal = ordinal
                val naturallyEnded = hasPlayed &&
                    !snapshot.isPlaying &&
                    snapshot.durationMs > 0L &&
                    snapshot.positionMs >= (snapshot.durationMs - END_TOLERANCE_MS).coerceAtLeast(0L)
                // Repeat-one can jump directly from the end back to zero without ever publishing
                // a paused state or changing queue identity. Remember the previous ticker sample
                // so "end of track" still means one play, not an infinite loop. That jump is also
                // what a manual seek back or a Previous restart inside the last seconds looks
                // like, so the wrap counts only when it is a repeat of the row and not the same
                // instance being scrubbed — see [isRepeatOfRow].
                val wrappedToStart = snapshot.repeatMode == RepeatMode.ONE &&
                    snapshot.positionMs <= END_TOLERANCE_MS &&
                    previousDurationMs > 0L &&
                    previousPositionMs >=
                    (previousDurationMs - END_TOLERANCE_MS).coerceAtLeast(0L) &&
                    snapshot.positionMs + END_TOLERANCE_MS < previousPositionMs
                val repeatedFromEnd = hasPlayed && wrappedToStart &&
                    isRepeatOfRow(previousStart, snapshot.playbackStart)
                previousPositionMs = snapshot.positionMs
                previousDurationMs = snapshot.durationMs
                previousStart = snapshot.playbackStart
                movedAway || naturallyEnded || repeatedFromEnd
            }
            playback.pause()
            mutableState.value = SleepTimerState.Off
        }
    }

    public fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = SleepTimerState.Off
    }

    /**
     * Whether [current] is the row playing out again rather than the same instance being moved.
     *
     * Repeat-one begins a new playback instance of the row and the controller books it as
     * [StartCause.REPEAT]; a seek back or a Previous restart only moves the playhead inside the
     * instance that is already playing and books nothing, and tapping the row again books
     * [StartCause.USER_PICK]. An instance that already repeated once keeps that cause, which is why
     * the [PlaybackStart.sequence] has to have grown as well. A state that publishes no start at all
     * (no ledger behind it) leaves the position wrap as the only signal there is.
     */
    private fun isRepeatOfRow(previous: PlaybackStart?, current: PlaybackStart?): Boolean = when {
        current == null -> true
        current.cause != StartCause.REPEAT -> false
        else -> current.sequence != previous?.sequence
    }

    /** How many rows of [trackId] come before the playhead: which of the track's rows is playing. */
    private fun NowPlaying.rowsOfTrackBeforePlayhead(trackId: TrackId): Int =
        queue.subList(0, queueIndex.coerceIn(0, queue.size)).count { it.id == trackId }

    private fun replace(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val COUNTDOWN_REFRESH_MS = 15_000L
        const val END_TOLERANCE_MS = 1_500L
    }
}

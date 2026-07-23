/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

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

    /** Stops when the currently selected queue entry finishes or is skipped. */
    public fun startAtEndOfTrack() {
        val initial = playback.state.value
        val trackId = initial.track?.id ?: return
        val queueIndex = initial.queueIndex
        replace {
            mutableState.value = SleepTimerState.EndOfTrack
            var hasPlayed = initial.isPlaying
            var previousPositionMs = initial.positionMs
            var previousDurationMs = initial.durationMs
            playback.state.first { snapshot ->
                if (snapshot.isPlaying) hasPlayed = true
                val movedAway = snapshot.track?.id != trackId || snapshot.queueIndex != queueIndex
                val naturallyEnded = hasPlayed &&
                    !snapshot.isPlaying &&
                    snapshot.durationMs > 0L &&
                    snapshot.positionMs >= (snapshot.durationMs - END_TOLERANCE_MS).coerceAtLeast(0L)
                // Repeat-one can jump directly from the end back to zero without ever publishing
                // a paused state or changing queue identity. Remember the previous ticker sample
                // so "end of track" still means one play, not an infinite loop.
                val repeatedFromEnd = hasPlayed &&
                    snapshot.repeatMode == RepeatMode.ONE &&
                    previousDurationMs > 0L &&
                    previousPositionMs >=
                    (previousDurationMs - END_TOLERANCE_MS).coerceAtLeast(0L) &&
                    snapshot.positionMs + END_TOLERANCE_MS < previousPositionMs
                previousPositionMs = snapshot.positionMs
                previousDurationMs = snapshot.durationMs
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

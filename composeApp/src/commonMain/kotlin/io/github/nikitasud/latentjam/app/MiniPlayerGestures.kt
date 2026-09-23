/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PREVIOUS_RESTART_THRESHOLD_MS
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Physical left advances; physical right goes back. The pill follows just enough to acknowledge
 * the drag, with one threshold pulse and a separate committed-action tick. Place before its
 * clip/background so the whole pill follows together.
 *
 * Entry keys cancel a drag when playback changes underneath it. Live state is read only while
 * handling the gesture: this modifier never subscribes to the playback position ticker.
 */
@Composable
internal fun Modifier.miniPlayerSwipe(
    playback: PlaybackController,
    trackId: TrackId?,
    queueIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier {
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember(playback, trackId, queueIndex) { mutableFloatStateOf(0f) }
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    return graphicsLayer {
        translationX = if (reduceMotion) 0f else offset.floatValue
    }.pointerInput(playback, trackId, queueIndex, reduceMotion, haptics) {
        val threshold = 56.dp.toPx()
        val maximumTravel = 24.dp.toPx()
        var travelled = 0f
        var thresholdAnnounced = false
        var entryStillValid = true
        var returnJob: Job? = null

        fun canSkip(): Boolean {
            val live = playback.state.value
            if (live.track?.id != trackId || live.queueIndex != queueIndex) entryStillValid = false
            if (!entryStillValid || live.track == null) return false
            return when {
                travelled < 0f -> live.queueIndex in 0 until live.queue.lastIndex ||
                    live.repeatMode == RepeatMode.ALL || live.shuffleMode == ShuffleMode.SMART
                travelled > 0f -> live.positionMs > PREVIOUS_RESTART_THRESHOLD_MS ||
                    live.queueIndex > 0 || live.repeatMode == RepeatMode.ALL
                else -> false
            }
        }

        fun settle() {
            returnJob?.cancel()
            val from = offset.floatValue
            if (reduceMotion || from == 0f) {
                offset.floatValue = 0f
            } else {
                returnJob = scope.launch {
                    animate(from, 0f, animationSpec = tween(Motion.QUICK_MS)) { value, _ ->
                        offset.floatValue = value
                    }
                }
            }
        }

        try {
            detectHorizontalDragGestures(
                onDragStart = {
                    returnJob?.cancel()
                    offset.floatValue = 0f
                    travelled = 0f
                    thresholdAnnounced = false
                    entryStillValid = true
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    travelled += amount
                    val allowed = canSkip()
                    if (!reduceMotion) {
                        // Direct layer state, with no coroutine or animation allocated per move.
                        offset.floatValue = if (entryStillValid) {
                            (travelled * if (allowed) 0.28f else 0.1f)
                                .coerceIn(-maximumTravel, maximumTravel)
                        } else {
                            0f
                        }
                    }
                    if (allowed && abs(travelled) >= threshold && !thresholdAnnounced) {
                        thresholdAnnounced = true
                        haptics.play(PlayerHaptic.THRESHOLD)
                    }
                },
                onDragEnd = {
                    // Eligibility can change while the finger is held still, so recheck on up.
                    val commit = abs(travelled) >= threshold && canSkip()
                    settle()
                    if (commit) {
                        haptics.play(PlayerHaptic.TAP)
                        if (travelled < 0f) currentOnNext() else currentOnPrevious()
                    }
                },
                onDragCancel = { settle() },
            )
        } finally {
            returnJob?.cancel()
            offset.floatValue = 0f
        }
    }
}

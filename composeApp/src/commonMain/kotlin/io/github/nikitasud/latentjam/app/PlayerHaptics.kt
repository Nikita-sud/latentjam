/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.math.abs

/**
 * What the player says through the vibration motor, named by meaning rather than by waveform.
 *
 * One table keeps every surface consistent: the same press feels the same everywhere, and a
 * platform that lacks a waveform degrades in one place instead of in every call site.
 */
internal enum class PlayerHaptic { TAP, HOLD, THRESHOLD, SUCCESS, REJECT, EDGE, SCRUB, RELEASE }

internal fun PlayerHaptic.feedbackType(): HapticFeedbackType = when (this) {
    PlayerHaptic.TAP -> HapticFeedbackType.KeyboardTap
    PlayerHaptic.HOLD -> HapticFeedbackType.LongPress
    PlayerHaptic.THRESHOLD -> HapticFeedbackType.GestureThresholdActivate
    PlayerHaptic.SUCCESS -> HapticFeedbackType.Confirm
    PlayerHaptic.REJECT -> HapticFeedbackType.Reject
    PlayerHaptic.EDGE -> HapticFeedbackType.SegmentTick
    PlayerHaptic.SCRUB -> HapticFeedbackType.SegmentFrequentTick
    PlayerHaptic.RELEASE -> HapticFeedbackType.GestureEnd
}

internal fun HapticFeedback.play(event: PlayerHaptic) = performHapticFeedback(event.feedbackType())

/** One gesture's distance/time gate: soft scrub ticks, without jitter or bursts on a fast swipe. */
internal class ScrubHapticState(
    durationMs: Long,
    initialPositionMs: Long,
    initialTimeMs: Long,
) {
    private val duration = durationMs.coerceAtLeast(1L)
    private val stepMs = (duration / 50L).coerceAtLeast(1L)
    private var previousPosition = initialPositionMs.coerceIn(0L, duration)
    private var lastTickPosition = previousPosition
    private var lastTickTime = initialTimeMs

    fun moveTo(positionMs: Long, timeMs: Long): PlayerHaptic? {
        val position = positionMs.coerceIn(0L, duration)
        val enteredEdge = position != previousPosition && (position == 0L || position == duration)
        previousPosition = position
        if (!enteredEdge && (
                abs(position - lastTickPosition) < stepMs || timeMs - lastTickTime < 100L
            )
        ) return null
        lastTickPosition = position
        lastTickTime = timeMs
        return if (enteredEdge) PlayerHaptic.EDGE else PlayerHaptic.SCRUB
    }
}

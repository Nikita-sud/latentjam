/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * What the player says through the vibration motor, named by meaning rather than by waveform.
 *
 * One table keeps every surface consistent: the same press feels the same everywhere, and a
 * platform that lacks a waveform degrades in one place instead of in every call site.
 */
internal enum class PlayerHaptic { TAP, HOLD, THRESHOLD, SUCCESS, REJECT, EDGE, RELEASE }

internal fun PlayerHaptic.feedbackType(): HapticFeedbackType = when (this) {
    PlayerHaptic.TAP -> HapticFeedbackType.KeyboardTap
    PlayerHaptic.HOLD -> HapticFeedbackType.LongPress
    PlayerHaptic.THRESHOLD -> HapticFeedbackType.GestureThresholdActivate
    PlayerHaptic.SUCCESS -> HapticFeedbackType.Confirm
    PlayerHaptic.REJECT -> HapticFeedbackType.Reject
    PlayerHaptic.EDGE -> HapticFeedbackType.SegmentTick
    PlayerHaptic.RELEASE -> HapticFeedbackType.GestureEnd
}

internal fun HapticFeedback.play(event: PlayerHaptic) = performHapticFeedback(event.feedbackType())

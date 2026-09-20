/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerHapticsTest {
    @Test
    fun everyEventMapsToTheIntendedSystemType() {
        assertEquals(HapticFeedbackType.KeyboardTap, PlayerHaptic.TAP.feedbackType())
        assertEquals(HapticFeedbackType.LongPress, PlayerHaptic.HOLD.feedbackType())
        assertEquals(
            HapticFeedbackType.GestureThresholdActivate,
            PlayerHaptic.THRESHOLD.feedbackType(),
        )
        assertEquals(HapticFeedbackType.Confirm, PlayerHaptic.SUCCESS.feedbackType())
        assertEquals(HapticFeedbackType.Reject, PlayerHaptic.REJECT.feedbackType())
        assertEquals(HapticFeedbackType.SegmentTick, PlayerHaptic.EDGE.feedbackType())
        assertEquals(HapticFeedbackType.GestureEnd, PlayerHaptic.RELEASE.feedbackType())
    }
}

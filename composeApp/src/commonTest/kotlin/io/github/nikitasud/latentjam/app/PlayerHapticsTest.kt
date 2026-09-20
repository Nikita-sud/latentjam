/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals(HapticFeedbackType.SegmentFrequentTick, PlayerHaptic.SCRUB.feedbackType())
        assertEquals(HapticFeedbackType.GestureEnd, PlayerHaptic.RELEASE.feedbackType())
    }

    @Test
    fun holdingStillAndSmallFingerJitterDoNotVibrate() {
        val scrub = ScrubHapticState(100_000L, 50_000L, 0L)
        assertNull(scrub.moveTo(50_000L, 1_000L))
        assertNull(scrub.moveTo(50_900L, 2_000L))
        assertNull(scrub.moveTo(49_100L, 3_000L))
        assertNull(scrub.moveTo(50_000L, 4_000L))
    }

    @Test
    fun movementAccumulatesButRapidEventsCannotCreateABuzz() {
        val scrub = ScrubHapticState(100_000L, 20_000L, 0L)
        assertNull(scrub.moveTo(21_000L, 100L))
        assertEquals(PlayerHaptic.SCRUB, scrub.moveTo(22_000L, 110L))
        assertNull(scrub.moveTo(40_000L, 150L))
        assertEquals(PlayerHaptic.SCRUB, scrub.moveTo(60_000L, 210L))
        assertNull(scrub.moveTo(60_000L, 1_000L))
    }

    @Test
    fun ScrubbingBackwardsAlsoProducesSoftTicks() {
        val scrub = ScrubHapticState(100_000L, 50_000L, 0L)
        assertEquals(PlayerHaptic.SCRUB, scrub.moveTo(48_000L, 100L))
        assertNull(scrub.moveTo(49_000L, 200L))
        assertEquals(PlayerHaptic.SCRUB, scrub.moveTo(50_000L, 300L))
    }

    @Test
    fun ReachingAnEdgeOverridesTheSoftTickAndDoesNotRepeatWhileClamped() {
        val scrub = ScrubHapticState(100_000L, 97_000L, 0L)
        assertEquals(PlayerHaptic.SCRUB, scrub.moveTo(99_000L, 100L))
        assertEquals(PlayerHaptic.EDGE, scrub.moveTo(100_000L, 110L))
        assertNull(scrub.moveTo(105_000L, 250L))
        assertNull(scrub.moveTo(99_000L, 300L))
        assertEquals(PlayerHaptic.EDGE, scrub.moveTo(100_000L, 310L))
    }

    @Test
    fun NewGestureDoesNotInheritThePreviousGesturesTick() {
        val first = ScrubHapticState(100_000L, 20_000L, 0L)
        assertEquals(PlayerHaptic.SCRUB, first.moveTo(22_000L, 100L))
        val next = ScrubHapticState(100_000L, 22_000L, 110L)
        assertNull(next.moveTo(24_000L, 150L))
        assertEquals(PlayerHaptic.SCRUB, next.moveTo(24_000L, 210L))
    }
}

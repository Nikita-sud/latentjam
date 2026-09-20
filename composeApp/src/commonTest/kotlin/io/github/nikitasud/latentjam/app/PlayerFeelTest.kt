/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFeelTest {
    @Test
    fun dragAxisIsUndecidedInsideTheSlopAndVerticalOnlyDownwards() {
        assertNull(artworkDragAxis(dx = 5f, dy = 5f, slop = 12f))
        assertEquals(ArtworkDragAxis.HORIZONTAL, artworkDragAxis(dx = -30f, dy = 4f, slop = 12f))
        assertEquals(ArtworkDragAxis.VERTICAL, artworkDragAxis(dx = 3f, dy = 40f, slop = 12f))
        // Upward movement cancels the gesture, so releasing cannot flip or open actions.
        assertEquals(ArtworkDragAxis.CANCELLED, artworkDragAxis(dx = 3f, dy = -40f, slop = 12f))
        assertEquals(ArtworkDragAxis.HORIZONTAL, artworkDragAxis(dx = 50f, dy = -40f, slop = 12f))
    }

    @Test
    fun swipeFollowsTheFingerAndRubberBandsAtTheEnds() {
        assertEquals(92f, swipeShown(dx = 100f, blocked = false), absoluteTolerance = 0.001f)
        assertEquals(30f, swipeShown(dx = 100f, blocked = true), absoluteTolerance = 0.001f)
        assertTrue(swipeCommits(dx = -120f, width = 342f, blocked = false))
        assertFalse(swipeCommits(dx = -90f, width = 342f, blocked = false))
        assertFalse(swipeCommits(dx = -300f, width = 342f, blocked = true))
    }

    @Test
    fun collapseResistsAndCommitsPastTheThreshold() {
        assertEquals(0f, collapseShown(dy = -50f))
        assertEquals(75f, collapseShown(dy = 100f))
        assertTrue(collapseCommits(dy = 141f, threshold = 140f))
        assertFalse(collapseCommits(dy = 140f, threshold = 140f))
    }

    @Test
    fun neighbourRevealIsContinuousDirectionalAndReversible() {
        val outward = listOf(0f, -6f, -30f, -60f, -96f).map {
            artworkNeighbourReveal(it, width = 400f, forward = true)
        }
        assertEquals(0f, outward.first())
        assertTrue(outward.zipWithNext().all { (a, b) -> a < b })
        assertEquals(1f, outward.last())
        assertEquals(0f, artworkNeighbourReveal(-60f, 400f, forward = false))
        assertEquals(outward[3], artworkNeighbourReveal(60f, 400f, forward = false))
        assertEquals(0f, artworkNeighbourReveal(0f, 400f, forward = true))
        assertEquals(1f, artworkNeighbourReveal(-800f, 400f, forward = true))
        assertEquals(0f, artworkNeighbourReveal(-80f, 0f, forward = true))
    }

    @Test
    fun fineScrubSlowsWithDistanceBelowTheBar() {
        assertEquals(1, scrubFineFactor(dyBelowBar = 0f, halfAt = 40f, quarterAt = 90f))
        assertEquals(1, scrubFineFactor(dyBelowBar = 40f, halfAt = 40f, quarterAt = 90f))
        assertEquals(2, scrubFineFactor(dyBelowBar = 41f, halfAt = 40f, quarterAt = 90f))
        assertEquals(4, scrubFineFactor(dyBelowBar = 91f, halfAt = 40f, quarterAt = 90f))
        // Above the bar the finger is still on the slider; no slowdown.
        assertEquals(1, scrubFineFactor(dyBelowBar = -200f, halfAt = 40f, quarterAt = 90f))
    }

    @Test
    fun scrubDeltaScalesWithWidthDurationAndFineness() {
        assertEquals(60_000L, scrubDeltaMs(dxPx = 171f, trackWidthPx = 342f, durationMs = 120_000L, fine = 1))
        assertEquals(15_000L, scrubDeltaMs(dxPx = 171f, trackWidthPx = 342f, durationMs = 120_000L, fine = 4))
        assertEquals(-60_000L, scrubDeltaMs(dxPx = -171f, trackWidthPx = 342f, durationMs = 120_000L, fine = 1))
        assertEquals(0L, scrubDeltaMs(dxPx = 50f, trackWidthPx = 0f, durationMs = 120_000L, fine = 1))
    }

    @Test
    fun skipHoldAcceleratesInStepsAndCaps() {
        assertEquals(4, skipHoldMultiplier(heldMs = 0L))
        assertEquals(4, skipHoldMultiplier(heldMs = 1_499L))
        assertEquals(8, skipHoldMultiplier(heldMs = 1_500L))
        assertEquals(16, skipHoldMultiplier(heldMs = 3_000L))
        assertEquals(32, skipHoldMultiplier(heldMs = 4_500L))
        assertEquals(32, skipHoldMultiplier(heldMs = 60_000L))
        assertEquals(32, skipHoldMultiplier(heldMs = Long.MAX_VALUE))
    }

    @Test
    fun bitrateAndFormatLabelComeFromSizeAndDuration() {
        assertEquals(320, estimatedBitrateKbps(sizeBytes = 9_600_000L, durationMs = 240_000L))
        assertNull(estimatedBitrateKbps(sizeBytes = null, durationMs = 240_000L))
        assertNull(estimatedBitrateKbps(sizeBytes = 9_600_000L, durationMs = 0L))
        assertEquals(
            "FLAC · 1 010 kbps · 34.2 MB",
            fileFormatLabel("01 - Blue Hour.flac", 34_200_000L, 270_890L),
        )
        assertEquals("MP3", fileFormatLabel("song.mp3", null, null))
        assertNull(fileFormatLabel("noextension", 1L, 1L))
        assertNull(fileFormatLabel(null, 1L, 1L))
    }
}

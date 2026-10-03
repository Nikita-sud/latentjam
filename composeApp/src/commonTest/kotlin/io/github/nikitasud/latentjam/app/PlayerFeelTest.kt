/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.geometry.Rect
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
    fun theCoverAnnouncesACommitOnlyPastTheThreshold() {
        assertTrue(collapseCommits(dy = 141f, threshold = 140f))
        assertFalse(collapseCommits(dy = 140f, threshold = 140f))
    }

    @Test
    fun theLibraryIsSkippedOnlyUnderAFullyExpandedPlayer() {
        assertFalse(libraryDrawnUnderPlayer(progress = 1f))
        // The first pixel of a pull uncovers the page it returns to.
        assertTrue(libraryDrawnUnderPlayer(progress = 0.9999f))
        assertTrue(libraryDrawnUnderPlayer(progress = 0.5f))
        assertTrue(libraryDrawnUnderPlayer(progress = 0f))
    }

    @Test
    fun aSlowReleaseSettlesByDistanceTheSameWayInBothDirections() {
        // From the mini player: a short lift falls back, one past the commit distance opens.
        assertFalse(expansionSettlesOpen(0.15f, velocity = 0f, wasOpen = false, commitFraction = 0.2f, flingVelocity = 1f))
        assertTrue(expansionSettlesOpen(0.25f, velocity = 0f, wasOpen = false, commitFraction = 0.2f, flingVelocity = 1f))
        // From the full player: the same distance down closes it, less springs back.
        assertTrue(expansionSettlesOpen(0.85f, velocity = 0f, wasOpen = true, commitFraction = 0.2f, flingVelocity = 1f))
        assertFalse(expansionSettlesOpen(0.75f, velocity = 0f, wasOpen = true, commitFraction = 0.2f, flingVelocity = 1f))
        // Drifting slowly the wrong way does not override the distance.
        assertTrue(expansionSettlesOpen(0.85f, velocity = -0.9f, wasOpen = true, commitFraction = 0.2f, flingVelocity = 1f))
    }

    @Test
    fun aFlingSettlesByItsDirectionWhereverItIsReleased() {
        assertTrue(expansionSettlesOpen(0.02f, velocity = 1.5f, wasOpen = false, commitFraction = 0.2f, flingVelocity = 1f))
        assertFalse(expansionSettlesOpen(0.98f, velocity = -1.5f, wasOpen = true, commitFraction = 0.2f, flingVelocity = 1f))
        // A fling back towards where it started cancels a long drag.
        assertFalse(expansionSettlesOpen(0.7f, velocity = -1.5f, wasOpen = false, commitFraction = 0.2f, flingVelocity = 1f))
        assertTrue(expansionSettlesOpen(0.3f, velocity = 1.5f, wasOpen = true, commitFraction = 0.2f, flingVelocity = 1f))
    }

    @Test
    fun theTwoContentsHandOverWithoutAGapOrAnOverlapAtTheEnds() {
        assertEquals(1f, miniPlayerContentAlpha(0f))
        assertEquals(0f, miniPlayerContentAlpha(0.25f))
        assertEquals(0f, miniPlayerContentAlpha(1f))
        assertEquals(0f, fullPlayerContentAlpha(0f))
        assertEquals(0f, fullPlayerContentAlpha(0.1f))
        assertEquals(0.5f, fullPlayerContentAlpha(0.35f), absoluteTolerance = 0.0001f)
        assertEquals(1f, fullPlayerContentAlpha(0.6f), absoluteTolerance = 0.0001f)
        assertEquals(1f, fullPlayerContentAlpha(1f))
        val steps = (0..100).map { it / 100f }
        assertTrue(steps.map(::miniPlayerContentAlpha).zipWithNext().all { (a, b) -> b <= a })
        assertTrue(steps.map(::fullPlayerContentAlpha).zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun theSurfaceColourAndTheScrimFollowTheProgress() {
        assertEquals(0f, playerSurfaceColorFraction(0f))
        assertEquals(0.5f, playerSurfaceColorFraction(0.25f))
        assertEquals(1f, playerSurfaceColorFraction(0.8f))
        assertEquals(0f, libraryScrimAlpha(0f))
        assertEquals(0.16f, libraryScrimAlpha(0.5f), absoluteTolerance = 0.0001f)
        assertEquals(0.32f, libraryScrimAlpha(1f), absoluteTolerance = 0.0001f)
        assertEquals(0.32f, libraryScrimAlpha(1.4f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun thePillSitsInsideTheNavigationBarsAndItsThumbnailAtTheStartEdge() {
        val pill = miniPlayerBounds(
            width = 1440f, height = 3120f, insetLeft = 0f, insetRight = 0f, insetBottom = 168f,
            margin = 28f, pillHeight = 252f,
        )
        assertEquals(Rect(28f, 2672f, 1412f, 2924f), pill)
        // Landscape three-button navigation sits on a side.
        val side = miniPlayerBounds(
            width = 3120f, height = 1440f, insetLeft = 0f, insetRight = 168f, insetBottom = 0f,
            margin = 28f, pillHeight = 252f,
        )
        assertEquals(Rect(28f, 1160f, 2924f, 1412f), side)
        assertEquals(Rect(63f, 2714f, 231f, 2882f), miniPlayerThumbnailBounds(pill, 35f, 168f, rtl = false))
        assertEquals(Rect(1209f, 2714f, 1377f, 2882f), miniPlayerThumbnailBounds(pill, 35f, 168f, rtl = true))
    }

    @Test
    fun theLibraryIsDrawnInDisjointPiecesExactlyWhereTheSheetLeavesItUncovered() {
        val w = 1080f
        val h = 2280f
        val surface = Rect(20f, 900f, 1060f, 2250f)
        val pieces = sheetUncoveredPieces(surface, radius = 60f, inset = 2f, width = w, height = h)
        assertEquals(
            listOf(
                Rect(0f, 0f, w, 962f), // above, down past the top corners
                Rect(0f, 2188f, w, h), // below, up past the bottom corners
                Rect(0f, 962f, 22f, 2188f), // left of the straight edge
                Rect(1058f, 962f, w, 2188f), // right of it
            ),
            pieces,
        )
        // Disjoint, so the scrim is never drawn twice anywhere.
        for (a in pieces.indices) for (b in pieces.indices) if (a < b) {
            val o = pieces[a].intersect(pieces[b])
            assertTrue(o.width <= 0f || o.height <= 0f)
        }
        // Every point outside the rounded surface is in some piece.
        for (x in listOf(0f, 10f, 21f, 25f, 540f, 1059f, 1070f, 1079f)) {
            for (y in listOf(0f, 500f, 905f, 930f, 1500f, 2240f, 2260f, 2279f)) {
                val p = androidx.compose.ui.geometry.Offset(x, y)
                if (!insideRoundedRect(p, surface, 60f)) assertTrue(pieces.any { it.contains(p) }, "uncovered $p")
            }
        }
        // A full-window square surface leaves nothing to draw; one only short of the top, a band.
        assertEquals(emptyList(), sheetUncoveredPieces(Rect(0f, 0f, w, h), 0f, 2f, w, h))
        assertEquals(listOf(Rect(0f, 0f, w, 13f)), sheetUncoveredPieces(Rect(0f, 10f, w, h), 0f, 3f, w, h))
    }

    @Test
    fun theCornersAreThePillsAtTheBottomAndGoneLongBeforeTheTop() {
        assertEquals(66f, sheetCornerRadius(66f, 0f))
        assertEquals(16.5f, sheetCornerRadius(66f, 0.5f), absoluteTolerance = 0.001f)
        // A short pull of the full player (down to ~0.88) stays under a pixel.
        assertTrue(sheetCornerRadius(66f, 0.88f) < 1f)
        assertEquals(0f, sheetCornerRadius(66f, 1f))
        assertEquals(0f, sheetCornerRadius(66f, 1.2f))
    }

    @Test
    fun aSurfaceThatReachesEveryEdgeWithSquareCornersHidesTheWholeLibrary() {
        assertTrue(sheetCoversWindow(Rect(0f, 0.3f, 1080f, 2280f), radius = 0.2f, width = 1080f, height = 2280f))
        // The last pixel of the top edge still uncovered.
        assertFalse(sheetCoversWindow(Rect(0f, 1f, 1080f, 2280f), radius = 0f, width = 1080f, height = 2280f))
        // Rounded corners leave slivers of the page visible.
        assertFalse(sheetCoversWindow(Rect(0f, 0f, 1080f, 2280f), radius = 3f, width = 1080f, height = 2280f))
        assertFalse(sheetCoversWindow(Rect(4f, 0f, 1076f, 2280f), radius = 0f, width = 1080f, height = 2280f))
    }

    private fun insideRoundedRect(point: androidx.compose.ui.geometry.Offset, rect: Rect, r: Float): Boolean {
        if (!rect.contains(point) && point.x != rect.right && point.y != rect.bottom) return false
        val cx = point.x.coerceIn(rect.left + r, rect.right - r)
        val cy = point.y.coerceIn(rect.top + r, rect.bottom - r)
        val dx = point.x - cx
        val dy = point.y - cy
        return dx * dx + dy * dy <= r * r + 0.001f
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

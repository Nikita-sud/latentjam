/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure helpers [MapTab] draws with: [screenPosition] (the zoom/pan transform shared by
 * the `Canvas` draw loop and hit-testing), [nearest] (hit-testing), and [largestRegionCentroids]
 * (label placement). Everything else in `MapTab.kt` is a `@Composable` and is not exercised here.
 */
class MapTabTest {

    private fun dot(trackId: String = "t", x: Float = 0.5f, y: Float = 0.5f, region: Int = 0) =
        MapDot(TrackId(trackId), x, y, region, plays = 0, skipRate = 0f)

    // Pins the one formula both the Canvas draw loop and `nearest` share: a dot at (0.25, 0.75) on
    // an 800x600 canvas, zoomed 2x and panned by (50, -20), lands at exactly (450, 880). If a future
    // change edits this formula, every caller moves together or this test catches the drift.
    @Test
    fun `screenPosition places a dot using the zoom and pan transform`() {
        val target = dot(x = 0.25f, y = 0.75f)

        val position = screenPosition(target, width = 800f, height = 600f, zoom = 2f, panX = 50f, panY = -20f)

        assertEquals(Offset(450f, 880f), position)
    }

    // Derives the expected tap point through screenPosition itself rather than re-deriving the
    // transform by hand, so this test cannot keep passing against a formula that has drifted from
    // the one screenPosition actually implements.
    @Test
    fun `nearest finds a dot under a zoomed and panned tap`() {
        val target = dot(trackId = "target", x = 0.5f, y = 0.5f)
        val decoy = dot(trackId = "decoy", x = 0.0f, y = 0.0f)
        val width = 800
        val height = 600
        val zoom = 2f
        val panX = 50f
        val panY = -20f

        val tap = screenPosition(target, width.toFloat(), height.toFloat(), zoom, panX, panY)

        val found = nearest(
            listOf(decoy, target),
            tap,
            width,
            height,
            zoom,
            panX,
            panY,
            hitRadiusPx = 24f,
        )
        assertEquals(target, found)
    }

    @Test
    fun `nearest returns the closer of two dots`() {
        val near = dot(trackId = "near", x = 0.1f, y = 0.1f)
        val far = dot(trackId = "far", x = 0.9f, y = 0.9f)

        val found = nearest(
            listOf(far, near),
            tap = Offset(100f, 100f),
            width = 1000,
            height = 1000,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            hitRadiusPx = 24f,
        )
        assertEquals(near, found)
    }

    // A generous hit target still has an edge. At zoom 1 with no pan, a dot sitting at the origin
    // has its screen centre at (0, 0); a tap 20px right and 10px down is 500 squared-px away
    // (inside a 24px radius, 576 squared-px), and a tap 20px right and 15px down is 625 squared-px
    // away (just outside it). The radius is a plain parameter here, independent of however the
    // caller (density-converted dp, in production) arrives at it.
    @Test
    fun `nearest respects the hit radius boundary`() {
        val target = dot(trackId = "origin", x = 0f, y = 0f)

        val inside = nearest(listOf(target), Offset(20f, 10f), 1, 1, 1f, 0f, 0f, hitRadiusPx = 24f)
        val outside = nearest(listOf(target), Offset(20f, 15f), 1, 1, 1f, 0f, 0f, hitRadiusPx = 24f)

        assertEquals(target, inside)
        assertNull(outside)
    }

    @Test
    fun `nearest returns null when every dot is out of reach`() {
        val target = dot(trackId = "far", x = 0.9f, y = 0.9f)
        val found = nearest(listOf(target), Offset(0f, 0f), 1000, 1000, 1f, 0f, 0f, hitRadiusPx = 24f)
        assertNull(found)
    }

    @Test
    fun `nearest returns null for an empty dot list`() {
        val found = nearest(emptyList(), Offset(100f, 100f), 1000, 1000, 1f, 0f, 0f, hitRadiusPx = 24f)
        assertNull(found)
    }

    // Closes the density finding directly: the same dp constant must resolve to more raw pixels on
    // a denser screen, or the hit target shrinks back to an unreachable sliver on high-density
    // phones exactly as it did when HIT_RADIUS was a raw px literal.
    @Test
    fun `HIT_RADIUS_DP converts to more raw pixels at higher density`() {
        val onePx = with(Density(density = 1f)) { HIT_RADIUS_DP.toPx() }
        val threePx = with(Density(density = 3f)) { HIT_RADIUS_DP.toPx() }

        assertEquals(48f, onePx)
        assertEquals(144f, threePx)
    }

    private fun page(dots: List<MapDot>) = MapPage(
        dots = dots,
        regionNames = List(dots.map { it.region }.distinct().size + 1) { "" },
        listening = LibraryListening(
            trackCount = dots.size,
            neverPlayed = 0,
            tracksForHalfOfPlays = 0,
            regions = emptyList(),
            darkestRegion = null,
            skippiestRegion = null,
            maxPlays = 0,
        ),
    )

    @Test
    fun `largestRegionCentroids orders by region size and truncates to the limit`() {
        val dots = listOf(
            dot("a1", region = 0), dot("a2", region = 0), dot("a3", region = 0),
            dot("b1", region = 1),
            dot("c1", region = 2), dot("c2", region = 2),
        )
        val top = largestRegionCentroids(page(dots), limit = 2)
        assertEquals(listOf(0, 2), top.map { it.first })
    }

    // Deliberately picks coordinates exact in binary floating point (0, 1, 0.25, 0.75) so the mean
    // compares with assertEquals rather than needing a tolerance.
    @Test
    fun `largestRegionCentroids centres a region at the mean of its members`() {
        val dots = listOf(
            dot("a1", x = 0f, y = 0.25f, region = 0),
            dot("a2", x = 1f, y = 0.75f, region = 0),
        )
        val centroids = largestRegionCentroids(page(dots), limit = 5)
        assertEquals(1, centroids.size)
        assertEquals(0, centroids.single().first)
        assertEquals(Offset(0.5f, 0.5f), centroids.single().second)
    }

    @Test
    fun `largestRegionCentroids returns every region when the limit is not reached`() {
        val dots = listOf(dot("a", region = 0), dot("b", region = 1), dot("c", region = 2))
        val centroids = largestRegionCentroids(page(dots), limit = 5)
        assertEquals(3, centroids.size)
    }

    @Test
    fun `largestRegionCentroids returns nothing for an empty page`() {
        assertTrue(largestRegionCentroids(page(emptyList()), limit = 5).isEmpty())
    }
}

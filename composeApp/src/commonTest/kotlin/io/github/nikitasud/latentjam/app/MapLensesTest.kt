/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.history.RegionListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapLensesTest {

    private fun dot(region: Int = 0, plays: Int = 0, skipRate: Float = 0f) =
        MapDot(TrackId("t"), 0.5f, 0.5f, region, plays, skipRate)

    @Test
    fun `spatial index includes every overview dot in original paint order`() {
        val dots = listOf(
            dot().copy(x = 0.9f, y = 0.9f),
            dot().copy(x = 0.1f, y = 0.1f),
            dot().copy(x = 0.5f, y = 0.5f),
            dot().copy(x = 1f, y = 0f),
        )
        val index = MapDotIndex(dots)

        assertContentEquals(intArrayOf(0, 1, 2, 3), index.visibleIndices(800f, 600f, 1f, 0f, 0f, 10f))
    }

    @Test
    fun `spatial index preserves arbitrary paint order after collecting separate cells`() {
        val dots = listOf(
            dot().copy(x = 0.54f, y = 0.55f),
            dot().copy(x = 0.45f, y = 0.45f),
            dot().copy(x = 0.9f, y = 0.9f),
            dot().copy(x = 0.46f, y = 0.56f),
            dot().copy(x = 0.53f, y = 0.48f),
        )
        val visible = MapDotIndex(dots).visibleIndices(800f, 600f, 6f, -2000f, -1500f, 10f)

        assertContentEquals(intArrayOf(0, 1, 3, 4), visible)
    }

    @Test
    fun `spatial index includes circle fringes crossing viewport and cell edges at every zoom`() {
        val dots = (0 until 4000).map { i ->
            dot().copy(x = (i % 100 + 0.5f) / 100f, y = (i / 100 + 0.5f) / 40f)
        }
        val index = MapDotIndex(dots)
        for (zoom in listOf(1f, 1.7f, 3f, 6f)) {
            for (fraction in listOf(0f, 0.37f, 1f)) {
                for (radius in listOf(3f, 24f, 95f)) {
                    val panX = 800f * (1f - zoom) * fraction
                    val panY = 600f * (1f - zoom) * fraction
                    val candidates = index.visibleIndices(800f, 600f, zoom, panX, panY, radius).toSet()
                    dots.forEachIndexed { i, dot ->
                        val x = dot.x * 800f * zoom + panX
                        val y = dot.y * 600f * zoom + panY
                        val visible = x + radius >= 0f && x - radius <= 800f &&
                            y + radius >= 0f && y - radius <= 600f
                        if (visible) assertTrue(i in candidates, "omitted dot $i at zoom $zoom / radius $radius")
                    }
                }
            }
        }
    }

    @Test
    fun `spatial index reuses candidate arrays while pan stays within the same cells`() {
        val dots = (0 until 1000).map { i ->
            dot().copy(x = (i % 100 + 0.5f) / 100f, y = (i / 100 + 0.5f) / 10f)
        }
        val index = MapDotIndex(dots)
        val first = index.visibleIndices(800f, 600f, 6f, -2000f, -1500f, 8f)
        val nearby = index.visibleIndices(800f, 600f, 6f, -2001f, -1501f, 8f)

        assertSame(first, nearby)
    }

    @Test
    fun `zoomed synthetic maps examine under one tenth of dots without changing draw coverage`() {
        for (count in listOf(1000, 4000)) {
            val rows = count / 100
            val dots = (0 until count).map { i ->
                dot().copy(x = (i % 100 + 0.5f) / 100f, y = (i / 100 + 0.5f) / rows)
            }
            val candidates = MapDotIndex(dots).visibleIndices(800f, 600f, 6f, -2000f, -1500f, 8f)
            println("Map index at 6x: $count dots -> ${candidates.size} ordered candidates")
            assertTrue(candidates.size < count / 10)
            assertTrue(candidates.isNotEmpty())
        }
    }

    @Test
    fun `empty or unmeasured maps have no spatial candidates`() {
        assertTrue(MapDotIndex(emptyList()).visibleIndices(800f, 600f, 1f, 0f, 0f, 8f).isEmpty())
        assertTrue(MapDotIndex(listOf(dot())).visibleIndices(0f, 0f, 1f, 0f, 0f, 8f).isEmpty())
    }

    @Test
    fun `worlds lens accents only the selected region`() {
        assertEquals(MapInk.Accent, MapLenses.ink(MapLens.WORLDS, dot(region = 2), 2, maxPlays = 9))
        assertEquals(MapInk.Neutral, MapLenses.ink(MapLens.WORLDS, dot(region = 3), 2, maxPlays = 9))
    }

    @Test
    fun `plays lens leaves unplayed tracks neutral and ramps the rest`() {
        assertEquals(MapInk.Neutral, MapLenses.ink(MapLens.PLAYS, dot(plays = 0), 0, maxPlays = 40))
        val top = MapLenses.ink(MapLens.PLAYS, dot(plays = 40), 0, maxPlays = 40)
        val low = MapLenses.ink(MapLens.PLAYS, dot(plays = 1), 0, maxPlays = 40)
        assertTrue(top is MapInk.Ramp && top.step == MapLenses.RAMP_STEPS - 1)
        assertTrue(low is MapInk.Ramp && low.step < MapLenses.RAMP_STEPS - 1)
    }

    // Never-played carries identity, so it must not rest on hue alone: the dot is also bigger.
    // Both branches matter here (unlike every other lens, where colour is only ever a magnitude),
    // so a played dot under this lens must come back Neutral, not just "not asserted on".
    @Test
    fun `never played lens marks unplayed tracks with colour and size`() {
        assertEquals(
            MapInk.Accent,
            MapLenses.ink(MapLens.NEVER_PLAYED, dot(plays = 0), 0, maxPlays = 9),
        )
        assertEquals(
            MapInk.Neutral,
            MapLenses.ink(MapLens.NEVER_PLAYED, dot(plays = 4), 0, maxPlays = 9),
        )
        val unplayed = MapLenses.radius(MapLens.NEVER_PLAYED, dot(plays = 0), 0)
        val played = MapLenses.radius(MapLens.NEVER_PLAYED, dot(plays = 4), 0)
        assertTrue(unplayed > played, "unplayed dot was not larger")
    }

    // An unclaimed track is drawn on every lens but counted by none of their figures, which come from
    // region-keyed stats. A played unclaimed track therefore must NOT take a ramp step, and an
    // unplayed one must NOT take the never-played lens's accent-and-large treatment, or the plot would
    // show more of whatever the headline counts than the headline says exists.
    @Test
    fun `no lens paints a track no region claimed`() {
        val unclaimed = dot(region = MapDot.NO_REGION, plays = 0)
        val unclaimedPlayed = dot(region = MapDot.NO_REGION, plays = 40, skipRate = 1f)

        for (lens in MapLens.entries) {
            assertEquals(
                MapInk.Neutral,
                MapLenses.ink(lens, unclaimed, selectedRegion = 0, maxPlays = 40),
                "$lens coloured an unclaimed unplayed track",
            )
            assertEquals(
                MapInk.Neutral,
                MapLenses.ink(lens, unclaimedPlayed, selectedRegion = 0, maxPlays = 40),
                "$lens coloured an unclaimed played track",
            )
            assertTrue(
                MapLenses.radius(lens, unclaimed, selectedRegion = 0) <
                    MapLenses.radius(MapLens.NEVER_PLAYED, dot(plays = 0), 0),
                "$lens gave an unclaimed track the counted never-played size",
            )
        }
    }

    // Worlds is the only other lens whose radius varies at all, and it varies by selection rather
    // than plays: the selected region's dot must be larger than an unselected region's dot.
    @Test
    fun `worlds lens grows the selected region dot and shrinks the rest`() {
        val selected = MapLenses.radius(MapLens.WORLDS, dot(region = 2), 2)
        val unselected = MapLenses.radius(MapLens.WORLDS, dot(region = 3), 2)
        assertTrue(selected > unselected, "selected region dot was not larger than an unselected one")
    }

    // Plays and Skips both fall through to the shared base radius: they carry no size distinction
    // of their own, and that shared radius must be a real, positive size rather than a collapsed 0.
    @Test
    fun `plays and skips lenses share a positive base radius`() {
        val playsRadius = MapLenses.radius(MapLens.PLAYS, dot(plays = 5), 0)
        val skipsRadius = MapLenses.radius(MapLens.SKIPS, dot(plays = 5), 0)
        assertTrue(playsRadius > 0f, "base radius must be positive")
        assertEquals(playsRadius, skipsRadius, "PLAYS and SKIPS should share the same base radius")
    }

    @Test
    fun `skips lens uses the warm ramp and skips unplayed tracks`() {
        assertEquals(MapInk.Neutral, MapLenses.ink(MapLens.SKIPS, dot(plays = 0), 0, maxPlays = 9))
        val hot = MapLenses.ink(MapLens.SKIPS, dot(plays = 5, skipRate = 1f), 0, maxPlays = 9)
        assertTrue(hot is MapInk.WarmRamp && hot.step == MapLenses.RAMP_STEPS - 1)
    }

    @Test
    fun `comparative lenses wait for enough history while unplayed is already actionable`() {
        val thin = LibraryListening(
            trackCount = 300, neverPlayed = 298, tracksForHalfOfPlays = 1,
            regions = listOf(RegionListening(0, 300, 298, 4, 0f)),
            darkestRegion = 0, skippiestRegion = null, maxPlays = 3,
        )
        assertEquals(listOf(MapLens.WORLDS, MapLens.NEVER_PLAYED), MapLenses.availableLenses(thin))

        val rich = thin.copy(
            neverPlayed = 150,
            regions = listOf(RegionListening(0, 300, 150, 900, 0.2f)),
            darkestRegion = 0,
            skippiestRegion = 0,
        )
        assertEquals(MapLens.entries.toList(), MapLenses.availableLenses(rich))
    }

    // Finding (Important 1) of the final review: total plays can clear minEvents while no single
    // region clears MIN_PLAYED_FOR_SKIPPIEST (a much harder bar than MIN_REGION_FOR_DARKEST on a
    // library with many regions and thin-per-region history) -- concretely, 873 tracks / 45 played
    // across 8 regions clears 50 total plays but leaves every region under 10 played tracks. Before
    // this fix the Skips chip still appeared and its headline rendered "You bail out of  more than
    // anywhere else — 0% of starts.", naming no region and asserting nothing true.
    @Test
    fun `available lenses omit skips when no region clears the skippiest threshold`() {
        val listening = LibraryListening(
            trackCount = 873, neverPlayed = 828, tracksForHalfOfPlays = 20,
            regions = listOf(RegionListening(0, 873, 828, 60, 0.1f)),
            darkestRegion = 0, skippiestRegion = null, maxPlays = 5,
        )
        val lenses = MapLenses.availableLenses(listening)
        assertTrue(MapLens.SKIPS !in lenses, "SKIPS must not appear without a skippiest region")
        assertEquals(listOf(MapLens.WORLDS, MapLens.PLAYS, MapLens.NEVER_PLAYED), lenses)
    }

    @Test
    fun `small regions offer the unplayed filter even without a darkest region`() {
        val listening = LibraryListening(
            trackCount = 16, neverPlayed = 8, tracksForHalfOfPlays = 3,
            regions = List(4) { RegionListening(it, 4, 2, 100, 0.1f) },
            darkestRegion = null, skippiestRegion = null, maxPlays = 50,
        )

        assertEquals(
            listOf(MapLens.WORLDS, MapLens.PLAYS, MapLens.NEVER_PLAYED),
            MapLenses.availableLenses(listening),
        )
    }

    @Test
    fun `a first recorded play makes unplayed tracks actionable in small regions`() {
        val listening = LibraryListening(
            trackCount = 4, neverPlayed = 3, tracksForHalfOfPlays = 1,
            regions = listOf(RegionListening(0, 4, 3, 1, 0f)),
            darkestRegion = null, skippiestRegion = null, maxPlays = 1,
        )

        assertEquals(listOf(MapLens.WORLDS, MapLens.NEVER_PLAYED), MapLenses.availableLenses(listening))
    }

    @Test
    fun `unplayed lens stays hidden when no listening has been recorded`() {
        val listening = LibraryListening(
            trackCount = 4, neverPlayed = 4, tracksForHalfOfPlays = 0,
            regions = listOf(RegionListening(0, 4, 4, 0, 0f)),
            darkestRegion = null, skippiestRegion = null, maxPlays = 0,
        )

        assertEquals(listOf(MapLens.WORLDS), MapLenses.availableLenses(listening))
    }

    @Test
    fun `unplayed lens stays hidden when every mapped track has been played`() {
        val listening = LibraryListening(
            trackCount = 4, neverPlayed = 0, tracksForHalfOfPlays = 2,
            regions = listOf(RegionListening(0, 4, 0, 100, 0f)),
            darkestRegion = null, skippiestRegion = null, maxPlays = 25,
        )

        assertEquals(listOf(MapLens.WORLDS, MapLens.PLAYS), MapLenses.availableLenses(listening))
    }

    // step() boundary: an index outside 0 until RAMP_STEPS would be an array-out-of-bounds waiting
    // to happen in the drawing task, and a mid-range skip rate must not collapse onto an extreme.
    @Test
    fun `skips lens ramp step never leaves its range and separates the middle from the ends`() {
        val low = MapLenses.ink(MapLens.SKIPS, dot(plays = 1, skipRate = 0f), 0, maxPlays = 9)
        val mid = MapLenses.ink(MapLens.SKIPS, dot(plays = 1, skipRate = 0.5f), 0, maxPlays = 9)
        val high = MapLenses.ink(MapLens.SKIPS, dot(plays = 1, skipRate = 1f), 0, maxPlays = 9)
        assertTrue(low is MapInk.WarmRamp && low.step == 0)
        assertTrue(mid is MapInk.WarmRamp && mid.step in 1 until MapLenses.RAMP_STEPS - 1)
        assertTrue(high is MapInk.WarmRamp && high.step == MapLenses.RAMP_STEPS - 1)

        // Every bucket boundary in between must resolve to a valid, non-decreasing step index.
        var previous = -1
        for (i in 0..20) {
            val rate = i / 20f
            val ink = MapLenses.ink(MapLens.SKIPS, dot(plays = 1, skipRate = rate), 0, maxPlays = 9)
            require(ink is MapInk.WarmRamp)
            assertTrue(ink.step in 0 until MapLenses.RAMP_STEPS, "step ${ink.step} out of range for rate $rate")
            assertTrue(ink.step >= previous, "step regressed at rate $rate")
            previous = ink.step
        }
    }

    // Same monotonicity sweep as the skip ramp above, but for plays. The pow(0.4f) compression is a
    // tunable presentation detail and deliberately left unpinned here; only the ordering matters.
    @Test
    fun `plays lens ramp step never decreases as plays increase`() {
        var previous = -1
        for (plays in 1..40) {
            val ink = MapLenses.ink(MapLens.PLAYS, dot(plays = plays), 0, maxPlays = 40)
            require(ink is MapInk.Ramp)
            assertTrue(ink.step in 0 until MapLenses.RAMP_STEPS, "step ${ink.step} out of range for plays $plays")
            assertTrue(ink.step >= previous, "step regressed at plays $plays")
            previous = ink.step
        }
    }

    // A track played more than the library's play-count maximum (a stale maxPlays snapshot, e.g.
    // from a filtered view) must clamp rather than throw or wrap to an invalid step.
    @Test
    fun `plays lens clamps a play count above maxPlays instead of throwing`() {
        val ink = MapLenses.ink(MapLens.PLAYS, dot(plays = 100), 0, maxPlays = 10)
        assertTrue(ink is MapInk.Ramp && ink.step == MapLenses.RAMP_STEPS - 1)
    }

    @Test
    fun `available lenses respects the exact minEvents boundary`() {
        val justBelow = LibraryListening(
            trackCount = 10, neverPlayed = 5, tracksForHalfOfPlays = 1,
            regions = listOf(RegionListening(0, 10, 5, 9, 0f)),
            darkestRegion = null, skippiestRegion = null, maxPlays = 9,
        )
        // darkestRegion/skippiestRegion are set here so this test isolates the minEvents boundary
        // from the separate darkest/skippiest gating covered by the tests above.
        val justAt = justBelow.copy(
            regions = listOf(RegionListening(0, 10, 5, 10, 0f)),
            darkestRegion = 0,
            skippiestRegion = 0,
        )

        assertEquals(
            listOf(MapLens.WORLDS, MapLens.NEVER_PLAYED),
            MapLenses.availableLenses(justBelow, minEvents = 10),
        )
        assertEquals(MapLens.entries.toList(), MapLenses.availableLenses(justAt, minEvents = 10))
    }

    @Test
    fun `legend names the right key for every lens`() {
        assertEquals(MapLegend.REGION_SELECTION, MapLenses.legend(MapLens.WORLDS))
        assertEquals(MapLegend.PLAY_RAMP, MapLenses.legend(MapLens.PLAYS))
        assertEquals(MapLegend.NEVER_PLAYED_KEY, MapLenses.legend(MapLens.NEVER_PLAYED))
        assertEquals(MapLegend.SKIP_RAMP, MapLenses.legend(MapLens.SKIPS))
    }
}

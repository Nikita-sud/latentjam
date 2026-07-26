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
import kotlin.test.assertTrue

class MapLensesTest {

    private fun dot(region: Int = 0, plays: Int = 0, skipRate: Float = 0f) =
        MapDot(TrackId("t"), 0.5f, 0.5f, region, plays, skipRate)

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

    // Cold start: a lens that would say "you have never played 100% of your library" is worthless.
    @Test
    fun `stat lenses stay hidden until there is enough history`() {
        val thin = LibraryListening(
            trackCount = 300, neverPlayed = 298, tracksForHalfOfPlays = 1,
            regions = listOf(RegionListening(0, 300, 298, 4, 0f)),
            darkestRegion = 0, skippiestRegion = null, maxPlays = 3,
        )
        assertEquals(listOf(MapLens.WORLDS), MapLenses.availableLenses(thin))

        val rich = thin.copy(neverPlayed = 150, regions = listOf(RegionListening(0, 300, 150, 900, 0.2f)))
        assertEquals(MapLens.entries.toList(), MapLenses.availableLenses(rich))
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
        val justAt = justBelow.copy(regions = listOf(RegionListening(0, 10, 5, 10, 0f)))

        assertEquals(listOf(MapLens.WORLDS), MapLenses.availableLenses(justBelow, minEvents = 10))
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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryListeningStatsTest {

    private fun stats(plays: Int, skips: Int) =
        TrackStats(plays = plays, completions = 0, skips = skips, totalPlayedMs = 0, lastPlayedAtMs = 0)

    @Test
    fun `summarize counts never-played tracks across the whole library`() {
        val regionOf = (0 until 10).associate { TrackId("t$it") to it % 2 }
        val played = mapOf(TrackId("t0") to stats(3, 0), TrackId("t1") to stats(1, 0))
        val summary = LibraryListeningStats.summarize(regionOf, played)
        assertEquals(10, summary.trackCount)
        assertEquals(8, summary.neverPlayed)
        assertEquals(3, summary.maxPlays)
    }

    // The concentration headline: how few tracks carry half the listening.
    @Test
    fun `tracksForHalfOfPlays counts down from the most played`() {
        val regionOf = (0 until 4).associate { TrackId("t$it") to 0 }
        val played = mapOf(
            TrackId("t0") to stats(10, 0),
            TrackId("t1") to stats(6, 0),
            TrackId("t2") to stats(3, 0),
            TrackId("t3") to stats(1, 0),
        )
        // Total 20; the top track alone is 10, which reaches half.
        assertEquals(1, LibraryListeningStats.summarize(regionOf, played).tracksForHalfOfPlays)
    }

    // Darkest region is a RATE, not a count: a big region always holds more unplayed tracks than a
    // small one, which would make the headline say "your biggest region" every time.
    @Test
    fun `darkestRegion uses the never-played rate and ignores tiny regions`() {
        val regionOf = buildMap {
            repeat(20) { put(TrackId("big$it"), 0) }
            repeat(10) { put(TrackId("dark$it"), 1) }
            repeat(3) { put(TrackId("tiny$it"), 2) }
        }
        val played = buildMap {
            repeat(14) { put(TrackId("big$it"), stats(1, 0)) }   // 30% unplayed
            repeat(2) { put(TrackId("dark$it"), stats(1, 0)) }   // 80% unplayed
        }                                                        // region 2: 100% but too small
        assertEquals(1, LibraryListeningStats.summarize(regionOf, played).darkestRegion)
    }

    @Test
    fun `skippiestRegion is null when no region has enough played tracks`() {
        val regionOf = (0 until 6).associate { TrackId("t$it") to 0 }
        val played = mapOf(TrackId("t0") to stats(1, 1))
        assertNull(LibraryListeningStats.summarize(regionOf, played).skippiestRegion)
    }

    @Test
    fun `darkestRegion is null when no region has enough tracks`() {
        val regionOf = (0 until 6).associate { TrackId("t$it") to 0 }
        assertNull(LibraryListeningStats.summarize(regionOf, emptyMap()).darkestRegion)
    }

    // --- Additional coverage: the brief's own tests only exercise one shape of each rule. ---

    @Test
    fun `summarize handles an empty library`() {
        val summary = LibraryListeningStats.summarize(emptyMap(), emptyMap())
        assertEquals(0, summary.trackCount)
        assertEquals(0, summary.neverPlayed)
        assertEquals(0, summary.tracksForHalfOfPlays)
        assertEquals(0, summary.maxPlays)
        assertEquals(emptyList(), summary.regions)
        assertNull(summary.darkestRegion)
        assertNull(summary.skippiestRegion)
    }

    @Test
    fun `tracksForHalfOfPlays is zero when nothing has ever been played`() {
        val regionOf = (0 until 5).associate { TrackId("w$it") to 0 }
        val summary = LibraryListeningStats.summarize(regionOf, emptyMap())
        assertEquals(0, summary.tracksForHalfOfPlays)
        assertEquals(5, summary.neverPlayed)
    }

    @Test
    fun `tracksForHalfOfPlays is one for a single played track`() {
        val regionOf = mapOf(TrackId("solo") to 0)
        val played = mapOf(TrackId("solo") to stats(5, 1))
        assertEquals(1, LibraryListeningStats.summarize(regionOf, played).tracksForHalfOfPlays)
    }

    @Test
    fun `tracksForHalfOfPlays needs more than one track when the top play count falls short of half`() {
        val regionOf = (0 until 4).associate { TrackId("u$it") to 0 }
        val played = mapOf(
            TrackId("u0") to stats(3, 0),
            TrackId("u1") to stats(3, 0),
            TrackId("u2") to stats(2, 0),
            TrackId("u3") to stats(2, 0),
        )
        // Total 10; the single biggest track (3) is short of half (5), a second track is needed.
        assertEquals(2, LibraryListeningStats.summarize(regionOf, played).tracksForHalfOfPlays)
    }

    @Test
    fun `tracksForHalfOfPlays reaches half exactly at the boundary`() {
        val regionOf = (0 until 2).associate { TrackId("v$it") to 0 }
        val played = mapOf(
            TrackId("v0") to stats(4, 0),
            TrackId("v1") to stats(4, 0),
        )
        // Total 8; the top track alone (4) equals half exactly, which must count as reaching it.
        assertEquals(1, LibraryListeningStats.summarize(regionOf, played).tracksForHalfOfPlays)
    }

    @Test
    fun `regions are listed in ascending order regardless of map iteration order`() {
        val region0 = (0 until 8).map { TrackId("r0-$it") to 0 }
        val region1 = (0 until 8).map { TrackId("r1-$it") to 1 }
        val region2 = (0 until 8).map { TrackId("r2-$it") to 2 }
        val forward = linkedMapOf(*(region0 + region1 + region2).toTypedArray())
        val backward = linkedMapOf(*(region2 + region1 + region0).toTypedArray())

        val forwardRegions = LibraryListeningStats.summarize(forward, emptyMap()).regions.map { it.region }
        val backwardRegions = LibraryListeningStats.summarize(backward, emptyMap()).regions.map { it.region }
        assertEquals(listOf(0, 1, 2), forwardRegions)
        assertEquals(listOf(0, 1, 2), backwardRegions)
    }

    @Test
    fun `darkestRegion breaks an exact tie toward the lower region id regardless of insertion order`() {
        val region0 = (0 until 8).map { TrackId("dark0-$it") to 0 }
        val region1 = (0 until 8).map { TrackId("dark1-$it") to 1 }
        val region0First = linkedMapOf(*(region0 + region1).toTypedArray())
        val region1First = linkedMapOf(*(region1 + region0).toTypedArray())
        val played = buildMap {
            repeat(6) { put(TrackId("dark0-$it"), stats(1, 0)) } // region 0: 2/8 = 25% unplayed
            repeat(6) { put(TrackId("dark1-$it"), stats(1, 0)) } // region 1: 2/8 = 25% unplayed, exact tie
        }
        assertEquals(0, LibraryListeningStats.summarize(region0First, played).darkestRegion)
        assertEquals(0, LibraryListeningStats.summarize(region1First, played).darkestRegion)
    }

    @Test
    fun `skippiestRegion breaks an exact tie toward the lower region id regardless of insertion order`() {
        val region0 = (0 until 10).map { TrackId("skip0-$it") to 0 }
        val region1 = (0 until 10).map { TrackId("skip1-$it") to 1 }
        val region0First = linkedMapOf(*(region0 + region1).toTypedArray())
        val region1First = linkedMapOf(*(region1 + region0).toTypedArray())
        val played = buildMap {
            repeat(10) { put(TrackId("skip0-$it"), stats(1, if (it < 5) 1 else 0)) } // region 0: 50% skip rate
            repeat(10) { put(TrackId("skip1-$it"), stats(1, if (it < 5) 1 else 0)) } // region 1: 50%, exact tie
        }
        assertEquals(0, LibraryListeningStats.summarize(region0First, played).skippiestRegion)
        assertEquals(0, LibraryListeningStats.summarize(region1First, played).skippiestRegion)
    }

    @Test
    fun `darkestRegion boundary includes a region with exactly eight tracks`() {
        val region0 = (0 until 8).map { TrackId("b0-$it") to 0 } // exactly the minimum size
        val region1 = (0 until 7).map { TrackId("b1-$it") to 1 } // one below the minimum
        val regionOf = linkedMapOf(*(region0 + region1).toTypedArray())
        val played = buildMap {
            repeat(6) { put(TrackId("b0-$it"), stats(1, 0)) } // region 0: 2/8 = 25% unplayed, qualifies
            // region 1 is entirely unplayed (7/7 = 100%) and would win if not filtered for size.
        }
        assertEquals(0, LibraryListeningStats.summarize(regionOf, played).darkestRegion)
    }

    @Test
    fun `skippiestRegion boundary includes a region with exactly ten played tracks`() {
        val region0 = (0 until 10).map { TrackId("c0-$it") to 0 } // exactly the minimum played count
        val region1 = (0 until 9).map { TrackId("c1-$it") to 1 }  // one below the minimum
        val regionOf = linkedMapOf(*(region0 + region1).toTypedArray())
        val played = buildMap {
            repeat(10) { put(TrackId("c0-$it"), stats(1, if (it < 5) 1 else 0)) } // region 0: 50% skip rate, qualifies
            repeat(9) { put(TrackId("c1-$it"), stats(1, 1)) } // region 1: 100% skip rate, but only 9 played
        }
        assertEquals(0, LibraryListeningStats.summarize(regionOf, played).skippiestRegion)
    }

    // skipRate must pool skips over plays, not average per-track ratios: the headline it feeds
    // ("X% of starts") is an event-level claim, and an unweighted mean would let a single
    // one-play track swing a region as hard as a track with real play evidence.
    @Test
    fun `skipRate pools skips over plays instead of averaging per-track ratios`() {
        val regionOf = mapOf(TrackId("rare") to 0, TrackId("common") to 0)
        val played = mapOf(
            TrackId("rare") to stats(1, 1),    // ratio 1.0, one play
            TrackId("common") to stats(99, 0), // ratio 0.0, ninety-nine plays
        )
        val region = LibraryListeningStats.summarize(regionOf, played).regions.single()
        // Pooled: 1 skip over 100 plays = 0.01. The unweighted mean of the two ratios (0.5)
        // would be the wrong, non-pooled definition.
        assertEquals(0.01f, region.skipRate)
    }

    // A caller that hands summarize a `regionOf` with a missing middle id (e.g. one that filtered
    // out every track of a region before calling in) gets a COMPACTED list back, not a dense one:
    // region id 2 lands at `regions[1]`, not `regions[2]`. Any caller that then reads `regions[i]`
    // positionally as "region i's stats" -- as the Map page does -- must keep `regionOf` dense
    // (every id 0 until n present) itself; summarize does not restore the gap.
    @Test
    fun `summarize compacts a regionOf that is missing a middle region id`() {
        val regionOf = mapOf(
            TrackId("a0") to 0,
            TrackId("a1") to 0,
            TrackId("c0") to 2,
        )
        val summary = LibraryListeningStats.summarize(regionOf, emptyMap())
        // Region 1 never appears, so the list has two entries, not three -- and the second one
        // (index 1) is region id 2, not the still-missing id 1.
        assertEquals(2, summary.regions.size)
        assertEquals(listOf(0, 2), summary.regions.map { it.region })
        assertEquals(2, summary.regions[1].region)
    }

    @Test
    fun `summarize ignores stats for tracks that are no longer in the library`() {
        val regionOf = mapOf(TrackId("kept0") to 0, TrackId("kept1") to 0)
        val played = mapOf(
            TrackId("kept0") to stats(2, 0),
            TrackId("ghost") to stats(100, 100),
        )
        val summary = LibraryListeningStats.summarize(regionOf, played)
        assertEquals(2, summary.trackCount)
        assertEquals(2, summary.maxPlays)
    }
}

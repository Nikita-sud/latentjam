/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ArtistRunTest {
    @Test
    fun `a run of one artist pays more with every track in a row`() {
        val snapshot = twoArtists(otherCloseness = 0.5f)
        val free = picks(snapshot, penalty = 0f)
        val spaced = picks(snapshot, penalty = 1f)
        assertTrue(longestRun(snapshot, free) >= 4, "without the penalty the seed's artist runs on: ${artists(snapshot, free)}")
        assertTrue(longestRun(snapshot, spaced) < longestRun(snapshot, free), "the penalty breaks the run: ${artists(snapshot, spaced)}")
        assertEquals(free.toSet().size, free.size)
    }

    @Test
    fun `an artist with the only close tracks keeps them`() {
        val snapshot = twoArtists(otherCloseness = 0.05f)
        val spaced = picks(snapshot, penalty = 0.25f)
        assertEquals(listOf("A", "A"), artists(snapshot, spaced).take(2))
    }

    @Test
    fun `no penalty changes nothing`() {
        val snapshot = twoArtists(otherCloseness = 0.8f)
        for (continuation in listOf(false, true)) {
            assertEquals(
                SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = continuation))
                    .build(TrackId("0"), 8, FloatArray(5)),
                SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = continuation, artistRunPenalty = 0f))
                    .build(TrackId("0"), 8, FloatArray(5)),
            )
        }
    }

    @Test
    fun `the order keeps one artist's tracks apart when it costs little`() {
        val snapshot = twoArtists(otherCloseness = 0.8f)
        // Seed artist A's tracks 1-5 sit close together, B's 6-10 a little further; the route starts and
        // ends with B so the window's fixed ends stay out of the way.
        val rows = listOf(6, 1, 2, 3, 4, 5, 7, 8, 9, 10)
        val plain = JourneySequencer.order(snapshot, rows)
        val spaced = JourneySequencer.order(snapshot, rows, sameArtistCost = 2f)
        assertEquals(rows.toSet(), spaced.toSet())
        assertEquals(rows.first(), spaced.first())
        assertEquals(rows.last(), spaced.last())
        assertTrue(neighbours(snapshot, spaced) < neighbours(snapshot, plain), "plain $plain, spaced $spaced")
        assertEquals(plain, JourneySequencer.order(snapshot, rows, sameArtistCost = 0f))
    }

    private fun picks(snapshot: SmartSnapshot, penalty: Float): List<Int> = SmartChain(
        snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true, artistRunPenalty = penalty),
    ).build(TrackId("0"), 8, FloatArray(5)).rows

    private fun artists(snapshot: SmartSnapshot, rows: List<Int>) = rows.map { snapshot.tracks[it].meta.artist }

    /** The longest run of one artist, the seed (row 0) first. */
    private fun longestRun(snapshot: SmartSnapshot, rows: List<Int>): Int {
        val names = artists(snapshot, listOf(0) + rows)
        var best = 1
        var run = 1
        for (i in 1 until names.size) {
            run = if (names[i] == names[i - 1]) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }

    private fun neighbours(snapshot: SmartSnapshot, rows: List<Int>) =
        artists(snapshot, rows).zipWithNext().count { (a, b) -> a == b }

    /**
     * The seed (row 0) and five more tracks by its artist A (1-5) that sound almost like it; five by B
     * (6-10) at [otherCloseness] to the seed; fillers that keep the library mean near zero.
     */
    private fun twoArtists(otherCloseness: Float): SmartSnapshot {
        fun unit(vararg parts: Pair<Int, Float>) = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { v ->
            for ((axis, value) in parts) v[axis] += value
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            for (i in v.indices) v[i] /= norm
        }
        val far = sqrt(1f - otherCloseness * otherCloseness)
        val tracks = buildList {
            add(unit(0 to 1f) to "A")
            for (i in 1..5) add(unit(0 to 1f, (10 + i) to 0.25f) to "A")
            for (j in 1..5) add(unit(0 to otherCloseness, 1 to 0.3f, (30 + j) to far) to "B")
            for (k in 1..12) add(unit(0 to -1f, (60 + k) to 0.5f) to "Filler $k")
        }
        return requireNotNull(SmartSnapshot.build(tracks.mapIndexed { row, (audio, artist) ->
            SmartTrack(TrackId(row.toString()), audio, meta = TrackMeta("Title $row", artist, null, null, null))
        }))
    }
}

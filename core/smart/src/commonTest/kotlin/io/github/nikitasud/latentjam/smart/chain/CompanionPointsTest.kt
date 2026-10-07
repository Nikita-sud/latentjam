/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Marked playlists as points (ChainTuning.companionPoints): nothing is guaranteed, everything is scored. */
internal class CompanionPointsTest {
    private val soft = ChainTuning(continueAfterExhaustion = true, neighbourhoodBonus = 0f)

    @Test
    fun `points change nothing without marked playlists`() {
        val snapshot = farPlaylist()
        assertEquals(
            SmartChain(snapshot, null, tuning = soft).build(TrackId("0"), 10, FloatArray(5)),
            SmartChain(snapshot, null, tuning = soft.copy(companionPoints = CompanionPoints(1f, 1f)))
                .build(TrackId("0"), 10, FloatArray(5)),
        )
    }

    @Test
    fun `a marked playlist gets no guaranteed turn`() {
        val snapshot = farPlaylist()
        val marked = listOf((0..4).map { TrackId("$it") }.toSet())
        // The shipped mechanism hands every third pick to the seed's marked playlist.
        val quota = SmartChain(snapshot, null, companionGroups = marked).build(TrackId("0"), 6, FloatArray(5)).rows
        assertTrue(quota[2] in 1..4, "quota turn: $quota")
        val points = SmartChain(
            snapshot, null, companionGroups = marked, tuning = soft.copy(companionPoints = CompanionPoints(0f, 0f)),
        ).build(TrackId("0"), 6, FloatArray(5)).rows
        assertTrue(points.none { it in 1..4 }, "no points, no turn: $points")
    }

    @Test
    fun `a marked playlist that sounds further away comes back with every pick it misses`() {
        val snapshot = farPlaylist()
        val marked = listOf((0..4).map { TrackId("$it") }.toSet())
        fun picks(comeback: Float) = SmartChain(
            snapshot, null, companionGroups = marked,
            tuning = soft.copy(companionPoints = CompanionPoints(0f, comeback)),
        ).build(TrackId("0"), 8, FloatArray(5)).rows
        assertTrue(picks(0f).none { it in 1..4 })
        val back = picks(1f)
        val first = back.indexOfFirst { it in 1..4 }
        assertTrue(first in 1..7, "it misses a pick or more, then plays: $back")
    }

    @Test
    fun `a run inside a small marked playlist pays almost no artist penalty`() {
        // Unrelated tracks make the seed's six-track playlist specific (its share of the library small).
        val snapshot = twoArtists(unrelated = 200)
        fun picks(penalty: Float, marked: List<Set<TrackId>> = emptyList()) = SmartChain(
            snapshot, null, companionGroups = marked,
            tuning = soft.copy(artistRunPenalty = penalty, companionPoints = CompanionPoints(0f, 0f)),
        ).build(TrackId("0"), 8, FloatArray(5)).rows
        val free = picks(0f)
        val paid = picks(1f)
        assertTrue(longestRun(snapshot, paid) < longestRun(snapshot, free), "the fixture's run must pay: $paid")
        // The seed's artist A has its tracks in one small marked playlist with the seed: they run on.
        val marked = picks(1f, listOf((0..5).map { TrackId("$it") }.toSet()))
        assertTrue(longestRun(snapshot, marked) > longestRun(snapshot, paid), "marked $marked, unmarked $paid")
    }

    @Test
    fun `a run inside a playlist of the whole library still pays most of the penalty`() {
        val snapshot = twoArtists()
        val everything = listOf(snapshot.tracks.map { it.id }.toSet())
        fun picks(penalty: Float, marked: List<Set<TrackId>> = emptyList()) = SmartChain(
            snapshot, null, companionGroups = marked,
            tuning = soft.copy(artistRunPenalty = penalty, companionPoints = CompanionPoints(0f, 0f)),
        ).build(TrackId("0"), 8, FloatArray(5)).rows
        // A playlist of everything says nothing about two tracks: its specificity is the floor, 1/4.
        assertTrue(longestRun(snapshot, picks(1f, everything)) < longestRun(snapshot, picks(0f)))
    }

    @Test
    fun `the order keeps one artist's tracks of a marked playlist together`() {
        val snapshot = twoArtists(unrelated = 200, otherCloseness = 0.8f, othersByOneArtist = false)
        val rows = listOf(6, 1, 2, 3, 4, 5, 7, 8, 9, 10)
        val spaced = JourneySequencer.order(snapshot, rows, sameArtistCost = 2f)
        assertTrue(neighbours(snapshot, spaced) < neighbours(snapshot, JourneySequencer.order(snapshot, rows)))
        // A's tracks in one small marked playlist: two of them side by side cost almost nothing more.
        val playlist = CompanionMembership.build(
            snapshot.tracks.map { it.id }, listOf((1..5).map { TrackId("$it") }.toSet()),
        )
        val marked = JourneySequencer.order(snapshot, rows, sameArtistCost = 2f, companions = playlist)
        assertTrue(neighbours(snapshot, marked) > neighbours(snapshot, spaced), "marked $marked, unmarked $spaced")
    }

    @Test
    fun `the order brings two tracks of a marked playlist next to each other`() {
        val snapshot = opposites()
        // Two marked tracks that sound opposite to each other, among close ones; the ends stay where they are.
        val rows = listOf(5, 1, 6, 7, 8, 2, 9, 10)
        val playlist = CompanionMembership.build(
            snapshot.tracks.map { it.id }, listOf(setOf(TrackId("1"), TrackId("2"))),
        )
        fun adjacent(order: List<Int>) = order.zipWithNext().any { (a, b) -> setOf(a, b) == setOf(1, 2) }
        assertFalse(adjacent(JourneySequencer.order(snapshot, rows)), "the fixture must keep them apart")
        assertTrue(adjacent(JourneySequencer.order(snapshot, rows, companions = playlist, togetherCost = 5f)))
    }

    private fun neighbours(snapshot: SmartSnapshot, rows: List<Int>) =
        rows.zipWithNext().count { (a, b) -> snapshot.tracks[a].meta.artist == snapshot.tracks[b].meta.artist }

    private fun longestRun(snapshot: SmartSnapshot, rows: List<Int>): Int {
        val names = (listOf(0) + rows).map { snapshot.tracks[it].meta.artist }
        var best = 1
        var run = 1
        for (i in 1 until names.size) {
            run = if (names[i] == names[i - 1]) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }

    private fun unit(vararg parts: Pair<Int, Float>) = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { v ->
        for ((axis, value) in parts) v[axis] += value
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= norm
    }

    /**
     * The seed (row 0) and its marked playlist (1-4, one artist) that sounds only a little like it; twenty
     * closer tracks by twenty artists (5-24); fillers that keep the library mean near zero.
     */
    private fun farPlaylist(): SmartSnapshot {
        val tracks = buildList {
            add(unit(0 to 1f) to "Seed")
            for (i in 1..4) add(unit(0 to 0.45f, (100 + i) to 0.9f) to "Marked")
            for (k in 5..24) add(unit(0 to 1f, (200 + k) to 0.45f) to "Close $k")
            for (k in 25..44) add(unit(0 to -1f, (400 + k) to 0.5f) to "Filler $k")
        }
        return snapshotOf(tracks)
    }

    /** The seed; two tracks (1, 2) a little like it and opposite to each other; close tracks 5-12; fillers. */
    private fun opposites(): SmartSnapshot {
        val tracks = buildList {
            add(unit(0 to 1f) to "Seed")
            add(unit(0 to 0.45f, 101 to 0.9f) to "Up")
            add(unit(0 to 0.45f, 101 to -0.9f) to "Down")
            for (k in 3..4) add(unit(0 to -1f, (300 + k) to 0.5f) to "Spare $k")
            for (k in 5..12) add(unit(0 to 1f, (200 + k) to 0.45f) to "Close $k")
            for (k in 13..30) add(unit(0 to -1f, (400 + k) to 0.5f) to "Filler $k")
        }
        return snapshotOf(tracks)
    }

    /**
     * The seed and five tracks by its artist A (1-5) close to it; five at [otherCloseness] (6-10), by B or by five
     * artists; fillers that keep the library mean near zero, and [unrelated] tracks each on an axis of its own.
     */
    private fun twoArtists(
        unrelated: Int = 0,
        otherCloseness: Float = 0.5f,
        othersByOneArtist: Boolean = true,
    ): SmartSnapshot {
        val far = sqrt(1f - otherCloseness * otherCloseness)
        val tracks = buildList {
            add(unit(0 to 1f) to "A")
            for (i in 1..5) add(unit(0 to 1f, (10 + i) to 0.25f) to "A")
            for (j in 1..5) {
                add(unit(0 to otherCloseness, 1 to 0.3f, (30 + j) to far) to if (othersByOneArtist) "B" else "B$j")
            }
            for (k in 1..12) add(unit(0 to -1f, (60 + k) to 0.5f) to "Filler $k")
            for (k in 1..unrelated) add(unit((600 + k) to 1f) to "Unrelated $k")
        }
        return snapshotOf(tracks)
    }

    private fun snapshotOf(tracks: List<Pair<FloatArray, String>>) =
        requireNotNull(SmartSnapshot.build(tracks.mapIndexed { row, (audio, artist) ->
            SmartTrack(TrackId(row.toString()), audio, meta = TrackMeta("Title $row", artist, null, null, null))
        }))
}

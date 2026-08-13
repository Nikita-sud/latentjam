/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ListeningOverviewTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    /** UTC calendar, so every expectation below is a plain division. */
    private val utc = { epochMs: Long ->
        LocalTimePoint(
            epochDay = epochMs.floorDiv(day),
            hourOfDay = (epochMs.mod(day) / hour).toInt(),
        )
    }

    private fun event(
        track: String,
        startedAtMs: Long,
        playedMs: Long = 60_000,
        completed: Boolean = true,
        skipped: Boolean = false,
    ) = ListenEvent(
        trackId = TrackId(track),
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = 180_000,
        completed = completed,
        skipped = skipped,
    )

    @Test
    fun periodFilterKeepsAggregatesInsideTheWindow() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("old", startedAtMs = 1 * day, playedMs = 999_999),
                event("a", startedAtMs = 10 * day + 8 * hour, playedMs = 120_000),
                event("a", startedAtMs = 10 * day + 8 * hour + 1, playedMs = 60_000),
                event("b", startedAtMs = 10 * day + 22 * hour, skipped = true, completed = false),
            ),
            artistOf = { if (it.value == "a") "Artist A" else null },
            sinceMs = 10 * day,
            nowMs = 10 * day + 23 * hour,
            timePointOf = utc,
        )

        assertEquals(3, overview.plays)
        assertEquals(240_000, overview.playedMs)
        assertEquals(2, overview.distinctTracks)
        assertEquals(2f / 3f, overview.completionRate)
        assertEquals(1f / 3f, overview.skipRate)
        assertEquals(2, overview.playsByHour[8])
        assertEquals(1, overview.playsByHour[22])
        assertEquals(
            listOf(TrackListening(TrackId("a"), plays = 2, playedMs = 180_000)),
            overview.topTracks.take(1),
        )
        // Track "b" has no artist and cannot dilute the artist chart.
        assertEquals(
            listOf(ArtistListening("Artist A", plays = 2, playedMs = 180_000)),
            overview.topArtists,
        )
    }

    @Test
    fun streaksJudgeTheWholeLogNotThePeriod() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("a", startedAtMs = 3 * day), // 3-day run start
                event("a", startedAtMs = 4 * day),
                event("a", startedAtMs = 5 * day),
                event("a", startedAtMs = 8 * day),
                event("a", startedAtMs = 9 * day),
            ),
            artistOf = { null },
            sinceMs = 9 * day, // window shows one day only
            nowMs = 9 * day + 5 * hour,
            timePointOf = utc,
        )

        assertEquals(2, overview.currentStreakDays)
        assertEquals(3, overview.longestStreakDays)
    }

    @Test
    fun aQuietTodayKeepsYesterdaysStreakAlive() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("a", startedAtMs = 6 * day),
                event("a", startedAtMs = 7 * day),
            ),
            artistOf = { null },
            sinceMs = null,
            nowMs = 8 * day + 9 * hour, // today has no listening yet
            timePointOf = utc,
        )
        assertEquals(2, overview.currentStreakDays)
    }

    @Test
    fun aRealGapEndsTheCurrentStreakButNotTheRecord() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("a", startedAtMs = 1 * day),
                event("a", startedAtMs = 2 * day),
                event("a", startedAtMs = 3 * day),
            ),
            artistOf = { null },
            sinceMs = null,
            nowMs = 9 * day,
            timePointOf = utc,
        )
        assertEquals(0, overview.currentStreakDays)
        assertEquals(3, overview.longestStreakDays)
    }

    @Test
    fun emptyLogYieldsZeroesWithoutDividingByThem() {
        val overview = ListeningOverviews.summarize(
            events = emptyList(),
            artistOf = { null },
            sinceMs = null,
            nowMs = 5 * day,
            timePointOf = utc,
        )
        assertEquals(0, overview.plays)
        assertEquals(0f, overview.completionRate)
        assertEquals(0f, overview.skipRate)
        assertEquals(0, overview.currentStreakDays)
        assertEquals(0, overview.longestStreakDays)
        assertEquals(List(24) { 0 }, overview.playsByHour)
    }
}

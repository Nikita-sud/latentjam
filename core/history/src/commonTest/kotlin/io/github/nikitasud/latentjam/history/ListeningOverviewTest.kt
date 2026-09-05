/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        shuffleMode: String? = null,
        listenedMs: Long? = null,
    ) = ListenEvent(
        trackId = TrackId(track),
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = 180_000,
        completed = completed,
        skipped = skipped,
        shuffleMode = shuffleMode,
        listenedMs = listenedMs,
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
        assertEquals(0, overview.activeDays)
        assertEquals(0, overview.distinctArtists)
        assertEquals(0, overview.newTracks)
        assertEquals(0, overview.repeatPlays)
        assertEquals(0, overview.libraryTracksHeard)
        assertEquals(0, overview.smartPlays)
        assertNull(overview.previousPeriod)
        assertEquals(30, overview.dailyListening.size)
        assertTrue(overview.dailyListening.all { it.plays == 0 && it.playedMs == 0L })
    }

    @Test
    fun futureClockSkewDoesNotCountOrBreakTheCurrentStreak() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("today", startedAtMs = 8 * day),
                event("future", startedAtMs = 20 * day),
            ),
            artistOf = { null },
            sinceMs = null,
            nowMs = 8 * day + hour,
            timePointOf = utc,
        )

        assertEquals(1, overview.plays)
        assertEquals(1, overview.currentStreakDays)
        assertEquals(1, overview.longestStreakDays)
    }

    @Test
    fun playedDurationsSaturateInsteadOfOverflowing() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("a", startedAtMs = day, playedMs = Long.MAX_VALUE),
                event("a", startedAtMs = day + 1, playedMs = 1),
            ),
            artistOf = { "Artist" },
            sinceMs = null,
            nowMs = 2 * day,
            timePointOf = utc,
        )

        assertEquals(Long.MAX_VALUE, overview.playedMs)
        assertEquals(Long.MAX_VALUE, overview.topTracks.single().playedMs)
        assertEquals(Long.MAX_VALUE, overview.topArtists.single().playedMs)
        assertEquals(Long.MAX_VALUE, overview.dailyListening.single { it.epochDay == 1L }.playedMs)
    }

    @Test
    fun topTrackLimitIsAppliedAfterUnavailableTracksAreExcluded() {
        val events = (0..10).flatMap { index ->
            List(20 - index) { event("track-$index", startedAtMs = day + it) }
        }
        val overview = ListeningOverviews.summarize(
            events = events,
            artistOf = { null },
            sinceMs = null,
            nowMs = 2 * day,
            timePointOf = utc,
            includeTrack = { it.value == "track-10" },
        )

        assertEquals(listOf(TrackId("track-10")), overview.topTracks.map { it.trackId })
    }

    @Test
    fun calendarConversionRunsOncePerEventPlusTheCurrentClock() {
        val events = List(50) { index -> event("a", startedAtMs = day + index) }
        var conversions = 0

        ListeningOverviews.summarize(
            events = events,
            artistOf = { null },
            sinceMs = day + 25,
            nowMs = 2 * day,
            timePointOf = { timestamp ->
                conversions++
                utc(timestamp)
            },
        )

        assertEquals(events.size + 1, conversions)
    }

    @Test
    fun discoveryVarietyAndSmartListeningDescribeTheSelectedPeriod() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("familiar", startedAtMs = day),
                event("familiar", startedAtMs = 10 * day, shuffleMode = "SMART"),
                event("familiar", startedAtMs = 10 * day + hour, shuffleMode = "ON"),
                event("new", startedAtMs = 11 * day, shuffleMode = "SMART"),
                event("removed", startedAtMs = 11 * day + hour),
                event("unknown", startedAtMs = 11 * day + 2 * hour),
            ),
            artistOf = {
                when (it.value) {
                    "familiar", "new" -> "Shared artist"
                    "removed" -> "Former artist"
                    else -> "  "
                }
            },
            sinceMs = 10 * day,
            nowMs = 12 * day,
            timePointOf = utc,
            includeTrack = { it.value == "familiar" || it.value == "new" },
        )

        assertEquals(5, overview.plays)
        assertEquals(4, overview.distinctTracks)
        assertEquals(2, overview.activeDays)
        assertEquals(2, overview.distinctArtists)
        assertEquals(3, overview.newTracks)
        assertEquals(1, overview.repeatPlays)
        assertEquals(2, overview.smartPlays)
        assertEquals(2, overview.libraryTracksHeard)
        assertEquals(listOf("familiar", "new"), overview.topTracks.map { it.trackId.value })
        assertEquals(
            listOf(ArtistListening("Shared artist", plays = 3, playedMs = 180_000)),
            overview.topArtists,
        )
    }

    @Test
    fun newTracksUseTheFirstRecordedListenRegardlessOfLogOrder() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("familiar", startedAtMs = 10 * day),
                event("new", startedAtMs = 10 * day + hour),
                event("new", startedAtMs = 10 * day + 2 * hour),
                event("familiar", startedAtMs = day),
                event("new", startedAtMs = -1),
                event("future", startedAtMs = 30 * day),
            ),
            artistOf = { null },
            sinceMs = 10 * day,
            nowMs = 11 * day,
            timePointOf = utc,
        )

        assertEquals(3, overview.plays)
        assertEquals(1, overview.newTracks)
        assertEquals(1, overview.repeatPlays)
    }

    @Test
    fun previousWindowHasEqualDurationAndDoesNotDoubleCountItsEnd() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("too-old", startedAtMs = 3 * day - 1),
                event("previous-start", startedAtMs = 3 * day, listenedMs = 20_000),
                event("previous-end", startedAtMs = 10 * day - 1, listenedMs = 30_000),
                event("current-start", startedAtMs = 10 * day, listenedMs = 40_000),
                event("now", startedAtMs = 17 * day, listenedMs = 50_000),
                event("future", startedAtMs = 17 * day + 1),
            ),
            artistOf = { null },
            sinceMs = 10 * day,
            nowMs = 17 * day,
            timePointOf = utc,
        )

        assertEquals(ListeningPeriodTotals(plays = 2, playedMs = 50_000), overview.previousPeriod)
        assertEquals(2, overview.plays)
        assertEquals(90_000, overview.playedMs)
        assertEquals(90_000, overview.dailyListening.sumOf { it.playedMs })
        assertEquals(90_000, overview.topTracks.sumOf { it.playedMs })
    }

    @Test
    fun longClockAndPreEpochComparisonBoundsCannotOverflow() {
        val since = Long.MAX_VALUE / 2
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("negative", startedAtMs = -1),
                event("epoch", startedAtMs = 0, playedMs = Long.MAX_VALUE),
                event("previous", startedAtMs = since - 1, playedMs = 1),
                event("current", startedAtMs = since),
                event("now", startedAtMs = Long.MAX_VALUE),
            ),
            artistOf = { null },
            sinceMs = since,
            nowMs = Long.MAX_VALUE,
            timePointOf = utc,
        )

        assertEquals(ListeningPeriodTotals(plays = 2, playedMs = Long.MAX_VALUE), overview.previousPeriod)
        assertEquals(2, overview.plays)
        assertEquals(30, overview.dailyListening.size)
        assertEquals(1, overview.dailyListening.last().plays)
    }

    @Test
    fun futurePeriodStartsAndNegativeCurrentClocksYieldEmptyTotals() {
        val events = listOf(event("a", startedAtMs = 0), event("b", startedAtMs = day))
        for ((since, now) in listOf(2 * day to day, 0L to -1L)) {
            val overview = ListeningOverviews.summarize(
                events = events,
                artistOf = { null },
                sinceMs = since,
                nowMs = now,
                timePointOf = utc,
            )

            assertEquals(0, overview.plays)
            assertEquals(0, overview.newTracks)
            assertEquals(ListeningPeriodTotals(0, 0), overview.previousPeriod)
        }
    }

    @Test
    fun calendarChartIsZeroFilledAndDistinctFromRollingPeriodTotals() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("partial-first-day", startedAtMs = 10 * day + 12 * hour),
                event("chart-start", startedAtMs = 11 * day),
                event("today", startedAtMs = 17 * day),
            ),
            artistOf = { null },
            sinceMs = 10 * day + 12 * hour,
            nowMs = 17 * day + 12 * hour,
            timePointOf = utc,
            chartDays = 7,
        )

        assertEquals(3, overview.plays)
        assertEquals(3, overview.activeDays)
        assertEquals((11L..17L).toList(), overview.dailyListening.map { it.epochDay })
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 1), overview.dailyListening.map { it.plays })
        assertEquals(listOf(60_000L, 0, 0, 0, 0, 0, 60_000L), overview.dailyListening.map { it.playedMs })
    }

    @Test
    fun dailyChartUsesLocalDatesAndClipsPlaysBeforeThePeriodBoundary() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("before-period", startedAtMs = 20 * day + 20 * hour),
                event("before-midnight", startedAtMs = 20 * day + 21 * hour),
                event("after-midnight", startedAtMs = 20 * day + 23 * hour),
            ),
            artistOf = { null },
            sinceMs = 20 * day + 21 * hour,
            nowMs = 20 * day + 23 * hour,
            timePointOf = { utc(it + 2 * hour) },
            chartDays = 2,
        )

        assertEquals(
            listOf(DailyListening(20, 1, 60_000), DailyListening(21, 1, 60_000)),
            overview.dailyListening,
        )
        assertEquals(2, overview.activeDays)
        assertEquals(1, overview.playsByHour[23])
        assertEquals(1, overview.playsByHour[1])
    }

    @Test
    fun allTimeChartsStayBoundedAndDoNotDiscardHistoricalTotals() {
        val events = listOf(event("old", startedAtMs = day), event("today", startedAtMs = 100 * day))
        for ((requestedDays, expectedDays) in listOf(Int.MAX_VALUE to 30, Int.MIN_VALUE to 1)) {
            val overview = ListeningOverviews.summarize(
                events = events,
                artistOf = { null },
                sinceMs = null,
                nowMs = 100 * day,
                timePointOf = utc,
                chartDays = requestedDays,
            )

            assertEquals(2, overview.plays)
            assertEquals(2, overview.newTracks)
            assertEquals(expectedDays, overview.dailyListening.size)
            assertEquals(1, overview.dailyListening.sumOf { it.plays })
            assertEquals(100, overview.dailyListening.last().epochDay)
            assertNull(overview.previousPeriod)
        }
    }

    @Test
    fun contradictoryOutcomesAndNegativeDurationsCannotInflateTheBreakdown() {
        val overview = ListeningOverviews.summarize(
            events = listOf(
                event("completed", startedAtMs = day, completed = true, skipped = true, listenedMs = -1),
                event("skipped", startedAtMs = day, completed = false, skipped = true, playedMs = -1),
                event("partial", startedAtMs = day, completed = false, skipped = false, listenedMs = 10_000),
            ),
            artistOf = { "Artist" },
            sinceMs = null,
            nowMs = day,
            timePointOf = utc,
        )

        assertEquals(1f / 3f, overview.completionRate)
        assertEquals(1f / 3f, overview.skipRate)
        assertTrue(overview.completionRate + overview.skipRate <= 1f)
        assertEquals(10_000, overview.playedMs)
        assertEquals(10_000, overview.topArtists.single().playedMs)
        assertEquals(10_000, overview.dailyListening.last().playedMs)
    }

    @Test
    fun rejectedEventsStillAllowLongHistoryWorkToBeCancelled() {
        var checks = 0

        assertFailsWith<IllegalStateException> {
            ListeningOverviews.summarize(
                events = List(10_000) { event("future", startedAtMs = 2 * day) },
                artistOf = { null },
                sinceMs = null,
                nowMs = day,
                timePointOf = utc,
                cancellationCheck = {
                    checks++
                    if (checks == 3) error("Cancelled")
                },
            )
        }

        assertEquals(3, checks)
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/** Local-timezone calendar coordinates of one instant, for clocks and streaks. */
public data class LocalTimePoint(
    public val epochDay: Long,
    public val hourOfDay: Int,
)

/** Converts an epoch instant into the device's local calendar day and hour. */
public expect fun localTimePoint(epochMs: Long): LocalTimePoint

/** One track's listening weight inside an overview period. */
public data class TrackListening(
    public val trackId: TrackId,
    public val plays: Int,
    public val playedMs: Long,
)

/** One artist's listening weight inside an overview period. */
public data class ArtistListening(
    public val artist: String,
    public val plays: Int,
    public val playedMs: Long,
)

/** Listening totals for the rolling window immediately before the selected period. */
public data class ListeningPeriodTotals(
    public val plays: Int,
    public val playedMs: Long,
)

/** One local calendar day's listening, including an explicit zero on quiet days. */
public data class DailyListening(
    public val epochDay: Long,
    public val plays: Int,
    public val playedMs: Long,
)

/**
 * Everything the statistics page states about a period, and nothing about how to word it.
 *
 * [playsByHour] always has 24 buckets in local time. The streaks deliberately ignore the
 * period filter: a habit is a fact about the whole log, not about the window being browsed.
 * Historical totals include tracks no longer in the library. [libraryTracksHeard] and the
 * rankings include only available tracks; [distinctArtists] counts all known artist names.
 * [newTracks] counts tracks whose first recorded listen falls in the selected period.
 * [dailyListening] is a bounded calendar-day chart, not a second rolling-period total.
 */
public data class ListeningOverview(
    public val plays: Int,
    public val playedMs: Long,
    public val distinctTracks: Int,
    public val completionRate: Float,
    public val skipRate: Float,
    public val playsByHour: List<Int>,
    public val currentStreakDays: Int,
    public val longestStreakDays: Int,
    public val topTracks: List<TrackListening>,
    public val topArtists: List<ArtistListening>,
    public val activeDays: Int = 0,
    public val distinctArtists: Int = 0,
    public val newTracks: Int = 0,
    public val repeatPlays: Int = 0,
    public val libraryTracksHeard: Int = 0,
    public val dailyListening: List<DailyListening> = emptyList(),
    public val smartPlays: Int = 0,
    public val previousPeriod: ListeningPeriodTotals? = null,
)

/**
 * Turns the raw listening log into the statistics page's facts.
 *
 * Pure and clock-injected: callers pass wall time and (in tests) the calendar mapping, so every
 * figure here is reproducible from a log snapshot alone.
 */
public object ListeningOverviews {

    private const val TOP_LIMIT = 10

    public fun summarize(
        events: List<ListenEvent>,
        artistOf: (TrackId) -> String?,
        /** Inclusive lower bound on [ListenEvent.startedAtMs]; null means the whole log. */
        sinceMs: Long?,
        nowMs: Long,
        timePointOf: (Long) -> LocalTimePoint = ::localTimePoint,
        includeTrack: (TrackId) -> Boolean = { true },
        cancellationCheck: () -> Unit = {},
        /**
         * Calendar dates shown, clamped to 1..30, ending with today's local date. Buckets contain
         * only selected-period events. A rolling seven-day total can start partway through an
         * eighth calendar date; a seven-date chart deliberately excludes that partial first date.
         * All-time overviews likewise show only these trailing calendar dates in the chart.
         */
        chartDays: Int = 30,
    ): ListeningOverview {
        cancellationCheck()
        val today = timePointOf(nowMs.coerceAtLeast(0)).epochDay
        val chartCount = chartDays.coerceIn(1, MAX_CHART_DAYS)
        val firstChartDay = today - (chartCount - 1)
        val dailyPlays = IntArray(chartCount)
        val dailyPlayedMs = LongArray(chartCount)
        val periodStart = sinceMs?.coerceAtLeast(0)
        // Both operands are non-negative, so subtraction cannot overflow even at Long.MAX_VALUE.
        // The previous window is [previousStart, periodStart); the current one includes its start.
        val previousStart = periodStart?.takeIf { it <= nowMs }?.let { start ->
            (start - (nowMs - start)).coerceAtLeast(0)
        }
        var plays = 0
        var playedMs = 0L
        var completions = 0
        var skips = 0
        var smartPlays = 0
        var previousPlays = 0
        var previousPlayedMs = 0L
        val byHour = IntArray(24)
        val listeningDays = HashSet<Long>()
        val periodDays = HashSet<Long>()
        val tracksBeforePeriod = HashSet<TrackId>()
        val perTrack = LinkedHashMap<TrackId, ListeningTotal>()
        events.forEachIndexed { index, event ->
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            // A clock correction must not inflate totals or break today's streak. Invalid
            // negative timestamps are also excluded, including from first-listen discovery.
            if (event.startedAtMs !in 0..nowMs) return@forEachIndexed
            val point = timePointOf(event.startedAtMs)
            listeningDays += point.epochDay
            val duration = event.effectiveListenedMs.coerceAtLeast(0)
            if (periodStart != null && event.startedAtMs < periodStart) {
                tracksBeforePeriod += event.trackId
                if (previousStart != null && event.startedAtMs >= previousStart) {
                    previousPlays++
                    previousPlayedMs = saturatingDurationAdd(previousPlayedMs, duration)
                }
                return@forEachIndexed
            }

            plays++
            playedMs = saturatingDurationAdd(playedMs, duration)
            // Completion wins when a corrupt/legacy event claims both outcomes. This keeps the
            // two outcome shares disjoint and their combined rate at or below 100 percent.
            if (event.completed) completions++ else if (event.skipped) skips++
            if (event.shuffleMode == "SMART") smartPlays++
            periodDays += point.epochDay
            if (point.hourOfDay in 0..23) byHour[point.hourOfDay]++
            if (point.epochDay in firstChartDay..today) {
                val dayIndex = (point.epochDay - firstChartDay).toInt()
                dailyPlays[dayIndex]++
                dailyPlayedMs[dayIndex] = saturatingDurationAdd(dailyPlayedMs[dayIndex], duration)
            }
            perTrack.getOrPut(event.trackId) { ListeningTotal() }.add(1, duration)
        }

        val availableTracks = ArrayList<TrackListening>()
        val perArtist = LinkedHashMap<String, ListeningTotal>()
        val knownArtists = HashSet<String>()
        var newTracks = 0
        perTrack.entries.forEachIndexed { index, (trackId, total) ->
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            if (trackId !in tracksBeforePeriod) newTracks++
            val artist = artistOf(trackId)?.takeIf { it.isNotBlank() }
            if (artist != null) knownArtists += artist
            if (!includeTrack(trackId)) return@forEachIndexed
            availableTracks += TrackListening(trackId, total.plays, total.playedMs)
            if (artist != null) {
                perArtist.getOrPut(artist) { ListeningTotal() }.add(total.plays, total.playedMs)
            }
        }
        val topTracks = availableTracks
            .sortedWith(
                compareByDescending<TrackListening> { it.plays }.thenByDescending { it.playedMs },
            )
            .take(TOP_LIMIT)
        val topArtists = perArtist.entries
            .map { (artist, total) -> ArtistListening(artist, total.plays, total.playedMs) }
            .sortedWith(
                compareByDescending<ArtistListening> { it.plays }.thenByDescending { it.playedMs },
            )
            .take(TOP_LIMIT)

        cancellationCheck()
        val (current, longest) = streaks(
            days = listeningDays,
            today = today,
            cancellationCheck = cancellationCheck,
        )

        return ListeningOverview(
            plays = plays,
            playedMs = playedMs,
            distinctTracks = perTrack.size,
            completionRate = if (plays == 0) 0f else completions.toFloat() / plays,
            skipRate = if (plays == 0) 0f else skips.toFloat() / plays,
            playsByHour = byHour.toList(),
            currentStreakDays = current,
            longestStreakDays = longest,
            topTracks = topTracks,
            topArtists = topArtists,
            activeDays = periodDays.size,
            distinctArtists = knownArtists.size,
            newTracks = newTracks,
            repeatPlays = plays - perTrack.size,
            libraryTracksHeard = availableTracks.size,
            dailyListening = List(chartCount) { index ->
                DailyListening(firstChartDay + index, dailyPlays[index], dailyPlayedMs[index])
            },
            smartPlays = smartPlays,
            previousPeriod = periodStart?.let {
                ListeningPeriodTotals(previousPlays, previousPlayedMs)
            },
        )
    }

    /**
     * A day counts once no matter how much was played. The current streak survives an
     * unfinished today: not having listened YET must not read as a broken habit at breakfast.
     */
    private fun streaks(
        days: Set<Long>,
        today: Long,
        cancellationCheck: () -> Unit,
    ): Pair<Int, Int> {
        val sortedDays = days.toLongArray()
            .also { it.sort() }
        if (sortedDays.isEmpty()) return 0 to 0

        var longest = 1
        var run = 1
        for (index in 1 until sortedDays.size) {
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            run = if (sortedDays[index] == sortedDays[index - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }

        val anchor = when {
            sortedDays.last() == today -> today
            sortedDays.last() == today - 1 -> today - 1
            else -> return 0 to longest
        }
        var current = 0
        var cursor = anchor
        while (cursor in days) {
            if (current and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            current++
            cursor--
        }
        return current to longest
    }

    /** Mutable per-key totals avoid allocating a replacement data class for every play. */
    private class ListeningTotal {
        var plays: Int = 0
        var playedMs: Long = 0

        fun add(plays: Int, playedMs: Long) {
            this.plays += plays
            this.playedMs = saturatingDurationAdd(this.playedMs, playedMs)
        }
    }

    private const val CANCELLATION_CHECK_MASK = 1023
    private const val MAX_CHART_DAYS = 30
}

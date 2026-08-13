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

/**
 * Everything the statistics page states about a period, and nothing about how to word it.
 *
 * [playsByHour] always has 24 buckets in local time. The streaks deliberately ignore the
 * period filter: a habit is a fact about the whole log, not about the window being browsed.
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
    ): ListeningOverview {
        val inPeriod = if (sinceMs == null) events else events.filter { it.startedAtMs >= sinceMs }

        val plays = inPeriod.size
        val completions = inPeriod.count { it.completed }
        val skips = inPeriod.count { it.skipped }

        val byHour = IntArray(24)
        for (event in inPeriod) {
            val hour = timePointOf(event.startedAtMs).hourOfDay
            if (hour in 0..23) byHour[hour]++
        }

        val perTrack = LinkedHashMap<TrackId, TrackListening>()
        for (event in inPeriod) {
            val previous = perTrack[event.trackId]
            perTrack[event.trackId] = TrackListening(
                trackId = event.trackId,
                plays = (previous?.plays ?: 0) + 1,
                playedMs = (previous?.playedMs ?: 0L) + event.playedMs,
            )
        }
        val topTracks = perTrack.values
            .sortedWith(
                compareByDescending<TrackListening> { it.plays }.thenByDescending { it.playedMs },
            )
            .take(TOP_LIMIT)

        val perArtist = LinkedHashMap<String, ArtistListening>()
        for (track in perTrack.values) {
            val artist = artistOf(track.trackId)?.takeIf { it.isNotBlank() } ?: continue
            val previous = perArtist[artist]
            perArtist[artist] = ArtistListening(
                artist = artist,
                plays = (previous?.plays ?: 0) + track.plays,
                playedMs = (previous?.playedMs ?: 0L) + track.playedMs,
            )
        }
        val topArtists = perArtist.values
            .sortedWith(
                compareByDescending<ArtistListening> { it.plays }.thenByDescending { it.playedMs },
            )
            .take(TOP_LIMIT)

        val (current, longest) = streaks(events, nowMs, timePointOf)

        return ListeningOverview(
            plays = plays,
            playedMs = inPeriod.sumOf { it.playedMs },
            distinctTracks = perTrack.size,
            completionRate = if (plays == 0) 0f else completions.toFloat() / plays,
            skipRate = if (plays == 0) 0f else skips.toFloat() / plays,
            playsByHour = byHour.toList(),
            currentStreakDays = current,
            longestStreakDays = longest,
            topTracks = topTracks,
            topArtists = topArtists,
        )
    }

    /**
     * A day counts once no matter how much was played. The current streak survives an
     * unfinished today: not having listened YET must not read as a broken habit at breakfast.
     */
    private fun streaks(
        events: List<ListenEvent>,
        nowMs: Long,
        timePointOf: (Long) -> LocalTimePoint,
    ): Pair<Int, Int> {
        val days = events.mapTo(HashSet()) { timePointOf(it.startedAtMs).epochDay }
            .toLongArray()
            .also { it.sort() }
        if (days.isEmpty()) return 0 to 0

        var longest = 1
        var run = 1
        for (index in 1 until days.size) {
            run = if (days[index] == days[index - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }

        val today = timePointOf(nowMs).epochDay
        val anchor = when {
            days.last() == today -> today
            days.last() == today - 1 -> today - 1
            else -> return 0 to longest
        }
        val daySet = days.toHashSet()
        var current = 0
        var cursor = anchor
        while (cursor in daySet) {
            current++
            cursor--
        }
        return current to longest
    }
}

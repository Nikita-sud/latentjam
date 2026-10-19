/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.pow

/** A track the listener keeps coming back to lately; [weight] ranks, the counts explain. */
public data class RepeatTrack(
    public val trackId: TrackId,
    public val days: Int,
    public val plays: Int,
    public val weight: Float,
)

/** An artist the listener keeps coming back to lately (the fallback when few single tracks repeat). */
public data class RepeatArtist(
    public val artist: String,
    public val days: Int,
    public val plays: Int,
    public val weight: Float,
)

/** An old favourite not started for a while; [chosenPlays] counts only plays the listener chose. */
public data class SlippingTrack(
    public val trackId: TrackId,
    public val chosenPlays: Int,
    public val lastStartMs: Long,
)

public data class TrackPlays(
    public val trackId: TrackId,
    public val plays: Int,
)

/** "After [from] the listener picked [to]" [count] of [total] times they picked something after [from]. */
public data class TrackSequence(
    public val from: TrackId,
    public val to: TrackId,
    public val count: Int,
    public val total: Int,
)

public data class DecadePlays(
    public val decade: Int,
    public val plays: Int,
)

/**
 * The page's "you lately" and "over time" facts. They describe the whole log as of now and ignore the
 * period filter.
 *
 * Empty lists mean "nothing to show"; the `…Locked` flags say whether that is because the log is still
 * too young (so the page may promise the card) rather than because nothing qualifies.
 */
public data class HabitInsights(
    public val onRepeatTracks: List<RepeatTrack>,
    public val onRepeatArtists: List<RepeatArtist>,
    public val onRepeatLocked: Boolean,
    public val currentStreakDays: Int,
    public val longestStreakDays: Int,
    public val playDaysLastWeek: Int,
    public val slippingAway: List<SlippingTrack>,
    public val slippingAwayLocked: Boolean,
    public val yearAgo: List<TrackPlays>,
    public val sameArtistPicks: Int,
    public val pickSteps: Int,
    public val sequences: List<TrackSequence>,
    public val libraryTracksPlayed: Int,
    public val librarySize: Int,
    public val decades: List<DecadePlays>,
    public val logStartMs: Long?,
) {
    /** Same-artist share is shown only over enough picked steps to mean something. */
    public val sameArtistShown: Boolean get() = pickSteps >= MIN_PICK_STEPS

    public companion object {
        public const val MIN_PICK_STEPS: Int = 30
    }
}

public enum class ActivityScale { DAY, MONTH, YEAR }

/**
 * One bar of the all-time activity chart. [key] is a month index (`year * 12 + month - 1`) or a year;
 * [ago] counts months or years back from the current one.
 */
public data class ActivityBucket(
    public val key: Int,
    public val ago: Int,
    public val plays: Int,
    public val playedMs: Long,
)

/** One ranked artist, album or genre; [trackId] is a representative track for artwork. */
public data class RankedName(
    public val name: String,
    public val plays: Int,
    public val playedMs: Long,
    public val subtitle: String? = null,
    public val trackId: TrackId? = null,
)

public data class SessionSummary(
    public val sessions: Int,
    public val medianListenedMs: Long,
    public val medianPlays: Int,
    public val longestListenedMs: Long,
    public val longestStartMs: Long,
)

/**
 * The period-filtered facts that the overview does not already state.
 *
 * Plays are non-skipped listens. A *new* play is the first non-skipped listen of its track in the whole
 * log; every other play is *familiar*, so each new track contributes exactly one new play.
 */
public data class PeriodInsights(
    public val playedTracks: Int,
    public val playedArtists: Int,
    public val burnedIn: Boolean,
    public val newPlays: Int,
    public val familiarPlays: Int,
    public val newTracks: Int,
    public val newArtists: Int,
    public val previousNewShare: Float?,
    public val stuckArtists: List<RankedName>,
    public val logDaysInsidePeriod: Int?,
    public val logStartDay: Long?,
    public val listenedMsByHour: List<Long>,
    public val sessions: SessionSummary?,
    public val topArtists: List<RankedName>,
    public val topAlbums: List<RankedName>?,
    public val topGenres: List<RankedName>?,
    public val activityScale: ActivityScale,
    public val activity: List<ActivityBucket>,
) {
    public companion object {
        public const val MIN_NEW_PLAYS_BASE: Int = 20
    }
}

/**
 * Turns a [NormalizedHistory] into the statistics page's behavioural facts.
 *
 * Pure and clock-injected like [ListeningOverviews]. [catalog] resolves tracks still in the library;
 * counts include tracks no longer there, while rows that rank or play include only available ones.
 */
public object ListeningInsights {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val REPEAT_WINDOW_DAYS = 28
    private const val REPEAT_HALF_LIFE_DAYS = 14.0
    private const val REPEAT_MIN_PLAYS = 3
    private const val REPEAT_MIN_DAYS = 2
    private const val REPEAT_ROWS = 5
    private const val REPEAT_MIN_TRACK_ROWS = 3
    private const val REPEAT_LOCK_DAYS = 7
    private const val SLIPPING_MIN_CHOSEN = 5
    private const val SLIPPING_MIN_DAYS = 3
    private const val SLIPPING_QUIET_DAYS = 60
    private const val SLIPPING_ACTIVE_DAYS = 10
    private const val SLIPPING_LOCK_DAYS = 90
    private const val SLIPPING_ROWS = 5
    private const val YEAR_DAYS = 365
    private const val YEAR_AGO_SPREAD = 3
    private const val YEAR_AGO_MIN_PLAYS = 2
    private const val YEAR_AGO_ROWS = 3
    private const val SEQUENCE_MIN_COUNT = 3
    private const val SEQUENCE_MIN_SESSIONS = 3
    private const val SEQUENCE_MIN_SHARE = 0.4f
    private const val SEQUENCE_ROWS = 3
    private const val TAG_COVERAGE = 0.6
    private const val TOP_LIMIT = 10
    private const val STUCK_BURN_IN_DAYS = 30
    private const val STUCK_ALL_TIME_DAYS = 90
    private const val STUCK_MIN_PLAYS = 5
    private const val STUCK_MIN_DAYS = 3
    private const val STUCK_ROWS = 3
    private const val MIN_SESSIONS = 3
    private const val MONTH_BARS = 12
    private const val DAILY_UNDER_DAYS = 60
    private const val YEARLY_FROM_DAYS = 2 * 365
    private const val CANCELLATION_CHECK_MASK = 1023

    public fun habits(
        history: NormalizedHistory,
        catalog: (TrackId) -> TrackDescriptor?,
        librarySize: Int,
        nowMs: Long,
        timePointOf: (Long) -> LocalTimePoint = ::localTimePoint,
        cancellationCheck: () -> Unit = {},
    ): HabitInsights {
        cancellationCheck()
        val events = history.events
        val today = timePointOf(nowMs.coerceAtLeast(0)).epochDay
        val firstRepeatDay = today - (REPEAT_WINDOW_DAYS - 1)
        val yearAgo = today - YEAR_DAYS
        val tracks = TrackTable()
        val artists = HashMap<String, ArtistRepeatTotal>()
        val playDays = ArrayList<Long>()
        val recentEventDays = HashSet<Long>()
        val pairs = HashMap<Long, PairTotal>()
        var sameArtistPicks = 0
        var pickSteps = 0
        var previousPlay = -1
        var previousSession = -1

        events.forEachIndexed { index, event ->
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            val day = timePointOf(event.startedAtMs).epochDay
            val track = tracks.indexOf(event.trackId)
            tracks.lastStartMs[track] = event.startedAtMs
            if (day > today - SLIPPING_QUIET_DAYS) recentEventDays += day
            val session = history.sessionIds[index]
            if (session != previousSession) previousPlay = -1
            previousSession = session
            if (event.skipped) return@forEachIndexed

            if (playDays.lastOrNull() != day) playDays += day
            tracks.plays[track]++
            if (event.isChosen()) {
                tracks.chosenPlays[track]++
                if (tracks.lastChosenDay[track] != day) {
                    tracks.chosenDays[track]++
                    tracks.lastChosenDay[track] = day
                }
            }
            if (day in firstRepeatDay..today) {
                tracks.windowPlays[track]++
                if (tracks.lastWindowDay[track] != day) {
                    tracks.windowDays[track]++
                    tracks.lastWindowDay[track] = day
                    tracks.weight[track] += decay(today - day)
                }
                val key = tracks.artistKey(track, catalog)
                if (key.isNotEmpty()) {
                    val total = artists.getOrPut(key) { ArtistRepeatTotal(tracks.artistName(track, catalog)) }
                    total.plays++
                    if (total.lastDay != day) {
                        total.days++
                        total.lastDay = day
                        total.weight += decay(today - day)
                    }
                }
            }
            if (day in (yearAgo - YEAR_AGO_SPREAD)..(yearAgo + YEAR_AGO_SPREAD)) tracks.yearAgoPlays[track]++

            if (previousPlay >= 0 && event.isPick()) {
                val from = tracks.indexOf(events[previousPlay].trackId)
                if (from != track) {
                    tracks.pickStepsFrom[from]++
                    val pair = PairTotal.key(from, track)
                    val total = pairs.getOrPut(pair) { PairTotal() }
                    total.count++
                    if (total.lastSession != session) {
                        total.sessions++
                        total.lastSession = session
                    }
                    val fromKey = tracks.artistKey(from, catalog)
                    val toKey = tracks.artistKey(track, catalog)
                    if (fromKey.isNotEmpty() && toKey.isNotEmpty()) {
                        pickSteps++
                        if (fromKey == toKey) sameArtistPicks++
                    }
                }
            }
            previousPlay = index
        }

        cancellationCheck()
        val logStart = history.logStartMs
        val available = { track: Int -> catalog(tracks.ids[track]) != null }

        // L1: what the listener is into right now, by days with a play in the last four weeks.
        val repeatCandidates = (0 until tracks.size)
            .filter { tracks.windowPlays[it] >= REPEAT_MIN_PLAYS && tracks.windowDays[it] >= REPEAT_MIN_DAYS }
            .filter(available)
            .sortedWith(
                compareByDescending<Int> { tracks.weight[it] }
                    .thenByDescending { tracks.windowPlays[it] }
                    .thenByDescending { tracks.lastStartMs[it] },
            )
        val onRepeatTracks = varied(repeatCandidates, REPEAT_ROWS, tracks, catalog).map {
            RepeatTrack(tracks.ids[it], tracks.windowDays[it], tracks.windowPlays[it], tracks.weight[it].toFloat())
        }
        val onRepeatArtists = if (onRepeatTracks.size >= REPEAT_MIN_TRACK_ROWS) {
            emptyList()
        } else {
            artists.values
                .filter { it.plays >= REPEAT_MIN_PLAYS && it.days >= REPEAT_MIN_DAYS }
                .sortedWith(compareByDescending<ArtistRepeatTotal> { it.weight }.thenByDescending { it.plays })
                .take(REPEAT_ROWS)
                .map { RepeatArtist(it.name, it.days, it.plays, it.weight.toFloat()) }
        }
        val shownRepeatTracks = if (onRepeatArtists.isEmpty()) onRepeatTracks else emptyList()
        val repeatEmpty = shownRepeatTracks.isEmpty() && onRepeatArtists.isEmpty()
        val onRepeatLocked = repeatEmpty && (logStart == null || logStart > nowMs - REPEAT_LOCK_DAYS * DAY_MS)

        // T1: favourites the listener chose, not started for two months, while the log kept recording.
        val slippingLocked = logStart == null || logStart > nowMs - SLIPPING_LOCK_DAYS * DAY_MS
        val recordingKeptUp = recentEventDays.size >= SLIPPING_ACTIVE_DAYS
        val slippingAway = if (slippingLocked || !recordingKeptUp) {
            emptyList()
        } else {
            val quietSince = nowMs - SLIPPING_QUIET_DAYS * DAY_MS
            val candidates = (0 until tracks.size)
                .filter {
                    tracks.chosenPlays[it] >= SLIPPING_MIN_CHOSEN &&
                        tracks.chosenDays[it] >= SLIPPING_MIN_DAYS &&
                        tracks.lastStartMs[it] < quietSince
                }
                .filter(available)
                .sortedWith(
                    compareByDescending<Int> { tracks.chosenPlays[it] }.thenByDescending { tracks.lastStartMs[it] },
                )
            varied(candidates, SLIPPING_ROWS, tracks, catalog).map {
                SlippingTrack(tracks.ids[it], tracks.chosenPlays[it], tracks.lastStartMs[it])
            }
        }

        // T2: a year ago this week, once the log fully covers that week.
        val yearAgoCovered = logStart != null &&
            timePointOf(logStart).epochDay <= yearAgo - YEAR_AGO_SPREAD
        val yearAgoRows = if (!yearAgoCovered) {
            emptyList()
        } else {
            val candidates = (0 until tracks.size)
                .filter { tracks.yearAgoPlays[it] >= YEAR_AGO_MIN_PLAYS }
                .filter(available)
                .sortedByDescending { tracks.yearAgoPlays[it] }
            varied(candidates, YEAR_AGO_ROWS, tracks, catalog).map { TrackPlays(tracks.ids[it], tracks.yearAgoPlays[it]) }
        }

        // T3: what the listener picks right after a track.
        val sequences = pairs.entries
            .asSequence()
            .map { (pair, total) -> Triple(PairTotal.from(pair), PairTotal.to(pair), total) }
            .filter { (from, _, total) ->
                total.count >= SEQUENCE_MIN_COUNT && total.sessions >= SEQUENCE_MIN_SESSIONS &&
                    total.count >= SEQUENCE_MIN_SHARE * tracks.pickStepsFrom[from]
            }
            .filter { (from, to, _) -> available(from) && available(to) }
            .sortedWith(
                compareByDescending<Triple<Int, Int, PairTotal>> { it.third.count }
                    .thenByDescending { it.third.sessions },
            )
            .take(SEQUENCE_ROWS)
            .map { (from, to, total) ->
                TrackSequence(tracks.ids[from], tracks.ids[to], total.count, tracks.pickStepsFrom[from])
            }
            .toList()

        // T4: how much of the library has been played, and from which decades.
        var libraryPlayed = 0
        var availablePlays = 0
        var yearPlays = 0
        val decadePlays = HashMap<Int, Int>()
        for (track in 0 until tracks.size) {
            if (tracks.plays[track] == 0) continue
            val descriptor = catalog(tracks.ids[track]) ?: continue
            libraryPlayed++
            availablePlays += tracks.plays[track]
            val year = (descriptor.originalYear ?: descriptor.year)?.takeIf { it in 1..9999 } ?: continue
            yearPlays += tracks.plays[track]
            decadePlays[year / 10 * 10] = (decadePlays[year / 10 * 10] ?: 0) + tracks.plays[track]
        }
        val decades = if (availablePlays > 0 && yearPlays >= TAG_COVERAGE * availablePlays) {
            decadePlays.entries.map { DecadePlays(it.key, it.value) }.sortedBy { it.decade }
        } else {
            emptyList()
        }

        val (current, longest) = streaks(playDays, today)
        return HabitInsights(
            onRepeatTracks = shownRepeatTracks,
            onRepeatArtists = onRepeatArtists,
            onRepeatLocked = onRepeatLocked,
            currentStreakDays = current,
            longestStreakDays = longest,
            playDaysLastWeek = playDays.count { it in (today - 6)..today },
            slippingAway = slippingAway,
            slippingAwayLocked = slippingLocked,
            yearAgo = yearAgoRows,
            sameArtistPicks = sameArtistPicks,
            pickSteps = pickSteps,
            sequences = sequences,
            libraryTracksPlayed = libraryPlayed,
            librarySize = librarySize,
            decades = decades,
            logStartMs = logStart,
        )
    }

    public fun period(
        history: NormalizedHistory,
        catalog: (TrackId) -> TrackDescriptor?,
        /** Inclusive lower bound on the listen's start; null means the whole log. */
        sinceMs: Long?,
        nowMs: Long,
        timePointOf: (Long) -> LocalTimePoint = ::localTimePoint,
        cancellationCheck: () -> Unit = {},
    ): PeriodInsights {
        cancellationCheck()
        val events = history.events
        val since = sinceMs?.coerceAtLeast(0)
        val previousStart = since?.takeIf { it <= nowMs }?.let { (it - (nowMs - it)).coerceAtLeast(0) }
        val logStart = history.logStartMs
        val burnedIn = since == null || (logStart != null && previousStart != null && logStart <= previousStart)
        val tracks = TrackTable()
        val firstArtistPlay = HashMap<String, ArtistFirst>()
        val playedTracks = HashSet<Int>()
        val playedArtists = HashSet<String>()
        val newTracks = HashSet<Int>()
        val hours = LongArray(24)
        val artistTotals = LinkedHashMap<String, NameTotal>()
        val albumTotals = LinkedHashMap<String, NameTotal>()
        val genreTotals = LinkedHashMap<String, NameTotal>()
        var availablePlays = 0
        var albumTagged = 0
        var genreTagged = 0
        var plays = 0
        var newPlays = 0
        var previousPlays = 0
        var previousNewPlays = 0
        val sessionListened = ArrayList<Long>()
        val sessionPlays = ArrayList<Int>()
        val sessionStarts = ArrayList<Long>()
        var currentSession = -1
        val today = timePointOf(nowMs.coerceAtLeast(0)).epochDay
        val buckets = HashMap<Int, BucketTotal>()
        val logStartDay = logStart?.let { timePointOf(it).epochDay }
        val logSpanDays = logStartDay?.let { today - it + 1 } ?: 0
        val scale = when {
            since != null || logSpanDays < DAILY_UNDER_DAYS -> ActivityScale.DAY
            logSpanDays >= YEARLY_FROM_DAYS -> ActivityScale.YEAR
            else -> ActivityScale.MONTH
        }

        events.forEachIndexed { index, event ->
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            val track = tracks.indexOf(event.trackId)
            val inPeriod = since == null || event.startedAtMs >= since
            val point = timePointOf(event.startedAtMs)
            val session = history.sessionIds[index]
            if (session != currentSession) {
                currentSession = session
                sessionListened += 0L
                sessionPlays += 0
                sessionStarts += event.startedAtMs
            }
            val listened = event.effectiveListenedMs.coerceAtLeast(0)
            sessionListened[sessionListened.lastIndex] = saturatingDurationAdd(sessionListened.last(), listened)
            if (inPeriod && point.hourOfDay in 0..23) {
                hours[point.hourOfDay] = saturatingDurationAdd(hours[point.hourOfDay], listened)
            }
            // Bars measure listening time, skipped starts included, like the daily chart.
            if (inPeriod && scale != ActivityScale.DAY) {
                val (year, month) = civilYearMonth(point.epochDay)
                val key = if (scale == ActivityScale.YEAR) year else year * 12 + month - 1
                buckets.getOrPut(key) { BucketTotal() }.add(if (event.skipped) 0 else 1, listened)
            }
            if (event.skipped) return@forEachIndexed
            sessionPlays[sessionPlays.lastIndex] = sessionPlays.last() + 1

            val firstPlay = tracks.plays[track] == 0
            tracks.plays[track]++
            val artistKey = tracks.artistKey(track, catalog)
            if (artistKey.isNotEmpty()) {
                val first = firstArtistPlay.getOrPut(artistKey) {
                    ArtistFirst(event.startedAtMs, tracks.artistName(track, catalog))
                }
                first.plays++
                if (first.lastDay != point.epochDay) {
                    first.days++
                    first.lastDay = point.epochDay
                }
            }
            if (previousStart != null && since != null && event.startedAtMs in previousStart until since) {
                previousPlays++
                if (firstPlay) previousNewPlays++
            }
            if (!inPeriod) return@forEachIndexed

            plays++
            if (firstPlay) {
                newPlays++
                newTracks += track
            }
            playedTracks += track
            if (artistKey.isNotEmpty()) playedArtists += artistKey
            val descriptor = catalog(event.trackId) ?: return@forEachIndexed
            availablePlays++
            if (artistKey.isNotEmpty()) {
                artistTotals.getOrPut(artistKey) {
                    NameTotal(tracks.artistName(track, catalog), trackId = event.trackId)
                }.add(listened)
            }
            val album = descriptor.album?.trim()?.takeIf(String::isNotEmpty)
            if (album != null) {
                albumTagged++
                val albumArtist = (descriptor.albumArtist ?: descriptor.artist)?.trim()?.takeIf(String::isNotEmpty)
                albumTotals.getOrPut(albumKey(descriptor) ?: album) {
                    NameTotal(album, subtitle = albumArtist, trackId = event.trackId)
                }.add(listened)
            }
            val genre = descriptor.genre?.trim()?.takeIf(String::isNotEmpty)
            if (genre != null) {
                genreTagged++
                genreTotals.getOrPut(genre.lowercase()) { NameTotal(genre) }.add(listened)
            }
        }

        cancellationCheck()
        val newArtists = firstArtistPlay.values.count { since == null || it.firstMs >= since }
        val stuckFrom = since ?: (nowMs - STUCK_ALL_TIME_DAYS * DAY_MS)
        val stuckArtists = if (logStart == null) {
            emptyList()
        } else {
            firstArtistPlay.values
                .filter {
                    it.firstMs >= stuckFrom && it.firstMs >= logStart + STUCK_BURN_IN_DAYS * DAY_MS &&
                        it.plays >= STUCK_MIN_PLAYS && it.days >= STUCK_MIN_DAYS
                }
                .sortedByDescending { it.plays }
                .take(STUCK_ROWS)
                .map { RankedName(it.name, it.plays, 0) }
        }

        val sessionIndices = sessionStarts.indices.filter {
            sessionPlays[it] > 0 && (since == null || sessionStarts[it] >= since)
        }
        val sessions = if (sessionIndices.size < MIN_SESSIONS) {
            null
        } else {
            val longest = sessionIndices.maxBy { sessionListened[it] }
            SessionSummary(
                sessions = sessionIndices.size,
                medianListenedMs = median(sessionIndices.map { sessionListened[it] }),
                medianPlays = median(sessionIndices.map { sessionPlays[it].toLong() }).toInt(),
                longestListenedMs = sessionListened[longest],
                longestStartMs = sessionStarts[longest],
            )
        }

        val activity = when (scale) {
            ActivityScale.DAY -> emptyList()
            ActivityScale.MONTH -> {
                val (year, month) = civilYearMonth(today)
                val currentMonth = year * 12 + month - 1
                val (startYear, startMonth) = civilYearMonth(logStartDay ?: today)
                val firstMonth = maxOf(currentMonth - (MONTH_BARS - 1), startYear * 12 + startMonth - 1)
                (firstMonth..currentMonth).map { key ->
                    val total = buckets[key]
                    ActivityBucket(key, currentMonth - key, total?.plays ?: 0, total?.playedMs ?: 0)
                }
            }
            ActivityScale.YEAR -> {
                val currentYear = civilYearMonth(today).first
                val firstYear = civilYearMonth(logStartDay ?: today).first
                (firstYear..currentYear).map { key ->
                    val total = buckets[key]
                    ActivityBucket(key, currentYear - key, total?.plays ?: 0, total?.playedMs ?: 0)
                }
            }
        }

        return PeriodInsights(
            playedTracks = playedTracks.size,
            playedArtists = playedArtists.size,
            burnedIn = burnedIn,
            newPlays = newPlays,
            familiarPlays = plays - newPlays,
            newTracks = newTracks.size,
            newArtists = newArtists,
            previousNewShare = if (since != null && burnedIn && previousPlays > 0) {
                previousNewPlays.toFloat() / previousPlays
            } else {
                null
            },
            stuckArtists = stuckArtists,
            logDaysInsidePeriod = if (since != null && logStart != null && logStartDay != null && logStart > since) {
                (today - logStartDay + 1).toInt()
            } else {
                null
            },
            logStartDay = logStartDay,
            listenedMsByHour = hours.toList(),
            sessions = sessions,
            topArtists = artistTotals.ranked(),
            topAlbums = albumTotals.ranked().takeIf { availablePlays > 0 && albumTagged >= TAG_COVERAGE * availablePlays },
            topGenres = genreTotals.ranked().takeIf { availablePlays > 0 && genreTagged >= TAG_COVERAGE * availablePlays },
            activityScale = scale,
            activity = activity,
        )
    }

    /** At most one track per album and two per artist, so one album cannot fill a list. */
    private fun varied(
        candidates: List<Int>,
        limit: Int,
        tracks: TrackTable,
        catalog: (TrackId) -> TrackDescriptor?,
    ): List<Int> {
        val albums = HashSet<String>()
        val artists = HashMap<String, Int>()
        val result = ArrayList<Int>(limit)
        for (track in candidates) {
            if (result.size == limit) break
            val descriptor = catalog(tracks.ids[track])
            val album = descriptor?.let(::albumKey)
            if (album != null && album in albums) continue
            val artist = tracks.artistKey(track, catalog)
            if (artist.isNotEmpty() && (artists[artist] ?: 0) >= 2) continue
            if (album != null) albums += album
            if (artist.isNotEmpty()) artists[artist] = (artists[artist] ?: 0) + 1
            result += track
        }
        return result
    }

    private fun albumKey(descriptor: TrackDescriptor): String? {
        val album = descriptor.album?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return album.lowercase() + "\u0000" + listeningArtistKey(descriptor.albumArtist ?: descriptor.artist)
    }

    private fun decay(daysAgo: Long): Double = 0.5.pow(daysAgo / REPEAT_HALF_LIFE_DAYS)

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** Current streak survives an unfinished today, as the overview's always did. */
    private fun streaks(days: List<Long>, today: Long): Pair<Int, Int> {
        if (days.isEmpty()) return 0 to 0
        var longest = 1
        var run = 1
        for (index in 1 until days.size) {
            run = if (days[index] == days[index - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }
        val last = days.last()
        if (last != today && last != today - 1) return 0 to longest
        var current = 1
        var index = days.lastIndex
        while (index > 0 && days[index - 1] == days[index] - 1) {
            current++
            index--
        }
        return current to longest
    }

    /** Civil year and month (1..12) of a day counted from 1970-01-01 (H. Hinnant's algorithm). */
    internal fun civilYearMonth(epochDay: Long): Pair<Int, Int> {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthPrime = (5 * dayOfYear + 2) / 153
        val month = (if (monthPrime < 10) monthPrime + 3 else monthPrime - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return year to month
    }

    private fun ListenEvent.isPick(): Boolean = !skipped && origin?.start == ListenStart.USER_PICK

    /**
     * A play the listener chose: a pick, or a finished play that SMART did not plan and that is not a
     * repeat-one loop. SMART repeating a track must not make it a favourite.
     */
    private fun ListenEvent.isChosen(): Boolean = !skipped && (
        isPick() || (
            completed && origin?.smartPlanPosition == null && shuffleMode != "SMART" &&
                origin?.start != ListenStart.REPEAT
            )
        )

    private fun Map<String, NameTotal>.ranked(): List<RankedName> = values
        .sortedWith(compareByDescending<NameTotal> { it.plays }.thenByDescending { it.playedMs })
        .take(TOP_LIMIT)
        .map { RankedName(it.name, it.plays, it.playedMs, it.subtitle, it.trackId) }

    /** Per-track counters in primitive arrays, indexed through one map. */
    private class TrackTable {
        private val index = HashMap<TrackId, Int>()
        val ids = ArrayList<TrackId>()
        private val artistKeys = ArrayList<String?>()
        var plays = IntArray(INITIAL)
        var chosenPlays = IntArray(INITIAL)
        var chosenDays = IntArray(INITIAL)
        var lastChosenDay = LongArray(INITIAL)
        var windowPlays = IntArray(INITIAL)
        var windowDays = IntArray(INITIAL)
        var lastWindowDay = LongArray(INITIAL)
        var weight = DoubleArray(INITIAL)
        var yearAgoPlays = IntArray(INITIAL)
        var pickStepsFrom = IntArray(INITIAL)
        var lastStartMs = LongArray(INITIAL)
        val size: Int get() = ids.size

        fun indexOf(id: TrackId): Int = index.getOrPut(id) {
            if (ids.size == plays.size) grow()
            ids += id
            artistKeys += null
            lastChosenDay[ids.lastIndex] = Long.MIN_VALUE
            lastWindowDay[ids.lastIndex] = Long.MIN_VALUE
            ids.lastIndex
        }

        fun artistKey(track: Int, catalog: (TrackId) -> TrackDescriptor?): String =
            artistKeys[track] ?: listeningArtistKey(catalog(ids[track])?.artist).also { artistKeys[track] = it }

        fun artistName(track: Int, catalog: (TrackId) -> TrackDescriptor?): String =
            catalog(ids[track])?.artist?.trim().orEmpty()

        private fun grow() {
            val capacity = plays.size * 2
            plays = plays.copyOf(capacity)
            chosenPlays = chosenPlays.copyOf(capacity)
            chosenDays = chosenDays.copyOf(capacity)
            lastChosenDay = lastChosenDay.copyOf(capacity)
            windowPlays = windowPlays.copyOf(capacity)
            windowDays = windowDays.copyOf(capacity)
            lastWindowDay = lastWindowDay.copyOf(capacity)
            weight = weight.copyOf(capacity)
            yearAgoPlays = yearAgoPlays.copyOf(capacity)
            pickStepsFrom = pickStepsFrom.copyOf(capacity)
            lastStartMs = lastStartMs.copyOf(capacity)
        }

        private companion object {
            const val INITIAL = 256
        }
    }

    private class ArtistRepeatTotal(val name: String) {
        var plays = 0
        var days = 0
        var lastDay = Long.MIN_VALUE
        var weight = 0.0
    }

    private class ArtistFirst(val firstMs: Long, val name: String) {
        var plays = 0
        var days = 0
        var lastDay = Long.MIN_VALUE
    }

    private class PairTotal {
        var count = 0
        var sessions = 0
        var lastSession = -1

        companion object {
            fun key(from: Int, to: Int): Long = (from.toLong() shl 32) or (to.toLong() and 0xffffffffL)
            fun from(key: Long): Int = (key ushr 32).toInt()
            fun to(key: Long): Int = key.toInt()
        }
    }

    private class NameTotal(val name: String, val subtitle: String? = null, val trackId: TrackId? = null) {
        var plays = 0
        var playedMs = 0L

        fun add(listened: Long) {
            plays++
            playedMs = saturatingDurationAdd(playedMs, listened)
        }
    }

    private class BucketTotal {
        var plays = 0
        var playedMs = 0L

        fun add(play: Int, listened: Long) {
            plays += play
            playedMs = saturatingDurationAdd(playedMs, listened)
        }
    }
}

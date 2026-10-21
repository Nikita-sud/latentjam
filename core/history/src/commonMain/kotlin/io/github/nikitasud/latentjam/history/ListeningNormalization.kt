/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * The listening log as the statistics page reads it: in start order, without exact duplicates, with a
 * pause-and-resume of one playback joined back into one listen, and cut into sessions.
 *
 * [events] reuses the store's own objects; only joined listens are new copies. The side arrays are
 * indexed like [events].
 *
 * @property sessionIds Session of each event. A session is a run of events whose gaps (next start minus
 *   previous end) are at most [SESSION_GAP_MS] and that no [breaksBefore] interrupts. Ids rise with time.
 * @property breaksBefore True where the event starts well before the previous one ended: a clock jump,
 *   or two devices' logs merged from a backup. No session or step crosses it.
 * @property logStartMs Start of the first valid event at or after [ListeningNormalization.PLAUSIBLE_START_MS]
 *   (of the first event when none is), or null for an empty log.
 */
public class NormalizedHistory internal constructor(
    public val events: List<ListenEvent>,
    public val sessionIds: IntArray,
    public val breaksBefore: BooleanArray,
    public val logStartMs: Long?,
) {
    public val size: Int get() = events.size

    public companion object {
        public val EMPTY: NormalizedHistory = NormalizedHistory(emptyList(), IntArray(0), BooleanArray(0), null)
    }
}

/** Builds [NormalizedHistory]; pure, so every figure is reproducible from a log snapshot. */
public object ListeningNormalization {

    /**
     * 2020-01-01 UTC. A listen logged before it comes from a clock that was not set (a phone back at
     * 1970 after a dead battery), never from LatentJam: it still counts, but it does not mark where
     * the log began, or one such listen would stretch the yearly chart back to 1970 and unlock
     * "a year ago" on a log days old.
     */
    public const val PLAUSIBLE_START_MS: Long = 1_577_836_800_000L

    /** Longest pause inside one session, measured from the previous listen's end. */
    public const val SESSION_GAP_MS: Long = 30L * 60 * 1000

    /** How much earlier than the previous listen's end a start may be before it reads as a clock break. */
    internal const val OVERLAP_TOLERANCE_MS: Long = 15_000

    /** Longest pause after which a resumed half still joins the listen it continues. */
    internal const val RESUME_WINDOW_MS: Long = 6L * 60 * 60 * 1000

    /** Slack in the resume-position test (the tracker loses up to a second per transition). */
    internal const val RESUME_POSITION_SLACK_MS: Long = 5_000

    private const val SKIP_THRESHOLD_MS = 30_000L
    private const val CANCELLATION_CHECK_MASK = 1023

    /** The first start at or after [PLAUSIBLE_START_MS] in the start-ordered [events], else the first. */
    private fun logStartOf(events: List<ListenEvent>): Long {
        val first = events.first().startedAtMs
        if (first >= PLAUSIBLE_START_MS) return first
        return events.firstOrNull { it.startedAtMs >= PLAUSIBLE_START_MS }?.startedAtMs ?: first
    }

    public fun normalize(
        events: List<ListenEvent>,
        nowMs: Long,
        cancellationCheck: () -> Unit = {},
    ): NormalizedHistory {
        cancellationCheck()
        // Memory matters here: the store already holds every event, so this pass keeps one output list
        // and primitive side arrays, and copies the input only when it must be filtered or sorted.
        // Out-of-range timestamps are excluded everywhere, as the overview does. The log is appended in
        // end order; restores and merges may interleave, and a stable sort keeps the store's order among
        // equal starts.
        var inRange = true
        var ordered = true
        for (position in events.indices) {
            val start = events[position].startedAtMs
            if (start !in 0..nowMs) inRange = false
            if (position > 0 && start < events[position - 1].startedAtMs) ordered = false
        }
        // A primitive comparison: sortedBy would box both starts on every comparison.
        val byStart = Comparator<ListenEvent> { left, right -> left.startedAtMs.compareTo(right.startedAtMs) }
        val input = when {
            inRange && ordered -> events
            inRange -> events.sortedWith(byStart)
            else -> events.filter { it.startedAtMs in 0..nowMs }.sortedWith(byStart)
        }
        if (input.isEmpty()) return NormalizedHistory.EMPTY

        val joined = ArrayList<ListenEvent>(input.size)
        val breaks = BooleanArray(input.size)
        val ends = LongArray(input.size)
        val sessionIds = IntArray(input.size)
        var session = 0
        var lastEnd = Long.MIN_VALUE

        fun accept(event: ListenEvent) {
            val previous = joined.lastOrNull()
            // 2. Overlap breaks, judged on uncapped ends.
            val overlap = previous != null && previous.listenedMs != null && event.listenedMs != null &&
                event.startedAtMs < lastEnd - OVERLAP_TOLERANCE_MS
            // 3. A resumed half joins the listen it continues.
            if (previous != null && !overlap && continuesListen(previous, lastEnd, event)) {
                joined[joined.lastIndex] = join(previous, event)
                lastEnd = event.startedAtMs + (event.listenedMs ?: 0)
                ends[joined.lastIndex] = lastEnd
                return
            }
            val position = joined.size
            if (previous != null) {
                // 4. The previous listen is final now: its end, capped at this start, cuts sessions.
                // A joined listen ends where its resumed half ended, not at its start plus time heard.
                val previousEnd = minOf(ends[position - 1], event.startedAtMs)
                if (overlap || event.startedAtMs - previousEnd > SESSION_GAP_MS) session++
            }
            joined += event
            breaks[position] = overlap
            sessionIds[position] = session
            lastEnd = event.startedAtMs + event.effectiveListenedMs.coerceAtLeast(0)
            ends[position] = lastEnd
        }

        // 1. Exact duplicates by (track, start): keep the copy that knows its origin, else the first.
        var index = 0
        while (index < input.size) {
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            val start = input[index].startedAtMs
            var end = index + 1
            while (end < input.size && input[end].startedAtMs == start) end++
            if (end - index == 1) {
                accept(input[index])
            } else {
                val group = input.subList(index, end)
                val seen = HashSet<TrackId>()
                group.forEachIndexed { position, event ->
                    if (!seen.add(event.trackId)) return@forEachIndexed
                    val withOrigin = group.subList(position, group.size)
                        .firstOrNull { it.trackId == event.trackId && it.origin != null }
                    accept(withOrigin ?: event)
                }
            }
            index = end
        }
        return NormalizedHistory(
            events = joined,
            sessionIds = if (joined.size == input.size) sessionIds else sessionIds.copyOf(joined.size),
            breaksBefore = if (joined.size == input.size) breaks else breaks.copyOf(joined.size),
            logStartMs = logStartOf(joined),
        )
    }

    /**
     * A pause recorded while the app went to the background splits one playback into two events; the
     * resumed half has no recorded start and begins where the first half stopped
     * ([HistorySessionTracker] makes the same test). A restart from the top fails the position test
     * whether or not the first half was finished. Legacy lines carry no origin and are never joined.
     */
    private fun continuesListen(previous: ListenEvent, previousEnd: Long, event: ListenEvent): Boolean {
        val origin = event.origin ?: return false
        if (origin.start != null || event.trackId != previous.trackId) return false
        val listened = event.listenedMs ?: return false
        if (previous.listenedMs == null) return false
        // Overlaps beyond the tolerance were already ruled out as breaks.
        if (event.startedAtMs - previousEnd > RESUME_WINDOW_MS) return false
        return event.playedMs - listened >= previous.playedMs - RESUME_POSITION_SLACK_MS
    }

    private fun join(first: ListenEvent, resumed: ListenEvent): ListenEvent {
        val listened = saturatingDurationAdd(first.listenedMs ?: 0, resumed.listenedMs ?: 0)
        val completed = first.completed || resumed.completed
        return first.copy(
            playedMs = maxOf(first.playedMs, resumed.playedMs),
            trackDurationMs = first.trackDurationMs ?: resumed.trackDurationMs,
            completed = completed,
            skipped = !completed && listened < SKIP_THRESHOLD_MS,
            listenedMs = listened,
        )
    }
}

/** The engine's artist key (lower case, single spaces, trimmed); empty means unknown. */
public fun listeningArtistKey(artist: String?): String =
    artist.orEmpty().lowercase().replace(ARTIST_WHITESPACE, " ").trim()

private val ARTIST_WHITESPACE = Regex("\\s+")

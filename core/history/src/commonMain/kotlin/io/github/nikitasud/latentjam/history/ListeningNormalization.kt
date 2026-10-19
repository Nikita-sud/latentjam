/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

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
 * @property logStartMs Start of the first valid event, or null for an empty log.
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

    public fun normalize(
        events: List<ListenEvent>,
        nowMs: Long,
        cancellationCheck: () -> Unit = {},
    ): NormalizedHistory {
        cancellationCheck()
        // Out-of-range timestamps are excluded everywhere, as the overview does.
        val valid = events.filter { it.startedAtMs in 0..nowMs }
        if (valid.isEmpty()) return NormalizedHistory.EMPTY
        // The log is appended in end order; restores and merges may interleave. A stable sort keeps
        // the store's order among equal starts.
        val sorted = if (valid.zipWithNext().all { (a, b) -> a.startedAtMs <= b.startedAtMs }) {
            valid
        } else {
            valid.sortedBy(ListenEvent::startedAtMs)
        }

        // 1. Exact duplicates by (track, start): keep the copy that knows its origin, else the first.
        val deduped = ArrayList<ListenEvent>(sorted.size)
        var index = 0
        while (index < sorted.size) {
            if (index and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            val start = sorted[index].startedAtMs
            var end = index + 1
            while (end < sorted.size && sorted[end].startedAtMs == start) end++
            if (end - index == 1) {
                deduped += sorted[index]
            } else {
                val group = sorted.subList(index, end)
                val seen = HashSet<String>()
                group.forEachIndexed { position, event ->
                    if (!seen.add(event.trackId.value)) return@forEachIndexed
                    val withOrigin = group.subList(position, group.size)
                        .firstOrNull { it.trackId == event.trackId && it.origin != null }
                    deduped += withOrigin ?: event
                }
            }
            index = end
        }

        // 2 + 3. Overlap breaks (judged on uncapped ends), then resumed halves joined into their listen.
        val joined = ArrayList<ListenEvent>(deduped.size)
        val breaks = ArrayList<Boolean>(deduped.size)
        val ends = ArrayList<Long>(deduped.size)
        var lastEnd = Long.MIN_VALUE
        deduped.forEachIndexed { position, event ->
            if (position and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            val previous = joined.lastOrNull()
            val overlap = previous != null && previous.listenedMs != null && event.listenedMs != null &&
                event.startedAtMs < lastEnd - OVERLAP_TOLERANCE_MS
            if (previous != null && !overlap && continuesListen(previous, lastEnd, event)) {
                joined[joined.lastIndex] = join(previous, event)
                lastEnd = event.startedAtMs + (event.listenedMs ?: 0)
                ends[ends.lastIndex] = lastEnd
                return@forEachIndexed
            }
            joined += event
            breaks += overlap
            lastEnd = event.startedAtMs + event.effectiveListenedMs.coerceAtLeast(0)
            ends += lastEnd
        }

        // 4. Ends capped at the next start, then sessions.
        val sessionIds = IntArray(joined.size)
        var session = 0
        for (position in 1 until joined.size) {
            if (position and CANCELLATION_CHECK_MASK == 0) cancellationCheck()
            val event = joined[position]
            // A joined listen ends where its resumed half ended, not at its start plus the time heard.
            val previousEnd = minOf(ends[position - 1], event.startedAtMs)
            if (breaks[position] || event.startedAtMs - previousEnd > SESSION_GAP_MS) session++
            sessionIds[position] = session
        }
        return NormalizedHistory(
            events = joined,
            sessionIds = sessionIds,
            breaksBefore = BooleanArray(joined.size) { breaks[it] },
            logStartMs = joined.first().startedAtMs,
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

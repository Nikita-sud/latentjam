/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.SonicJourney

/**
 * The four listening phases of a day.
 *
 * Fixed wall-clock boundaries, taken from the cross-cultural five-phase structure found in a
 * two-billion-event diurnal study (morning / afternoon / evening / night, with late-night folded
 * into night — a personal music log rarely has enough 3 a.m. plays to treat it separately).
 * Learned per-listener boundaries are a later refinement; the fixed prior is what makes the
 * feature exist on week one.
 */
enum class ForYouDaypart {
    MORNING,
    DAY,
    EVENING,
    NIGHT,
}

/**
 * Rhythm: what the listening log says about WHEN this listener plays WHAT.
 *
 * Everything here is a pure fold over the event log the page already receives — no new models,
 * no new storage. The point is timing: the research round behind this file converged on the
 * finding that the same card lands as clairvoyant or as noise depending on when it appears, and
 * that ~93% of listeners concentrate specific artists around specific hours. This listener's own
 * exported log agrees: the morning and evening top-20 track sets overlap in ZERO items.
 */
internal object ForYouRhythm {

    /** Boundaries of [ForYouDaypart]; 6/12/18 match the published cross-cultural phase edges. */
    fun daypartOf(hour: Int): ForYouDaypart = when (hour) {
        in 6..11 -> ForYouDaypart.MORNING
        in 12..17 -> ForYouDaypart.DAY
        in 18..23 -> ForYouDaypart.EVENING
        else -> ForYouDaypart.NIGHT
    }

    /**
     * Below this many logged events in a daypart the pattern is noise, not a habit, and the
     * section stays silent rather than guessing — a wrong "your mornings" claim reads as broken,
     * which costs more than absence (a weak reason hurts more than no reason).
     */
    const val DAYPART_MIN_EVENTS = 25

    /** Row length: the page's standard row, doubled — this row is a mix offer, not a shelf. */
    const val DAYPART_ROW_TRACKS = 12

    /**
     * Slots reserved for unheard tracks pulled from the worlds this daypart's listening lives
     * in. One-third: every research lens that touched blending landed near 70% proven / 30%
     * new, with the unknown inheriting credibility from familiar neighbours.
     */
    const val DAYPART_FRESH_SLOTS = 4

    /**
     * Days an unheard offer rests after being shown and ignored. Three: long enough that the
     * slot visibly moves on, short enough that a genuinely central track returns while the
     * context that suggested it still holds.
     */
    const val FRESH_COOLDOWN_DAYS = 3L

    /** One track's standing inside one daypart: ranking weight plus the honest play count. */
    data class DaypartAffinity(val weight: Int, val plays: Int)

    /**
     * Per-track affinity inside one daypart: completions count double, skips count nothing.
     * A completed play is the only unambiguous positive the log has; a skip in this daypart is
     * direct evidence AGAINST offering the track in this daypart again. The raw play count
     * rides along because it is what a caption may honestly claim.
     */
    fun daypartAffinity(
        events: List<ListenEvent>,
        daypart: ForYouDaypart,
        hourOf: (Long) -> Int,
    ): Map<TrackId, DaypartAffinity> {
        val affinity = HashMap<TrackId, DaypartAffinity>()
        for (event in events) {
            if (daypartOf(hourOf(event.startedAtMs)) != daypart) continue
            if (event.skipped) continue
            val prior = affinity[event.trackId]
            affinity[event.trackId] = DaypartAffinity(
                weight = (prior?.weight ?: 0) + if (event.completed) 2 else 1,
                plays = (prior?.plays ?: 0) + 1,
            )
        }
        return affinity
    }

    /** Total daypart events (skips included — a skip still proves the listener was listening). */
    fun daypartEventCount(
        events: List<ListenEvent>,
        daypart: ForYouDaypart,
        hourOf: (Long) -> Int,
    ): Int = events.count { daypartOf(hourOf(it.startedAtMs)) == daypart }

    /**
     * The daypart row: the tracks this listener provably plays in this phase of the day, plus
     * [DAYPART_FRESH_SLOTS] unheard tracks drawn from the same worlds that listening lives in —
     * discovery routed through the door the listener already opens at this hour. Output is
     * [familiaritySandwich]-ordered so no two unknowns sit together.
     *
     * @param dayIndex rotates the proven picks so the row is not the same list every single day;
     *   the pool is the daypart repertoire, the visible dozen walks through it.
     */
    fun daypartRow(
        affinity: Map<TrackId, DaypartAffinity>,
        byId: Map<TrackId, TrackDescriptor>,
        worlds: List<LibraryWorld>,
        stats: Map<TrackId, TrackStats>,
        used: Set<TrackId>,
        dayIndex: Int,
        cooled: Set<TrackId> = emptySet(),
    ): List<TrackDescriptor> {
        val proven = affinity.entries
            .asSequence()
            .filter { it.key !in used && byId.containsKey(it.key) }
            .sortedWith(
                compareByDescending<Map.Entry<TrackId, DaypartAffinity>> { it.value.weight }
                    .thenBy { it.key.value },
            )
            .map { it.key }
            .toList()
        if (proven.isEmpty()) return emptyList()

        // The row keeps a stable identity head — the phase's strongest tracks, which are the
        // claim "this is your morning" — and rotates only the remaining slots through the rest
        // of the pool. Uniform rotation over everything was measured at 0% session-hit on the
        // exported log: a window that walks the whole pool almost never contains the tracks the
        // phase is actually made of. A row that never changes is furniture; a row that never
        // repeats is noise; the head-plus-tail split is both claims kept honest.
        val provenTarget = DAYPART_ROW_TRACKS - DAYPART_FRESH_SLOTS
        val headSize = provenTarget / 2
        val window = if (proven.size <= provenTarget) {
            proven
        } else {
            val head = proven.take(headSize)
            val tailPool = proven.drop(headSize)
            head + List(provenTarget - headSize) {
                tailPool[(it + dayIndex * (provenTarget - headSize)).mod(tailPool.size)]
            }
        }

        // The daypart's worlds, weighted by where its (weighted) plays actually live.
        val worldAffinity = worlds.map { world ->
            world to world.tracks.sumOf { affinity[it.id]?.weight ?: 0 }
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        val fresh = ArrayList<TrackDescriptor>(DAYPART_FRESH_SLOTS)
        val freshSeen = HashSet<TrackId>()
        outer@ for ((world, _) in worldAffinity) {
            // Rotate inside each world too, so the fresh slots explore the region over the days
            // instead of retrying the same four strangers forever.
            val unheard = world.tracks
                .filter {
                    it.id !in used && it.id !in freshSeen &&
                        (stats[it.id]?.plays ?: 0) == 0 && byId.containsKey(it.id)
                }
                // Recently offered and ignored goes to the back; strangers get their turn.
                .sortedBy { it.id in cooled }
            if (unheard.isEmpty()) continue
            val start = dayIndex.mod(unheard.size)
            for (offset in unheard.indices) {
                val candidate = unheard[(start + offset).mod(unheard.size)]
                fresh.add(candidate)
                freshSeen.add(candidate.id)
                if (fresh.size >= DAYPART_FRESH_SLOTS) break@outer
            }
        }

        val provenTracks = window.mapNotNull(byId::get)
        return familiaritySandwich(provenTracks + fresh, stats)
    }

    // ------------------------------------------------------------------ binge

    /** A phase the listener is visibly in RIGHT NOW: one artist suddenly dominating the week. */
    data class Binge(
        val artistKey: String,
        val artistName: String,
        val recentPlays: Int,
    )

    /** The week is the unit a phase is felt in; shorter windows flag every single session. */
    const val BINGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Floor of in-window plays before an artist can be a phase. Below this the "phase" is one
     * album spin. On the exported log the real JoJo phase shows 20 plays/week; a quiet week's
     * top artist shows 5-7.
     */
    const val BINGE_MIN_PLAYS = 10

    /** The phase must also be a real share of the week, not just absolute volume. */
    const val BINGE_MIN_SHARE = 0.12

    /**
     * How many times the artist's share of listening must exceed their long-term baseline share.
     * An artist with no baseline at all (first week ever) passes automatically — a NEW obsession
     * is the strongest phase signal there is.
     */
    const val BINGE_MIN_LIFT = 3.0

    fun currentBinge(
        events: List<ListenEvent>,
        nowMs: Long,
        artistOf: (TrackId) -> String?,
        artistKeyOf: (String) -> String,
    ): Binge? {
        val cutoff = nowMs - BINGE_WINDOW_MS
        val recent = HashMap<String, Pair<String, Int>>() // key -> (display name, plays)
        var recentTotal = 0
        val baseline = HashMap<String, Int>()
        var baselineTotal = 0
        for (event in events) {
            if (event.skipped) continue
            val artist = artistOf(event.trackId)?.takeIf { it.isNotBlank() } ?: continue
            val key = artistKeyOf(artist)
            if (event.startedAtMs > cutoff) {
                val prior = recent[key]
                recent[key] = artist to ((prior?.second ?: 0) + 1)
                recentTotal++
            } else {
                baseline[key] = (baseline[key] ?: 0) + 1
                baselineTotal++
            }
        }
        if (recentTotal == 0) return null
        // The phase artist is the one with the best PHASE evidence, not the week's raw volume
        // leader: a steady lifetime favourite out-volumes a fresh obsession most weeks, and a
        // steady favourite is taste, not a phase. Candidates pass the volume and share floors,
        // then the largest lift over their own baseline wins — an artist with no baseline at
        // all is the strongest signal there is.
        val best = recent.entries
            .asSequence()
            .filter { it.value.second >= BINGE_MIN_PLAYS }
            .filter { it.value.second.toDouble() / recentTotal >= BINGE_MIN_SHARE }
            .map { entry ->
                val share = entry.value.second.toDouble() / recentTotal
                val baseShare = if (baselineTotal == 0) {
                    0.0
                } else {
                    (baseline[entry.key] ?: 0).toDouble() / baselineTotal
                }
                val lift = if (baseShare == 0.0) Double.POSITIVE_INFINITY else share / baseShare
                Triple(entry, lift, entry.value.second)
            }
            .filter { (_, lift, _) -> lift >= BINGE_MIN_LIFT }
            .sortedWith(
                compareByDescending<Triple<Map.Entry<String, Pair<String, Int>>, Double, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.key },
            )
            .firstOrNull() ?: return null
        val (name, plays) = best.first.value
        return Binge(artistKey = best.first.key, artistName = name, recentPlays = plays)
    }

    /**
     * The binged artist's unheard tracks, most-likely-to-fit first: tracks sharing a world with
     * what the phase actually played come before the rest of the catalogue — the clustering
     * already knows which side of a composer's output this phase is about.
     */
    fun bingeDeepCuts(
        binge: Binge,
        library: List<TrackDescriptor>,
        events: List<ListenEvent>,
        stats: Map<TrackId, TrackStats>,
        worlds: List<LibraryWorld>,
        nowMs: Long,
        used: Set<TrackId>,
        artistKeyOf: (String) -> String,
    ): List<TrackDescriptor> {
        val cutoff = nowMs - BINGE_WINDOW_MS
        val playedInPhase = events.asSequence()
            .filter { it.startedAtMs > cutoff && !it.skipped }
            .map { it.trackId }
            .toHashSet()
        val phaseWorlds = worlds.filter { world -> world.tracks.any { it.id in playedInPhase } }
            .flatMapTo(HashSet()) { world -> world.tracks.map { it.id } }
        return library.asSequence()
            .filter { track ->
                track.id !in used &&
                    (stats[track.id]?.plays ?: 0) == 0 &&
                    !track.artist.isNullOrBlank() &&
                    artistKeyOf(track.artist!!) == binge.artistKey
            }
            .sortedWith(
                compareByDescending<TrackDescriptor> { it.id in phaseWorlds }
                    .thenBy { it.album ?: "" }
                    .thenBy { it.id.value },
            )
            .toList()
    }

    // --------------------------------------------------------------- wildcard

    /**
     * How long a world must have been untouched for a pick from it to feel like a left turn
     * rather than more of the same. 30 days matches the "recent rotation" window the serendipity
     * literature contrasts against lifetime taste — but it is a CEILING: on a 26-day-old install
     * a fixed month made the slot structurally impossible (measured: zero qualifying worlds all
     * week), so the working value adapts to half the history span, floored at five days.
     */
    const val WILDCARD_DORMANT_MS = 30L * 24 * 60 * 60 * 1000

    /** Below this a "dormant" world was simply last weekend, and the left turn is a lane change. */
    const val WILDCARD_MIN_DORMANT_MS = 5L * 24 * 60 * 60 * 1000

    /** Lifetime completions a world must hold before it counts as loved rather than visited. */
    const val WILDCARD_MIN_COMPLETIONS = 5

    /** The wildcard and the loved track that vouches for it. */
    data class Wildcard(
        val pick: TrackDescriptor,
        val anchor: TrackDescriptor,
    )

    /**
     * One serendipity slot: an unheard track from a world with proven lifetime love and zero
     * recent plays — close in taste-space, far from the current rotation. That pair (relevant ×
     * unexpected) is the literature's definition of serendipity, and the dose is exactly one:
     * the measured per-item enjoyment cost of surprise makes a page of wildcards worse than none.
     * Rotates daily through the qualifying worlds and their candidates.
     */
    fun wildcard(
        worlds: List<LibraryWorld>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        used: Set<TrackId>,
        dayIndex: Int,
        cooled: Set<TrackId> = emptySet(),
    ): Wildcard? {
        // Adaptive dormancy, exactly like the page's quiet window: half the observed history
        // span, clamped between the floor and the mature ceiling.
        val oldest = stats.values.minOfOrNull { it.lastPlayedAtMs } ?: return null
        val dormantMs = ((nowMs - oldest) / 2)
            .coerceIn(WILDCARD_MIN_DORMANT_MS, WILDCARD_DORMANT_MS)
        data class Candidate(
            val anchor: TrackDescriptor,
            val unheard: List<TrackDescriptor>,
            val lastPlayed: Long,
        )

        val loved = worlds.mapNotNull { world ->
            var completions = 0
            var lastPlayed = 0L
            var bestAnchor: TrackDescriptor? = null
            var bestAnchorCompletions = 0
            for (track in world.tracks) {
                val s = stats[track.id] ?: continue
                completions += s.completions
                if (s.lastPlayedAtMs > lastPlayed) lastPlayed = s.lastPlayedAtMs
                if (s.completions > bestAnchorCompletions) {
                    bestAnchorCompletions = s.completions
                    bestAnchor = track
                }
            }
            val anchor = bestAnchor ?: return@mapNotNull null
            if (completions < WILDCARD_MIN_COMPLETIONS) return@mapNotNull null
            // Below the floor the world was simply this week's rotation; never a left turn.
            if (lastPlayed > nowMs - WILDCARD_MIN_DORMANT_MS) return@mapNotNull null
            val unheard = world.tracks
                .filter { it.id !in used && (stats[it.id]?.plays ?: 0) == 0 }
                .sortedBy { it.id in cooled }
            if (unheard.isEmpty()) return@mapNotNull null
            Candidate(anchor, unheard, lastPlayed)
        }
        if (loved.isEmpty()) return null
        // Prefer worlds past the adaptive threshold; a week of everything-at-once listening can
        // leave none (measured live: 14 of 17 worlds touched in the final six days), and then
        // the honest best available left turn is the QUIETEST loved world past the floor —
        // still a region untouched for days, vouched for by proven love.
        val dormantCutoff = nowMs - dormantMs
        val dormant = loved.filter { it.lastPlayed <= dormantCutoff }
        val pool = dormant.ifEmpty {
            listOf(loved.minBy { it.lastPlayed })
        }
        val candidate = pool[dayIndex.mod(pool.size)]
        return Wildcard(
            pick = candidate.unheard[dayIndex.mod(candidate.unheard.size)],
            anchor = candidate.anchor,
        )
    }

    // --------------------------------------------------------------- ordering

    /**
     * Familiarity score for ordering: recency-blind but completion-aware. Plays prove exposure,
     * completions prove the exposure was welcome.
     */
    private fun familiarity(stats: Map<TrackId, TrackStats>, id: TrackId): Int {
        val s = stats[id] ?: return 0
        return s.plays + s.completions
    }

    /** How many journeys to precompute; the page rotates through them day by day. */
    const val JOURNEY_POOL = 3

    /**
     * Plots the journey pool: each starts at one of the listener's most-loved tracks (distinct
     * primary artists, so three journeys are three different stories) and travels to the unheard
     * track farthest from it — the most travel the library can offer. Selection lives here;
     * geometry lives in [SonicJourney].
     */
    fun sonicJourneys(
        session: SonicJourney,
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        artistKeyOf: (String?) -> String,
    ): List<List<TrackId>> {
        val byId = library.associateBy { it.id }
        val unheard = library.filter { (stats[it.id]?.plays ?: 0) == 0 }.map { it.id }
        if (unheard.isEmpty()) return emptyList()
        val anchors = ArrayList<TrackId>(JOURNEY_POOL)
        val usedArtists = HashSet<String>()
        for (
            (id, _) in stats.entries
                .sortedWith(
                    compareByDescending<Map.Entry<TrackId, TrackStats>> {
                        it.value.completions * 10 + it.value.plays
                    }.thenBy { it.key.value },
                )
        ) {
            val track = byId[id] ?: continue
            if ((stats[id]?.completions ?: 0) == 0) break
            if (!usedArtists.add(artistKeyOf(track.artist))) continue
            anchors.add(id)
            if (anchors.size >= JOURNEY_POOL) break
        }
        return anchors.mapNotNull { anchor ->
            val destination = session.farthestFrom(anchor, unheard) ?: return@mapNotNull null
            session.plot(
                from = anchor,
                to = destination,
                artistKeyOf = { id -> byId[id]?.artist?.let(artistKeyOf) },
            )
        }
    }

    /**
     * Orders a mixed list so it OPENS with the most familiar track and no two unheard tracks sit
     * next to each other while anchors remain — every discovery is wrapped in proof the list
     * knows the listener. This is the one assembly rule every research lens converged on: the
     * unknown inherits credibility from its neighbours, and the first track decides whether the
     * mix gets a second one.
     */
    fun familiaritySandwich(
        tracks: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
    ): List<TrackDescriptor> {
        if (tracks.size <= 2) return tracks.sortedByDescending { familiarity(stats, it.id) }
        val (known, unknown) = tracks.partition { (stats[it.id]?.plays ?: 0) > 0 }
        if (unknown.isEmpty() || known.isEmpty()) {
            return tracks.sortedByDescending { familiarity(stats, it.id) }
        }
        val anchors = ArrayDeque(known.sortedByDescending { familiarity(stats, it.id) })
        val discoveries = ArrayDeque(unknown)
        val out = ArrayList<TrackDescriptor>(tracks.size)
        // Open on the strongest anchor, then alternate while both sides last; when anchors run
        // out the leftover discoveries follow at the tail — the deep end of the mix, where a
        // listener who is still listening has already accepted the premise.
        var takeAnchor = true
        while (anchors.isNotEmpty() || discoveries.isNotEmpty()) {
            val next = when {
                anchors.isEmpty() -> discoveries.removeFirst()
                discoveries.isEmpty() -> anchors.removeFirst()
                takeAnchor -> anchors.removeFirst()
                else -> discoveries.removeFirst()
            }
            out.add(next)
            takeAnchor = !takeAnchor
        }
        return out
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * One row of the For You page.
 *
 * @param reason a per-item, data-derived caption, or null. Deliberately not a fixed sentence: an
 *   explanation that reads the same every day carries no information and is decoration.
 */
data class ForYouCard(
    val track: TrackDescriptor,
    val reason: String? = null,
)

data class ForYouSection(
    val id: String,
    val title: String,
    val cards: List<ForYouCard>,
)

/**
 * Builds the For You page from listening history and the library.
 *
 * Pure functions over plain data, so every rule here is testable without a device — which is the
 * only reason rules this fiddly stay correct. Nothing reaches for a model: the personal signal picks
 * what to surface, and SMART is what sequences it once the user taps.
 *
 * The whole page is built in one pass so sections can be deduplicated against each other. With ~850
 * tracks and several rows drawing on overlapping pools, the same track otherwise appears three
 * times and the page looks broken.
 */
object ForYouBuilder {

    /** Below this, a play is an accident or an audition, not a listen. */
    const val MIN_PLAYS_TO_COUNT = 3

    /**
     * How long a track must be untouched to count as forgotten.
     *
     * 90 days, not 30. A month is an ordinary gap in rotation, so a shorter window surfaces things
     * the listener does not experience as absent — which is exactly how the row loses credibility.
     */
    const val QUIET_MS = 90L * 24 * 60 * 60 * 1000

    /** Rows are short on purpose: attention falls off sharply past the first few items. */
    const val ROW_LIMIT = 8

    /** One artist cannot own a row. A crude calibration, but the one that matters most. */
    const val MAX_PER_ARTIST = 2

    fun build(
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        recentEvents: List<ListenEvent>,
        nowMs: Long,
        excluded: Set<TrackId> = emptySet(),
    ): List<ForYouSection> {
        val byId = library.associateBy { it.id }
        // Accumulates across sections, so a track shown once is not shown again further down.
        val used = HashSet<TrackId>(excluded)

        val sections = mutableListOf<ForYouSection>()

        worthRevisiting(byId, stats, nowMs, used)?.let(sections::add)
        foundBySmart(byId, recentEvents, used)?.let(sections::add)
        neverPlayed(library, stats, used)?.let(sections::add)

        return sections
    }

    /**
     * Proven favourites gone quiet — the section with the least competition from ordinary browsing,
     * and the reason this page exists.
     */
    private fun worthRevisiting(
        byId: Map<TrackId, TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        used: MutableSet<TrackId>,
    ): ForYouSection? {
        val candidates = stats.entries
            .filter { (id, stat) ->
                id !in used &&
                    stat.plays >= MIN_PLAYS_TO_COUNT &&
                    stat.lastPlayedAtMs > 0 &&
                    nowMs - stat.lastPlayedAtMs >= QUIET_MS &&
                    // Something finished repeatedly is loved; something started repeatedly and
                    // abandoned is not, and would be a poor thing to press back on the listener.
                    stat.completions >= stat.skips
            }
            .sortedByDescending { it.value.completions }
            .mapNotNull { (id, stat) ->
                byId[id]?.let { track -> track to stat }
            }

        val picked = capPerArtist(candidates.map { it.first })
        if (picked.isEmpty()) return null
        val statOf = candidates.associate { it.first.id to it.second }
        return ForYouSection(
            id = "worth-revisiting",
            title = "Worth revisiting",
            cards = picked.map { track ->
                val plays = statOf[track.id]?.plays ?: 0
                ForYouCard(track, reason = "${plays}× before")
            }.onEach { used.add(it.track.id) },
        )
    }

    /**
     * Tracks SMART surfaced that the listener then played through.
     *
     * The shuffle mode at the time of each play has been recorded since the beginning and read by
     * nothing. It answers "is this thing any good?" with the listener's own behaviour, which no
     * amount of confidence scoring can.
     */
    private fun foundBySmart(
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        used: MutableSet<TrackId>,
    ): ForYouSection? {
        val picked = capPerArtist(
            recentEvents
                .filter { it.shuffleMode == "SMART" && it.completed && it.trackId !in used }
                .distinctBy { it.trackId }
                .mapNotNull { byId[it.trackId] },
        )
        if (picked.size < 3) return null
        return ForYouSection(
            id = "found-by-smart",
            title = "Found by SMART",
            cards = picked.map { ForYouCard(it) }.onEach { used.add(it.track.id) },
        )
    }

    /**
     * Owned but never heard. Newest first, because something added recently and still unplayed is a
     * stronger intention than something that has sat there for years.
     */
    private fun neverPlayed(
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        used: MutableSet<TrackId>,
    ): ForYouSection? {
        val picked = capPerArtist(
            library
                .filter { it.id !in used && (stats[it.id]?.plays ?: 0) == 0 }
                .sortedByDescending { it.addedAtMs ?: 0L },
        )
        if (picked.isEmpty()) return null
        return ForYouSection(
            id = "never-played",
            title = "Never played",
            cards = picked.map { ForYouCard(it) }.onEach { used.add(it.track.id) },
        )
    }

    /**
     * Takes up to [ROW_LIMIT], letting no artist hold more than [MAX_PER_ARTIST] slots.
     *
     * Order is otherwise preserved, so the caller's ranking survives. Untagged tracks share the
     * empty artist key and are capped together — one block of "Unknown artist" is exactly the kind
     * of row this is meant to prevent.
     */
    private fun capPerArtist(tracks: List<TrackDescriptor>): List<TrackDescriptor> {
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<TrackDescriptor>(ROW_LIMIT)
        for (track in tracks) {
            if (out.size >= ROW_LIMIT) break
            val key = track.artist.orEmpty()
            val count = perArtist[key] ?: 0
            if (count >= MAX_PER_ARTIST) continue
            perArtist[key] = count + 1
            out.add(track)
        }
        return out
    }
}

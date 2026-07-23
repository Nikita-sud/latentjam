/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldContent
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldNameSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle

/**
 * Which row this is.
 *
 * The builder decides which rows exist; it deliberately does not decide what they are called.
 * A heading is presentation, and presentation here is localised — a pure function that answered
 * in English would force every caller, the unit tests included, to speak it too.
 */
enum class ForYouSectionKind {
    /** Tracks left unfinished. */
    CONTINUE,

    /** Proven favourites gone quiet. */
    WORTH_REVISITING,

    /**
     * Regions the library falls into, found in embedding space rather than in the tags.
     *
     * The only row here that needs no listening history at all, and the only one that cannot
     * collapse under its own feedback — everything above it is derived from what was already
     * played, so it can only ever reflect existing habits back.
     */
    WORLDS,

    /** Owned but never heard. */
    NEVER_PLAYED,
}

/**
 * Why a card is on the page, as data rather than as a sentence.
 *
 * Deliberately not a fixed line: an explanation that reads the same every day carries no
 * information and is decoration. Every variant carries its number rather than a formatted string,
 * because the number is what selects the plural form — "1 трек" and "5 треков" are different words
 * in most of the languages this ships in, and only the UI layer knows which language that is.
 */
sealed interface ForYouCaption {

    /** How often this track was played back when it was in rotation. */
    data class PlayedBefore(val plays: Int) : ForYouCaption

    /** How many dormant tracks the collection on this card stands for. */
    data class TrackCount(val tracks: Int) : ForYouCaption
}

/**
 * One row of the For You page.
 *
 * @param caption a per-item, data-derived reason, or null when the card is better served by the
 *   artist name.
 */
data class ForYouCard(
    /** Representative track: supplies the cover, and the title when this is not a collection. */
    val track: TrackDescriptor,
    val caption: ForYouCaption? = null,
    /** When set, this card stands for a whole playlist or album rather than one track. */
    val collection: ForYouCollection? = null,
)

/**
 * A group offered as one thing.
 *
 * The unit in which music was loved is not always the track. Someone who only ever played a song
 * inside one playlist did not fall for the song — resurfacing it alone misrepresents how they
 * listened, and the playlist is the honest offer.
 */
data class ForYouCollection(
    val title: String,
    val tracks: List<TrackDescriptor>,
)

data class ForYouSection(
    val kind: ForYouSectionKind,
    val cards: List<ForYouCard>,
)

/**
 * Why the hero is the hero — the same argument the captions make, in the register of a headline.
 *
 * Separate from [ForYouCaption] because the two are worded differently even when they rest on the
 * same fact: a card says "8× before", the hero says it played this eight times.
 */
sealed interface ForYouKicker {

    /** Something was interrupted, and the hero picks it back up. */
    data object Resume : ForYouKicker

    /** A proven favourite gone quiet, with how often it was played. */
    data class PlayedTimes(val plays: Int) : ForYouKicker

    /** Owned but never heard. */
    data object NeverPlayed : ForYouKicker
}

/**
 * The single card at the top of the page.
 *
 * It exists because the decision window is about a minute: the first screen has to make one
 * confident play possible without scrolling or comparing. Everything below it is for browsing; this
 * is for not having to.
 *
 * @param kicker why this one. Data-derived and therefore different day to day — a line that always
 *   reads the same would be decoration.
 * @param resumeAtMs where to start, when this is an unfinished track
 */
data class ForYouHero(
    val track: TrackDescriptor,
    val kicker: ForYouKicker,
    val resumeAtMs: Long? = null,
)

/** The page: one hero, then rows. */
data class ForYouPage(
    val hero: ForYouHero? = null,
    val sections: List<ForYouSection> = emptyList(),
) {
    val isEmpty: Boolean get() = hero == null && sections.isEmpty()
}

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

    /** A personalized shelf needs a pattern, not one isolated old play-count record. */
    const val MIN_REVISIT_TRACKS = 3

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

    /** A mix is a decision-sized playlist, not every track assigned to an embedding cluster. */
    const val MIX_TRACK_LIMIT = 30

    /**
     * A genre or discovery mix represents a region, not one artist's discography.
     *
     * Artist-labelled mixes are exempt because their title explicitly promises that artist.
     */
    const val MIX_MAX_PER_ARTIST = 3

    /**
     * How many dormant tracks a playlist or album needs before it is offered as a whole.
     *
     * Two is coincidence — any pair of tracks shares some album. Three is a pattern, and at that
     * point the group is a better description of what went quiet than three separate cards are.
     */
    const val COLLAPSE_MIN = 3

    /**
     * A part-played track counts as resumable only past this point — before it, the listener was
     * auditioning rather than interrupted, and offering to resume reads as noise.
     */
    const val MIN_RESUME_MS = 45_000L

    /** Normal songs restart cleanly; resuming is useful for mixes, sets and other long recordings. */
    const val MIN_RESUMABLE_DURATION_MS = 8L * 60 * 1000

    fun build(
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        recentEvents: List<ListenEvent>,
        nowMs: Long,
        excluded: Set<TrackId> = emptySet(),
        playlists: List<Playlist> = emptyList(),
        worlds: List<LibraryWorld> = emptyList(),
        discoveryMixLabel: String = "Discovery mix",
        includeNoveltyMixes: Boolean = false,
        semanticMixLabels: Map<LibraryWorldSemanticTitle, String> = emptyMap(),
    ): ForYouPage {
        val byId = library.associateBy { it.id }
        // Accumulates across the page, so a track shown once is not shown again further down.
        val used = HashSet<TrackId>(excluded)

        val hero = hero(byId, stats, recentEvents, nowMs, excluded)
        hero?.let { used.add(it.track.id) }

        val sections = mutableListOf<ForYouSection>()
        continueListening(byId, recentEvents, used)?.let(sections::add)
        worthRevisiting(byId, stats, nowMs, used, playlists)?.let(sections::add)
        // Above "never played" and below the history rows on purpose. With nothing logged the rows
        // above are all empty and this becomes the first thing on the page — which is what it is
        // for. Once there IS history, rows built from it have the better claim on the top of a
        // surface where the first two get read and the rest largely do not.
        worlds(
            worlds = worlds,
            stats = stats,
            nowMs = nowMs,
            used = used,
            excluded = excluded,
            discoveryMixLabel = discoveryMixLabel,
            includeNoveltyMixes = includeNoveltyMixes,
            semanticMixLabels = semanticMixLabels,
        )?.let(sections::add)
        neverPlayed(library, stats, used)?.let(sections::add)

        return ForYouPage(hero, sections)
    }

    /**
     * Picks the one thing most worth offering, in descending order of how strong a claim it has:
     * something interrupted, then a proven favourite gone quiet, then something owned and unheard.
     *
     * Falling through rather than insisting on one signal is what lets the card exist on day one and
     * still be the best available answer in month six.
     */
    private fun hero(
        byId: Map<TrackId, TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        recentEvents: List<ListenEvent>,
        nowMs: Long,
        excluded: Set<TrackId>,
    ): ForYouHero? {
        interrupted(byId, recentEvents, excluded)?.let { (track, at) ->
            return ForYouHero(track, ForYouKicker.Resume, at)
        }
        val revisit = stats.entries
            .filter { (id, stat) ->
                id !in excluded &&
                    stat.plays >= MIN_PLAYS_TO_COUNT &&
                    stat.lastPlayedAtMs > 0 &&
                    nowMs - stat.lastPlayedAtMs >= QUIET_MS &&
                    stat.completions >= stat.skips
            }
            .maxByOrNull { it.value.completions }
        revisit?.let { (id, stat) ->
            byId[id]?.let { return ForYouHero(it, ForYouKicker.PlayedTimes(stat.plays)) }
        }
        return byId.values
            .filter { (stats[it.id]?.plays ?: 0) == 0 }
            .filter { it.id !in excluded }
            .maxByOrNull { it.addedAtMs ?: Long.MIN_VALUE }
            ?.let { ForYouHero(it, ForYouKicker.NeverPlayed) }
    }

    /** The most recent track abandoned past [MIN_RESUME_MS], with where it stopped. */
    private fun interrupted(
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        excluded: Set<TrackId>,
    ): Pair<TrackDescriptor, Long>? =
        recentEvents
            .firstOrNull { event ->
                !event.completed &&
                    event.playedMs >= MIN_RESUME_MS &&
                    (event.trackDurationMs ?: 0) >= MIN_RESUMABLE_DURATION_MS &&
                    // Guard against a stale position past the end of a since-replaced file.
                    (event.trackDurationMs ?: 0) > event.playedMs &&
                    event.trackId !in excluded &&
                    byId.containsKey(event.trackId)
            }
            ?.let { event -> byId.getValue(event.trackId) to event.playedMs }

    /**
     * Tracks left unfinished. Distinct from "recently played", which is memory — this is an
     * outstanding intention, and the only row here with a resume position attached.
     */
    private fun continueListening(
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        used: MutableSet<TrackId>,
    ): ForYouSection? {
        val picked = capPerArtist(
            recentEvents
                .filter { event ->
                    !event.completed &&
                        event.playedMs >= MIN_RESUME_MS &&
                        (event.trackDurationMs ?: 0) >= MIN_RESUMABLE_DURATION_MS &&
                        (event.trackDurationMs ?: 0) > event.playedMs &&
                        event.trackId !in used
                }
                .distinctBy { it.trackId }
                .mapNotNull { byId[it.trackId] },
        )
        if (picked.isEmpty()) return null
        return ForYouSection(
            kind = ForYouSectionKind.CONTINUE,
            cards = picked.map { ForYouCard(it) }.onEach { used.add(it.track.id) },
        )
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
        playlists: List<Playlist>,
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

        val dormant = candidates.map { it.first }
        if (dormant.size < MIN_REVISIT_TRACKS) return null
        val statOf = candidates.associate { it.first.id to it.second }

        // Groups first, so the tracks they absorb never also appear as singles below them.
        val claimed = HashSet<TrackId>()
        val groups = collapseToCollections(dormant, playlists, claimed)
        val singles = capPerArtist(dormant.filterNot { it.id in claimed })

        val cards = groups + singles.map { track ->
            ForYouCard(track, caption = ForYouCaption.PlayedBefore(statOf[track.id]?.plays ?: 0))
        }
        if (cards.isEmpty()) return null
        return ForYouSection(
            kind = ForYouSectionKind.WORTH_REVISITING,
            cards = cards.take(ROW_LIMIT).onEach { card ->
                used.add(card.track.id)
                card.collection?.tracks?.forEach { used.add(it.id) }
            },
        )
    }

    /**
     * Offers a playlist or album in place of its dormant members, when enough of them went quiet
     * together.
     *
     * Playlists win over albums: a playlist is something the listener assembled deliberately, so it
     * describes their intent better than a shared release does.
     *
     * This infers grouping from MEMBERSHIP, not from where the plays actually happened — listening
     * events carry no record of the playlist or album they were started from. That would be the
     * precise version of this rule, and it needs a new field on the event log.
     */
    private fun collapseToCollections(
        dormant: List<TrackDescriptor>,
        playlists: List<Playlist>,
        claimed: MutableSet<TrackId>,
    ): List<ForYouCard> {
        val out = mutableListOf<ForYouCard>()
        val dormantById = dormant.associateBy { it.id }

        for (playlist in playlists) {
            val members = playlist.trackIds
                .mapNotNull { id -> dormantById[TrackId(id)] }
                .filterNot { it.id in claimed }
            if (members.size < COLLAPSE_MIN) continue
            members.forEach { claimed.add(it.id) }
            out.add(
                ForYouCard(
                    track = members.first(),
                    caption = ForYouCaption.TrackCount(members.size),
                    collection = ForYouCollection(playlist.name, members),
                ),
            )
        }

        dormant
            .filterNot { it.id in claimed }
            .filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!! }
            .filterValues { it.size >= COLLAPSE_MIN }
            .forEach { (album, members) ->
                members.forEach { claimed.add(it.id) }
                out.add(
                    ForYouCard(
                        track = members.first(),
                        caption = ForYouCaption.TrackCount(members.size),
                        collection = ForYouCollection(album, members),
                    ),
                )
            }
        return out
    }

    /**
     * The library's own regions, ordered by completion/skips, freshness, and exploration.
     *
     * The worlds themselves are found without any reference to listening — that is the whole point
     * of the row — but which of them leads is a personal question. A Bayesian-smoothed mean keeps a
     * large region or a pile of neutral starts from winning by volume alone.
     *
     * Unlike every other row here, a world does NOT consume its members. Its pool is the entire
     * library, so retiring several hundred tracks from the rows below it would starve them for the
     * sake of a rule meant to stop the same album appearing three times. Only the cover is claimed,
     * because two identical covers on one page is exactly what that rule is about.
     */
    private fun worlds(
        worlds: List<LibraryWorld>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        used: MutableSet<TrackId>,
        excluded: Set<TrackId>,
        discoveryMixLabel: String,
        includeNoveltyMixes: Boolean,
        semanticMixLabels: Map<LibraryWorldSemanticTitle, String>,
    ): ForYouSection? {
        if (worlds.isEmpty()) return null
        val visibleWorlds = worlds.filter { world ->
            includeNoveltyMixes ||
                world.content !in setOf(
                    LibraryWorldContent.NOVELTY,
                    LibraryWorldContent.SOUND_EFFECTS,
                )
        }
        if (visibleWorlds.isEmpty()) return null
        val sorted = ForYouFeedbackRanker.rankWorlds(
            worlds = visibleWorlds,
            stats = stats,
            nowMs = nowMs,
            quietMs = QUIET_MS,
            excluded = excluded,
        )
        val genericCount = sorted.count { it.nameSource == LibraryWorldNameSource.GENERIC }
        var genericIndex = 0
        val cards = sorted
            .mapNotNull { world ->
                val eligibleTracks = world.tracks.filterNot { it.id in excluded }
                if (eligibleTracks.isEmpty()) return@mapNotNull null
                // Feedback and freshness lead; embedding centrality remains the stable tie-break.
                val ordered = ForYouFeedbackRanker.rankTracks(
                    tracks = eligibleTracks,
                    stats = stats,
                    nowMs = nowMs,
                    quietMs = QUIET_MS,
                )
                // Keep the generated claim and its art in agreement. For a genre/decade mix, for
                // example, a fresh cover from another family is not a valid substitute.
                val cover = ordered.firstOrNull { it.id !in used && world.supportsName(it) }
                    ?: ordered.firstOrNull { it.id !in used }
                    ?: return@mapNotNull null
                val ranked = listOf(cover) + ordered.filterNot { it.id == cover.id }
                val mixTracks = if (world.nameSource == LibraryWorldNameSource.ARTIST) {
                    ranked.take(MIX_TRACK_LIMIT)
                } else {
                    balanceMixArtists(ranked)
                }
                val title = when {
                    world.semanticTitle != null ->
                        semanticMixLabels[world.semanticTitle] ?: world.name
                    world.nameSource == LibraryWorldNameSource.GENERIC -> {
                        genericIndex++
                        if (genericCount == 1) {
                            discoveryMixLabel
                        } else {
                            "$discoveryMixLabel $genericIndex"
                        }
                    }
                    else -> world.name
                }
                ForYouCard(
                    track = cover,
                    caption = ForYouCaption.TrackCount(mixTracks.size),
                    collection = ForYouCollection(
                        title = title,
                        // The cover leads the list too. A card that shows one record and starts on
                        // another is the smallest possible way to look broken.
                        tracks = mixTracks,
                    ),
                )
            }
            .take(ROW_LIMIT)
        if (cards.isEmpty()) return null
        return ForYouSection(
            kind = ForYouSectionKind.WORLDS,
            cards = cards.onEach { used.add(it.track.id) },
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
            kind = ForYouSectionKind.NEVER_PLAYED,
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

    /**
     * Keeps the cluster's centrality order while preventing one artist from occupying the front.
     *
     * The first eligible track remains the cover. Each next pick is the highest-ranked track from
     * a different artist when possible, falling back to the highest-ranked eligible track only
     * when the remaining cluster contains one artist. Featured-artist suffixes are grouped under
     * the primary artist, so "Eminem", "Eminem feat. …", and "Eminem featuring …" share one cap.
     */
    private fun balanceMixArtists(tracks: List<TrackDescriptor>): List<TrackDescriptor> {
        val remaining = tracks.toMutableList()
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<TrackDescriptor>(minOf(MIX_TRACK_LIMIT, tracks.size))
        var previousArtist: String? = null

        while (out.size < MIX_TRACK_LIMIT && remaining.isNotEmpty()) {
            var selected = remaining.indexOfFirst { track ->
                val artist = primaryArtistKey(track.artist)
                (perArtist[artist] ?: 0) < MIX_MAX_PER_ARTIST &&
                    (out.isEmpty() || artist != previousArtist)
            }
            if (selected < 0) {
                selected = remaining.indexOfFirst { track ->
                    val artist = primaryArtistKey(track.artist)
                    (perArtist[artist] ?: 0) < MIX_MAX_PER_ARTIST
                }
            }
            if (selected < 0) break

            val track = remaining.removeAt(selected)
            val artist = primaryArtistKey(track.artist)
            perArtist[artist] = (perArtist[artist] ?: 0) + 1
            previousArtist = artist
            out.add(track)
        }
        return out
    }

    private fun primaryArtistKey(artist: String?): String {
        val normalized = artist?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return "<unknown>"
        val collaborationMarkers = listOf(
            " feat.",
            " feat ",
            " featuring ",
            " ft.",
            " ft ",
            " with ",
        )
        val cut = collaborationMarkers
            .map { marker -> normalized.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
        return if (cut == null) normalized else normalized.substring(0, cut).trim()
    }
}

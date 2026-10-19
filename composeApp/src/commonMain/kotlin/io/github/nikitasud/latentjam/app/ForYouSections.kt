/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.localTimePoint
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

    /**
     * What this listener provably plays at THIS time of day, plus a few unheard tracks from the
     * same regions. The section that answers "how did it know what I want right now": the same
     * card lands as clairvoyant or as noise depending on when it appears, and this listener's
     * own log shows morning and evening repertoires that share zero top tracks.
     */
    DAYPART,

    /** Proven favourites gone quiet. */
    WORTH_REVISITING,

    /**
     * One artist suddenly dominating the week — a phase, detected while it is happening — and
     * that artist's unheard tracks, closest-in-sound first. The row only exists mid-phase.
     *
     * The phase names the artist; the cards are that artist's UNHEARD catalogue by construction
     * (see [ForYouRhythm.bingeDeepCuts]), and every one of them is an offer whose show is written
     * to the impression journal — which is what keeps the row a discovery instead of a replay of
     * the listener's week. A cut offered on a recent day and still unplayed therefore steps behind
     * one the listener has not been shown, so the phase keeps introducing the catalogue rather
     * than re-offering the same handful.
     */
    ON_A_ROLL,

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

    /**
     * The loved track that vouches for the daypart row's one far pick (the former wildcard) — the
     * receipt that makes one unexpected card trustworthy instead of random.
     */
    data class LeftTurnFrom(val title: String) : ForYouCaption
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
    /** Stable owner when this offer represents a user-created playlist. */
    val playlistId: String? = null,
    /**
     * The card's own identity — `collection:<playlist id>`, `collection:<album>|<album artist>` or
     * `mix:<representative track id>` — under which its impressions are recorded. Never the cover's
     * track id: showing a collection or a mix is not an offer of its cover track.
     */
    val key: String = "",
)

/** What a card's impressions are recorded under: its collection's own key, else its track. */
val ForYouCard.impressionKey: String
    get() = collection?.key?.takeIf { it.isNotEmpty() } ?: track.id.value

data class ForYouSection(
    val kind: ForYouSectionKind,
    val cards: List<ForYouCard>,
    /** Which phase of the day a [ForYouSectionKind.DAYPART] section speaks for. */
    val daypart: ForYouDaypart? = null,
    /** The artist a [ForYouSectionKind.ON_A_ROLL] section is about; the header names them. */
    val subject: String? = null,
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

/**
 * The page: one hero, then rows.
 *
 * The page carries no list of "offers" for the caller to record. Building a page offers nothing —
 * the rows compose only what fits the viewport — so the unit of an impression is a card reaching
 * the screen, and it is the surface that knows it (see ForYouTab's shown-card reporting).
 */
data class ForYouPage(
    val hero: ForYouHero? = null,
    val sections: List<ForYouSection> = emptyList(),
) {
    val isEmpty: Boolean get() = hero == null && sections.isEmpty()
}

/**
 * What the page showed on prior days, in the shapes [ForYouBuilder.build] takes.
 *
 * The hero and "worth revisiting" share one record — a favourite offered in either and not taken
 * steps back in both — the daypart row keeps its own, and never-played offers passed over on
 * enough days retire for a while ([ForYouRhythm.retiredUntil]).
 */
internal data class ForYouShownHistory(
    val revisit: Map<String, Long> = emptyMap(),
    val daypart: Map<String, Long> = emptyMap(),
    val retired: Set<TrackId> = emptySet(),
) {
    companion object {
        /** [bySection]: section → card key → prior shown days, as the impression journal returns them. */
        fun from(
            bySection: Map<String, Map<String, List<Long>>>,
            stats: Map<TrackId, TrackStats>,
            today: Long,
        ): ForYouShownHistory {
            fun lastShown(vararg sections: String): Map<String, Long> = buildMap {
                for (section in sections) {
                    bySection[section]?.forEach { (key, days) ->
                        val last = days.maxOrNull() ?: return@forEach
                        if (last > (get(key) ?: Long.MIN_VALUE)) put(key, last)
                    }
                }
            }
            val retired = bySection["never-played"].orEmpty()
                .filter { (key, days) ->
                    (stats[TrackId(key)]?.plays ?: 0) == 0 &&
                        (ForYouRhythm.retiredUntil(days) ?: Long.MIN_VALUE) > today
                }
                .keys
                .mapTo(HashSet(), ::TrackId)
            return ForYouShownHistory(
                revisit = lastShown("hero", "worth-revisiting"),
                daypart = lastShown("daypart"),
                retired = retired,
            )
        }
    }
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
     * The mature "forgotten" window: 90 days, not 30 — a month is an ordinary gap in rotation.
     *
     * This is a CEILING, not the working value. Measured on a real 26-day-old install, a fixed
     * 90-day window meant the page's flagship section had never existed and could not exist for
     * months: 0 candidates at 90d and 30d, 28 at 14d. [adaptiveQuietMs] scales the window to the
     * log's own age so the section lives from the first weeks and matures toward this value.
     */
    const val QUIET_MS = 90L * 24 * 60 * 60 * 1000

    /** Below a week, "gone quiet" is indistinguishable from "listened on Tuesday". */
    const val MIN_QUIET_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Ranked slots the worlds row always keeps at the front; the rest of the row rotates daily.
     * Measured before rotation: the page was IDENTICAL day over day (worlds Jaccard 1.00 at -1d)
     * — a page with nothing new on reopen is a page that never becomes a habit.
     */
    const val WORLDS_KEEP_RANKED = 3

    /** Newest-unplayed pool the daily rotation draws from; newest-first remains the intent. */
    const val NEVER_PLAYED_WINDOW = 24

    /**
     * Guaranteed slots for the listener's own regions. Measured without them: the ranker's
     * freshness+exploration terms give every UNTOUCHED member a perfect score, so the worlds the
     * listener actually lives in were outranked by worlds they had never opened — the three
     * most-played clusters did not appear on the page at all. Affinity is evidence-weighted
     * satisfaction, so one accidental play cannot claim a slot.
     */
    const val WORLDS_AFFINITY_SLOTS = 3

    /** Member scores counted toward a world's affinity: enough for a pattern, blind to size. */
    const val WORLDS_AFFINITY_EVIDENCE = 3

    /**
     * Loved anchors leading each non-artist mix. Measured without them: only 2 of the listener's
     * top-20 tracks appeared anywhere in the mixes row — a "For You" with almost no "you" in it,
     * because unheard tracks win both freshness and exploration. Anchors put recognizable loved
     * tracks (and the cover) first while the tail keeps exploring.
     */
    const val MIX_ANCHOR_SLOTS = 6

    /**
     * Half the log's observed age, clamped to [[MIN_QUIET_MS], [QUIET_MS]]. The oldest last-touch
     * across stats approximates when listening began without a new history API.
     */
    internal fun adaptiveQuietMs(stats: Map<TrackId, TrackStats>, nowMs: Long): Long {
        val oldestTouch = stats.values.asSequence()
            .map { it.lastPlayedAtMs }
            .filter { it > 0 }
            .minOrNull() ?: return QUIET_MS
        return ((nowMs - oldestTouch) / 2).coerceIn(MIN_QUIET_MS, QUIET_MS)
    }

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

    /** A playlist larger than this is a catch-all, not a unit of taste, and is never offered whole. */
    const val COLLAPSE_MAX_PLAYLIST_TRACKS = 200

    /** The same, as a share of the library — for playlists big enough for a share to mean anything. */
    const val COLLAPSE_MAX_PLAYLIST_SHARE = 0.5
    const val COLLAPSE_SHARE_MIN_TRACKS = 20

    /**
     * A part-played track counts as resumable only past this point — before it, the listener was
     * auditioning rather than interrupted, and offering to resume reads as noise.
     */
    const val MIN_RESUME_MS = 45_000L

    /** Normal songs restart cleanly; resuming is useful for mixes, sets and other long recordings. */
    const val MIN_RESUMABLE_DURATION_MS = 8L * 60 * 1000

    /**
     * How long an interruption stays an open intention. Past two weeks the listener has moved on, and
     * a stale resume offer would otherwise hold the hero for as long as it sits in the event window.
     */
    const val RESUME_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

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
        /** Local wall-clock hour for an instant; injectable so replays can pin a timezone. */
        localHourOf: (Long) -> Int = { localTimePoint(it).hourOfDay },
        /**
         * Local calendar day for an instant — the same epoch day the impression store records
         * (`localTimePoint(...).epochDay`). Every rotation below turns over at LOCAL midnight,
         * exactly where the cool-off window and the impression journal already turn over: keyed to
         * the UTC day instead, the page rotated at an hour belonging to the listener's previous or
         * next day, so a "fresh" slot could hand back what the cooldown had just retired while the
         * listener's own day had not started yet. Injectable so tests and replays can pin it.
         */
        localDayOf: (Long) -> Long = { localTimePoint(it).epochDay },
        /**
         * Unheard tracks offered on recent PRIOR days and still not played — they yield their
         * discovery slots to strangers that have not had a turn yet. Same-day offers are never
         * in this set, so the page stays stable within its day.
         */
        cooledDiscoveries: Set<TrackId> = emptySet(),
        /**
         * The last PRIOR local day each card was shown as the hero or in "worth revisiting", by
         * [ForYouCard.impressionKey]. A favourite shown and not played since steps behind the
         * others for [ForYouRhythm.DEMOTION_DAYS], so these offers move day to day.
         */
        revisitShownDays: Map<String, Long> = emptyMap(),
        /** The same for the daypart row's cards; there a play on or after the shown day cancels it. */
        daypartShownDays: Map<String, Long> = emptyMap(),
        /** Unheard tracks offered on several days and passed over (see [ForYouRhythm.retiredUntil]). */
        retiredDiscoveries: Set<TrackId> = emptySet(),
    ): ForYouPage {
        val byId = library.associateBy { it.id }
        // Accumulates across the page, so a track shown once is not shown again further down.
        val used = HashSet<TrackId>(excluded)

        val quietMs = adaptiveQuietMs(stats, nowMs)
        // One day key for the whole page, taken from the listener's own calendar day rather than
        // from elapsed UTC time: the cool-off window above and the impression journal both count
        // local days, and a page whose rotation disagreed with them by a timezone offset either
        // re-offered what had just cooled or stood still after its own day had ended.
        val dayIndex = localDayOf(nowMs).toInt()
        val today = localDayOf(nowMs)
        // Shown on a prior day within the demotion window, and on or after the day of the last
        // play: offered, and not taken. Day granularity is all the impression journal records.
        val revisitIgnored: (String, Long) -> Boolean = { key, lastPlayedAtMs ->
            val shown = revisitShownDays[key]
            shown != null && shown >= today - ForYouRhythm.DEMOTION_DAYS && shown < today &&
                shown >= localDayOf(lastPlayedAtMs)
        }
        val hero = hero(
            byId, stats, recentEvents, nowMs, quietMs, excluded, cooledDiscoveries, dayIndex,
            revisitIgnored,
        )
        hero?.let { used.add(it.track.id) }

        val sections = mutableListOf<ForYouSection>()
        continueListening(byId, recentEvents, nowMs, used)?.let(sections::add)
        // Directly under the resume row: timing is the cheapest magic there is, and this is the
        // one section whose claim is about RIGHT NOW.
        daypartSection(
            byId, recentEvents, worlds, stats, nowMs, used, localHourOf, cooledDiscoveries, dayIndex,
            daypartIgnored = { key, lastPlayedAtMs ->
                val shown = daypartShownDays[key]
                shown != null && shown >= today - ForYouRhythm.DEMOTION_DAYS && shown < today &&
                    localDayOf(lastPlayedAtMs) < shown
            },
        )?.let(sections::add)
        worthRevisiting(byId, stats, nowMs, quietMs, used, playlists, revisitIgnored)?.let(sections::add)
        onARoll(library, byId, recentEvents, stats, worlds, nowMs, used, cooledDiscoveries)
            ?.let(sections::add)
        // Above "never played" and below the history rows on purpose. With nothing logged the rows
        // above are all empty and this becomes the first thing on the page — which is what it is
        // for. Once there IS history, rows built from it have the better claim on the top of a
        // surface where the first two get read and the rest largely do not.
        worlds(
            worlds = worlds,
            stats = stats,
            nowMs = nowMs,
            quietMs = quietMs,
            used = used,
            excluded = excluded,
            discoveryMixLabel = discoveryMixLabel,
            includeNoveltyMixes = includeNoveltyMixes,
            semanticMixLabels = semanticMixLabels,
            dayIndex = dayIndex,
        )?.let(sections::add)
        neverPlayed(
            library, stats, nowMs, used, cooledDiscoveries, retiredDiscoveries, dayIndex, localDayOf,
        )?.let(sections::add)

        return ForYouPage(hero, sections)
    }

    /**
     * Picks the one thing most worth offering, in descending order of how strong a claim it has:
     * something interrupted, then a proven favourite gone quiet, then something owned and unheard.
     *
     * Falling through rather than insisting on one signal is what lets the card exist on day one and
     * still be the best available answer in month six. The unheard fallback skips
     * [cooledDiscoveries] — the hero's own card is written to the impression journal like any other
     * (see ForYouTab's shown-card reporting), so it has to answer that journal too; a hero that
     * ignored it cycled the same handful of tracks for as long as the library stayed quiet. When
     * every unheard track is cooling the fallback yields no hero for that day and the rows below
     * carry the page — a card the listener has already scrolled past three days running is the
     * weaker offer of the two.
     */
    private fun hero(
        byId: Map<TrackId, TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        recentEvents: List<ListenEvent>,
        nowMs: Long,
        quietMs: Long,
        excluded: Set<TrackId>,
        cooledDiscoveries: Set<TrackId>,
        dayIndex: Int,
        ignored: (String, Long) -> Boolean,
    ): ForYouHero? {
        interrupted(byId, recentEvents, nowMs, excluded)?.let { (track, at) ->
            return ForYouHero(track, ForYouKicker.Resume, at)
        }
        // The strongest favourite gone quiet — unless it was the offer on a recent day and was
        // not taken, in which case it steps behind the others (ForYouRhythm.demoteIgnored). That
        // moves the hero every day without throwing the ranking away, and the pool never empties.
        val ranked = stats.entries
            .filter { (id, stat) ->
                id !in excluded &&
                    byId.containsKey(id) &&
                    stat.listens >= MIN_PLAYS_TO_COUNT &&
                    stat.lastPlayedAtMs > 0 &&
                    nowMs - stat.lastPlayedAtMs >= quietMs &&
                    stat.completions >= stat.skips
            }
            .sortedWith(
                compareByDescending<Map.Entry<TrackId, TrackStats>> { it.value.completions }
                    .thenBy { it.key.value },
            )
        val revisit = ForYouRhythm.demoteIgnored(
            ranked,
            ranked.filterTo(HashSet()) { (id, stat) -> ignored(id.value, stat.lastPlayedAtMs) },
        ).firstOrNull()
        if (revisit != null) {
            val (id, stat) = revisit
            return ForYouHero(byId.getValue(id), ForYouKicker.PlayedTimes(stat.listens))
        }
        val unheard = byId.values
            .filter { (stats[it.id]?.plays ?: 0) == 0 }
            .filter { it.id !in excluded && it.id !in cooledDiscoveries }
            .sortedByDescending { it.addedAtMs ?: Long.MIN_VALUE }
            .take(HERO_ROTATION_POOL)
        if (unheard.isEmpty()) return null
        return ForYouHero(unheard[dayIndex.mod(unheard.size)], ForYouKicker.NeverPlayed)
    }

    /** How many top candidates the hero cycles through, one per day. */
    const val HERO_ROTATION_POOL = 5

    /**
     * Tracks whose NEWEST event left them unfinished past [MIN_RESUME_MS], within
     * [RESUME_WINDOW_MS], newest first.
     *
     * Only the newest event of a track decides: an older interruption followed by a full play is a
     * finished recording, and offering to resume it at the old position reads as the page not
     * noticing what the listener did. [recentEvents] arrive newest first, so the first event per
     * track is its newest.
     */
    private fun resumable(recentEvents: List<ListenEvent>, nowMs: Long): List<ListenEvent> =
        recentEvents
            .distinctBy { it.trackId }
            .filter { event ->
                !event.completed &&
                    event.playedMs >= MIN_RESUME_MS &&
                    (event.trackDurationMs ?: 0) >= MIN_RESUMABLE_DURATION_MS &&
                    // Guard against a stale position past the end of a since-replaced file.
                    (event.trackDurationMs ?: 0) > event.playedMs &&
                    nowMs - event.startedAtMs <= RESUME_WINDOW_MS
            }

    /** The most recent track abandoned past [MIN_RESUME_MS], with where it stopped. */
    private fun interrupted(
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        nowMs: Long,
        excluded: Set<TrackId>,
    ): Pair<TrackDescriptor, Long>? =
        resumable(recentEvents, nowMs)
            .firstOrNull { event -> event.trackId !in excluded && byId.containsKey(event.trackId) }
            ?.let { event -> byId.getValue(event.trackId) to event.playedMs }

    /**
     * Tracks left unfinished. Distinct from "recently played", which is memory — this is an
     * outstanding intention, and the only row here with a resume position attached.
     */
    private fun continueListening(
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        nowMs: Long,
        used: MutableSet<TrackId>,
    ): ForYouSection? {
        val picked = capPerArtist(
            resumable(recentEvents, nowMs)
                .filter { it.trackId !in used }
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
        quietMs: Long,
        used: MutableSet<TrackId>,
        playlists: List<Playlist>,
        ignored: (String, Long) -> Boolean,
    ): ForYouSection? {
        val candidates = stats.entries
            .filter { (id, stat) ->
                id !in used &&
                    stat.listens >= MIN_PLAYS_TO_COUNT &&
                    stat.lastPlayedAtMs > 0 &&
                    nowMs - stat.lastPlayedAtMs >= quietMs &&
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
        val groups = collapseToCollections(dormant, playlists, claimed, byId.size)
        val singleCards = capPerArtist(dormant.filterNot { it.id in claimed }, limit = Int.MAX_VALUE)
            .map { track ->
                ForYouCard(track, caption = ForYouCaption.PlayedBefore(statOf[track.id]?.listens ?: 0))
            }
        // One ranking for collections and singles: a collection stands where its strongest member
        // would, so a playlist no longer takes the row's head just by being a playlist. Then the
        // offers shown and not taken step behind the rest, the collection under its own key with
        // its newest member play as "last play".
        val rank = dormant.withIndex().associate { (index, track) -> track.id to index }
        val rankOf: (ForYouCard) -> Int = { card ->
            card.collection?.tracks?.minOf { rank[it.id] ?: Int.MAX_VALUE } ?: rank[card.track.id] ?: Int.MAX_VALUE
        }
        val lastPlayOf: (ForYouCard) -> Long = { card ->
            card.collection?.tracks?.maxOf { statOf[it.id]?.lastPlayedAtMs ?: 0L }
                ?: statOf[card.track.id]?.lastPlayedAtMs ?: 0L
        }
        val ordered = (groups + singleCards).sortedBy(rankOf)
        val cards = ForYouRhythm.demoteIgnored(
            ordered,
            ordered.filterTo(HashSet()) { ignored(it.impressionKey, lastPlayOf(it)) },
        )
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
        librarySize: Int,
    ): List<ForYouCard> {
        val out = mutableListOf<ForYouCard>()
        val dormantById = dormant.associateBy { it.id }

        for (playlist in playlists) {
            // A catch-all playlist ("All music" imported from an M3U) is not a unit of taste: it
            // would swallow every dormant track into one card.
            val size = playlist.trackIds.size
            if (size > COLLAPSE_MAX_PLAYLIST_TRACKS ||
                (size >= COLLAPSE_SHARE_MIN_TRACKS && size > librarySize * COLLAPSE_MAX_PLAYLIST_SHARE)
            ) {
                continue
            }
            val members = playlist.trackIds
                .mapNotNull { id -> dormantById[TrackId(id)] }
                .filterNot { it.id in claimed }
            if (members.size < COLLAPSE_MIN) continue
            members.forEach { claimed.add(it.id) }
            out.add(
                ForYouCard(
                    track = members.first(),
                    caption = ForYouCaption.TrackCount(members.size),
                    collection = ForYouCollection(
                        playlist.name,
                        members,
                        playlistId = playlist.id,
                        key = "collection:${playlist.id}",
                    ),
                ),
            )
        }

        // An album is its title AND its artist: two artists' "Greatest Hits" are two albums.
        dormant
            .filterNot { it.id in claimed }
            .filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!! to (it.albumArtist?.trim()?.lowercase() ?: primaryArtistKey(it)) }
            .filterValues { it.size >= COLLAPSE_MIN }
            .forEach { (album, members) ->
                members.forEach { claimed.add(it.id) }
                out.add(
                    ForYouCard(
                        track = members.first(),
                        caption = ForYouCaption.TrackCount(members.size),
                        collection = ForYouCollection(
                            album.first,
                            members,
                            key = "collection:${album.first}|${album.second}",
                        ),
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
        quietMs: Long,
        used: MutableSet<TrackId>,
        excluded: Set<TrackId>,
        discoveryMixLabel: String,
        includeNoveltyMixes: Boolean,
        semanticMixLabels: Map<LibraryWorldSemanticTitle, String>,
        dayIndex: Int,
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
        val ranked = ForYouFeedbackRanker.rankWorlds(
            worlds = visibleWorlds,
            stats = stats,
            nowMs = nowMs,
            quietMs = quietMs,
            excluded = excluded,
        )
        // Row composition: first the worlds the listener demonstrably loves, then the ranker's
        // best exploration candidates, then a daily rotation through everything else — so the
        // page always contains "you", always offers something new, and changes across days
        // while staying stable within one.
        val affinity = visibleWorlds
            .map { world ->
                // The strongest few members, not a sum over all: summing lets a large world with
                // thirty incidental plays outrank a tight one holding the listener's real
                // favourites — exactly the bias the ranker's trimmed mean exists to prevent.
                world to world.tracks
                    .mapNotNull { track ->
                        val stat = stats[track.id] ?: return@mapNotNull null
                        if (stat.plays <= 0) return@mapNotNull null
                        ForYouFeedbackRanker.satisfaction(stat) * kotlin.math.ln(1.0 + stat.plays)
                    }
                    .sortedDescending()
                    .take(WORLDS_AFFINITY_EVIDENCE)
                    .sum()
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(WORLDS_AFFINITY_SLOTS)
            .map { it.first }
        val rankedHead = ranked.filterNot { it in affinity }.take(WORLDS_KEEP_RANKED)
        val rotatingTail = ranked.filterNot { it in affinity || it in rankedHead }
        val sorted = affinity + rankedHead + if (rotatingTail.isEmpty()) {
            rotatingTail
        } else {
            List(rotatingTail.size) { rotatingTail[(it + dayIndex).mod(rotatingTail.size)] }
        }
        // A generic mix is numbered by its own identity, not by where today's rotation put it: a
        // number counted in display order made "Discovery mix 2" a different mix every day.
        val genericNumber = visibleWorlds
            .filter { it.nameSource == LibraryWorldNameSource.GENERIC }
            .sortedBy { it.representative.id.value }
            .withIndex()
            .associate { (index, world) -> world to index + 1 }
        val cards = sorted
            .mapNotNull { world ->
                val eligibleTracks = world.tracks.filterNot { it.id in excluded }
                if (eligibleTracks.isEmpty()) return@mapNotNull null
                // Feedback and freshness lead; embedding centrality remains the stable tie-break.
                val ordered = ForYouFeedbackRanker.rankTracks(
                    tracks = eligibleTracks,
                    stats = stats,
                    nowMs = nowMs,
                    quietMs = quietMs,
                )
                // Loved anchors guarantee the listener's own tracks a place INSIDE the mix; the
                // COVER still comes from the balanced ranking, so a track played yesterday does
                // not front a discovery card while a rested favourite or fresh central one can.
                val anchors = eligibleTracks
                    .filter { (stats[it.id]?.plays ?: 0) > 0 }
                    .sortedByDescending { ForYouFeedbackRanker.satisfaction(stats[it.id]) }
                    .take(MIX_ANCHOR_SLOTS)
                val anchorIds = anchors.mapTo(HashSet()) { it.id }
                // Keep the generated claim and its art in agreement. For a genre/decade mix, for
                // example, a fresh cover from another family is not a valid substitute.
                val cover = ordered.firstOrNull { it.id !in used && world.supportsName(it) }
                    ?: ordered.firstOrNull { it.id !in used }
                    ?: return@mapNotNull null
                val ranked = listOf(cover) +
                    (anchors + ordered.filterNot { it.id in anchorIds })
                        .filterNot { it.id == cover.id }
                val balanced = if (world.nameSource == LibraryWorldNameSource.ARTIST) {
                    ranked.take(MIX_TRACK_LIMIT)
                } else {
                    balanceMixArtists(ranked)
                }
                // Sandwich the body: no two unheard tracks adjacent while anchors remain, so
                // every discovery inherits credibility from a proven neighbour. The cover stays
                // first — a card that shows one record and starts on another looks broken.
                val mixTracks = if (balanced.isEmpty()) balanced else {
                    listOf(balanced.first()) +
                        ForYouRhythm.familiaritySandwich(balanced.drop(1), stats)
                }
                val title = when {
                    world.semanticTitle != null ->
                        semanticMixLabels[world.semanticTitle] ?: world.name
                    world.nameSource == LibraryWorldNameSource.GENERIC -> {
                        if (genericNumber.size == 1) {
                            discoveryMixLabel
                        } else {
                            "$discoveryMixLabel ${genericNumber.getValue(world)}"
                        }
                    }
                    else -> world.name
                }
                ForYouCard(
                    track = cover,
                    caption = ForYouCaption.TrackCount(mixTracks.size),
                    collection = ForYouCollection(
                        key = "mix:${world.representative.id.value}",
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
     * What this listener plays at THIS phase of the day. Gated on a real pattern
     * ([ForYouRhythm.DAYPART_MIN_EVENTS] in-phase events) so it never guesses; a wrong "your
     * mornings" claim would cost more than the section's absence.
     */
    private fun daypartSection(
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        worlds: List<LibraryWorld>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        used: MutableSet<TrackId>,
        localHourOf: (Long) -> Int,
        cooledDiscoveries: Set<TrackId>,
        dayIndex: Int,
        daypartIgnored: (String, Long) -> Boolean,
    ): ForYouSection? {
        val daypart = ForYouRhythm.daypartOf(localHourOf(nowMs))
        val inPhase = ForYouRhythm.daypartEventCount(recentEvents, daypart, localHourOf)
        if (inPhase < ForYouRhythm.DAYPART_MIN_EVENTS) return null
        val affinity = ForYouRhythm.daypartAffinity(recentEvents, daypart, localHourOf)
        // The former "wildcard" row: one unheard track from a loved region gone quiet, now the
        // last discovery slot of this row instead of a one-card row of its own.
        val far = ForYouRhythm.wildcard(
            worlds = worlds,
            stats = stats,
            nowMs = nowMs,
            used = used,
            dayIndex = dayIndex,
            cooled = cooledDiscoveries,
        )
        val row = ForYouRhythm.daypartRow(
            affinity = affinity,
            byId = byId,
            worlds = worlds,
            stats = stats,
            used = used,
            dayIndex = dayIndex,
            cooled = cooledDiscoveries,
            recentlyPlayed = stats.entries
                .filter { nowMs - it.value.lastPlayedAtMs < RECENTLY_PLAYED_MS }
                .mapTo(HashSet()) { it.key },
            ignored = affinity.keys.filterTo(HashSet()) { id ->
                daypartIgnored(id.value, stats[id]?.lastPlayedAtMs ?: 0L)
            },
            farPick = far?.pick,
        )
        if (row.size < COLLAPSE_MIN) return null
        return ForYouSection(
            kind = ForYouSectionKind.DAYPART,
            daypart = daypart,
            cards = row.map { track ->
                // The receipt claims only what the log proves: plays IN this daypart. Unheard
                // fresh picks carry no caption — the artist line is their honest introduction —
                // except the far pick, vouched for by the loved track it leads on from.
                val plays = affinity[track.id]?.plays ?: 0
                val vouch = far?.takeIf { it.pick.id == track.id }?.anchor
                ForYouCard(
                    track = track,
                    caption = when {
                        plays > 0 -> ForYouCaption.PlayedBefore(plays)
                        vouch != null -> (vouch.title ?: vouch.artist)?.let { ForYouCaption.LeftTurnFrom(it) }
                        else -> null
                    },
                )
            }.onEach { used.add(it.track.id) },
        )
    }

    /** Yesterday is memory: tracks played this recently stay out of the history rows' proven slots. */
    const val RECENTLY_PLAYED_MS = 24L * 60 * 60 * 1000

    /**
     * The phase the listener is in RIGHT NOW, answered with the binged artist's unheard tracks.
     * The row's existence is the recommendation: it appears mid-phase, names the artist in its
     * header, and disappears when the phase does. [cooledDiscoveries] is handed to the rhythm
     * layer so a deep cut already offered on a recent day yields its slot to one of this artist's
     * tracks the listener has never been shown (see [ForYouRhythm.bingeDeepCuts]).
     */
    private fun onARoll(
        library: List<TrackDescriptor>,
        byId: Map<TrackId, TrackDescriptor>,
        recentEvents: List<ListenEvent>,
        stats: Map<TrackId, TrackStats>,
        worlds: List<LibraryWorld>,
        nowMs: Long,
        used: MutableSet<TrackId>,
        cooledDiscoveries: Set<TrackId>,
    ): ForYouSection? {
        val binge = ForYouRhythm.currentBinge(
            events = recentEvents,
            nowMs = nowMs,
            artistOf = { byId[it]?.artist },
            artistKeyOf = ::primaryArtistKey,
        ) ?: return null
        val cuts = ForYouRhythm.bingeDeepCuts(
            binge = binge,
            library = library,
            events = recentEvents,
            stats = stats,
            worlds = worlds,
            nowMs = nowMs,
            used = used,
            cooled = cooledDiscoveries,
            artistKeyOf = ::primaryArtistKey,
        ).take(ROW_LIMIT)
        // A one-card "phase" row over-claims; with nothing unheard left the phase needs no help.
        if (cuts.size < COLLAPSE_MIN) return null
        return ForYouSection(
            kind = ForYouSectionKind.ON_A_ROLL,
            subject = binge.artistName,
            cards = cuts.map { ForYouCard(it) }.onEach { used.add(it.track.id) },
        )
    }

    /**
     * Owned but never heard. Newest first, because something added recently and still unplayed is a
     * stronger intention than something that has sat there for years.
     */
    private fun neverPlayed(
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        used: MutableSet<TrackId>,
        cooledDiscoveries: Set<TrackId>,
        retired: Set<TrackId>,
        dayIndex: Int,
        localDayOf: (Long) -> Long,
    ): ForYouSection? {
        val unheard = library.filter {
            it.id !in used && it.id !in retired && (stats[it.id]?.plays ?: 0) == 0
        }
        // Recent additions lead: something added this month and still unplayed is the strongest
        // intention on the row. Only when the add dates mean something — see [addDatesTrusted].
        val recentSince = nowMs - RECENT_ADDITION_MS
        val trusted = addDatesTrusted(library, nowMs, localDayOf)
        val recent = if (!trusted) {
            emptyList()
        } else {
            unheard
                .filter { (it.addedAtMs ?: Long.MIN_VALUE) >= recentSince }
                .sortedWith(
                    compareBy<TrackDescriptor> { it.id in cooledDiscoveries }
                        .thenByDescending { it.addedAtMs ?: 0L },
                )
        }
        val recentIds = recent.mapTo(HashSet()) { it.id }
        // Then the rest as before: offers ignored on recent days step aside before the window is
        // cut, and the day's rotation turns among the strangers that remain (see
        // [ForYouRhythm.freshRotationOrder]): a repeat may fill a leftover slot, but never leads.
        val fresh = unheard
            .filter { it.id !in recentIds }
            .sortedWith(
                compareBy<TrackDescriptor> { it.id in cooledDiscoveries }
                    .thenByDescending { it.addedAtMs ?: 0L },
            )
            .take(NEVER_PLAYED_WINDOW)
        val picked = capPerArtist(
            recent + ForYouRhythm.freshRotationOrder(fresh, cooledDiscoveries, dayIndex),
        )
        if (picked.isEmpty()) return null
        return ForYouSection(
            kind = ForYouSectionKind.NEVER_PLAYED,
            cards = picked.map { ForYouCard(it) }.onEach { used.add(it.track.id) },
        )
    }

    /** "Recently added" for the never-played row. */
    const val RECENT_ADDITION_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Whether add dates describe when the listener added music, rather than when the files reached
     * this phone. A migration, a restore or a MediaStore rebuild stamps most of the library with one
     * day; if that day falls inside the "recent" window, "recently added" would mean "everything",
     * so the boost is skipped. A library copied once, long before, keeps it — its later additions
     * are real. Missing dates (iOS Music.app) simply never count as recent.
     */
    internal fun addDatesTrusted(
        library: List<TrackDescriptor>,
        nowMs: Long,
        localDayOf: (Long) -> Long,
    ): Boolean {
        val dated = library.mapNotNull { it.addedAtMs }
        if (dated.isEmpty()) return true
        val (massDay, count) = dated.groupingBy(localDayOf).eachCount().maxBy { it.value }
        if (count * 2 <= library.size) return true
        return massDay < localDayOf(nowMs - RECENT_ADDITION_MS)
    }

    /**
     * Takes up to [ROW_LIMIT], letting no artist hold more than [MAX_PER_ARTIST] slots.
     *
     * Order is otherwise preserved, so the caller's ranking survives. Untagged tracks share the
     * empty artist key and are capped together — one block of "Unknown artist" is exactly the kind
     * of row this is meant to prevent.
     */
    private fun capPerArtist(
        tracks: List<TrackDescriptor>,
        limit: Int = ROW_LIMIT,
    ): List<TrackDescriptor> {
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<TrackDescriptor>(minOf(limit, tracks.size))
        for (track in tracks) {
            if (out.size >= limit) break
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
                val artist = primaryArtistKey(track)
                (perArtist[artist] ?: 0) < MIX_MAX_PER_ARTIST &&
                    (out.isEmpty() || artist != previousArtist)
            }
            if (selected < 0) {
                selected = remaining.indexOfFirst { track ->
                    val artist = primaryArtistKey(track)
                    (perArtist[artist] ?: 0) < MIX_MAX_PER_ARTIST
                }
            }
            if (selected < 0) break

            val track = remaining.removeAt(selected)
            val artist = primaryArtistKey(track)
            perArtist[artist] = (perArtist[artist] ?: 0) + 1
            previousArtist = artist
            out.add(track)
        }
        return out
    }

    /** Tags name the primary credit authoritatively; the display-string cut is the fallback. */
    private fun primaryArtistKey(track: TrackDescriptor): String =
        track.artists.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
            ?: primaryArtistKey(track.artist)

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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.Genres
import kotlin.math.abs

/**
 * Metadata-derived multipliers applied on top of the learned scores.
 *
 * Everything here is a multiplier around 1.0, combined into the chain score in log space so a
 * neutral verdict is exactly zero. Same-album proximity is only softly diversified: one familiar
 * neighbour is useful context, while the chain's artist-spacing rule prevents an album dump.
 */
internal object MetadataRerank {

    const val SAME_GENRE_BONUS = 1.20f
    const val SAME_ARTIST_BONUS = 1.12f
    const val CROSS_GENRE_MALUS = 0.90f
    const val CROSS_LANGUAGE_PENALTY = 0.75f
    const val SAME_ALBUM_PENALTY = 0.15f
    const val ERA_DECADE_PENALTY = 0.04f

    /**
     * A listener who explicitly starts from one track expects the beginning of the queue to sound
     * like that choice. The learned scorer and audio geometry remain primary; this is only a soft
     * penalty, and only when the retrieved pool contains enough independent evidence that the
     * seed's genre tag is credible. Once four seed-family tracks have been selected, the guard
     * releases and the walk may explore normally.
     *
     * 0.80 was selected on 1,519 real positive next-track examples: it increased same-family top-1
     * continuity by roughly four points, slightly improved full-history MRR, and changed cold-start
     * MRR by about -0.5%. With deliberately corrupted seed genres, the support gate activated on
     * only ~23% of examples and kept incremental MRR loss below 0.7%.
     */
    const val SEED_CROSS_GENRE_PENALTY = 0.80f
    const val SEED_GENRE_MIN_POOL_SUPPORT = 6
    const val SEED_GENRE_PREFIX_TARGET = 4

    /**
     * Script-based language detection: Cyrillic → ru, CJK → ja, everything else → en.
     *
     * Deliberately NOT smarter. A diacritic-based Romanian rule was measured and rejected: it split
     * the library's 11 Romanian tracks across en/ro/ru and the resulting in-cluster cross-language
     * penalties hurt more than the mislabelling did. The semantic z-term is the right layer for
     * that distinction.
     */
    fun detectLanguage(title: String?, artist: String?): String {
        val text = (title.orEmpty()) + (artist.orEmpty())
        for (character in text) {
            val code = character.code
            if (code in 0x0400..0x04FF) return "ru"
            if (code in 0x3040..0x30FF || code in 0x4E00..0x9FFF) return "ja"
        }
        return "en"
    }

    /**
     * Coarse genre family, or null when the tag is missing or meaningless.
     *
     * The vocabulary itself lives in [Genres] because library clustering names its clusters with
     * the same families; two copies of that table would drift.
     */
    fun normalizeGenre(genre: String?): String? = Genres.normalize(genre)

    /**
     * Whether the track belongs to the dense cinematic/game/anime cluster. That cluster is a hub in
     * embedding space: it leaks into chains from sparse seeds unless damped.
     */
    fun isHubGenre(genre: String?): Boolean = Genres.isHub(genre)

    private val BRACKETED = Regex("\\s*[\\(\\[][^()\\[\\]]*[\\)\\]]\\s*")
    private val WHITESPACE = Regex("\\s+")

    /** Title with bracketed qualifiers stripped, for duplicate detection across releases. */
    fun normalizeTitle(title: String?): String =
        title.orEmpty().lowercase()
            .replace(BRACKETED, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /** Canonical artist key shared by pairwise scoring, queue spacing, and per-artist caps. */
    fun normalizeArtist(artist: String?): String =
        artist.orEmpty().lowercase()
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Pairwise multiplier between the chain's current anchor and a candidate. Base 1.0; the caller
     * clamps and takes the logarithm.
     */
    fun adjustMultiplier(anchor: TrackMeta, candidate: TrackMeta): Float {
        var multiplier = 1.0f
        if (!anchor.album.isNullOrEmpty() && anchor.album == candidate.album) {
            multiplier -= SAME_ALBUM_PENALTY
        }
        if (anchor.artistKey.isNotEmpty() && anchor.artistKey == candidate.artistKey) {
            // Only one such neighbour can appear before artist spacing activates, so this modest
            // confidence signal improves the first hop without turning SMART into "play artist".
            multiplier *= SAME_ARTIST_BONUS
        }
        val anchorGenres = Genres.families(anchor.genre)
        val candidateGenres = Genres.families(candidate.genre)
        if (anchorGenres.isNotEmpty() && candidateGenres.isNotEmpty()) {
            // Any shared family counts: a "Trip Hop; Rock" track walking to a "Rock" track is a
            // same-genre step. Single-genre tags reduce this to the old equality exactly.
            val shared = anchorGenres.any { it in candidateGenres }
            multiplier *= if (shared) SAME_GENRE_BONUS else CROSS_GENRE_MALUS
        }
        if (candidate.language != anchor.language) multiplier *= CROSS_LANGUAGE_PENALTY
        val anchorYear = anchor.eraYear
        val candidateYear = candidate.eraYear
        if (anchorYear != null && candidateYear != null) {
            multiplier *= 1f - ERA_DECADE_PENALTY * abs(anchorYear - candidateYear) / 10f
        }
        return multiplier
    }

    /** Number of retrieved candidates that corroborate any of the seed's genre families. */
    fun seedGenreSupport(seedGenres: Set<String>, candidates: Iterable<TrackMeta>): Int {
        if (seedGenres.isEmpty()) return 0
        return candidates.count { candidate ->
            Genres.families(candidate.genre).any { it in seedGenres }
        }
    }

    /**
     * Seed-intent multiplier for one candidate. Missing tags stay neutral; titles are never read.
     */
    fun seedIntentMultiplier(
        seedGenres: Set<String>,
        poolSupport: Int,
        seedFamilyPicks: Int,
        candidate: TrackMeta,
    ): Float {
        if (seedGenres.isEmpty() || poolSupport < SEED_GENRE_MIN_POOL_SUPPORT) return 1f
        if (seedFamilyPicks >= SEED_GENRE_PREFIX_TARGET) return 1f
        val candidateGenres = Genres.families(candidate.genre)
        if (candidateGenres.isEmpty()) return 1f
        return if (candidateGenres.any { it in seedGenres }) 1f else SEED_CROSS_GENRE_PENALTY
    }
}

/** The metadata the chain reasons about, resolved once per track. */
internal data class TrackMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
    /** For duration-sanity damping only; null (every legacy caller) is neutral. */
    val durationMs: Long? = null,
    /**
     * The first credited individual from the file's tags, when read. Null (every fixture and
     * every unread file) keeps [artistKey] on the display string — the recorded parity replays
     * are unchanged by construction.
     */
    val primaryArtist: String? = null,
    /** First-release year; the era term prefers it so a remaster keeps its real decade. */
    val originalYear: Int? = null,
) {
    val language: String = MetadataRerank.detectLanguage(title, artist)
    val normalizedTitle: String = MetadataRerank.normalizeTitle(title)
    val isHub: Boolean = MetadataRerank.isHubGenre(genre)

    /**
     * Artist under the chain's spacing and cap rules. Untagged tracks share the empty key
     * deliberately: they behave as one artist, so a run of them gets spaced apart like any other
     * repeat rather than clustering because nothing identified them.
     */
    val artistKey: String = MetadataRerank.normalizeArtist(primaryArtist ?: artist)

    /** What era this track belongs to: the recording's year, not the edition's. */
    val eraYear: Int? get() = originalYear ?: year
}

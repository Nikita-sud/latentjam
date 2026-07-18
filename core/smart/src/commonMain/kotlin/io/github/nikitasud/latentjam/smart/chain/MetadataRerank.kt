/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.abs

/**
 * Metadata-derived multipliers applied on top of the learned scores.
 *
 * Everything here is a multiplier around 1.0, combined into the chain score in log space so a
 * neutral verdict is exactly zero and a strong veto (same album) reads as a large negative.
 */
internal object MetadataRerank {

    const val SAME_GENRE_BONUS = 1.20f
    const val CROSS_GENRE_MALUS = 0.90f
    const val CROSS_LANGUAGE_PENALTY = 0.75f
    const val SAME_ALBUM_PENALTY = 1.0f
    const val ERA_DECADE_PENALTY = 0.04f

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

    private val GENRE_ALIASES = listOf(
        "hip" to "rap", "rap" to "rap", "trap" to "rap", "phonk" to "rap",
        "rock" to "rock", "metal" to "rock", "punk" to "rock", "grunge" to "rock",
        "pop" to "pop",
        "dance" to "dance", "electronic" to "dance", "edm" to "dance",
        "house" to "dance", "techno" to "dance",
        "classical" to "classical", "orchestral" to "classical", "baroque" to "classical",
        "soundtrack" to "soundtrack", "score" to "soundtrack",
    )

    /** Coarse genre family, or null when the tag is missing or meaningless. */
    fun normalizeGenre(genre: String?): String? {
        val raw = genre?.lowercase()?.trim().orEmpty()
        if (raw.isEmpty() || raw == "<unknown>" || raw == "unknown" || raw == "other") return null
        for ((needle, family) in GENRE_ALIASES) if (needle in raw) return family
        return raw
    }

    private val HUB_GENRE_TOKENS = setOf(
        "ost", "soundtrack", "score", "anime", "cinematic",
        "orchestral", "game", "ambient", "library", "western",
    )

    private val TOKEN_SPLIT = Regex("[^a-zа-яё]+")

    /**
     * Whether the track belongs to the dense cinematic/game/anime cluster. That cluster is a hub in
     * embedding space: it leaks into chains from sparse seeds unless damped.
     */
    fun isHubGenre(genre: String?): Boolean =
        genre?.lowercase()?.split(TOKEN_SPLIT)?.any { it in HUB_GENRE_TOKENS } == true

    private val BRACKETED = Regex("\\s*[\\(\\[][^()\\[\\]]*[\\)\\]]\\s*")
    private val WHITESPACE = Regex("\\s+")

    /** Title with bracketed qualifiers stripped, for duplicate detection across releases. */
    fun normalizeTitle(title: String?): String =
        title.orEmpty().lowercase()
            .replace(BRACKETED, " ")
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
        val anchorGenre = normalizeGenre(anchor.genre)
        val candidateGenre = normalizeGenre(candidate.genre)
        if (anchorGenre != null && candidateGenre != null) {
            multiplier *= if (anchorGenre == candidateGenre) SAME_GENRE_BONUS else CROSS_GENRE_MALUS
        }
        if (candidate.language != anchor.language) multiplier *= CROSS_LANGUAGE_PENALTY
        val anchorYear = anchor.year
        val candidateYear = candidate.year
        if (anchorYear != null && candidateYear != null) {
            multiplier *= 1f - ERA_DECADE_PENALTY * abs(anchorYear - candidateYear) / 10f
        }
        return multiplier
    }
}

/** The metadata the chain reasons about, resolved once per track. */
internal data class TrackMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
) {
    val language: String = MetadataRerank.detectLanguage(title, artist)
    val normalizedTitle: String = MetadataRerank.normalizeTitle(title)
    val isHub: Boolean = MetadataRerank.isHubGenre(genre)

    /**
     * Artist under the chain's spacing and cap rules. Untagged tracks share the empty key
     * deliberately: they behave as one artist, so a run of them gets spaced apart like any other
     * repeat rather than clustering because nothing identified them.
     */
    val artistKey: String = artist.orEmpty()
}

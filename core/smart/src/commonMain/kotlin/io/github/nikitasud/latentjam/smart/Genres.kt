/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * The coarse genre vocabulary the app reasons about.
 *
 * Genre tags in a real library are a long tail of near-synonyms — `Hip-Hop`, `Trap`, `Phonk` and
 * `Russian Rap` are one family that four different taggers spelled four ways. Two features need
 * that family rather than the raw tag: the SMART chain, which rewards staying inside one, and
 * library clustering, which names a cluster after the one its members share. They share this table
 * so the two can never drift apart.
 */
public object Genres {

    /**
     * Substring → family. ORDER MATTERS: the first needle found wins, so `Pop Rock` resolves to
     * `rock` because `rock` is listed above `pop`. Reordering this list silently re-labels part of
     * every library.
     */
    private val ALIASES = listOf(
        "hip" to "rap", "rap" to "rap", "trap" to "rap", "phonk" to "rap",
        "rock" to "rock", "metal" to "rock", "punk" to "rock", "grunge" to "rock",
        "pop" to "pop",
        "dance" to "dance", "electronic" to "dance", "edm" to "dance",
        "house" to "dance", "techno" to "dance",
        "classical" to "classical", "orchestral" to "classical", "baroque" to "classical",
        "soundtrack" to "soundtrack", "score" to "soundtrack",
    )

    /**
     * Coarse genre family, or null when the tag is missing or says nothing.
     *
     * A tag outside the alias table is returned lowercased rather than discarded: an untranslated
     * niche genre is still a perfectly good grouping key, it simply has no family to collapse into.
     */
    public fun normalize(genre: String?): String? {
        val raw = genre?.lowercase()?.trim().orEmpty()
        if (raw.isEmpty() || raw == "<unknown>" || raw == "unknown" || raw == "other") return null
        for ((needle, family) in ALIASES) if (needle in raw) return family
        return raw
    }

    private val HUB_TOKENS = setOf(
        "ost", "soundtrack", "score", "anime", "cinematic",
        "orchestral", "game", "ambient", "library", "western",
    )

    private val TOKEN_SPLIT = Regex("[^a-zа-яё]+")

    /**
     * Whether the track belongs to the dense cinematic/game/anime cluster. That cluster is a hub in
     * embedding space: it leaks into chains from sparse seeds unless damped.
     */
    public fun isHub(genre: String?): Boolean =
        genre?.lowercase()?.split(TOKEN_SPLIT)?.any { it in HUB_TOKENS } == true
}

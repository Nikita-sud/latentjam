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
     * Token/phrase → family. ORDER MATTERS: the first alias found wins, so `Pop Rock` resolves
     * to `rock` because `rock` is listed above `pop`. Reordering this list silently re-labels part
     * of every library.
     *
     * Matching whole words is load-bearing. A substring check labels `Chiptune` as rap because it
     * contains `hip`, and similarly lets ordinary words such as `popular` masquerade as genres.
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
        val tokens = tokenize(raw)
        for ((phrase, family) in ALIASES) {
            val phraseTokens = phrase.split(' ')
            if (tokens.containsPhrase(phraseTokens)) return family
        }
        return raw
    }

    /**
     * Every coarse family of a possibly multi-genre tag ("Electronic; Rock; Trip Hop" carries
     * three). For a single-genre tag this is exactly `setOf(normalize(tag))`, so set
     * intersection degrades to the old equality — the chain's behaviour on single-genre
     * libraries (and thus the recorded parity fixtures) is unchanged by construction.
     */
    public fun families(genre: String?): Set<String> =
        rawList(genre).mapNotNullTo(LinkedHashSet()) { normalize(it) }

    /** The individual raw values of a joined genre tag, original casing preserved. */
    public fun rawList(genre: String?): List<String> =
        genre?.split(';', '/', ',', '|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** Lowercased raw values, for exact-subtype membership checks. */
    public fun rawSet(genre: String?): Set<String> =
        rawList(genre).mapTo(LinkedHashSet()) { it.lowercase() }

    /** Common-code tokenizer; unlike `String.split`, this keeps Unicode letter boundaries. */
    private fun tokenize(value: String): List<String> {
        val tokens = ArrayList<String>()
        var start = -1
        for (index in value.indices) {
            if (value[index].isLetterOrDigit()) {
                if (start < 0) start = index
            } else if (start >= 0) {
                tokens.add(value.substring(start, index))
                start = -1
            }
        }
        if (start >= 0) tokens.add(value.substring(start))
        return tokens
    }

    private fun List<String>.containsPhrase(phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > size) return false
        for (start in 0..size - phrase.size) {
            if (phrase.indices.all { offset -> this[start + offset] == phrase[offset] }) return true
        }
        return false
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

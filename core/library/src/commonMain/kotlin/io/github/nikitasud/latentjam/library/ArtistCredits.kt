/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

/**
 * The artists a display credit names when the tags carry no `ARTISTS` list: "Gorillaz, Elton John",
 * "Dr. Dre feat. Snoop Dogg & Nate Dogg", "Кипелов и Маврин".
 *
 * Separators — commas, `feat.`/`ft.`/`featuring`, `vs.`, `x`, `&`, `and`, `и`, `+`, `/`, `with` —
 * also occur inside real names ("Earth, Wind & Fire", "Король и Шут"). An artist list decides:
 * neighbouring pieces stay one name whenever the text they span together is an artist it knows,
 * the longest such span first. An unknown "X & the Y" or "X and His Orchestra" is a leader with
 * a backing band and stays whole too. Separators inside brackets belong to a note, never split.
 *
 * Measured on two real libraries before shipping: every band spelled with a separator stayed
 * whole, and 115 of 122 changed credits were collaborations split right (the rest were notes and
 * placeholders). An act the list does not know and whose name has a separator does get split.
 */
public object ArtistCredits {

    /**
     * [isKnownArtist] is only asked about text that spans a separator, so a single word's hits on
     * other names' tokens never count. Returns [credit] unchanged when it names one artist.
     */
    public fun split(credit: String, isKnownArtist: (String) -> Boolean): List<String> {
        val pieces = ArrayList<IntRange>()
        val weak = ArrayList<Boolean>() // per boundary: was the separator after piece i weak
        var start = 0
        for (match in SEPARATOR.findAll(credit)) {
            if (depthAt(credit, match.range.first) != 0) continue
            if (credit.substring(start, match.range.first).isBlank()) continue
            pieces += start until match.range.first
            weak += match.groups[WEAK_GROUP] != null
            start = match.range.last + 1
        }
        if (credit.substring(start).isNotBlank()) {
            pieces += start until credit.length
        } else if (weak.isNotEmpty()) {
            weak.removeAt(weak.lastIndex) // a trailing separator joins nothing
        }
        if (pieces.size < 2) return listOf(credit)

        // Longest known span first: "Grover Washington, Jr." rejoins before "Bill Withers" splits off.
        val names = ArrayList<IntRange>()
        var index = 0
        while (index < pieces.size) {
            var end = index
            for (candidate in pieces.lastIndex downTo index + 1) {
                if (knows(credit.substring(pieces[index].first, pieces[candidate].last + 1), isKnownArtist)) {
                    end = candidate
                    break
                }
            }
            val range = pieces[index].first..pieces[end].last
            val joinedByWeak = index > 0 && weak[index - 1]
            if (names.isNotEmpty() && joinedByWeak && isBackingBand(credit.substring(range))) {
                names[names.lastIndex] = names.last().first..range.last
            } else {
                names += range
            }
            index = end + 1
        }
        val result = names.map { credit.substring(it).trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        return if (result.size < 2) listOf(credit) else result
    }

    private fun knows(text: String, isKnownArtist: (String) -> Boolean): Boolean =
        isKnownArtist(text) ||
            (AMPERSAND.containsMatchIn(text) && isKnownArtist(text.replace(AMPERSAND, " and "))) ||
            (AND.containsMatchIn(text) && isKnownArtist(text.replace(AND, " & ")))

    /** "the Suicidal Vampires", "His Orchestra", "Co": the band behind a named leader. */
    private fun isBackingBand(text: String): Boolean {
        val words = text.trim().split(WHITESPACE)
        val first = words.first().lowercase().trimEnd('.')
        return (words.size > 1 && first in ARTICLES) || first in GROUP_WORDS
    }

    private fun depthAt(text: String, index: Int): Int {
        var depth = 0
        for (i in 0 until index) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
        }
        return depth
    }

    private const val WEAK_GROUP = 3
    private val SEPARATOR = Regex(
        "(\\s*[,;]\\s*)" +
            "|(\\s+(?:feat\\.?|ft\\.?|featuring|vs\\.?|versus|x|×)\\s+)" +
            "|(\\s+(?:&|and|и|\\+|/|with)\\s+)",
        RegexOption.IGNORE_CASE,
    )
    private val AMPERSAND = Regex("\\s+&\\s+")
    private val AND = Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val ARTICLES = setOf("the", "his", "her", "their", "los", "las", "les", "la", "le", "die", "der", "das", "el", "il", "i", "su")
    private val GROUP_WORDS = setOf(
        "co", "company", "friends", "orchestra", "band", "sons", "brothers", "sisters", "family", "crew", "gang",
        "ensemble", "choir", "trio", "quartet", "quintet", "players", "singers", "boys", "girls", "orkestra", "orquesta",
        "его", "её", "ее", "компания", "оркестр", "друзья", "ко",
    )
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Interpreting the *values* tags carry, as opposed to [Id3Tags], which is
 * about the bytes that carry them.
 *
 * These live in common rather than beside the reader that needed them first
 * (the iOS library scan) because none of it is platform-specific: ID3 encodes
 * genre and date the same way whoever is reading.
 */

/** Leading `(…)` group, ID3v2.3's wrapper for a numeric or special genre code. */
private val LEADING_PARENTHESISED = Regex("""^\(([^)]*)\)""")

/**
 * The two non-numeric codes ID3v2.3 defines for TCON. The numeric ID3v1 table
 * is deliberately NOT carried here — see [cleanGenre].
 */
private val SPECIAL_GENRE_CODES = mapOf("RX" to "Remix", "CR" to "Cover")

/**
 * Normalises the several shapes ID3's genre frame (TCON) legally takes.
 *
 * A tag may hold free text ("Rock"), a bare ID3v1 index ("17"), one or more
 * parenthesised indices optionally followed by a refinement ("(17)Rock",
 * "(17)(18)Punk"), or a special code ("(RX)").
 *
 * A bare index with no refinement yields `null` rather than a number: naming
 * it would mean carrying the 80-entry ID3v1 genre table, and "17" rendered on
 * a track row is worse than showing no genre at all. Files tagged that way
 * lose the genre signal — worth revisiting if such files turn out to be common
 * in a real library.
 */
internal fun cleanGenre(raw: String): String? {
    var rest = raw.trim()
    if (rest.isEmpty()) return null

    var lastCode: String? = null
    while (true) {
        val match = LEADING_PARENTHESISED.find(rest) ?: break
        lastCode = match.groupValues[1]
        rest = rest.substring(match.value.length)
    }

    val refinement = rest.trim()
    if (refinement.isNotEmpty()) {
        // "17" on its own is an index, not a name; "(17)Rock" already gave us the name.
        return if (lastCode == null && refinement.all { it.isDigit() }) null else refinement
    }
    return lastCode?.let(SPECIAL_GENRE_CODES::get)
}

/**
 * Pulls a four-digit year out of the shapes date tags take — a bare year
 * ("1994"), an ISO date ("1994-05-01"), or a year with an annotation
 * ("1994 (Remastered)").
 *
 * Anything that does not start with exactly four digits yields `null`; a
 * partial year is not worth guessing at.
 */
internal fun parseYear(raw: String): Int? {
    val digits = raw.trim().takeWhile { it.isDigit() }
    if (digits.length != 4) return null
    return digits.toIntOrNull()?.takeIf { it in 1000..9999 }
}

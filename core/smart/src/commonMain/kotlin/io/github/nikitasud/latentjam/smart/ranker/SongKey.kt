/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

/** Night r4 (reference.py song_key): lower case, bracketed parts and a " - …" suffix dropped, letters / digits / spaces kept. */
internal object SongKey {
    fun of(title: String?, minChars: Int): String? {
        val t = title.orEmpty().lowercase()
        val sb = StringBuilder()
        var depth = 0
        for (c in t) {
            when {
                c == '(' || c == '[' -> depth++
                (c == ')' || c == ']') && depth > 0 -> depth--
                depth == 0 -> sb.append(c)
            }
        }
        var s = sb.toString()
        for (sep in listOf(" - ", " – ", " — ")) {
            val at = s.indexOf(sep)
            if (at > 0) s = s.substring(0, at)
        }
        val out = StringBuilder()
        for (c in s) out.append(if (c.isLetterOrDigit()) c else ' ')
        val key = out.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        return key.takeIf { it.length >= minChars }
    }

    /** vc22: the shorter key (at least [minChars]) begins the longer one followed by a space. */
    fun prefixMatch(a: String, b: String, minChars: Int): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= minChars && long.length > short.length && long.startsWith("$short ")
    }
}

/** vc22 (reference.py album_core): the album's words minus generic soundtrack / volume words; "" when nothing is left. */
internal object AlbumCore {
    private val GENERIC = setOf(
        "original", "soundtrack", "ost", "tv", "animation", "anime", "volume", "vol", "disc", "cd", "the", "music", "score",
        "motion", "picture", "series", "edition", "deluxe", "remastered", "collection", "best", "of", "and", "a", "version",
    )

    fun of(album: String?): String {
        val out = StringBuilder()
        for (c in album.orEmpty().lowercase()) out.append(if (c.isLetterOrDigit()) c else ' ')
        return out.toString().split(' ').filter { it.isNotEmpty() && it !in GENERIC && !it.all(Char::isDigit) }.joinToString(" ")
    }
}

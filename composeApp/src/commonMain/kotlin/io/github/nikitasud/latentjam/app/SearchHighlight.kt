/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.text.SearchFold

/**
 * Where a search query matched inside the ORIGINAL display string, as index ranges.
 *
 * The comparison runs in the same folded space the search itself uses (lowercase, diacritics
 * stripped, Cyrillic transliterated), but the returned ranges address the unfolded text the row
 * actually renders — a per-character fold keeps the mapping exact even where transliteration
 * changes lengths (`ю` folds to `yu`). Fuzzy/typo matches produce no ranges on purpose: bolding
 * text that is NOT the query would claim a literal match the result does not have.
 */
internal fun searchHighlightRanges(text: String, query: String): List<IntRange> {
    val foldedQuery = SearchFold.fold(query).trim()
    if (foldedQuery.isEmpty() || text.isEmpty()) return emptyList()

    // Per-character fold with a boundary map: foldedStart[i] is where text[i]'s folded form
    // begins inside the concatenated folded string. Punctuation folds to nothing (length 0).
    // The "ph" digraph spans TWO original characters folding to one "f": both map to the same
    // folded start so a match covers the whole pair in the rendered text.
    val foldedStart = IntArray(text.length + 1)
    val folded = buildString {
        var index = 0
        while (index < text.length) {
            foldedStart[index] = length
            val ch = text[index]
            if ((ch == 'p' || ch == 'P') && index + 1 < text.length &&
                (text[index + 1] == 'h' || text[index + 1] == 'H')
            ) {
                append('f')
                // The pair folds to one "f": the P owns the folded piece, the h gets an empty
                // piece and is re-attached after range computation (see below).
                foldedStart[index + 1] = length
                index += 2
                continue
            }
            append(SearchFold.fold(ch.toString()))
            index++
        }
        foldedStart[text.length] = length
    }

    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = folded.indexOf(foldedQuery, startIndex = from)
        if (at < 0) break
        val end = at + foldedQuery.length
        // A character belongs to the highlight iff its folded piece [start, next) overlaps the
        // matched folded interval. This covers digraphs (both "P" and "h" share one folded "f")
        // and naturally excludes zero-width leading punctuation (an empty piece overlaps
        // nothing before the match).
        var first = -1
        var last = -1
        for (index in text.indices) {
            val pieceStart = foldedStart[index]
            val pieceEnd = foldedStart[index + 1]
            if (pieceStart < end && pieceEnd > at) {
                if (first < 0) first = index
                last = index
            }
        }
        if (first >= 0) {
            // A digraph's second character carries an empty piece; when the pair's head made
            // the match, the h belongs in the visible highlight too.
            if (last + 1 < text.length &&
                (text[last] == 'p' || text[last] == 'P') &&
                (text[last + 1] == 'h' || text[last + 1] == 'H')
            ) {
                last++
            }
            ranges.add(first..last)
        }
        from = end
    }
    return ranges
}

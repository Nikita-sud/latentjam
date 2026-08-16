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
    val foldedStart = IntArray(text.length + 1)
    val folded = buildString {
        for (index in text.indices) {
            foldedStart[index] = length
            append(SearchFold.fold(text[index].toString()))
        }
        foldedStart[text.length] = length
    }

    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = folded.indexOf(foldedQuery, startIndex = from)
        if (at < 0) break
        val end = at + foldedQuery.length
        var first = 0
        var last = text.length - 1
        for (index in text.indices) {
            if (foldedStart[index] <= at) first = index
            if (foldedStart[index] < end) last = index else break
        }
        // A character folding to nothing (punctuation) at the boundary must not be swallowed
        // into the highlight: keep only characters whose folded form overlaps the match.
        while (first < last && foldedStart[first + 1] <= at) first++
        ranges.add(first..last)
        from = end
    }
    return ranges
}

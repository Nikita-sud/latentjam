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
 * stripped, Cyrillic transliterated, "ph" collapsed to "f" over the whole field), but the returned
 * ranges address the unfolded text the row actually renders — a per-character fold keeps the
 * mapping exact even where transliteration changes lengths (`ю` folds to `yu`). Fuzzy/typo matches
 * produce no ranges on purpose: bolding text that is NOT the query would claim a literal match the
 * result does not have.
 */
internal fun searchHighlightRanges(text: String, query: String): List<IntRange> {
    val foldedQuery = SearchFold.fold(query).trim()
    if (foldedQuery.isEmpty() || text.isEmpty()) return emptyList()

    // Fold each character on its own so every folded position still knows which character it came
    // from: punctuation and combining marks fold to nothing, while `ю` keeps both of its "yu"
    // positions on one original character. [foldedStart] is where a character's folded form begins
    // inside the concatenated folded string.
    val foldedStart = IntArray(text.length + 1)
    val folded = StringBuilder(text.length)
    for (index in text.indices) {
        foldedStart[index] = folded.length
        folded.append(SearchFold.fold(text[index].toString()))
    }
    foldedStart[text.length] = folded.length

    // The character behind every folded position: pieces are contiguous and [foldedStart] only
    // grows, so one advancing cursor resolves them all.
    val foldedOwner = IntArray(folded.length)
    var cursor = 0
    for (position in folded.indices) {
        while (foldedStart[cursor + 1] <= position) cursor++
        foldedOwner[position] = cursor
    }

    // [SearchFold] collapses "ph" to "f" over the WHOLE field, so the pair also spans characters
    // the per-character fold left no trace of: "p-honk" and "ṕhonk" both fold to "fonk" there,
    // while their pieces above still read "phonk" and the exact match would stay unhighlighted.
    // Rebuild the folded string with the same collapse, remembering which character owns each
    // position and, behind a collapsed "f", the "h" that is highlighted together with its "p".
    val collapsed = StringBuilder(folded.length)
    val owner = IntArray(folded.length)
    val pairTail = IntArray(folded.length) { -1 }
    var position = 0
    while (position < folded.length) {
        owner[collapsed.length] = foldedOwner[position]
        if (folded[position] == 'p' && position + 1 < folded.length && folded[position + 1] == 'h') {
            collapsed.append('f')
            pairTail[collapsed.length - 1] = foldedOwner[position + 1]
            position += 2
        } else {
            collapsed.append(folded[position])
            position++
        }
    }

    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = collapsed.indexOf(foldedQuery, startIndex = from)
        if (at < 0) break
        val end = at + foldedQuery.length
        // A character is covered when the match includes a folded position it owns. The "h" of a
        // collapsed "ph" owns none of its own: it joins the range whenever the "p" carrying the
        // shared "f" does, so a query ending on that "f" still bolds the whole pair.
        var first = -1
        var last = -1
        for (index in at until end) {
            if (first < 0) first = owner[index]
            if (owner[index] > last) last = owner[index]
            if (pairTail[index] > last) last = pairTail[index]
        }
        if (first >= 0) ranges.add(first..last)
        from = end
    }
    return ranges
}

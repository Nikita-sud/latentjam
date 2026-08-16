/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * Script-folding for lexical search. Applied identically to candidate names (index side) and to the
 * query (query side) so a Latin-keyboard query can reach non-Latin metadata that a substring matcher
 * would otherwise miss.
 *
 * Two folds, in order:
 * 1. NFD diacritic strip — Latin accents decompose to base + combining mark, the marks (Unicode
 *    `Mn`) are dropped (`Guță` → `guta`). ASCII punctuation is dropped too, spaces preserved.
 * 2. Cyrillic → Latin transliteration — canonical decomposition has no mapping for Cyrillic base
 *    letters, so a Latin query (`kino`, `gurtskaya`) never reached a Cyrillic name (`Кино`,
 *    `Гурцкая`). A fixed romanization table bridges it — the single biggest recall gain measured for
 *    this library, and one embeddings cannot cross at all.
 *
 * Non-Latin, non-Cyrillic scripts pass through untouched: Japanese kana/kanji have no romanization
 * here, so a romaji query still won't reach `初音ミク` — a separate kana romanizer is out of scope.
 *
 * The NFD seam ([nfdNormalize]) decomposes `й` to `и` + combining breve exactly as the reference's
 * NFKD did, so the breve is stripped and `Цой` folds to `tsoi` (not `tsoy`).
 */
public object SearchFold {

    /**
     * Fold [text] to a lowercase, diacritic-free, Cyrillic-transliterated form for substring
     * comparison. Idempotent for already-ASCII lowercase input.
     */
    public fun fold(text: String): String {
        val lowered = text.lowercase()
        val decomposed = nfdNormalize(lowered)
        val stripped = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (isNonSpacingMark(ch.code)) continue
            if (isAsciiPunctuation(ch)) continue
            stripped.append(ch)
        }
        if (stripped.none { it in CYRILLIC_RANGE }) return collapsePh(stripped.toString())
        val out = StringBuilder(stripped.length)
        for (ch in stripped) {
            val mapped = CYRILLIC_TRANSLIT[ch]
            if (mapped != null) out.append(mapped) else out.append(ch)
        }
        return collapsePh(out.toString())
    }

    /**
     * "ph" and "f" are one sound, and the fold's Cyrillic side already commits to it: ф→f. A
     * Latin "Phonk" must land on the same form the query «фонк» lands on, or the genre the
     * listener literally named only ever fuzzy-matches — tied with Funk AND Folk, both one edit
     * away (the reported polka-in-the-phonk-results failure). Applied symmetrically to index
     * and query, so it can never create a one-sided mismatch.
     */
    private fun collapsePh(value: String): String =
        if (value.contains("ph")) value.replace("ph", "f") else value

    /** ASCII punctuation, matching the reference's `\p{Punct}` (POSIX/ASCII) strip. */
    private fun isAsciiPunctuation(ch: Char): Boolean =
        (ch.code < 128 && !ch.isLetterOrDigit() && !ch.isWhitespace()) ||
            // The typographic apostrophe family strips exactly like the ASCII one. Left as
            // ordinary characters they became SPACES downstream, and "Can\u2019t" tokenized to
            // ["can", "t"] — the orphan "t" then fuzzy-matched every query starting with t.
            ch == '\u2018' || ch == '\u2019' || ch == '\u02BC'

    private val CYRILLIC_RANGE = 'Ѐ'..'ӿ'

    /**
     * Lowercase Russian/Ukrainian romanization (BGN/PCGN-ish). Digraphs (`ц`→`ts`, `я`→`ya`) are
     * why the table maps to String, not Char. Hard/soft signs fold to nothing. Applied after
     * `lowercase()`, so only lowercase keys are needed.
     */
    private val CYRILLIC_TRANSLIT: Map<Char, String> =
        mapOf(
            'а' to "a",
            'б' to "b",
            'в' to "v",
            'г' to "g",
            'ґ' to "g",
            'д' to "d",
            'е' to "e",
            'ё' to "e",
            'є' to "ye",
            'ж' to "zh",
            'з' to "z",
            'и' to "i",
            'і' to "i",
            'ї' to "yi",
            'й' to "y",
            'к' to "k",
            'л' to "l",
            'м' to "m",
            'н' to "n",
            'о' to "o",
            'п' to "p",
            'р' to "r",
            'с' to "s",
            'т' to "t",
            'у' to "u",
            'ф' to "f",
            'х' to "kh",
            'ц' to "ts",
            'ч' to "ch",
            'ш' to "sh",
            'щ' to "shch",
            'ъ' to "",
            'ы' to "y",
            'ь' to "",
            'э' to "e",
            'ю' to "yu",
            'я' to "ya",
        )
}

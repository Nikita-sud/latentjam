/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards the lexical fold: a folded ASCII query must reach the folded candidate name. */
internal class SearchFoldTest {
    private fun matches(query: String, candidate: String): Boolean {
        val fq = SearchFold.fold(query)
        return fq.isNotEmpty() && SearchFold.fold(candidate).contains(fq)
    }

    @Test
    fun latinDiacritics_fold() {
        // Romanian: NFD strips the accents so an ASCII query matches.
        assertEquals("guta", SearchFold.fold("Guță"))
        assertTrue(matches("guta", "Guță"))
        assertTrue(matches("zdob si zdub", "Zdob și Zdub"))
    }

    @Test
    fun cyrillic_transliterates() {
        assertEquals("kino", SearchFold.fold("Кино"))
        assertEquals("gurtskaya", SearchFold.fold("Гурцкая"))
        assertTrue(matches("kino", "Кино"))
        assertTrue(matches("gurtskaya", "Диана Гурцкая"))
        // Digraph ц→ts; NFD decomposes й to и + combining breve, so the breve is stripped and й
        // folds to i (matching the experiment's `tsoi` finding), not y.
        assertEquals("tsoi", SearchFold.fold("Цой"))
    }

    @Test
    fun plainEnglish_noRegression() {
        assertEquals("hello world", SearchFold.fold("Hello, World!"))
        assertTrue(matches("eminem", "Eminem"))
        assertTrue(matches("michael", "Michael Jackson"))
        // Substring, not fuzzy: a typo still must not match.
        assertFalse(matches("eminm", "Eminem"))
    }

    @Test
    fun japanese_passesThroughUnromanized() {
        // Kana/kanji have no romanization here, so the name is unchanged and a romaji query misses.
        assertEquals("初音ミク", SearchFold.fold("初音ミク"))
        assertFalse(matches("hatsune", "初音ミク"))
    }

    @Test
    fun idempotentOnFoldedInput() {
        val once = SearchFold.fold("Гурцкая")
        assertEquals(once, SearchFold.fold(once))
    }
}

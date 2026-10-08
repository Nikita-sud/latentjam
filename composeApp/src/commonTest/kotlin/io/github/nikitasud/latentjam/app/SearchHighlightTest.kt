/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHighlightTest {

    private fun highlighted(text: String, query: String): List<String> =
        searchHighlightRanges(text, query).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `a ph pair is highlighted through its shared f`() {
        assertEquals(listOf("Phonk"), highlighted("The Phonk Song", "fonk"))
        assertEquals(listOf("phonk", "fonk"), highlighted("phonk fonk", "fonk"))
        assertEquals(listOf("PhOnK"), highlighted("PhOnK", "phônk"))
    }

    @Test
    fun `ph split by folded-away characters is still one match`() {
        // The search index folds the whole field, so its "ph" -> "f" collapse also crosses the
        // hyphen and the accent the per-character fold erased: "p-honk" and "ṕhonk" both fold to
        // "fonk" there, and the row has to bold the same match the result was found by.
        assertEquals(listOf("p-honk"), highlighted("p-honk", "fonk"))
        assertEquals(listOf("ṕhonk"), highlighted("ṕhonk", "fonk"))
        assertEquals(listOf("p\u0301honk"), highlighted("p\u0301honk", "fonk"))
        // A query that ends on the shared "f" bolds the pair, not just its first character.
        assertEquals(listOf("p-h"), highlighted("p-honk", "f"))
        assertEquals(listOf("Ph"), highlighted("Phonk", "f"))
    }

    @Test
    fun `a match is mapped back across transliteration lengths`() {
        assertEquals(listOf("Фонк"), highlighted("Фонк", "fonk"))
        assertEquals(listOf("Фонк"), highlighted("Фонк", "phonk"))
        // "ю" folds to two characters; both stay on the single rendered "Ю".
        assertEquals(listOf("Юля"), highlighted("Юля", "yulya"))
        assertEquals(listOf("Юля"), highlighted("Юля", "ulya"))
    }

    @Test
    fun `text that does not match stays plain`() {
        assertEquals(emptyList(), highlighted("Phonk", "folk"))
        assertEquals(emptyList(), highlighted("Phonk", ""))
        assertEquals(emptyList(), highlighted("Phonk", "   "))
        assertEquals(emptyList(), highlighted("", "fonk"))
    }
}

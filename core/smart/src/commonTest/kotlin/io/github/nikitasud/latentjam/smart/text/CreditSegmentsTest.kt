/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** X v5's eleven pre-registered unit cases (smart-research review_ext/FIXES_NIGHT4.md, X3). */
class CreditSegmentsTest {
    private val whole = setOf("simon & garfunkel", "earth, wind & fire")
    private fun matches(credit: String, title: String, name: String) =
        CreditSegments.matches(name, credit, title) { it.lowercase() in whole }

    @Test
    fun `a credit the pack knows as one artist is not split`() {
        assertFalse(matches("Simon & Garfunkel", "Song", "Simon"))
        assertFalse(matches("Earth, Wind & Fire", "Song", "Earth"))
    }

    @Test
    fun `a name is matched whole and never as a word inside it`() {
        assertFalse(matches("Mr. X", "Song", "Mr"))
        assertFalse(matches("David Mumford", "Song", "David"))
    }

    @Test
    fun `the first title segment counts only with an empty credit`() {
        assertFalse(matches("Some Band", "Love - Song", "Love"))
        assertTrue(matches("", "SK - Beats - Ready for action", "SK"))
    }

    @Test
    fun `whole segments of a duo or a feat credit match`() {
        assertTrue(matches("Justin and Tanya", "Song", "Justin"))
        assertTrue(matches("Denitsa Mineva & Kosta T", "Song", "Kosta T"))
        assertTrue(matches("Monplaisir feat Southman", "Song", "Southman"))
    }

    @Test
    fun `a name of several artists needs all of them`() {
        assertTrue(matches("", "HH - Kosta T & Ghofra Z", "Kosta T feat. Ghofra Z"))
        assertFalse(matches("Kosta T", "Song", "Kosta T feat. Ghofra Z"))
    }

    @Test
    fun `empty never matches`() {
        assertFalse(matches("", "Preludes, Book 2 - ", ""))
        assertFalse(matches("", "", "Someone"))
    }

    @Test
    fun `diacritics fold`() {
        assertTrue(matches("Beyoncé & Jay-Z", "Song", "Beyonce"))
        assertTrue(matches("Artist", "Song feat. Björk", "Bjork"))
    }

    @Test
    fun `a band named like a version word does not hide versions`() {
        assertFalse(matches("Pink Floyd", "Comfortably Numb - Live", "Live"))
        assertFalse(matches("The Beatles", "Help! - Mono", "Mono"))
        assertTrue(matches("Live", "Lightning Crashes", "Live"))
        assertTrue(matches("Live & Friends", "Song", "Live"))
    }

    @Test
    fun `words of a song title are not a credit`() {
        assertFalse(matches("Motörhead", "Killed by Death", "Death"))
        assertFalse(matches("Johann Sebastian Bach", "Orchestral Suite No. 3 in D Major, BWV 1068 - Air", "Air"))
        assertTrue(matches("Various", "Song (by Kosta T)", "Kosta T"))
        assertTrue(matches("Simon & Garfunkel", "Song - simon & garfunkel", "Simon & Garfunkel"), "a part the pack knows")
    }
}

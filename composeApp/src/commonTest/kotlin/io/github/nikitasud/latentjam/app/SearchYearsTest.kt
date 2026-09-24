/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchYearsTest {

    private fun years(first: Int, last: Int = first, subject: String = "") = SearchYears(first, last, subject)

    @Test
    fun `a year reads the same in any of the ways people type it`() {
        assertEquals(years(1985), SearchYears.parse("1985"))
        assertEquals(years(1985), SearchYears.parse("songs from 1985"))
        assertEquals(years(1985), SearchYears.parse("песни 1985 года"))
        assertEquals(years(1985), SearchYears.parse("1985г."))
        assertEquals(years(1985), SearchYears.parse("'85"))
        assertEquals(years(2005), SearchYears.parse("’05"))
        assertEquals(years(1989, subject = "Taylor Swift"), SearchYears.parse("Taylor Swift 1989"))
    }

    @Test
    fun `a decade reads in English Russian and the other app languages`() {
        val eighties = years(1980, 1989)
        listOf(
            "80s", "80's", "1980s", "80’s hits", "music of the 80s", "песни 80-х", "80-е годы", "80х", "80-ые",
            "80er Jahre", "anni '80", "música de los años 80", "années 80", "lata 80.", "80'ler", "80-talet", "80年代",
            "80년대",
        ).forEach { assertEquals(eighties, SearchYears.parse(it), it) }
        assertEquals(years(2000, 2009), SearchYears.parse("00s"))
        assertEquals(years(2020, 2029), SearchYears.parse("20s"))
        assertEquals(years(1950, 1959), SearchYears.parse("50s"))
    }

    @Test
    fun `the rest of the query is what to look for in those years`() {
        assertEquals(years(1990, 1999, "rock"), SearchYears.parse("1990s rock"))
        assertEquals(years(1980, 1989, "кино"), SearchYears.parse("кино 80-х"))
        assertEquals(years(1980, 1989, "Modern Talking"), SearchYears.parse("Modern Talking 80s"))
        // Filler words go: they would only keep a title or artist from matching token by token.
        assertEquals(years(1980, 1989, "Weeknd"), SearchYears.parse("The Weeknd 80s"))
    }

    @Test
    fun `numbers that are names are not years`() {
        listOf("50 Cent", "Adele 21", "blink-182", "10cc", "20th Century Fox", "99 Luftballons", "1985s", "3000", "80")
            .forEach { assertNull(SearchYears.parse(it), it) }
    }
}

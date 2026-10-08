/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchYearsTest {

    private fun years(first: Int, last: Int = first, subject: String = "") = SearchYears(first, last, subject)

    private fun track(id: String, title: String, year: Int) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        year = year,
    )

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

    @Test
    fun `lyrics answer the cleaned phrase rather than the year`() {
        assertEquals("silver", lyricSearchPhrase("silver 1985"))
        assertEquals("silver", lyricSearchPhrase("silver 80s"))
        assertEquals("кино", lyricSearchPhrase("кино 80-х"))
        assertEquals("Modern Talking", lyricSearchPhrase("Modern Talking 80s"))
        // A time-only query has no words to look for: the year branch enumerates the library.
        assertEquals("1985", lyricSearchPhrase("1985"))
        assertEquals("песни 80-х", lyricSearchPhrase("песни 80-х"))
        // Without a year the query itself is the phrase.
        assertEquals("silver moon", lyricSearchPhrase("silver moon"))
    }

    /**
     * The lyric tier is asked for the phrase of the level that is running: the year branch recurses
     * on the cleaned subject, so its hits are found by "silver" and then kept to those years by the
     * same filter every other candidate passes. Searching the texts for the whole "silver 1985"
     * phrase finds nothing at all.
     */
    @Test
    fun `a year query finds lyric hits by the subject and keeps them in that year`() {
        val eighties = track("eighties", "Eighties", 1985)
        val modern = track("modern", "Modern", 2015)
        val documents = mapOf(
            eighties.id to LyricSearchDocument.build("a distant silver moon")!!,
            modern.id to LyricSearchDocument.build("a distant silver moon")!!,
        )
        val lyrics = LyricHits { phrase -> searchLyrics(documents, phrase).keys }

        for (query in listOf("silver 1985", "silver 80s", "silver 80-х")) {
            assertEquals(
                listOf(eighties.id),
                hybridSearch(listOf(modern, eighties), query, emptyList(), lyrics = lyrics).map { it.id },
                query,
            )
        }
    }

    @Test
    fun `a lyric hit from another year does not answer a year query`() {
        val modern = track("modern", "Modern", 2015)
        val documents = mapOf(modern.id to LyricSearchDocument.build("a distant silver moon")!!)
        val lyrics = LyricHits { phrase -> searchLyrics(documents, phrase).keys }

        // Nothing is from 1985, so the year branch returns nothing and the search falls back to
        // reading the number as a name ("Taylor Swift 1989"): the phrase is then the whole
        // "silver 1985", which no text holds — a 2015 song must not answer just for saying "silver".
        assertEquals(
            emptyList(),
            hybridSearch(listOf(modern), "silver 1985", emptyList(), lyrics = lyrics).map { it.id },
        )
    }
}

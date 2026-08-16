/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagFactsTest {

    @Test
    fun vorbisArtistsFieldsBecomeTheCreditList() {
        val facts = TagFacts.fromComments(
            listOf(
                "ARTIST" to "Gorillaz feat. Bootie Brown",
                "ARTISTS" to "Gorillaz",
                "ARTISTS" to "Bootie Brown",
                "GENRE" to "Electronic",
                "ORIGINALDATE" to "2005-05-24",
            ),
        )
        assertEquals(listOf("Gorillaz", "Bootie Brown"), facts.artists)
        assertEquals(listOf("Electronic"), facts.genres)
        assertEquals(2005, facts.originalYear)
    }

    @Test
    fun aLoneDisplayArtistIsNeverGuessedApart() {
        // "feat."-cutting display strings is how taggers ruin band names; a single ARTIST
        // field carries no split information and produces no credit list.
        val facts = TagFacts.fromComments(listOf("ARTIST" to "Crosby, Stills & Nash"))
        assertEquals(emptyList(), facts.artists)
    }

    @Test
    fun multipleArtistFieldsAreALegitimateMultiCredit() {
        val facts = TagFacts.fromComments(
            listOf("ARTIST" to "William Davies", "ARTIST" to "Edward Nutbrown"),
        )
        assertEquals(listOf("William Davies", "Edward Nutbrown"), facts.artists)
    }

    @Test
    fun joinedTxxxArtistsSplitOnListSeparatorsOnly() {
        assertEquals(
            listOf("Gorillaz", "Bootie Brown"),
            TagFacts.splitArtists("Gorillaz;Bootie Brown"),
        )
        assertEquals(
            listOf("Gorillaz", "Bootie Brown"),
            TagFacts.splitArtists("Gorillaz\u0000Bootie Brown"),
        )
        // A space never separates — one person, two words.
        assertEquals(listOf("Bootie Brown"), TagFacts.splitArtists("Bootie Brown"))
    }

    @Test
    fun originalYearComesFromTheFirstSaneSource() {
        assertEquals(1987, TagFacts.fromComments(listOf("TDOR" to "1987")).originalYear)
        assertEquals(1987, TagFacts.fromComments(listOf("ORIGINALYEAR" to "1987-06")).originalYear)
        assertNull(TagFacts.fromComments(listOf("TDOR" to "0000")).originalYear)
        assertNull(TagFacts.fromComments(listOf("TDOR" to "next year")).originalYear)
        // Classical tagging puts the composition year here; that intent survives.
        assertEquals(1707, TagFacts.fromComments(listOf("ORIGINALDATE" to "1707")).originalYear)
    }

    @Test
    fun duplicateCreditsCollapseCaseInsensitively() {
        val facts = TagFacts.fromComments(
            listOf("ARTISTS" to "Gorillaz", "ARTISTS" to "gorillaz", "ARTISTS" to "Bootie Brown"),
        )
        assertEquals(listOf("Gorillaz", "Bootie Brown"), facts.artists)
    }
}

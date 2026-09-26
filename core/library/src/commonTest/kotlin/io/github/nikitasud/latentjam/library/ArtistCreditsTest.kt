/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals

internal class ArtistCreditsTest {

    /** Stands in for the MusicBrainz artist list: the names it knows, compared case-insensitively. */
    private fun knowing(vararg names: String): (String) -> Boolean {
        val known = names.map { it.lowercase() }.toSet()
        return { it.lowercase() in known }
    }

    private val nobody = knowing()

    @Test
    fun aCommaListNamesEveryArtist() {
        assertEquals(listOf("Gorillaz", "Elton John"), ArtistCredits.split("Gorillaz, Elton John", nobody))
    }

    @Test
    fun featuringAndJoinersNameEveryArtist() {
        assertEquals(
            listOf("Dr. Dre", "Snoop Dogg", "Nate Dogg"),
            ArtistCredits.split("Dr. Dre feat. Snoop Dogg & Nate Dogg", nobody),
        )
        assertEquals(listOf("Marshmello", "Anne-Marie"), ArtistCredits.split("Marshmello x Anne-Marie", nobody))
        assertEquals(listOf("Don Omar", "Tego Calderon"), ArtistCredits.split("Don Omar ft. Tego Calderon", nobody))
        assertEquals(listOf("Кипелов", "Маврин"), ArtistCredits.split("Кипелов и Маврин", nobody))
    }

    @Test
    fun aBandTheListKnowsStaysWhole() {
        val list = knowing("Earth, Wind & Fire", "Король и Шут")
        assertEquals(listOf("Earth, Wind & Fire"), ArtistCredits.split("Earth, Wind & Fire", list))
        assertEquals(listOf("Король и Шут"), ArtistCredits.split("Король и Шут", list))
    }

    @Test
    fun aKnownNameInsideALongerCreditStaysWhole() {
        val list = knowing("Macklemore & Ryan Lewis", "Grover Washington, Jr.")
        assertEquals(
            listOf("Macklemore & Ryan Lewis", "Wanz"),
            ArtistCredits.split("Macklemore & Ryan Lewis feat. Wanz", list),
        )
        assertEquals(
            listOf("Grover Washington, Jr.", "Bill Withers"),
            ArtistCredits.split("Grover Washington, Jr., Bill Withers", list),
        )
    }

    @Test
    fun anAmpersandStillFindsABandTheListSpellsWithAnd() {
        assertEquals(listOf("Simon & Garfunkel"), ArtistCredits.split("Simon & Garfunkel", knowing("Simon and Garfunkel")))
        assertEquals(listOf("Simon and Garfunkel"), ArtistCredits.split("Simon and Garfunkel", knowing("Simon & Garfunkel")))
    }

    @Test
    fun aBackingBandStaysWithItsLeader() {
        assertEquals(
            listOf("Freud and the Suicidal Vampires"),
            ArtistCredits.split("Freud and the Suicidal Vampires", nobody),
        )
        assertEquals(listOf("Van Alexander & His Orchestra"), ArtistCredits.split("Van Alexander & His Orchestra", nobody))
        assertEquals(listOf("Чиж & Co"), ArtistCredits.split("Чиж & Co", nobody))
        // A rapper called I-20 is not the Italian article "i".
        assertEquals(
            listOf("Ludacris", "Mystikal", "I-20"),
            ArtistCredits.split("Ludacris feat. Mystikal and I-20", nobody),
        )
    }

    @Test
    fun separatorsInsideBracketsBelongToTheNote() {
        assertEquals(
            listOf("Andrew Underberg", "Sam Haft (performed by Darren Criss, Shoba Narayan)"),
            ArtistCredits.split("Andrew Underberg & Sam Haft (performed by Darren Criss, Shoba Narayan)", nobody),
        )
    }

    @Test
    fun symbolsInsideANameNeverSplitIt() {
        assertEquals(listOf("AC/DC"), ArtistCredits.split("AC/DC", nobody))
        assertEquals(listOf("Malcolm X"), ArtistCredits.split("Malcolm X", nobody))
        assertEquals(
            listOf("C+C Music Factory", "Freedom Williams"),
            ArtistCredits.split("C+C Music Factory featuring Freedom Williams", nobody),
        )
    }

    @Test
    fun aCreditNamingOneArtistIsReturnedAsWritten() {
        assertEquals(listOf("Gorillaz"), ArtistCredits.split("Gorillaz", nobody))
    }
}

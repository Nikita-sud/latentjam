/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.text.ArtistSets
import io.github.nikitasud.latentjam.smart.text.KnownArtists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The chain's artist identity from the artist list: every credited artist counts, a known whole name stays one. */
internal class ChainArtistSetsTest {

    /** A name list in place of the pack: a credit part is known when it is one of these names, keyed by its position. */
    private val known = object : KnownArtists {
        val names = listOf("Queen", "David Bowie", "Dr. Dre", "Snoop Dogg", "Nate Dogg", "Earth, Wind & Fire", "Maroon 5",
            "Christina Aguilera", "Simon & Garfunkel")
        override fun canonical(matchKey: Long): Long? = null
        override fun canonicalOf(raw: String): Long? =
            names.indexOfFirst { ArtistSets.normalize(it) == ArtistSets.normalize(raw) }.takeIf { it >= 0 }?.toLong()?.plus(1)
    }

    private fun meta(artist: String, title: String = "t"): TrackMeta = TrackMeta(
        title, artist, null, null, null,
        artistSet = ArtistSets.parse(artist, title, known).artists.map { it.toULong().toString(36) },
    )

    @Test
    fun `a featured artist is the same artist as their own tracks`() {
        val duet = meta("Queen feat. David Bowie")
        assertEquals(2, duet.artists.size)
        assertTrue(duet.sharesArtistWith(meta("Queen")))
        assertTrue(duet.sharesArtistWith(meta("David Bowie")))
        assertEquals(meta("Queen").artistKey, duet.artistKey)
    }

    @Test
    fun `a whole credit of several artists counts for each of them`() {
        val episode = meta("Dr. Dre feat. Snoop Dogg & Nate Dogg")
        assertEquals(3, episode.artists.size)
        assertTrue(episode.sharesArtistWith(meta("Snoop Dogg")))
        assertTrue(episode.hasArtist(meta("Nate Dogg").artistKey))
        assertTrue(meta("Maroon 5 feat. Christina Aguilera").sharesArtistWith(meta("Maroon 5")))
    }

    @Test
    fun `a known name with a separator in it stays one artist`() {
        assertEquals(1, meta("Earth, Wind & Fire").artists.size)
        assertEquals(1, meta("Simon & Garfunkel").artists.size)
        assertFalse(meta("Simon & Garfunkel").sharesArtistWith(meta("Earth, Wind & Fire")))
    }

    @Test
    fun `an unknown credit is split only where every part is known`() {
        assertEquals(1, meta("Foo Fighters & Bar Band").artists.size)
        assertEquals(2, meta("Queen & David Bowie").artists.size)
    }

    @Test
    fun `a title's featured artist is credited too`() {
        assertTrue(meta("Dr. Dre", "Still D.R.E. (feat. Snoop Dogg)").sharesArtistWith(meta("Snoop Dogg")))
    }

    @Test
    fun `a placeholder credit has no artist and groups nothing`() {
        val various = meta("Various Artists")
        assertTrue(various.artists.isEmpty())
        assertEquals("", various.artistKey)
        assertFalse(various.sharesArtistWith(meta("Various Artists")))
    }

    @Test
    fun `without the artist list the one display-string key is unchanged`() {
        val plain = TrackMeta("t", "Queen feat. David Bowie", null, null, null)
        assertEquals(listOf("queen feat. david bowie"), plain.artists)
        assertEquals("queen feat. david bowie", plain.artistKey)
    }
}

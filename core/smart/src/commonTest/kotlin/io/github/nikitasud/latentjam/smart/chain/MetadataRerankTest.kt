/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.Genres
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataRerankTest {

    @Test
    fun `a copied track recomputes its genre families from the edited tag`() {
        val original = meta(genre = "Trip Hop; Rock / House")
        val edited = original.copy(genre = "Classical; Orchestral")
        assertEquals(Genres.families(original.genre), original.genreFamilies)
        assertEquals(setOf("classical"), edited.genreFamilies)
        assertEquals(setOf("trip hop", "rock", "dance"), original.genreFamilies)
        assertEquals(emptySet(), original.copy(genre = null).genreFamilies)
    }

    @Test
    fun `versions of one song share a title and a movement keeps its own`() {
        for (version in listOf(
            "Moves Like Jagger - Studio Recording From The Voice Performance", "Moves Like Jagger - Remix",
            "Moves Like Jagger (feat. Christina Aguilera)", "Moves Like Jagger - Radio Edit - Remastered 2011",
            "Moves Like Jagger \u2013 Live",
        )) {
            assertEquals("moves like jagger", MetadataRerank.normalizeTitle(version), version)
        }
        assertEquals("piano sonata no. 14 - i. adagio sostenuto", MetadataRerank.normalizeTitle("Piano Sonata No. 14 - I. Adagio sostenuto"))
        assertEquals("nowadays/hot honey rag - medley title", MetadataRerank.normalizeTitle("Nowadays/Hot Honey Rag - Medley Title"))
        // A title that is nothing but a version word stays what it is.
        assertEquals("remix", MetadataRerank.normalizeTitle("Remix"))
    }

    @Test
    fun `supported seed family softly penalizes an early cross-family candidate`() {
        val seedGenre = Genres.families("Brazilian Phonk")
        val pool = List(6) { meta(genre = "Phonk") } + meta(genre = "House")
        val support = MetadataRerank.seedGenreSupport(seedGenre, pool)

        assertEquals(6, support)
        assertEquals(
            MetadataRerank.SEED_CROSS_GENRE_PENALTY,
            MetadataRerank.seedIntentMultiplier(
                seedGenre, support, seedFamilyPicks = 2, candidate = meta(genre = "House"),
            ),
        )
    }

    @Test
    fun `unsupported or completed seed prefix stays neutral`() {
        val seedGenre = Genres.families("Brazilian Phonk")
        val dance = meta(genre = "House")

        assertEquals(
            1f,
            MetadataRerank.seedIntentMultiplier(
                seedGenre, poolSupport = 5, seedFamilyPicks = 0, candidate = dance,
            ),
        )
        assertEquals(
            1f,
            MetadataRerank.seedIntentMultiplier(
                seedGenre,
                poolSupport = 20,
                seedFamilyPicks = MetadataRerank.SEED_GENRE_PREFIX_TARGET,
                candidate = dance,
            ),
        )
    }

    @Test
    fun `title genre bait is irrelevant to the seed guard`() {
        val seedGenre = Genres.families("Brazilian Phonk")
        val candidate = meta(title = "Jazz Techno Classical Mix", genre = "Phonk")

        assertEquals(
            1f,
            MetadataRerank.seedIntentMultiplier(
                seedGenre, poolSupport = 12, seedFamilyPicks = 0, candidate = candidate,
            ),
        )
    }

    @Test
    fun `same album is diversified softly instead of vetoed`() {
        val seed = TrackMeta("Seed", "Artist", "Album", "Rock", 1990)
        val neighbour = TrackMeta("Neighbour", "Artist", "Album", "Rock", 1990)

        val multiplier = MetadataRerank.adjustMultiplier(seed, neighbour)

        assertTrue(multiplier > 0.5f, "a genuine same-album neighbour must remain competitive")
    }

    @Test
    fun `same artist is a modest first-hop confidence signal`() {
        val seed = TrackMeta("Seed", "Band", "First", null, null)
        val neighbour = TrackMeta("Neighbour", "band", "Second", null, null)

        assertEquals(
            MetadataRerank.SAME_ARTIST_BONUS,
            MetadataRerank.adjustMultiplier(seed, neighbour),
        )
    }

    @Test
    fun `artist identity normalizes case edge whitespace and repeated whitespace`() {
        val seed = TrackMeta("Seed", "  The   Band\t", null, null, null)
        val neighbour = TrackMeta("Neighbour", "the band", null, null, null)

        assertEquals("the band", seed.artistKey)
        assertEquals(seed.artistKey, neighbour.artistKey)
        assertEquals(
            MetadataRerank.SAME_ARTIST_BONUS,
            MetadataRerank.adjustMultiplier(seed, neighbour),
        )
    }

    private fun meta(
        title: String = "Track",
        genre: String? = null,
    ): TrackMeta = TrackMeta(
        title = title,
        artist = "Artist",
        album = null,
        genre = genre,
        year = null,
    )
}

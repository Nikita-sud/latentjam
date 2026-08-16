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

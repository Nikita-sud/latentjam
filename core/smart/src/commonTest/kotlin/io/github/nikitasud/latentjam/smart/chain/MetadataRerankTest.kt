/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataRerankTest {

    @Test
    fun `supported seed family softly penalizes an early cross-family candidate`() {
        val seedGenre = MetadataRerank.normalizeGenre("Brazilian Phonk")
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
        val seedGenre = MetadataRerank.normalizeGenre("Brazilian Phonk")
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
        val seedGenre = MetadataRerank.normalizeGenre("Brazilian Phonk")
        val candidate = meta(title = "Jazz Techno Classical Mix", genre = "Phonk")

        assertEquals(
            1f,
            MetadataRerank.seedIntentMultiplier(
                seedGenre, poolSupport = 12, seedFamilyPicks = 0, candidate = candidate,
            ),
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

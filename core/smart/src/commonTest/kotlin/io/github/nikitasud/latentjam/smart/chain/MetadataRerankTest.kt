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
    fun `the v2 version key keeps a part or piece bracket like reference strip_brackets`() {
        // Expected values from the research fixtures.normalize_title at 1c66b8c8 with LJ_VERSION_V2=1.
        for ((title, expected) in listOf<Pair<String?, String>>(
            "Song (Pt. 2)" to "song pt. 2",
            "Song (pt. 1)" to "song pt. 1",
            "Song (Part II)" to "song part ii",
            "Song [Vol.3]" to "song vol.3",
            "Song (Volume 12) - Remastered 2011" to "song volume 12",
            "Symphony No. 5 (Movement 4)" to "symphony no. 5 movement 4",
            "Song (Mvt. iv)" to "song mvt. iv",
            "Track (Intro)" to "track intro",
            "Track (Interlude)" to "track interlude",
            "Track (Outro) [Live]" to "track outro",
            "Song (Reprise)" to "song reprise",
            "Prelude (Overture)" to "prelude overture",
            "Skit (skit)" to "skit skit",
            "Song (Remix)" to "song",
            "Song (Live at Wembley)" to "song",
            "Song (feat. X) (Pt. 3)" to "song pt. 3",
            "Song (Part 2\u00e9)" to "song",
            "Song (part2)" to "song part2",
            "Song (apart 2)" to "song",
            "Song (Act 1, Scene 2)" to "song act 1, scene 2",
            "Song (Chapter 7) - Radio Edit" to "song chapter 7",
            "Song (Chap. 3)" to "song chap. 3",
            "Song (intro mix)" to "song intro mix",
            "Song (introduction)" to "song",
            "Song (Partial 2)" to "song",
            "Song (pt 2) - Live" to "song pt 2",
            "Song\u00a0(Pt. 2)\u00a0" to "song pt. 2",
            "Song (Part X)" to "song part x",
            "Song (Part XI)" to "song part xi",
            "Song (Part 2_)" to "song",
            "(Pt. 1)" to "pt. 1",
            "(Remix)" to "",
            "Song (vol. iii) [bonus]" to "song vol. iii",
            "Song (Movement \u0663)" to "song",
            "Song (outro)  - acoustic version" to "song outro",
            "\u041f\u0435\u0441\u043d\u044f (\u0447\u0430\u0441\u0442\u044c 2)" to "\u043f\u0435\u0441\u043d\u044f",
            "Song (Act IV)" to "song act iv",
            "Song ((Pt. 2))" to "song ( pt. 2 )",
            "" to "",
            null to "",
        )) {
            assertEquals(expected, MetadataRerank.normalizeTitle(title, keepParts = true), title)
        }
        // v1 (the shipped models) still drops every bracket.
        assertEquals("song", MetadataRerank.normalizeTitle("Song (Pt. 2)"))
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

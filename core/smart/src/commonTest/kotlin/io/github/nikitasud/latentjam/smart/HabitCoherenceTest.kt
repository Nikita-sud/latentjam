/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.EntityIndexBytes
import io.github.nikitasud.latentjam.smart.text.KnowledgePackBytes
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitCoherenceTest {
    // Tiny pack: Alpha [1,0,0,1] Romanian, Beta [0,1,1,0] English, Gamma unconfident,
    // Filler 1 and 2 [0,1,0,1] with no language.
    private val knowledge = ArtistKnowledge(
        MusicEntityResolver {
            AssetBytes.of(
                EntityIndexBytes.of(
                    "alpha" to intArrayOf(0), "beta" to intArrayOf(1), "gamma" to intArrayOf(2),
                    "filler 1" to intArrayOf(3), "filler 2" to intArrayOf(4), entityCount = 5,
                ),
            )
        },
    ) { AssetBytes.of(KnowledgePackBytes.tiny(fillers = 2)) }

    private var next = 0
    private fun track(artist: String, genre: String? = null) =
        TrackDescriptor(TrackId("t${next++}"), title = "x", artist = artist, genre = genre)

    @Test
    fun `a row is coherent only when both are known and agree in family and language and sound`() {
        val fromAlpha = HabitCoherence(knowledge).from(track("Alpha", "Rock"))
        assertTrue(fromAlpha(track("Alpha", "Hard Rock")))
        assertTrue(fromAlpha(track("Alpha"))) // no family on one side: not compared
        assertFalse(fromAlpha(track("Alpha", "Pop"))) // rock vs pop
        assertFalse(fromAlpha(track("Beta", "Rock"))) // cosine 0 and en vs ro
        assertFalse(fromAlpha(track("Filler 1", "Rock"))) // cosine 0.5 < 0.6037
        assertFalse(fromAlpha(track("Nobody", "Rock"))) // unknown is not coherent
        assertFalse(fromAlpha(track("Gamma", "Rock"))) // unconfident entity: unknown
        // No language on either side is not compared; same sound passes.
        assertTrue(HabitCoherence(knowledge).from(track("Filler 1"))(track("Filler 2")))
    }

    @Test
    fun `an unknown seed or a missing pack admits nothing`() {
        assertFalse(HabitCoherence(knowledge).from(track("Nobody"))(track("Alpha")))
        assertFalse(HabitCoherence(null).from(track("Alpha"))(track("Alpha")))
    }

    @Test
    fun `the family is the alias family of the first genre value`() {
        assertEquals("rap", HabitCoherence.family("Phonk; Rock"))
        assertEquals("rock", HabitCoherence.family("Hard Rock"))
        assertNull(HabitCoherence.family("Chiptune"))
        assertNull(HabitCoherence.family(null))
    }
}

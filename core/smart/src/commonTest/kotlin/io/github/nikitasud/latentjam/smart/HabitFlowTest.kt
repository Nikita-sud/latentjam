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
import kotlinx.coroutines.test.runTest
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HabitFlowTest {
    // Tiny pack: Alpha [1,0,0,1] Romanian, Beta [0,1,1,0] English.
    private val knowledge = ArtistKnowledge(
        MusicEntityResolver {
            AssetBytes.of(EntityIndexBytes.of("alpha" to intArrayOf(0), "beta" to intArrayOf(1), entityCount = 2))
        },
    ) { AssetBytes.of(KnowledgePackBytes.tiny()) }

    private fun track(id: String, artist: String, genre: String? = null) =
        TrackDescriptor(TrackId(id), title = id, artist = artist, genre = genre)

    @Test
    fun `incoherence averages family language descriptor and audio`() = runTest {
        // The library's audio sits within a few degrees of one direction, so its random pairs are close.
        val library = (0 until 20).map { track("lib$it", "Lib") }
        val audio = HashMap<TrackId, FloatArray>()
        library.forEachIndexed { i, t -> audio[t.id] = floatArrayOf(cos(i * 0.01).toFloat(), sin(i * 0.01).toFloat()) }
        val seed = track("seed", "Alpha", "Rock").also { audio[it.id] = floatArrayOf(1f, 0f) }
        val twin = track("twin", "Alpha", "Hard Rock").also { audio[it.id] = floatArrayOf(1f, 0f) }
        val far = track("far", "Beta", "Pop").also { audio[it.id] = floatArrayOf(0f, 1f) }
        val unknown = track("unknown", "Nobody")
        val p = HabitFlow(knowledge) { audio[it] }.incoherence(seed, library)
        assertEquals(0.0, p(twin), 1e-9)
        assertEquals(1.0, p(far), 1e-9)
        assertEquals(0.5, p(unknown), 1e-9) // family, language, descriptor and audio all unknown
        // Without the pack and without audio, only the family can be judged.
        val bare = HabitFlow(null) { null }.incoherence(seed, library)
        assertEquals((0.0 + 0.5 + 0.5 + 0.5) / 4, bare(twin), 1e-9)
    }

    @Test
    fun `rows are kept when transition strength outweighs incoherence`() {
        val counts = mapOf("a" to 4, "b" to 1, "c" to 2)
        val rows = listOf("a", "b", "c").map(::TrackId)
        val picked = HabitFlow.pick(
            rows,
            count = { counts.getValue(it.value) },
            incoherence = mapOf(TrackId("a") to 0.9, TrackId("b") to 0.5, TrackId("c") to 0.0),
        )
        // a: 1 - 0.9 = 0.1; b: ln2/ln5 - 0.5 < 0; c: ln3/ln5 = 0.68.
        assertEquals(listOf(TrackId("c"), TrackId("a")), picked)
        assertEquals(emptyList(), HabitFlow.pick(emptyList(), { 0 }, emptyMap()))
    }

    @Test
    fun `distances and percentiles follow their anchors`() {
        assertEquals(0.0, HabitFlow.distance(0.9, 0.8, 0.6036645770072937))
        assertEquals(1.0, HabitFlow.distance(0.5, 0.8, 0.6036645770072937))
        assertEquals(0.5, HabitFlow.distance(0.7, 0.8, 0.6), 1e-9)
        assertEquals(0.5, HabitFlow.distance(null, 0.8, 0.6))
        assertEquals(1.75, HabitFlow.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.25), 1e-12)
        assertEquals("rap", HabitFlow.family("Phonk; Rock"))
        assertNull(HabitFlow.family("Chiptune"))
    }
}

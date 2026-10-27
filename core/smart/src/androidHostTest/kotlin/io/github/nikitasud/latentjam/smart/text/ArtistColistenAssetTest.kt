/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped co-listening table reads back the research's pop_k10.npz lists (tools/research/ranker/pack_colisten.py), floored
 * at 50 listeners (PLAN_SIZE, shrink_tables.py).
 */
class ArtistColistenAssetTest {
    private fun asset(name: String) = File("../../androidApp/src/main/assets/ml/$name").also { assertTrue(it.isFile) }.readBytes()

    private val table by lazy { assertNotNull(ArtistNeighbours.parse(asset("artist_colisten.bin")), "the shipped table must parse") }

    private fun list(entity: Int) = buildList { table.forEachNeighbour(entity) { n, s -> add(n to Math.round(s * 255)) } }

    @Test
    fun `lists are the research npz's, floored at 50 listeners`() {
        // PLAN_SIZE: an artist under 50 listeners has no list and is no one's neighbour; the others keep their order.
        assertEquals(
            listOf(5 to 175, 4 to 160, 15 to 152, 20 to 146, 11 to 145, 10 to 143, 16 to 140, 17 to 139, 43 to 139, 24 to 131),
            list(2),
        )
        assertEquals(listOf(24975 to 41), list(37), "nine of its ten neighbours are under the floor")
        assertFalse(table.has(150167), "under the floor")
        assertFalse(table.has(28))
    }

    @Test
    fun `well-known artists resolve to an entity with a list`() {
        val knowledge = ArtistKnowledge(MusicEntityResolver { AssetBytes.of(asset("music_entities_250k.bin")) }) {
            AssetBytes.of(asset("artist_knowledge.bin"))
        }
        for (name in listOf("ABBA", "Eminem", "Кино", "Ramin Djawadi", "Daft Punk")) {
            val entity = assertNotNull(knowledge.entity(name), name)
            assertTrue(table.has(entity), "$name ($entity) has no co-listening list")
        }
    }
}

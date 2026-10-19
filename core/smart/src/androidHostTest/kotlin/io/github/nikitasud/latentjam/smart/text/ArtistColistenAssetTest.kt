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

/** The shipped co-listening table reads back the research's pop_k10.npz lists (tools/research/ranker/pack_colisten.py). */
class ArtistColistenAssetTest {
    private fun asset(name: String) = File("../../androidApp/src/main/assets/ml/$name").also { assertTrue(it.isFile) }.readBytes()

    private val table by lazy { assertNotNull(ArtistNeighbours.parse(asset("artist_colisten.bin")), "the shipped table must parse") }

    private fun list(entity: Int) = buildList { table.forEachNeighbour(entity) { n, s -> add(n to Math.round(s * 255)) } }

    @Test
    fun `lists match the research npz`() {
        assertEquals(
            listOf(14104 to 193, 37921 to 190, 235731 to 170, 255215 to 170, 246006 to 170, 305880 to 165, 78210 to 160,
                53529 to 156, 230512 to 152, 139326 to 150),
            list(150167),
        )
        assertEquals(
            listOf(51748 to 122, 30463 to 121, 10844 to 120, 80011 to 119, 143121 to 119, 75681 to 115, 150201 to 114,
                187491 to 114, 150622 to 113, 123310 to 111),
            list(349975),
        )
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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shipped artist popularity (LJPOPS1) and family (LJFAMS1) tables, as serving/artist_pop.json / artist_family.json describe them. */
class ArtistTablesAssetTest {
    private fun asset(name: String) = AssetBytes.of(File("../../androidApp/src/main/assets/ml/$name").readBytes())

    @Test
    fun theFamilyTableParsesWithItsCounts() {
        val table = assertNotNull(ArtistFamilies.parse(asset("artist_family.bin")))
        val counts = HashMap<String, Int>()
        for (e in 0 until 350_000) table.family(e)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        assertEquals(
            mapOf("classical" to 21506, "dance" to 10705, "pop" to 24443, "rap" to 8798, "rock" to 37760, "soundtrack" to 1453),
            counts,
        )
        assertNull(table.family(-1))
        assertNull(ArtistPopularity.parse(asset("artist_family.bin")), "a family table is not a popularity table")
    }

    @Test
    fun thePopularityTableParsesInItsRange() {
        val table = assertNotNull(ArtistPopularity.parse(asset("artist_pop.bin")))
        var n = 0
        for (e in 0 until 350_000) table.value(e)?.let { v ->
            n++
            assertTrue(v in 2.3978..9.7805, "value $v")
        }
        assertEquals(174_044, n)
    }
}

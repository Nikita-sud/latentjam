/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import io.github.nikitasud.latentjam.smart.ranker.RankerModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistPopularityTest {
    /** The layout serving/artist_pop.py writes: LJVECS1's with magic LJPOPS1, dimension 1, float32. */
    private fun asset(space: Int, values: Map<Int, Float>, magic: String = "LJPOPS1\u0000", dimension: Int = 1): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) { out.add(v.toByte()) }
        fun u16(v: Int) { u8(v and 0xff); u8((v ushr 8) and 0xff) }
        fun u32(v: Long) { for (b in 0 until 4) u8(((v ushr (8 * b)) and 0xff).toInt()) }
        magic.encodeToByteArray().forEach(out::add)
        u32(1); u32(space.toLong()); u32(values.size.toLong()); u16(dimension); u16(4)
        val bits = LongArray((space + 63) / 64)
        for (e in values.keys) bits[e ushr 6] = bits[e ushr 6] or (1L shl (e and 63))
        for (w in bits) for (b in 0 until 8) u8(((w ushr (8 * b)) and 0xff).toInt())
        for (block in 0 until (space + 511) / 512 + 1) u32(values.keys.count { it < block * 512 }.toLong())
        for ((_, v) in values.entries.sortedBy { it.key }) repeat(dimension) { u32(v.toRawBits().toLong() and 0xffffffffL) }
        return out.toByteArray()
    }

    private val values = mapOf(3 to 2.5f, 700 to 9.75f, 1100 to 0.125f)

    @Test
    fun readsValuesByEntityAndKnowsWhoHasOne() {
        val table = assertNotNull(ArtistPopularity.parse(asset(1200, values)))
        assertEquals(2.5, table.value(3))
        assertEquals(9.75, table.value(700))
        assertEquals(0.125, table.value(1100))
        assertTrue(table.has(700))
        assertFalse(table.has(4))
        assertNull(table.value(4))
        assertNull(table.value(-1))
        assertNull(table.value(5000))
    }

    @Test
    fun aDamagedOrForeignAssetIsRefused() {
        val good = asset(1200, values)
        assertNull(ArtistPopularity.parse(good.copyOf(good.size - 1)))
        assertNull(ArtistPopularity.parse(asset(1200, values, magic = "LJVECS1\u0000")))
        assertNull(ArtistPopularity.parse(asset(1200, values, dimension = 2)))
        assertNull(ArtistVectors.parse(good), "a popularity table is not a vector table")
    }

    @Test
    fun theModelNamesWhereLogpopComesFrom() {
        fun model(inputs: String) = RankerModel.parse(
            """{"features": ["logpop"], "mean": [0], "sd": [1], "weights": [1] $inputs}""",
        )
        assertEquals(RankerModel.LOGPOP_NONE, model("")?.logpop)
        assertEquals(RankerModel.LOGPOP_NONE, model(""", "inputs": {"logpop": "none"}""")?.logpop)
        assertEquals(RankerModel.LOGPOP_ARTIST, model(""", "inputs": {"logpop": "artist_pop"}""")?.logpop)
        assertNull(model(""", "inputs": {"logpop": "track"}"""), "a source the app cannot compute refuses the asset")
    }
}

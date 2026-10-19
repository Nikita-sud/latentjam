/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ArtistNeighboursTest {
    /** The layout tools/research/ranker/pack_colisten.py writes. */
    private fun asset(space: Int, lists: Map<Int, List<Pair<Int, Int>>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) { out.add(v.toByte()) }
        fun u32(v: Long) { for (b in 0 until 4) u8(((v ushr (8 * b)) and 0xff).toInt()) }
        "LJNBRS1\u0000".encodeToByteArray().forEach(out::add)
        u32(1); u32(space.toLong()); u32(lists.size.toLong()); u8(4); u8(0)
        val words = (space + 63) / 64
        val bits = LongArray(words)
        for (e in lists.keys) bits[e ushr 6] = bits[e ushr 6] or (1L shl (e and 63))
        for (w in bits) for (b in 0 until 8) u8(((w ushr (8 * b)) and 0xff).toInt())
        for (block in 0 until (space + 511) / 512 + 1) u32(lists.keys.count { it < block * 512 }.toLong())
        var offset = 0L
        val sorted = lists.entries.sortedBy { it.key }.associate { it.key to it.value }
        u32(0)
        for (list in sorted.values) { offset += list.size; u32(offset) }
        for (list in sorted.values) for ((n, s) in list) { u8(n and 0xff); u8((n ushr 8) and 0xff); u8(n ushr 16); u8(s) }
        return out.toByteArray()
    }

    private val lists = mapOf(3 to listOf(700 to 255, 9 to 128), 700 to listOf(3 to 255), 1100 to emptyList())

    @Test
    fun `reads lists best first and knows who has one`() {
        val table = assertNotNull(ArtistNeighbours.parse(asset(1200, lists)))
        val seen = ArrayList<Pair<Int, Float>>()
        table.forEachNeighbour(3) { n, s -> seen += n to s }
        assertEquals(listOf(700 to 1f, 9 to 128 / 255f), seen)
        assertTrue(table.has(1100) && !table.has(9) && !table.has(5000))
        assertEquals(700, table.entityOf(intArrayOf(9, 700, 3)))
        assertEquals(-1, table.entityOf(intArrayOf(9)))
    }

    @Test
    fun `a damaged or foreign asset is refused`() {
        val good = asset(1200, lists)
        assertNull(ArtistNeighbours.parse(good.copyOf(good.size - 1)))
        assertNull(ArtistNeighbours.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(ArtistNeighbours.parse(good.copyOf().also { it[8] = 2 }))
    }
}

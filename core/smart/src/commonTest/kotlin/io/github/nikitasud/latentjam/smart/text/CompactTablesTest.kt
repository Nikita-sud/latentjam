/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The compact table layouts (LJENT3, knowledge pack version 3) answer exactly as the ones they replace. */
class CompactTablesTest {

    private val mappings = arrayOf(
        "viktor tsoi" to intArrayOf(17, 42),
        "виктор" to intArrayOf(17, 42),
        "кино" to intArrayOf(42),
        "kino" to intArrayOf(42),
        "silver" to intArrayOf(3, 130, 131, 4_000, 99_999),
        "beta" to intArrayOf(1),
    )

    @Test
    fun `the compact entity index resolves every key and stranger exactly as LJENT2 does`() {
        val wide = assertNotNull(MusicEntityIndex.parse(EntityIndexBytes.of(*mappings, entityCount = 100_000)))
        val compact = assertNotNull(MusicEntityIndex.parse(EntityIndexBytes.compact(*mappings, entityCount = 100_000)))
        for (name in mappings.map { it.first } + listOf("Viktor Tsoi", "KINO!", "nobody", "kin", "")) {
            assertContentEquals(wide.resolve(name), compact.resolve(name), name)
        }
        assertTrue(compact.matches("Виктор", "КИНО"))
        assertFalse(compact.matches("Виктор", "Silver"))
    }

    @Test
    fun `keys sharing a bucket are walked in order up to the last one`() {
        val bucket = 0x1234uL shl 32
        val keys = listOf(bucket or 0x100uL to intArrayOf(1), bucket or 0x200uL to intArrayOf(2, 3), bucket or 0xffff00uL to intArrayOf(9))
        val compact = assertNotNull(MusicEntityIndex.parse(EntityIndexBytes.compactHashed(keys)))
        val wide = assertNotNull(MusicEntityIndex.parse(EntityIndexBytes.hashed(keys)))
        assertEquals(wide.resolve("x").size, compact.resolve("x").size)
    }

    @Test
    fun `a corrupt compact index fails closed`() {
        val good = EntityIndexBytes.compact(*mappings, entityCount = 100_000)
        assertNotNull(MusicEntityIndex.parse(good))
        // The directory is checked when the file is parsed.
        assertNull(MusicEntityIndex.parse(good.copyOf(good.size - 1)))
        assertNull(MusicEntityIndex.parse(good.copyOf(EntityIndexBytes.KEYS_OFFSET)))
        assertNull(MusicEntityIndex.parse(good.copyOf().also { EntityIndexBytes.writeInt(it, EntityIndexBytes.HEADER_SIZE + 4, 7) }))
        // A key's bytes are checked when a lookup reads them: an id at or beyond the entity count answers
        // nothing for that key alone.
        val narrow = assertNotNull(MusicEntityIndex.parse(EntityIndexBytes.compact(*mappings, entityCount = 50)))
        assertContentEquals(IntArray(0), narrow.resolve("silver"))
        assertContentEquals(intArrayOf(17, 42), narrow.resolve("viktor tsoi"))
        // Damage anywhere never breaks a lookup: whatever a damaged entry yields is a well-formed id
        // list (sorted, distinct, below the entity count), or nothing. Like LJENT2, a flipped bit inside
        // an id can still name another valid entity; the format checks structure, not content.
        for (at in EntityIndexBytes.KEYS_OFFSET until good.size) {
            for (bit in 0 until 8) {
                val damaged = good.copyOf().also { it[at] = (it[at].toInt() xor (1 shl bit)).toByte() }
                val index = MusicEntityIndex.parse(damaged) ?: continue
                for ((name, _) in mappings) {
                    val got = index.resolve(name)
                    assertTrue(got.all { it in 0 until 100_000 }, "byte $at bit $bit: $name -> ${got.toList()}")
                    assertTrue((1 until got.size).all { got[it] > got[it - 1] }, "byte $at bit $bit: $name -> ${got.toList()}")
                }
            }
        }
    }

    @Test
    fun `the version 3 pack answers exactly as version 2`() {
        for (fillers in listOf(0, 3, 600)) {
            val wide = assertNotNull(ArtistKnowledgePack.parse(KnowledgePackBytes.tiny(fillers)))
            val compact = assertNotNull(ArtistKnowledgePack.parse(KnowledgePackBytes.tinyCompact(fillers)))
            val queries = listOf(
                intArrayOf(0, 1) to "Beta", intArrayOf(0, 1) to "someone else", intArrayOf(2) to "Gamma",
                intArrayOf(0, 2) to "Gamma", intArrayOf(7) to "Nobody", intArrayOf() to "Nobody",
                intArrayOf(0, 3, 4, 5) to "Silver", intArrayOf(0, 3, 4, 5) to "Connect R",
                intArrayOf(fillers + 2) to "Filler $fillers", intArrayOf(1, fillers + 2) to "Filler $fillers",
            )
            for ((ids, name) in queries) {
                assertEquals(wide.facts(ids, name), compact.facts(ids, name), "$fillers $name")
                val a = wide.descriptor(ids, name)
                val b = compact.descriptor(ids, name)
                assertEquals(a == null, b == null, "$fillers $name")
                if (a != null && b != null) assertContentEquals(a, b, "$fillers $name")
            }
        }
    }

    @Test
    fun `a corrupt version 3 pack fails closed`() {
        val good = KnowledgePackBytes.tinyCompact(fillers = 3)
        assertNotNull(ArtistKnowledgePack.parse(good))
        assertNull(ArtistKnowledgePack.parse(good.copyOf(good.size - 1)))
        // Presence starts after the 21-byte header and 16 codebook bytes: claim an absent entity.
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[37] = (it[37].toInt() or 0x04).toByte() }))
        // A presence bit beyond the entity count.
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[44] = 0x80.toByte() }))
        // The running count after the last block must equal the stored records.
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[49] = 9 }))
    }
}

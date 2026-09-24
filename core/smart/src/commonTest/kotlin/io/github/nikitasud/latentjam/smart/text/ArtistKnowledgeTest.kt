/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArtistKnowledgeTest {

    private val half = 1f / sqrt(2f)
    private val entities = MusicEntityResolver { entityIndex("alpha" to 0, "beta" to 1, "gamma" to 2) }

    @Test
    fun `a known artist gets its entity's descriptor`() {
        val knowledge = ArtistKnowledge(entities) { pack() }

        assertVector(floatArrayOf(0f, half, half, 0f), knowledge.descriptor("Beta"))
    }

    @Test
    fun `a collaboration tag falls back to its first credited artist`() {
        val knowledge = ArtistKnowledge(entities) { pack() }

        assertVector(floatArrayOf(half, 0f, 0f, half), knowledge.descriptor("Alpha & Beta", primaryArtist = "Alpha"))
    }

    @Test
    fun `unknown, unconfident, blank and pack-less lookups have no descriptor`() {
        val knowledge = ArtistKnowledge(entities) { pack() }

        assertNull(knowledge.descriptor("Nobody"))
        assertNull(knowledge.descriptor("Gamma"))
        assertNull(knowledge.descriptor(null))
        assertNull(knowledge.descriptor("  "))
        assertNull(ArtistKnowledge(entities) { null }.descriptor("Beta"))
    }

    private fun assertVector(expected: FloatArray, actual: FloatArray?) {
        val values = assertNotNull(actual)
        assertEquals(expected.size, values.size)
        expected.indices.forEach { assertEquals(expected[it], values[it], 1e-6f) }
    }

    /** Dimension 4, M = 2, K = 2 ([1, 0] and [0, 1]); Alpha (0, 1), Beta (1, 0), Gamma unconfident. */
    private fun pack(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += value.toByte() }
        fun u16(value: Int) { u8(value and 0xff); u8(value shr 8) }
        fun u32(value: Int) { u16(value and 0xffff); u16(value ushr 16) }
        "LJKNOW1\u0000".encodeToByteArray().forEach { out += it }
        u32(1); u16(4); u8(2); u16(2); u32(3)
        repeat(2) { u16(0x3C00); u16(0x0000); u16(0x0000); u16(0x3C00) }
        fun record(first: Int, second: Int, name: String, confidence: Int) {
            u8(first); u8(second); u16(ArtistKnowledgePack.fingerprint(name)); u8(0); u8(0); u8(confidence)
        }
        record(0, 1, "Alpha", 200)
        record(1, 0, "Beta", 200)
        record(0, 0, "Gamma", 0)
        return out.toByteArray()
    }

    /** A MusicEntityIndex whose normalized names each resolve to one entity id. */
    private fun entityIndex(vararg names: Pair<String, Int>): ByteArray {
        val ordered = names.map { (key, id) -> MusicEntityIndex.fnv1a64(key.encodeToByteArray()) to id }.sortedBy { it.first }
        val bytes = ByteArray(20 + ordered.size * 16 + ordered.size * 4)
        byteArrayOf(0x4c, 0x4a, 0x45, 0x4e, 0x54, 0x31, 0, 0).copyInto(bytes)
        fun int(offset: Int, value: Int) { for (i in 0 until 4) bytes[offset + i] = (value ushr (i * 8)).toByte() }
        int(8, ordered.size); int(12, ordered.size); int(16, names.size)
        ordered.forEachIndexed { index, (hash, id) ->
            val entry = 20 + index * 16
            for (i in 0 until 8) bytes[entry + i] = (hash shr (i * 8)).toByte()
            int(entry + 8, index)
            bytes[entry + 12] = 1
            int(20 + ordered.size * 16 + index * 4, id)
        }
        return bytes
    }
}

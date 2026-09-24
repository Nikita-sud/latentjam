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

class ArtistKnowledgePackTest {

    private val half = 1f / sqrt(2f)

    @Test
    fun `the entity whose own name matches wins and otherwise the most popular confident one does`() {
        val pack = assertNotNull(ArtistKnowledgePack.parse(tinyPack()))

        // Ids 0 and 1 both answer the query; 1 is the one actually named "Beta".
        assertVector(floatArrayOf(0f, half, half, 0f), pack.descriptor(intArrayOf(0, 1), "Beta"), 1e-6f)
        assertVector(floatArrayOf(half, 0f, 0f, half), pack.descriptor(intArrayOf(0, 1), "someone else"), 1e-6f)
        // Normalization is the entity index's, so case and punctuation do not matter.
        assertVector(floatArrayOf(0f, half, half, 0f), pack.descriptor(intArrayOf(0, 1), "BETA!"), 1e-6f)
    }

    @Test
    fun `an unconfident or unknown entity has no descriptor`() {
        val pack = assertNotNull(ArtistKnowledgePack.parse(tinyPack()))

        assertNull(pack.descriptor(intArrayOf(2), "Gamma"))
        assertNull(pack.descriptor(intArrayOf(7), "Nobody"))
        assertNull(pack.descriptor(intArrayOf(), "Nobody"))
    }

    @Test
    fun `corrupt or truncated or newer assets are rejected`() {
        val good = tinyPack()
        assertNull(ArtistKnowledgePack.parse(good.copyOf(good.size - 1)))
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[8] = 2 }))
        assertNull(ArtistKnowledgePack.parse(ByteArray(0)))
    }

    @Test
    fun `half floats decode exactly`() {
        assertEquals(1f, ArtistKnowledgePack.halfToFloat(0x3C00))
        assertEquals(-2f, ArtistKnowledgePack.halfToFloat(0xC000))
        assertEquals(0.5f, ArtistKnowledgePack.halfToFloat(0x3800))
        assertEquals(5.9604645e-8f, ArtistKnowledgePack.halfToFloat(0x0001))
        assertEquals(0f, ArtistKnowledgePack.halfToFloat(0x0000))
    }

    /**
     * Dimension 4, two sub-spaces of two, two centroids each: [1, 0] and [0, 1]. Entity 0 ("Alpha")
     * codes (0, 1), entity 1 ("Beta") codes (1, 0), entity 2 ("Gamma") has confidence 0.
     */
    private fun tinyPack(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += value.toByte() }
        fun u16(value: Int) { u8(value and 0xff); u8(value shr 8) }
        fun u32(value: Int) { u16(value and 0xffff); u16(value ushr 16) }
        "LJKNOW1\u0000".encodeToByteArray().forEach { out += it }
        u32(1); u16(4); u8(2); u16(2); u32(3)
        repeat(2) { u16(0x3C00); u16(0x0000); u16(0x0000); u16(0x3C00) } // per sub-space: [1, 0], [0, 1]
        fun record(first: Int, second: Int, name: String, confidence: Int) {
            u8(first); u8(second); u16(ArtistKnowledgePack.fingerprint(name)); u8(0); u8(0); u8(confidence)
        }
        record(0, 1, "Alpha", 200)
        record(1, 0, "Beta", 200)
        record(0, 0, "Gamma", 0)
        return out.toByteArray()
    }

    private fun assertVector(expected: FloatArray, actual: FloatArray?, tolerance: Float) {
        val values = assertNotNull(actual)
        assertEquals(expected.size, values.size)
        expected.indices.forEach { assertEquals(expected[it], values[it], tolerance) }
    }
}

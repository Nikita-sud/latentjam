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
        val pack = assertNotNull(ArtistKnowledgePack.parse(KnowledgePackBytes.tiny()))

        // Ids 0 and 1 both answer the query; 1 is the one actually named "Beta".
        assertVector(floatArrayOf(0f, half, half, 0f), pack.descriptor(intArrayOf(0, 1), "Beta"), 1e-6f)
        assertVector(floatArrayOf(half, 0f, 0f, half), pack.descriptor(intArrayOf(0, 1), "someone else"), 1e-6f)
        // Normalization is the entity index's, so case and punctuation do not matter.
        assertVector(floatArrayOf(0f, half, half, 0f), pack.descriptor(intArrayOf(0, 1), "BETA!"), 1e-6f)
    }

    @Test
    fun `an unconfident or unknown entity has no descriptor`() {
        val pack = assertNotNull(ArtistKnowledgePack.parse(KnowledgePackBytes.tiny()))

        assertNull(pack.descriptor(intArrayOf(2), "Gamma"))
        assertNull(pack.descriptor(intArrayOf(7), "Nobody"))
        assertNull(pack.descriptor(intArrayOf(), "Nobody"))
    }

    @Test
    fun `corrupt or truncated or other-version assets are rejected`() {
        val good = KnowledgePackBytes.tiny()
        assertNull(ArtistKnowledgePack.parse(good.copyOf(good.size - 1)))
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[8] = 1 }))
        assertNull(ArtistKnowledgePack.parse(good.copyOf().also { it[8] = 3 }))
        assertNull(ArtistKnowledgePack.parse(ByteArray(0)))
    }

    @Test
    fun `a record cannot reference a centroid outside its codebook`() {
        // Header (21 bytes), then eight FP16 codebook values (16 bytes).
        val corrupt = KnowledgePackBytes.tiny().also { it[37] = 2 }
        assertNull(ArtistKnowledgePack.parse(corrupt))
    }

    @Test
    fun `nonfinite codebook values are rejected before reaching recommendations`() {
        val corrupt = KnowledgePackBytes.tiny().also {
            it[21] = 0
            it[22] = 0x7c // positive infinity in FP16
        }
        assertNull(ArtistKnowledgePack.parse(corrupt))
    }

    @Test
    fun `facts come from the entity the descriptor reads`() {
        val pack = assertNotNull(ArtistKnowledgePack.parse(KnowledgePackBytes.tiny()))

        assertEquals(ArtistFacts("en", null), pack.facts(intArrayOf(0, 1), "Beta"))
        assertEquals(ArtistFacts("ro", 1980), pack.facts(intArrayOf(0, 1), "someone else"))
        assertNull(pack.facts(intArrayOf(2), "Gamma"))
        assertNull(pack.facts(intArrayOf(), "Nobody"))
    }

    @Test
    fun `half floats decode exactly`() {
        assertEquals(1f, ArtistKnowledgePack.halfToFloat(0x3C00))
        assertEquals(-2f, ArtistKnowledgePack.halfToFloat(0xC000))
        assertEquals(0.5f, ArtistKnowledgePack.halfToFloat(0x3800))
        assertEquals(5.9604645e-8f, ArtistKnowledgePack.halfToFloat(0x0001))
        assertEquals(0f, ArtistKnowledgePack.halfToFloat(0x0000))
    }

    private fun assertVector(expected: FloatArray, actual: FloatArray?, tolerance: Float) {
        val values = assertNotNull(actual)
        assertEquals(expected.size, values.size)
        expected.indices.forEach { assertEquals(expected[it], values[it], tolerance) }
    }
}

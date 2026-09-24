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

class ArtistAdapterTest {

    @Test
    fun `gelu is the tanh form training uses`() {
        assertEquals(0.841192f, ArtistAdapter.gelu(1f), 1e-5f)
        assertEquals(-0.158808f, ArtistAdapter.gelu(-1f), 1e-5f)
        assertEquals(0f, ArtistAdapter.gelu(0f))
    }

    @Test
    fun `the residual layers map a text vector to a unit descriptor`() {
        // W1 = W2 = identity, biases zero: out = normalize(x + gelu(x)).
        val adapter = assertNotNull(ArtistAdapter.parse(identityAdapter()))
        val out = assertNotNull(adapter.descriptor(floatArrayOf(1f, -1f)))
        val a = 1f + ArtistAdapter.gelu(1f)
        val b = -1f + ArtistAdapter.gelu(-1f)
        val norm = sqrt(a * a + b * b)
        assertEquals(a / norm, out[0], 1e-4f)
        assertEquals(b / norm, out[1], 1e-4f)
        assertNull(adapter.descriptor(floatArrayOf(1f, 0f, 0f)), "another input width is not this adapter's")
    }

    @Test
    fun `corrupt or truncated or newer assets are rejected`() {
        val good = identityAdapter()
        assertNull(ArtistAdapter.parse(good.copyOf(good.size - 1)))
        assertNull(ArtistAdapter.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(ArtistAdapter.parse(good.copyOf().also { it[8] = 2 }))
    }

    private fun identityAdapter(): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(value: Int) { out += (value and 0xff).toByte(); out += (value shr 8).toByte() }
        "LJADPT1\u0000".encodeToByteArray().forEach { out += it }
        u16(1); u16(0); u16(2); u16(2)
        listOf(0x3C00, 0, 0, 0x3C00).forEach(::u16) // W1 = I
        repeat(2) { u16(0) } // b1
        listOf(0x3C00, 0, 0, 0x3C00).forEach(::u16) // W2 = I
        repeat(2) { u16(0) } // b2
        return out.toByteArray()
    }
}

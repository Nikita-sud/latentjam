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

    @Test
    fun `CQ weights rebuild through the Hadamard rotation`() {
        // Every W1 code is the top level 3 at length 1, so each row rebuilds as [3 sqrt 32, 0, …]; W2 at
        // length 1/32 likewise. For x = e0 every output gains the same v = 3 sqrt 32 / 32 · gelu(3 sqrt 32).
        val adapter = assertNotNull(ArtistAdapter.parse(cqAdapter()))
        val x = FloatArray(32).also { it[0] = 1f }
        val c = 3f * sqrt(32f)
        val v = c / 32f * ArtistAdapter.gelu(c)
        val norm = sqrt((1f + v) * (1f + v) + 31f * v * v)
        val out = assertNotNull(adapter.descriptor(x))
        assertEquals((1f + v) / norm, out[0], 1e-4f)
        for (k in 1 until 32) assertEquals(v / norm, out[k], 1e-4f)
    }

    @Test
    fun `corrupt CQ adapters are rejected`() {
        val good = cqAdapter()
        assertNotNull(ArtistAdapter.parse(good))
        assertNull(ArtistAdapter.parse(good.copyOf(good.size - 1)))
        assertNull(ArtistAdapter.parse(good.copyOf().also { it[16] = 3 }), "3-bit codes do not pack into bytes evenly")
        assertNull(ArtistAdapter.parse(good.copyOf().also { it[17] = 24 }), "the group must be a power of two")
        assertNull(ArtistAdapter.parse(good.copyOf().also { it[18] = 5 }), "the level count must be 2^bits")
    }

    /** 32 x 32 in both layers, 2-bit codes in one group per row, levels -3 -1 1 3. */
    private fun cqAdapter(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(value: Int) { out += value.toByte() }
        fun u16(value: Int) { u8(value and 0xff); u8(value shr 8) }
        fun f32(value: Float) { val bits = value.toRawBits(); u16(bits and 0xffff); u16(bits ushr 16) }
        "LJADPT1\u0000".encodeToByteArray().forEach { out += it }
        u16(2); u16(0); u16(32); u16(32); u8(2); u8(32); u16(4)
        listOf(-3f, -1f, 1f, 3f).forEach(::f32)
        for (length in listOf(0x3C00, 0x2800)) { // W1 at length 1, W2 at length 1/32
            repeat(32 * 32 * 2 / 8) { u8(0xff) } // every code 3
            repeat(32) { u16(length) }
            repeat(32) { u16(0) } // bias
        }
        return out.toByteArray()
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

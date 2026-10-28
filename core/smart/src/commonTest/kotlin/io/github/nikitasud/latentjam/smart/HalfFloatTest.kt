/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The half codec the audio vectors are stored in, and its equivalence fixture (the same bits on the JVM and iOS). */
class HalfFloatTest {

    private fun bits(value: Float) = HalfFloat.toHalf(value).toInt() and 0xffff

    @Test
    fun knownValues() {
        assertEquals(0x3c00, bits(1f))
        assertEquals(0xbc00, bits(-1f))
        assertEquals(0x0000, bits(0f))
        assertEquals(0x8000, bits(-0f))
        assertEquals(0x7bff, bits(65504f))
        assertEquals(0x7bff, bits(1e9f), "beyond the range saturates")
        assertEquals(0x7bff, bits(Float.POSITIVE_INFINITY))
        assertEquals(0x0001, bits(5.9604645e-8f), "the smallest subnormal")
        assertEquals(0x0000, bits(2.9802322e-8f), "half the smallest subnormal ties to even (zero)")
        assertEquals(0x0400, bits(6.1035156e-5f), "the smallest normal")
        assertEquals(0x2e66, bits(0.1f))
        assertEquals(0x3555, bits(1f / 3f))
        // 1 + 2^-11 lies halfway between 1 and the next half: ties to even (1); 1 + 3 * 2^-11 rounds up to even.
        assertEquals(0x3c00, bits(1f + 1f / 2048f))
        assertEquals(0x3c02, bits(1f + 3f / 2048f))
        assertTrue(HalfFloat.toFloat(HalfFloat.toHalf(Float.NaN)).isNaN())
    }

    @Test
    fun everyHalfRoundTripsExactly() {
        for (h in 0 until 0x10000) {
            val value = HalfFloat.toFloat(h.toShort())
            if (value.isNaN()) continue
            val back = bits(value)
            // Infinity saturates to the largest finite half; every finite half comes back as itself.
            if (value.isInfinite()) assertEquals(if (h and 0x8000 != 0) 0xfbff else 0x7bff, back) else assertEquals(h, back, "half $h")
        }
    }

    @Test
    fun roundingIsNearest() {
        val random = Random(1)
        repeat(100_000) {
            val v = (random.nextFloat() * 2f - 1f) * if (random.nextBoolean()) 1f else 1e-3f
            val r = HalfFloat.round(v)
            val h = bits(v)
            // No other half is nearer.
            for (n in listOf(h - 1, h + 1)) {
                val other = HalfFloat.toFloat(n.toShort())
                if (!other.isNaN()) assertTrue(kotlin.math.abs(other - v) >= kotlin.math.abs(r - v), "$v: $r vs $other")
            }
        }
    }

    @Test
    fun aHalfStoreReadsBackTheRoundedFloats() {
        val store = HalfRowStore(4)
        val row = store.append(floatArrayOf(0.1f, -0.25f, 1f / 3f, 0f))
        val out = FloatArray(4)
        store.row(row).copyInto(out)
        assertEquals(listOf(HalfFloat.round(0.1f), -0.25f, HalfFloat.round(1f / 3f), 0f), out.toList())
        assertEquals(HalfFloat.round(0.1f), store.row(row)[0])
    }

    /**
     * Equivalence fixture: 200 seeded unit 960-d vectors, rounded to halves, hashed bit by bit, and a cosine computed
     * from the widened floats. The same numbers on the JVM and on iOS (this test runs on both).
     */
    @Test
    fun equivalenceFixture() {
        val random = Random(20261028)
        var hash = -0x340d631b7bdddcdbL
        val vectors = Array(200) {
            val v = FloatArray(960) { (random.nextDouble() * 2 - 1).toFloat() }
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(960) { d -> v[d] / norm }
        }
        for (v in vectors) for (x in v) hash = (hash xor bits(x).toLong()) * 0x100000001b3L
        var dot = 0f
        val a = vectors[0].map(HalfFloat::round)
        val b = vectors[1].map(HalfFloat::round)
        for (d in 0 until 960) dot += a[d] * b[d]
        assertEquals(FIXTURE_HASH, hash, "half bits of the fixture")
        assertEquals(FIXTURE_DOT, dot, "a float32 dot of widened halves")
    }

    private companion object {
        const val FIXTURE_HASH = -1088617212811394025L
        const val FIXTURE_DOT = -0.0054490063f
    }
}

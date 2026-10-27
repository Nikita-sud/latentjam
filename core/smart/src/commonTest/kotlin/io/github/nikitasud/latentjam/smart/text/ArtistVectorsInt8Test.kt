/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAN_SIZE's smaller layout: LJVECS1 element width 1 (int8 + a float32 scale per vector). */
class ArtistVectorsInt8Test {
    private class Out {
        val bytes = ArrayList<Byte>()
        fun u8(v: Int) { bytes.add(v.toByte()) }
        fun u16(v: Int) { u8(v and 0xff); u8((v ushr 8) and 0xff) }
        fun u32(v: Long) { for (b in 0 until 4) u8(((v ushr (8 * b)) and 0xff).toInt()) }
        fun le(v: Long, n: Int) { for (b in 0 until n) u8(((v ushr (8 * b)) and 0xff).toInt()) }
    }

    private val values = mapOf(3 to floatArrayOf(0.5f, -0.25f), 700 to floatArrayOf(-1f, 0.125f))

    private fun vectors(width: Int): ByteArray = Out().run {
        "LJVECS1\u0000".encodeToByteArray().forEach(bytes::add)
        u32(1); u32(1200); u32(values.size.toLong()); u16(2); u16(width)
        val bits = LongArray((1200 + 63) / 64)
        for (e in values.keys) bits[e ushr 6] = bits[e ushr 6] or (1L shl (e and 63))
        for (w in bits) le(w, 8)
        for (block in 0 until (1200 + 511) / 512 + 1) u32(values.keys.count { it < block * 512 }.toLong())
        val sorted = values.entries.sortedBy { it.key }.map { it.value }
        if (width == 1) {
            val scales = sorted.map { v -> v.maxOf { abs(it) } / 127f }
            for ((v, s) in sorted.zip(scales)) for (x in v) u8(kotlin.math.round(x / s).toInt())
            for (s in scales) u32(s.toRawBits().toLong() and 0xffffffffL)
        } else {
            for (v in sorted) for (x in v) u32(x.toRawBits().toLong() and 0xffffffffL)
        }
        bytes.toByteArray()
    }

    @Test
    fun int8VectorsReadBackAndFloat32StillDoes() {
        for (width in listOf(1, 4)) {
            val table = assertNotNull(ArtistVectors.parse(vectors(width)), "width $width")
            for ((e, want) in values) {
                val got = assertNotNull(table.vector(e))
                for (i in want.indices) assertTrue(abs(got[i] - want[i]) <= 0.5f * abs(want.maxOf { abs(it) }) / 127f, "width $width e $e")
            }
            assertNull(table.vector(4))
            val good = vectors(width)
            assertNull(ArtistVectors.parse(good.copyOf(good.size - 1)), "a truncated width-$width table is refused")
        }
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * IEEE 754 binary16 ("half"), the storage of SMART's audio vectors: a unit 960-d vector's components keep about three
 * significant digits, which moves a cosine by ~1e-4, while the vectors take half the memory and half the file. Every
 * computation stays in float32: a half is only ever widened to the float it represents exactly.
 *
 * Conversion rounds to nearest, ties to even; values beyond the half range saturate to ±65504 (a unit vector never
 * gets near it) and NaN stays NaN. The same code on every platform gives the same bits (the equivalence fixture).
 */
public object HalfFloat {
    /** [value] rounded to the nearest half, as its 16 bits. */
    public fun toHalf(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xff
        val mantissa = bits and 0x7fffff
        if (exponent == 0xff) {
            // Infinity saturates; NaN keeps a quiet NaN.
            return (sign or if (mantissa != 0) 0x7e00 else 0x7bff).toShort()
        }
        val e = exponent - 127 + 15
        if (e >= 0x1f) return (sign or 0x7bff).toShort()
        if (e <= 0) {
            // A subnormal half (or zero): shift the full significand into place, rounding to nearest even.
            if (e < -10) return sign.toShort()
            val significand = mantissa or 0x800000
            val shift = 14 - e
            val half = significand ushr shift
            val remainder = significand and ((1 shl shift) - 1)
            val midpoint = 1 shl (shift - 1)
            val rounded = if (remainder > midpoint || (remainder == midpoint && (half and 1) == 1)) half + 1 else half
            return (sign or rounded).toShort()
        }
        val half = (e shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1fff
        // A carry out of the mantissa raises the exponent, which is the correct result (and saturates at the top).
        val rounded = if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) == 1)) half + 1 else half
        return (sign or minOf(rounded, 0x7bff)).toShort()
    }

    /** The float [half] represents, exactly. */
    public fun toFloat(half: Short): Float {
        val h = half.toInt() and 0xffff
        val sign = (h and 0x8000) shl 16
        val exponent = (h ushr 10) and 0x1f
        val mantissa = h and 0x3ff
        return when (exponent) {
            0 -> {
                if (mantissa == 0) return Float.fromBits(sign)
                // Subnormal: normalise it.
                var m = mantissa
                var e = -14
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                Float.fromBits(sign or ((e + 127) shl 23) or ((m and 0x3ff) shl 13))
            }
            0x1f -> Float.fromBits(sign or 0x7f800000 or (mantissa shl 13))
            else -> Float.fromBits(sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13))
        }
    }

    /** [value] as the nearest half widened back: what a half store keeps of it. */
    public fun round(value: Float): Float = toFloat(toHalf(value))
}

/** A heap [RowStore] of halves: half the memory of [HeapRowStore]; rows read back the rounded floats. */
public class HalfRowStore(override val dim: Int) : RowStore {
    private val rows = ArrayList<ShortArray?>()

    override val rowCount: Int get() = rows.size

    override fun append(values: FloatArray): Int {
        require(values.size == dim) { "Row has dimension ${values.size}, expected $dim" }
        rows += ShortArray(dim) { HalfFloat.toHalf(values[it]) }
        return rows.lastIndex
    }

    override fun release(row: Int) {
        rows[row] = null
    }

    override fun row(row: Int): StoredVectorRow = HalfVectorRow(checkNotNull(rows[row]) { "row $row was released" })

    override fun compact(rows: IntArray) {
        val kept = ArrayList<ShortArray?>(rows.size)
        for (r in rows) kept += this.rows[r]
        this.rows.clear()
        this.rows.addAll(kept)
    }

    override fun clear() {
        rows.clear()
    }
}

/** One row of halves, widened on read. */
public class HalfVectorRow(private val halves: ShortArray) : StoredVectorRow {
    override val size: Int get() = halves.size
    override fun get(d: Int): Float = HalfFloat.toFloat(halves[d])
    override fun copyInto(out: FloatArray, offset: Int) {
        for (d in halves.indices) out[offset + d] = HalfFloat.toFloat(halves[d])
    }
}

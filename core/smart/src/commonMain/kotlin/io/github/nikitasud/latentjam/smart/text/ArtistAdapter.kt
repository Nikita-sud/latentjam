/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Guesses the knowledge-pack descriptor of an artist the pack does not cover, from the track's own
 * trusted-text vector: `normalize(x + W2 · gelu(W1 · x + b1) + b2)`. Two dense layers, so it needs
 * no inference runtime. It runs once per uncovered track, when the snapshot is built.
 *
 * Layout, little endian: magic `LJADPT1\0`, u32 version, u16 input width, u16 hidden width, then FP16
 * `W1 [hidden, input]`, `b1 [hidden]`, `W2 [input, hidden]`, `b2 [input]`. GELU is the tanh form, and
 * training must use the same form.
 *
 * Version 2 keeps both matrices as CQ codes (tools/research/compact_artist_adapter.py), a quarter of the
 * bytes at 4 bits: after the widths, u8 bits, u8 group size, u16 level count and the levels as f32; then
 * per matrix its codes, packed from each byte's low bits up, and one fp16 length per group of a row,
 * each followed by its fp16 bias. A group of weights is `(levels[codes] * length) · H`, H the
 * normalised Walsh-Hadamard matrix of the group size; since H is its own inverse, the adapter keeps the
 * matrices as `levels[codes] * length` and rotates its inputs instead, a few butterflies per call. It keeps them
 * packed, too: each weight is that product, formed when it is used (2.4 MB of floats would otherwise sit on the heap).
 */
public class ArtistAdapter private constructor(
    public val width: Int,
    private val hidden: Int,
    private val w1: Weights,
    private val b1: FloatArray,
    private val w2: Weights,
    private val b2: FloatArray,
    /** Version 2: the Hadamard group size the matrices are stored rotated by; 0 for plain weights. */
    private val rotation: Int = 0,
) {
    /** The guessed descriptor, unit length; null when [text] is not this adapter's input width. */
    public fun descriptor(text: FloatArray): FloatArray? {
        if (text.size != width) return null
        val x = if (rotation > 0) rotate(text.copyOf(), rotation) else text
        val h = FloatArray(hidden) { j -> gelu(w1.accumulate(j, x, b1[j])) }
        val hr = if (rotation > 0) rotate(h.copyOf(), rotation) else h
        val out = FloatArray(width) { i -> w2.accumulate(i, hr, b2[i] + text[i]) }
        var norm = 0.0
        for (value in out) norm += value.toDouble() * value
        if (norm <= 0.0) return null
        val scale = (1.0 / sqrt(norm)).toFloat()
        for (i in out.indices) out[i] *= scale
        return out
    }

    /** A weight matrix [rows, inputs]: `start + Σ w[row][i] · x[i]`, i ascending. */
    private interface Weights {
        fun accumulate(row: Int, x: FloatArray, start: Float): Float
    }

    private class DenseWeights(private val w: FloatArray, private val inputs: Int) : Weights {
        override fun accumulate(row: Int, x: FloatArray, start: Float): Float {
            var sum = start
            val base = row * inputs
            for (i in 0 until inputs) sum += w[base + i] * x[i]
            return sum
        }
    }

    /**
     * CQ codes as stored: weight `[row][i]` is `levels[code] * length` of its group, the float the eager decode
     * stored, so every sum is the same.
     */
    private class PackedWeights(
        private val inputs: Int,
        private val bits: Int,
        private val group: Int,
        private val levels: FloatArray,
        private val codes: ByteArray,
        private val lengths: FloatArray,
    ) : Weights {
        private val groups = inputs / group
        private val perByte = 8 / bits
        private val mask = (1 shl bits) - 1

        override fun accumulate(row: Int, x: FloatArray, start: Float): Float {
            var sum = start
            val scaled = FloatArray(levels.size)
            for (g in 0 until groups) {
                val length = lengths[row * groups + g]
                for (level in levels.indices) scaled[level] = levels[level] * length
                val k = row * inputs + g * group
                var i = g * group
                val stop = i + group
                if (bits == 4) {
                    var at = k / 2
                    while (i < stop) {
                        val byte = codes[at++].toInt()
                        sum += scaled[byte and 15] * x[i++]
                        sum += scaled[(byte ushr 4) and 15] * x[i++]
                    }
                } else {
                    var at = k / perByte
                    while (i < stop) {
                        var byte = codes[at++].toInt() and 0xff
                        for (slot in 0 until perByte) {
                            sum += scaled[byte and mask] * x[i++]
                            byte = byte ushr bits
                        }
                    }
                }
            }
            return sum
        }
    }

    public companion object {
        private val MAGIC = "LJADPT1\u0000".encodeToByteArray()
        private const val VERSION = 1
        private const val VERSION_CQ = 2
        private const val HEADER_SIZE = 16
        private const val SQRT_2_OVER_PI = 0.7978845608f

        private fun parseCq(bytes: ByteArray): ArtistAdapter? {
            if (bytes.size < HEADER_SIZE + 4) return null
            fun u8(offset: Int): Int = bytes[offset].toInt() and 0xff
            fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
            val width = u16(12)
            val hidden = u16(14)
            val bits = u8(16)
            val group = u8(17)
            val levelCount = u16(18)
            if (width == 0 || hidden == 0 || bits !in setOf(2, 4, 8) || levelCount != 1 shl bits) return null
            if (group == 0 || group and (group - 1) != 0 || width % group != 0 || hidden % group != 0) return null
            var offset = HEADER_SIZE + 4
            if (bytes.size < offset + levelCount * 4) return null
            val levels = FloatArray(levelCount) { index ->
                val at = offset + index * 4
                Float.fromBits(u16(at) or (u16(at + 2) shl 16))
            }
            offset += levelCount * 4
            if (levels.any { !it.isFinite() }) return null

            /** One CQ matrix [rows, inputs] and its fp16 bias [rows]; null when the file is too short. */
            fun matrix(rows: Int, inputs: Int): Pair<Weights, FloatArray>? {
                val codeBytes = (rows * inputs * bits + 7) / 8
                val groups = inputs / group
                val end = offset + codeBytes + rows * groups * 2 + rows * 2
                if (end > bytes.size) return null
                val codesAt = offset
                val lengthsAt = codesAt + codeBytes
                val lengths = FloatArray(rows * groups) { ArtistKnowledgePack.halfToFloat(u16(lengthsAt + it * 2)) }
                if (lengths.any { !it.isFinite() }) return null
                val codes = bytes.copyOfRange(codesAt, lengthsAt)
                val biasAt = lengthsAt + rows * groups * 2
                val bias = FloatArray(rows) { ArtistKnowledgePack.halfToFloat(u16(biasAt + 2 * it)) }
                offset = end
                return PackedWeights(inputs, bits, group, levels, codes, lengths) to bias
            }
            val (w1, b1) = matrix(hidden, width) ?: return null
            val (w2, b2) = matrix(width, hidden) ?: return null
            if (offset != bytes.size) return null // levels and lengths are finite, so every weight is
            return ArtistAdapter(width, hidden, w1, b1, w2, b2, rotation = group)
        }

        /** Each run of [group] values times the normalised Sylvester-Hadamard matrix, in place. */
        private fun rotate(values: FloatArray, group: Int): FloatArray {
            val scale = 1f / sqrt(group.toFloat())
            var base = 0
            while (base < values.size) {
                var span = 1
                while (span < group) {
                    var start = base
                    while (start < base + group) {
                        for (k in start until start + span) {
                            val a = values[k]
                            val b = values[k + span]
                            values[k] = a + b
                            values[k + span] = a - b
                        }
                        start += 2 * span
                    }
                    span *= 2
                }
                for (k in base until base + group) values[k] *= scale
                base += group
            }
            return values
        }

        internal fun gelu(x: Float): Float = 0.5f * x * (1f + tanh(SQRT_2_OVER_PI * (x + 0.044715f * x * x * x)))

        /** Returns null for a missing, corrupt or newer-format asset; uncovered artists then stay without. */
        public fun parse(bytes: ByteArray): ArtistAdapter? {
            if (bytes.size < HEADER_SIZE || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun u16(offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            val version = u16(8).toLong() or (u16(10).toLong() shl 16)
            if (version == VERSION_CQ.toLong()) return parseCq(bytes)
            if (version != VERSION.toLong()) return null
            val width = u16(12)
            val hidden = u16(14)
            if (width == 0 || hidden == 0) return null
            val sizes = intArrayOf(hidden * width, hidden, width * hidden, width)
            if (HEADER_SIZE + 2L * sizes.sum() != bytes.size.toLong()) return null
            var offset = HEADER_SIZE
            val tensors = sizes.map { size ->
                FloatArray(size) { ArtistKnowledgePack.halfToFloat(u16(offset + 2 * it)) }.also { offset += 2 * size }
            }
            return ArtistAdapter(
                width, hidden,
                DenseWeights(tensors[0], width), tensors[1],
                DenseWeights(tensors[2], hidden), tensors[3],
            )
        }
    }
}

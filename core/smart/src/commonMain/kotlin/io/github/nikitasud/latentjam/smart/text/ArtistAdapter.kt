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
 */
public class ArtistAdapter private constructor(
    public val width: Int,
    private val hidden: Int,
    private val w1: FloatArray,
    private val b1: FloatArray,
    private val w2: FloatArray,
    private val b2: FloatArray,
) {
    /** The guessed descriptor, unit length; null when [text] is not this adapter's input width. */
    public fun descriptor(text: FloatArray): FloatArray? {
        if (text.size != width) return null
        val h = FloatArray(hidden) { j ->
            var sum = b1[j]
            val row = j * width
            for (i in 0 until width) sum += w1[row + i] * text[i]
            gelu(sum)
        }
        val out = FloatArray(width) { i ->
            var sum = b2[i] + text[i]
            val row = i * hidden
            for (j in 0 until hidden) sum += w2[row + j] * h[j]
            sum
        }
        var norm = 0.0
        for (value in out) norm += value.toDouble() * value
        if (norm <= 0.0) return null
        val scale = (1.0 / sqrt(norm)).toFloat()
        for (i in out.indices) out[i] *= scale
        return out
    }

    public companion object {
        private val MAGIC = "LJADPT1\u0000".encodeToByteArray()
        private const val VERSION = 1
        private const val HEADER_SIZE = 16
        private const val SQRT_2_OVER_PI = 0.7978845608f

        internal fun gelu(x: Float): Float = 0.5f * x * (1f + tanh(SQRT_2_OVER_PI * (x + 0.044715f * x * x * x)))

        /** Returns null for a missing, corrupt or newer-format asset; uncovered artists then stay without. */
        public fun parse(bytes: ByteArray): ArtistAdapter? {
            if (bytes.size < HEADER_SIZE || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun u16(offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            if ((u16(8).toLong() or (u16(10).toLong() shl 16)) != VERSION.toLong()) return null
            val width = u16(12)
            val hidden = u16(14)
            if (width == 0 || hidden == 0) return null
            val sizes = intArrayOf(hidden * width, hidden, width * hidden, width)
            if (HEADER_SIZE + 2L * sizes.sum() != bytes.size.toLong()) return null
            var offset = HEADER_SIZE
            val tensors = sizes.map { size ->
                FloatArray(size) { ArtistKnowledgePack.halfToFloat(u16(offset + 2 * it)) }.also { offset += 2 * size }
            }
            return ArtistAdapter(width, hidden, tensors[0], tensors[1], tensors[2], tensors[3])
        }
    }
}

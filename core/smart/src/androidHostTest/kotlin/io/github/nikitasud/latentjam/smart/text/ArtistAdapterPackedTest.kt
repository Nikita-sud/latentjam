/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The shipped adapter keeps its CQ weights packed and forms each weight when it multiplies. Its outputs must be the
 * eager decode's bit for bit: the reference below is that decode and forward pass, kept verbatim.
 */
class ArtistAdapterPackedTest {

    private val asset = File("../../androidApp/src/main/assets/ml/artist_adapter.bin")

    @Test
    fun packedWeightsGiveTheEagerDecodesFloats() {
        val bytes = asset.readBytes()
        val adapter = assertNotNull(ArtistAdapter.parse(bytes))
        val reference = assertNotNull(EagerReference.parse(bytes))
        val random = Random(20261020)
        repeat(200) { case ->
            val x = FloatArray(adapter.width) { random.nextFloat() - 0.5f }
            var norm = 0.0
            for (v in x) norm += v.toDouble() * v
            for (i in x.indices) x[i] = (x[i] / sqrt(norm)).toFloat()
            val expected = assertNotNull(reference.descriptor(x)).map(Float::toRawBits)
            val actual = assertNotNull(adapter.descriptor(x)).map(Float::toRawBits)
            assertEquals(expected, actual, "case $case")
        }
    }

    /** ArtistAdapter's version-2 parse and descriptor() as they were before the weights stayed packed. */
    internal class EagerReference(
        val width: Int,
        val hidden: Int,
        val w1: FloatArray,
        val b1: FloatArray,
        val w2: FloatArray,
        val b2: FloatArray,
        val rotation: Int,
    ) {
        fun descriptor(text: FloatArray): FloatArray? {
            if (text.size != width) return null
            val x = if (rotation > 0) rotate(text.copyOf(), rotation) else text
            val h = FloatArray(hidden) { j ->
                var sum = b1[j]
                val row = j * width
                for (i in 0 until width) sum += w1[row + i] * x[i]
                ArtistAdapter.gelu(sum)
            }
            val hr = if (rotation > 0) rotate(h.copyOf(), rotation) else h
            val out = FloatArray(width) { i ->
                var sum = b2[i] + text[i]
                val row = i * hidden
                for (j in 0 until hidden) sum += w2[row + j] * hr[j]
                sum
            }
            var norm = 0.0
            for (value in out) norm += value.toDouble() * value
            if (norm <= 0.0) return null
            val scale = (1.0 / sqrt(norm)).toFloat()
            for (i in out.indices) out[i] *= scale
            return out
        }

        companion object {
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

            fun parse(bytes: ByteArray): EagerReference? {
                fun u8(offset: Int): Int = bytes[offset].toInt() and 0xff
                fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
                if (u16(8) != 2) return null
                val width = u16(12)
                val hidden = u16(14)
                val bits = u8(16)
                val group = u8(17)
                val levelCount = u16(18)
                var offset = 16 + 4
                val levels = FloatArray(levelCount) { index ->
                    val at = offset + index * 4
                    Float.fromBits(u16(at) or (u16(at + 2) shl 16))
                }
                offset += levelCount * 4
                fun matrix(rows: Int, inputs: Int): Pair<FloatArray, FloatArray> {
                    val codeBytes = (rows * inputs * bits + 7) / 8
                    val groups = inputs / group
                    val end = offset + codeBytes + rows * groups * 2 + rows * 2
                    val codesAt = offset
                    val lengthsAt = codesAt + codeBytes
                    val out = FloatArray(rows * inputs)
                    val mask = (1 shl bits) - 1
                    val perByte = 8 / bits
                    val scaled = FloatArray(levelCount)
                    for (row in 0 until rows) {
                        for (g in 0 until groups) {
                            val length = ArtistKnowledgePack.halfToFloat(u16(lengthsAt + (row * groups + g) * 2))
                            for (level in 0 until levelCount) scaled[level] = levels[level] * length
                            var at = codesAt + (row * inputs + g * group) / perByte
                            var k = row * inputs + g * group
                            val stop = k + group
                            if (bits == 4) {
                                while (k < stop) {
                                    val byte = bytes[at++].toInt()
                                    out[k++] = scaled[byte and 15]
                                    out[k++] = scaled[(byte ushr 4) and 15]
                                }
                            } else {
                                while (k < stop) {
                                    var byte = bytes[at++].toInt() and 0xff
                                    for (slot in 0 until perByte) {
                                        out[k++] = scaled[byte and mask]
                                        byte = byte ushr bits
                                    }
                                }
                            }
                        }
                    }
                    val biasAt = lengthsAt + rows * groups * 2
                    val bias = FloatArray(rows) { ArtistKnowledgePack.halfToFloat(u16(biasAt + 2 * it)) }
                    offset = end
                    return out to bias
                }
                val (w1, b1) = matrix(hidden, width)
                val (w2, b2) = matrix(width, hidden)
                return EagerReference(width, hidden, w1, b1, w2, b2, group)
            }
        }
    }
}

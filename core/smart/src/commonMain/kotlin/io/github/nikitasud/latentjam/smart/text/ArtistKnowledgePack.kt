/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.math.sqrt

/**
 * Read-only artist knowledge pack: for each entity of [MusicEntityIndex], a product-quantized
 * descriptor of what a teacher model knows about that artist, generated offline.
 *
 * Layout, little endian:
 * - magic `LJKNOW1\0`, u32 version, u16 dimension, u8 sub-spaces M, u16 centroids K, u32 entity count
 * - FP16 codebooks `[M, K, dimension / M]`
 * - one fixed-size record per dense entity id: M code bytes, a u16 fingerprint of the entity's own
 *   normalized name, two language bytes and a confidence byte (1/255 units)
 *
 * There is no id list: the entity index's dense popularity-ranked ids are the key space.
 */
public class ArtistKnowledgePack private constructor(
    private val bytes: ByteArray,
    public val dimension: Int,
    private val subspaces: Int,
    private val centroids: Int,
    private val entityCount: Int,
    private val codebooks: FloatArray,
    private val recordsOffset: Int,
) {
    private val recordSize = subspaces + RECORD_TAIL

    /**
     * The unit-length descriptor for a library [artist] whose name resolved to [entityIds].
     *
     * The ids come sorted, as [MusicEntityIndex.resolve] returns them, so the first is the most
     * popular. Among confident entities, the one whose own name matches [artist] wins over a group it
     * belongs to or a token match. Otherwise the most popular confident entity is used. Null when
     * none is confident.
     */
    public fun descriptor(entityIds: IntArray, artist: String): FloatArray? {
        val confident = entityIds.filter { it in 0 until entityCount && confidence(it) >= MIN_CONFIDENCE }
        if (confident.isEmpty()) return null
        val own = fingerprint(artist)
        return decode(confident.firstOrNull { fingerprintOf(it) == own } ?: confident.first())
    }

    private fun record(entity: Int): Int = recordsOffset + entity * recordSize

    private fun confidence(entity: Int): Int = bytes[record(entity) + subspaces + 4].toInt() and 0xff

    private fun fingerprintOf(entity: Int): Int {
        val at = record(entity) + subspaces
        return (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
    }

    private fun decode(entity: Int): FloatArray {
        val width = dimension / subspaces
        val out = FloatArray(dimension)
        val at = record(entity)
        for (j in 0 until subspaces) {
            val start = (j * centroids + (bytes[at + j].toInt() and 0xff)) * width
            codebooks.copyInto(out, j * width, start, start + width)
        }
        var norm = 0.0
        for (value in out) norm += value.toDouble() * value
        val scale = if (norm > 0.0) (1.0 / sqrt(norm)).toFloat() else 0f
        for (index in out.indices) out[index] *= scale
        return out
    }

    public companion object {
        private val MAGIC = "LJKNOW1\u0000".encodeToByteArray()
        private const val VERSION = 1
        private const val HEADER_SIZE = 21
        private const val RECORD_TAIL = 5 // fingerprint (2), two language bytes, confidence
        private const val MIN_CONFIDENCE = 128 // 0.5 in 1/255 units

        /** The 16-bit fingerprint of a normalized artist name that each record carries. */
        internal fun fingerprint(artist: String): Int =
            (MusicEntityIndex.fnv1a64(MusicEntityIndex.normalize(artist).encodeToByteArray()) and 0xffffuL).toInt()

        /** Returns null for a missing, corrupt or newer-format asset, so SMART runs without knowledge. */
        public fun parse(bytes: ByteArray): ArtistKnowledgePack? {
            if (bytes.size < HEADER_SIZE || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun u8(offset: Int): Int = bytes[offset].toInt() and 0xff
            fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
            fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
            if (u32(8) != VERSION.toLong()) return null
            val dimension = u16(12)
            val subspaces = u8(14)
            val centroids = u16(15)
            val entities = u32(17)
            if (dimension == 0 || subspaces == 0 || dimension % subspaces != 0 || centroids !in 1..256) return null
            val bookValues = subspaces.toLong() * centroids * (dimension / subspaces)
            val recordsOffset = HEADER_SIZE + bookValues * 2
            if (recordsOffset + entities * (subspaces + RECORD_TAIL) != bytes.size.toLong()) return null
            val books = FloatArray(bookValues.toInt()) { index -> halfToFloat(u16(HEADER_SIZE + 2 * index)) }
            return ArtistKnowledgePack(
                bytes, dimension, subspaces, centroids, entities.toInt(), books, recordsOffset.toInt(),
            )
        }

        /** IEEE 754 binary16 to binary32. */
        internal fun halfToFloat(bits: Int): Float {
            val exponent = (bits shr 10) and 0x1f
            val mantissa = bits and 0x3ff
            val magnitude = when (exponent) {
                0 -> mantissa / 1024f / 16384f // subnormal: mantissa * 2^-24
                0x1f -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + mantissa / 1024f) * Float.fromBits((exponent - 15 + 127) shl 23)
            }
            return if ((bits shr 15) and 1 == 1) -magnitude else magnitude
        }
    }
}

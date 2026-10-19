/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * One unit vector per known artist (an entity of the music entity index): the SMART ranker's LightFM artist
 * embeddings, learned offline from public listening histories (ListenBrainz, CC0); the device only reads them.
 * tools/research/ranker/pack_lfm.py writes the asset.
 *
 * Layout (little-endian), read in place:
 * ```
 * "LJVECS1\0" | u32 version | u32 entity id space | u32 vectors | u16 dimension | u16 element width (4 = f32)
 * presence: one bit per entity id, u64 words | running counts: u32 per 512 ids, plus the total
 * vectors: dimension float32 each, in entity order
 * ```
 */
public class ArtistVectors private constructor(
    private val bytes: AssetBytes,
    private val entityCount: Int,
    public val dimension: Int,
    private val ranks: IntArray,
    private val vectorsOffset: Int,
) {
    /** Whether [entity] has a vector. */
    public fun has(entity: Int): Boolean = entity in 0 until entityCount && present(entity)

    /** [entity]'s vector, or null. */
    public fun vector(entity: Int): FloatArray? {
        if (!has(entity)) return null
        val at = vectorsOffset + rank(entity) * dimension * 4
        return FloatArray(dimension) { Float.fromBits(le32(at + it * 4)) }
    }

    private fun u8(at: Int): Int = bytes[at].toInt() and 0xff

    private fun le32(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

    private fun word(index: Int): Long {
        val at = HEADER + index * 8
        return (le32(at).toLong() and 0xffffffffL) or (le32(at + 4).toLong() shl 32)
    }

    private fun present(entity: Int): Boolean = (word(entity ushr 6) ushr (entity and 63)) and 1L == 1L

    private fun rank(entity: Int): Int {
        var count = ranks[entity ushr 9]
        for (index in (entity ushr 9) * 8 until (entity ushr 6)) count += word(index).countOneBits()
        val below = entity and 63
        if (below > 0) count += (word(entity ushr 6) and ((1L shl below) - 1)).countOneBits()
        return count
    }

    public companion object {
        private val MAGIC = "LJVECS1\u0000".encodeToByteArray()
        private const val VERSION = 1L
        private const val HEADER = 24
        private const val WIDTH = 4

        public fun parse(bytes: ByteArray): ArtistVectors? = parse(AssetBytes.of(bytes))

        /** Null for a missing, damaged or newer-format asset, so the ranker runs without the feature. */
        public fun parse(bytes: AssetBytes): ArtistVectors? = parse(bytes, MAGIC)

        /** The same layout under another magic (LJPOPS1, [ArtistPopularity]). */
        internal fun parse(bytes: AssetBytes, magic: ByteArray): ArtistVectors? {
            if (bytes.size < HEADER || !magic.indices.all { bytes[it] == magic[it] }) return null
            fun u8(at: Int): Int = bytes[at].toInt() and 0xff
            fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
            fun u32(at: Int): Long = (u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)).toLong() or (u8(at + 3).toLong() shl 24)
            if (u32(8) != VERSION || u16(22) != WIDTH) return null
            val entities = u32(12)
            val count = u32(16)
            val dimension = u16(20)
            if (entities > (1 shl 24) || count > entities || dimension == 0) return null
            val words = ((entities + 63) / 64).toInt()
            val blocks = ((entities + 511) / 512).toInt() + 1
            val ranksAt = HEADER + words * 8
            val vectorsAt = ranksAt + blocks * 4
            if (vectorsAt.toLong() + count * dimension * WIDTH != bytes.size.toLong()) return null
            val ranks = IntArray(blocks) { u32(ranksAt + it * 4).toInt() }
            var counted = 0L
            for (word in 0 until words) {
                if (word and 7 == 0 && ranks[word ushr 3].toLong() != counted) return null
                for (b in 0 until 8) counted += u8(HEADER + word * 8 + b).countOneBits()
            }
            if (counted != count || ranks[blocks - 1].toLong() != counted) return null
            return ArtistVectors(bytes, entities.toInt(), dimension, ranks, vectorsAt)
        }
    }
}

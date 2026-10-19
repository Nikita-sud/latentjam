/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * Which artists the same people listen to: for each known artist (an entity of the music entity
 * index), its nearest artists by co-listening, best first, with their similarity. Counted offline
 * from public listening histories; the device only reads it.
 *
 * Layout (little-endian), read in place:
 * ```
 * "LJNBRS1\0" | u32 version | u32 entity id space | u32 artists with a list | u16 entry width (4)
 * presence: one bit per entity id, u64 words | running counts: u32 per 512 ids, plus the total
 * offsets: u32 per listed artist, plus the end, in entries
 * entries: u24 neighbour entity id | u8 similarity (0..255 = 0..1)
 * ```
 */
public class ArtistNeighbours private constructor(
    private val bytes: AssetBytes,
    private val entityCount: Int,
    private val presenceOffset: Int,
    private val ranks: IntArray,
    private val offsetsOffset: Int,
    private val entriesOffset: Int,
) {
    /** Whether [entity] has a neighbour list. */
    public fun has(entity: Int): Boolean = entity in 0 until entityCount && present(entity)

    /** The first of [candidates] (an artist name's resolved entities) that has a list, or -1. */
    public fun entityOf(candidates: IntArray): Int = candidates.firstOrNull(::has) ?: -1

    /** Calls [visit] with each neighbour of [entity] and its similarity, best first. */
    public inline fun forEachNeighbour(entity: Int, visit: (neighbour: Int, similarity: Float) -> Unit) {
        val range = entries(entity)
        for (at in range) visit(neighbourAt(at), similarityAt(at))
    }

    /** The entry positions of [entity]'s list; empty when it has none. */
    @PublishedApi
    internal fun entries(entity: Int): IntRange {
        if (!has(entity)) return IntRange.EMPTY
        val list = rank(entity)
        return le32(offsetsOffset + list * 4) until le32(offsetsOffset + (list + 1) * 4)
    }

    @PublishedApi
    internal fun neighbourAt(entry: Int): Int {
        val at = entriesOffset + entry * ENTRY
        return u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)
    }

    @PublishedApi
    internal fun similarityAt(entry: Int): Float = u8(entriesOffset + entry * ENTRY + 3) / 255f

    private fun u8(at: Int): Int = bytes[at].toInt() and 0xff

    private fun le32(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

    private fun word(index: Int): Long {
        val at = presenceOffset + index * 8
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
        private val MAGIC = "LJNBRS1\u0000".encodeToByteArray()
        private const val VERSION = 1
        private const val HEADER = 22
        private const val ENTRY = 4

        public fun parse(bytes: ByteArray): ArtistNeighbours? = parse(AssetBytes.of(bytes))

        /** Null for a missing, damaged or newer-format asset, so SMART runs without it. */
        public fun parse(bytes: AssetBytes): ArtistNeighbours? {
            if (bytes.size < HEADER || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun u8(at: Int): Int = bytes[at].toInt() and 0xff
            fun u32(at: Int): Long = (u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)).toLong() or (u8(at + 3).toLong() shl 24)
            if (u32(8) != VERSION.toLong() || (u8(20) or (u8(21) shl 8)) != ENTRY) return null
            val entities = u32(12)
            val lists = u32(16)
            if (entities > (1 shl 24) || lists > entities) return null
            val words = ((entities + 63) / 64).toInt()
            val blocks = ((entities + 511) / 512).toInt() + 1
            val presence = HEADER
            val ranksAt = presence + words * 8
            val offsetsAt = ranksAt + blocks * 4
            val entriesAt = offsetsAt.toLong() + (lists + 1) * 4
            if (entriesAt > bytes.size) return null
            val ranks = IntArray(blocks) { u32(ranksAt + it * 4).toInt() }
            var counted = 0L
            for (word in 0 until words) {
                if (word and 7 == 0 && ranks[word ushr 3].toLong() != counted) return null
                for (b in 0 until 8) counted += u8(presence + word * 8 + b).countOneBits()
            }
            if (counted != lists || ranks[blocks - 1].toLong() != counted) return null
            val total = u32(offsetsAt + lists.toInt() * 4)
            if (u32(offsetsAt) != 0L || entriesAt + total * ENTRY != bytes.size.toLong()) return null
            return ArtistNeighbours(bytes, entities.toInt(), presence, ranks, offsetsAt, entriesAt.toInt())
        }
    }
}

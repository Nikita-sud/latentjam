/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

/**
 * The offline semantic descriptors: one 768-d vector per known track, describing what the music
 * *is* rather than what it sounds like.
 *
 * These came from an LLM description of each track, embedded offline — the phone cannot compute
 * them. They are what lets the chain hold a niche cluster together: for a Moldovan estradá or anime
 * seed the genuinely in-cluster candidates sit 2–3σ above the pool mean here, while the audio scorer
 * confidently demotes them.
 *
 * Lookup is by normalised metadata, not by any identifier: the asset is keyed
 * `"artisttitle"`, and additionally by bare title where that title is unique in the corpus.
 * Tracks the asset has never seen simply have no descriptor, and [SemanticZ] weights them out.
 */
internal class SemanticDescriptorStore private constructor(
    private val vectors: FloatArray,
    private val rowByKey: Map<String, Int>,
    val dim: Int,
) {
    val size: Int get() = rowByKey.size

    /** @return a unit-norm descriptor, or null when this track is not in the asset. */
    fun lookup(artist: String?, title: String?): FloatArray? {
        val normalizedTitle = MetadataRerank.normalizeTitle(title)
        if (normalizedTitle.isEmpty()) return null
        val normalizedArtist = artist?.lowercase()?.replace(WHITESPACE, " ")?.trim().orEmpty()
        val row = (
            if (normalizedArtist.isNotEmpty()) rowByKey["$normalizedArtist$UNIT_SEPARATOR$normalizedTitle"] else null
            ) ?: rowByKey[normalizedTitle] ?: return null
        return vectors.copyOfRange(row * dim, (row + 1) * dim)
    }

    companion object {
        private const val MAGIC = 0x4C4A5344 // "LJSD"
        private const val VERSION = 2
        private const val UNIT_SEPARATOR = ''
        private val WHITESPACE = Regex("\\s+")

        /**
         * Parses the packed asset. Returns null on a bad magic, an unexpected version or a truncated
         * file — the chain then runs without descriptors rather than with corrupt ones.
         *
         * Layout, little-endian: `magic, version, count, dim`, then `count` entries of
         * `keyLength, key (UTF-8), dim floats`.
         */
        fun parse(bytes: ByteArray): SemanticDescriptorStore? {
            val reader = LittleEndianReader(bytes)
            if (!reader.has(16)) return null
            if (reader.int() != MAGIC || reader.int() != VERSION) return null
            val count = reader.int()
            val dim = reader.int()
            if (count <= 0 || dim <= 0) return null

            val vectors = FloatArray(count * dim)
            val rowByKey = HashMap<String, Int>(count * 2)
            for (row in 0 until count) {
                if (!reader.has(4)) return null
                val keyLength = reader.int()
                if (keyLength <= 0 || !reader.has(keyLength + dim * 4)) return null
                rowByKey[reader.utf8(keyLength)] = row
                reader.floats(vectors, row * dim, dim)
            }
            return SemanticDescriptorStore(vectors, rowByKey, dim)
        }
    }
}

/** Minimal little-endian cursor — kotlinx-io would be a dependency for four read operations. */
private class LittleEndianReader(private val bytes: ByteArray) {
    private var offset = 0

    fun has(count: Int): Boolean = offset + count <= bytes.size

    fun int(): Int {
        val v = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        offset += 4
        return v
    }

    fun utf8(length: Int): String =
        bytes.decodeToString(offset, offset + length).also { offset += length }

    fun floats(into: FloatArray, at: Int, count: Int) {
        for (i in 0 until count) into[at + i] = Float.fromBits(int())
    }
}

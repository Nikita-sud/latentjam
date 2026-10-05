/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.math.sqrt

/**
 * Read-only artist knowledge pack: for each entity of [MusicEntityIndex], a product-quantized
 * descriptor of what a teacher model knows about that artist, generated offline, and two facts.
 *
 * Layout, little endian:
 * - magic `LJKNOW1\0`, u32 version (2), u16 dimension, u8 sub-spaces M, u16 centroids K, u32 entity
 *   count
 * - FP16 codebooks `[M, K, dimension / M]`
 * - one fixed-size record per dense entity id: M code bytes, a u16 fingerprint of the entity's own
 *   normalized name, two language bytes (1-based indices into [LANGUAGES]), a decade byte
 *   (`1 + (decade - 1000) / 10`, 0 when unknown) and a confidence byte (1/255 units)
 *
 * There is no id list: the entity index's dense popularity-ranked ids are the key space.
 *
 * Version 3 stores only what the app reads: the confident entities (confidence ≥ 0.5, the only ones
 * [descriptor] and [facts] ever use), without the confidence and second-language bytes. After the
 * codebooks: a presence bit per entity id (u64 words, bit `id % 64` of word `id / 64`), one u32
 * running count of present entities per 512 ids, then one record per present entity in id order — M
 * code bytes, the u16 fingerprint, a language byte and a decade byte. It answers exactly as the
 * version-2 pack it was cut from.
 */
public class ArtistKnowledgePack private constructor(
    private val bytes: ByteArray,
    public val dimension: Int,
    private val subspaces: Int,
    private val centroids: Int,
    private val entityCount: Int,
    private val codebooks: FloatArray,
    private val recordsOffset: Int,
    /** Version 3 only: where the presence words start, and the running counts per 512 ids. */
    private val presenceOffset: Int = -1,
    private val ranks: IntArray? = null,
) {
    private val compact = ranks != null
    private val recordSize = subspaces + if (compact) RECORD_TAIL_COMPACT else RECORD_TAIL

    /**
     * The unit-length descriptor for a library [artist] whose name resolved to [entityIds].
     *
     * The ids come sorted, as [MusicEntityIndex.resolve] returns them, so the first is the most
     * popular. A confident entity whose own name matches [artist] wins. Otherwise a name of several
     * words takes the most popular confident entity — its key holds only names and aliases, like a
     * member listed with the band. A single word does so only when its key names at most
     * [SPECIFIC_KEY_SIZE] entities: a word that many names contain ("Silver", "Unknown", "Traditional")
     * says nothing about this artist, and borrowing the most popular such name gave tracks a
     * stranger's language and decade. Null leaves the artist to the adapter.
     */
    public fun descriptor(entityIds: IntArray, artist: String): FloatArray? = entity(entityIds, artist)?.let(::decode)

    /** What the teacher says about the entity [descriptor] picks; null when none is confident. */
    public fun facts(entityIds: IntArray, artist: String): ArtistFacts? = entity(entityIds, artist)?.let { entity ->
        val at = record(entity) + subspaces + 2
        val decade = bytes[at + if (compact) 1 else 2].toInt() and 0xff
        ArtistFacts(
            language = LANGUAGES.getOrNull((bytes[at].toInt() and 0xff) - 1),
            decade = if (decade == 0) null else 1000 + (decade - 1) * 10,
        )
    }

    private fun entity(entityIds: IntArray, artist: String): Int? {
        val confident = entityIds.filter { it in 0 until entityCount && confidence(it) >= MIN_CONFIDENCE }
        if (confident.isEmpty()) return null
        val own = fingerprint(artist)
        confident.firstOrNull { fingerprintOf(it) == own }?.let { return it }
        val severalWords = ' ' in MusicEntityIndex.normalize(artist)
        return if (severalWords || entityIds.size <= SPECIFIC_KEY_SIZE) confident.first() else null
    }

    private fun record(entity: Int): Int = recordsOffset + (if (compact) rank(entity) else entity) * recordSize

    private fun confidence(entity: Int): Int = when {
        !compact -> bytes[record(entity) + subspaces + 5].toInt() and 0xff
        present(entity) -> 255
        else -> 0
    }

    private fun word(index: Int): Long {
        var value = 0L
        for (byte in 7 downTo 0) value = (value shl 8) or (bytes[presenceOffset + index * 8 + byte].toLong() and 0xff)
        return value
    }

    private fun present(entity: Int): Boolean = (word(entity ushr 6) ushr (entity and 63)) and 1L == 1L

    /** How many present entities have a smaller id. */
    private fun rank(entity: Int): Int {
        var count = ranks!![entity ushr 9]
        for (index in (entity ushr 9) * 8 until (entity ushr 6)) count += word(index).countOneBits()
        val below = entity and 63
        if (below > 0) count += (word(entity ushr 6) and ((1L shl below) - 1)).countOneBits()
        return count
    }

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
        private const val VERSION = 2
        private const val VERSION_COMPACT = 3
        private const val HEADER_SIZE = 21
        private const val RECORD_TAIL = 6 // fingerprint (2), two language bytes, decade, confidence
        private const val RECORD_TAIL_COMPACT = 4 // fingerprint (2), language, decade
        private const val MIN_CONFIDENCE = 128 // 0.5 in 1/255 units

        /**
         * The most entities a one-word key may name and still identify the artist. Measured on two
         * real libraries: member and alias keys named 1–2 entities, the misleading words 4 to 2,206.
         */
        private const val SPECIFIC_KEY_SIZE = 3

        /** The language bytes' codes, in the order of LANGUAGES in tools/research/build_artist_knowledge.py. */
        internal val LANGUAGES: List<String> = listOf(
            "en", "ru", "ro", "uk", "ja", "ko", "zh", "es", "pt", "fr", "de", "it", "tr", "pl", "ar", "hi",
            "kk", "be", "sr", "hr", "bg", "el", "he", "fa", "nl", "sv", "fi", "no", "da", "cs", "hu", "id",
            "th", "vi", "tl", "ka", "hy", "az", "uz", "la", "instrumental",
        )

        /** The 16-bit fingerprint of a normalized artist name that each record carries. */
        internal fun fingerprint(artist: String): Int =
            (MusicEntityIndex.fnv1a64(MusicEntityIndex.normalize(artist).encodeToByteArray()) and 0xffffuL).toInt()

        /** Returns null for a missing, corrupt or newer-format asset, so SMART runs without knowledge. */
        public fun parse(bytes: ByteArray): ArtistKnowledgePack? {
            if (bytes.size < HEADER_SIZE || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun u8(offset: Int): Int = bytes[offset].toInt() and 0xff
            fun u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)
            fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
            val version = u32(8)
            if (version != VERSION.toLong() && version != VERSION_COMPACT.toLong()) return null
            val dimension = u16(12)
            val subspaces = u8(14)
            val centroids = u16(15)
            val entities = u32(17)
            if (dimension == 0 || subspaces == 0 || dimension % subspaces != 0 || centroids !in 1..256) return null
            val bookValues = subspaces.toLong() * centroids * (dimension / subspaces)
            if (version == VERSION_COMPACT.toLong()) return parseCompact(bytes, dimension, subspaces, centroids, entities, bookValues)
            val recordsOffset = HEADER_SIZE + bookValues * 2
            if (recordsOffset + entities * (subspaces + RECORD_TAIL) != bytes.size.toLong()) return null
            val books = FloatArray(bookValues.toInt()) { index -> halfToFloat(u16(HEADER_SIZE + 2 * index)) }
            if (books.any { !it.isFinite() }) return null
            // A valid file length does not prove its codes index the declared codebooks. Reject
            // damaged records here, before a lookup can read another subspace or past the array.
            if (centroids < 256) {
                for (entity in 0 until entities.toInt()) {
                    val at = recordsOffset.toInt() + entity * (subspaces + RECORD_TAIL)
                    for (space in 0 until subspaces) if (u8(at + space) >= centroids) return null
                }
            }
            return ArtistKnowledgePack(
                bytes, dimension, subspaces, centroids, entities.toInt(), books, recordsOffset.toInt(),
            )
        }

        /** Version 3: the presence words and running counts must agree with each other and the file size. */
        private fun parseCompact(
            bytes: ByteArray,
            dimension: Int,
            subspaces: Int,
            centroids: Int,
            entities: Long,
            bookValues: Long,
        ): ArtistKnowledgePack? {
            if (entities > Int.MAX_VALUE / 2) return null
            val words = ((entities + 63) / 64).toInt()
            val blocks = ((entities + 511) / 512).toInt() + 1
            val presenceOffset = HEADER_SIZE + bookValues * 2
            val ranksOffset = presenceOffset + words * 8L
            val recordsOffset = ranksOffset + blocks * 4L
            if (recordsOffset > bytes.size) return null
            val ranks = IntArray(blocks) { le32(bytes, (ranksOffset + it * 4L).toInt()) }
            var counted = 0L
            val presence = presenceOffset.toInt()
            for (word in 0 until words) {
                if (word and 7 == 0 && ranks[word ushr 3].toLong() != counted) return null
                val at = presence + word * 8
                val value = (le32(bytes, at).toLong() and 0xffffffffL) or (le32(bytes, at + 4).toLong() shl 32)
                if (word == words - 1 && entities % 64 != 0L && value ushr (entities % 64).toInt() != 0L) return null
                counted += value.countOneBits()
            }
            if (ranks[blocks - 1].toLong() != counted) return null
            val recordSize = subspaces + RECORD_TAIL_COMPACT
            if (recordsOffset + counted * recordSize != bytes.size.toLong()) return null
            val books = FloatArray(bookValues.toInt()) { index ->
                val at = HEADER_SIZE + 2 * index
                halfToFloat((bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8))
            }
            if (books.any { !it.isFinite() }) return null
            if (centroids < 256) {
                for (record in 0 until counted.toInt()) {
                    val at = recordsOffset.toInt() + record * recordSize
                    for (space in 0 until subspaces) if ((bytes[at + space].toInt() and 0xff) >= centroids) return null
                }
            }
            return ArtistKnowledgePack(
                bytes, dimension, subspaces, centroids, entities.toInt(), books, recordsOffset.toInt(),
                presenceOffset.toInt(), ranks,
            )
        }

        private fun le32(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
                ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)

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

/**
 * What the teacher said about an artist: the ISO 639-1 code of the language they mainly sing in, or
 * `instrumental`, and the middle of the decades they were most active in (the later one of two).
 */
public data class ArtistFacts(val language: String?, val decade: Int?)

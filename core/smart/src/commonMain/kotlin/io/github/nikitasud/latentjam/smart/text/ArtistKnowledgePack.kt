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
        val decade = bytes[at + 2].toInt() and 0xff
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

    private fun record(entity: Int): Int = recordsOffset + entity * recordSize

    private fun confidence(entity: Int): Int = bytes[record(entity) + subspaces + 5].toInt() and 0xff

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
        private const val HEADER_SIZE = 21
        private const val RECORD_TAIL = 6 // fingerprint (2), two language bytes, decade, confidence
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

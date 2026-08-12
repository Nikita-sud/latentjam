/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * Read-only MusicBrainz alias/relationship index used to ground local search.
 *
 * The pack contains no artist-specific application logic. Names and aliases resolve to anonymous
 * entity ids; a member name additionally resolves to their groups. A query matches a library artist
 * when those id sets intersect. The sorted hash table keeps the global pack small and makes lookup
 * independent of library size.
 */
public class MusicEntityIndex private constructor(
    private val bytes: ByteArray,
    private val entryCount: Int,
    private val valuesOffset: Int,
) {

    /** Resolves a full name or a stored name token to sorted MusicBrainz entity ids. */
    public fun resolve(value: String): IntArray {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return IntArray(0)
        val target = fnv1a64(normalized.encodeToByteArray())
        var low = 0
        var high = entryCount - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val entry = HEADER_SIZE + middle * ENTRY_SIZE
            val found = readULong(entry)
            val comparison = found.compareTo(target)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> {
                    val start = readInt(entry + 8)
                    val count = readUShort(entry + 12)
                    return IntArray(count) { index -> readInt(valuesOffset + (start + index) * 4) }
                }
            }
        }
        return IntArray(0)
    }

    /** True when the grounded query names this artist or one of its groups. */
    public fun matches(query: String, libraryArtist: String?): Boolean {
        if (libraryArtist.isNullOrBlank()) return false
        val queryIds = resolve(query)
        if (queryIds.isEmpty()) return false
        val artistIds = resolve(libraryArtist)
        var queryIndex = 0
        var artistIndex = 0
        while (queryIndex < queryIds.size && artistIndex < artistIds.size) {
            when {
                queryIds[queryIndex] < artistIds[artistIndex] -> queryIndex++
                queryIds[queryIndex] > artistIds[artistIndex] -> artistIndex++
                else -> return true
            }
        }
        return false
    }

    private fun readInt(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readUShort(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readULong(offset: Int): ULong {
        var result = 0uL
        for (index in 0 until 8) {
            result = result or ((bytes[offset + index].toULong() and 0xffuL) shl (index * 8))
        }
        return result
    }

    public companion object {
        private const val HEADER_SIZE = 20
        private const val ENTRY_SIZE = 16
        private val MAGIC = byteArrayOf(0x4c, 0x4a, 0x45, 0x4e, 0x54, 0x31, 0, 0)

        /** Returns null for a missing/corrupt asset so ordinary metadata search remains available. */
        public fun parse(bytes: ByteArray): MusicEntityIndex? {
            if (bytes.size < HEADER_SIZE || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun intAt(offset: Int): Int =
                (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xff) shl 24)
            fun uShortAt(offset: Int): Int =
                (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8)
            fun uLongAt(offset: Int): ULong {
                var result = 0uL
                for (index in 0 until 8) {
                    result = result or
                        ((bytes[offset + index].toULong() and 0xffuL) shl (index * 8))
                }
                return result
            }
            val entryCount = intAt(8)
            val valueCount = intAt(12)
            val entityCount = intAt(16)
            if (entryCount < 0 || valueCount < 0 || entityCount < 0) return null
            val valuesOffset = HEADER_SIZE.toLong() + entryCount.toLong() * ENTRY_SIZE
            val required = valuesOffset + valueCount.toLong() * 4
            if (valuesOffset > Int.MAX_VALUE || required != bytes.size.toLong()) return null

            // resolve() binary-searches hashes, and matches() merge-scans each id slice. Validate
            // both ordering contracts once here so corrupt optional assets fail closed instead of
            // producing false negatives or an out-of-bounds read later on a user query.
            val valuesStart = valuesOffset.toInt()
            var previousHash: ULong? = null
            for (index in 0 until entryCount) {
                val entry = HEADER_SIZE + index * ENTRY_SIZE
                val hash = uLongAt(entry)
                val lastHash = previousHash
                if (lastHash != null && hash <= lastHash) return null
                previousHash = hash

                val start = intAt(entry + 8)
                val count = uShortAt(entry + 12)
                if (start < 0 || start.toLong() + count > valueCount.toLong()) return null

                var previousEntity = -1
                for (valueIndex in 0 until count) {
                    val entity = intAt(valuesStart + (start + valueIndex) * 4)
                    if (entity < 0 || entity >= entityCount || entity <= previousEntity) return null
                    previousEntity = entity
                }
            }
            return MusicEntityIndex(bytes, entryCount, valuesOffset.toInt())
        }

        internal fun normalize(value: String): String = buildString(value.length) {
            var previousSpace = true
            value.lowercase().forEach { original ->
                val character = if (original == 'ё') 'е' else original
                when {
                    character.isLetterOrDigit() -> {
                        append(character)
                        previousSpace = false
                    }
                    !previousSpace -> {
                        append(' ')
                        previousSpace = true
                    }
                }
            }
        }.trim()

        internal fun fnv1a64(bytes: ByteArray): ULong {
            var result = 0xcbf29ce484222325uL
            for (byte in bytes) {
                result = result xor (byte.toULong() and 0xffuL)
                result *= 0x100000001b3uL
            }
            return result
        }
    }
}

/** Lazily loads the pack off the launch path and fails closed when the optional asset is absent. */
public class MusicEntityResolver(
    loadBytes: () -> ByteArray?,
) {
    private val index: MusicEntityIndex? by lazy {
        runCatching { loadBytes()?.let(MusicEntityIndex::parse) }.getOrNull()
    }

    /** Forces the lazy asset read; intended for an app-lifetime background coroutine. */
    public fun preload() {
        index
    }

    public fun matches(query: String, libraryArtist: String?): Boolean =
        index?.matches(query, libraryArtist) == true
}

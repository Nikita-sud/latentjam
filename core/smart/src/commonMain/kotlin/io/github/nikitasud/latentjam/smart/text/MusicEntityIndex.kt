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
 *
 * Layout `LJENT2`, little endian:
 * - magic `LJENT2\0\0`, u32 key count, u32 value count, u32 entity count, four reserved bytes
 * - 65,537 u32 key positions: where the keys whose 48-bit hash has each top-16-bit value start,
 *   then the key count
 * - per key, sorted by hash: the hash's low 32 bits and the u24 offset of its first value; a key's
 *   values run to the next key's offset, the last key's to the value count
 * - the values: u24 entity ids, sorted within each key
 *
 * A key hash is the low 48 bits of FNV-1a 64 over a normalized name or name token. The shipped
 * index has no collision at 48 bits; one would only make a name ambiguous.
 */
public class MusicEntityIndex private constructor(
    private val bytes: ByteArray,
    private val keyCount: Int,
    private val valueCount: Int,
) {
    private val valuesOffset = KEYS_OFFSET + keyCount * KEY_SIZE

    /** Resolves a full name or a stored name token to sorted MusicBrainz entity ids. */
    public fun resolve(value: String): IntArray {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return IntArray(0)
        val hash = fnv1a64(normalized.encodeToByteArray()) and HASH_MASK
        val bucket = (hash shr 32).toInt()
        val target = (hash and 0xffffffffuL).toLong()
        var low = u32(bytes, HEADER_SIZE + bucket * 4).toInt()
        var high = u32(bytes, HEADER_SIZE + (bucket + 1) * 4).toInt() - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val found = u32(bytes, KEYS_OFFSET + middle * KEY_SIZE)
            when {
                found < target -> low = middle + 1
                found > target -> high = middle - 1
                else -> {
                    val start = firstValue(middle)
                    val end = if (middle + 1 < keyCount) firstValue(middle + 1) else valueCount
                    return IntArray(end - start) { index -> u24(bytes, valuesOffset + (start + index) * 3) }
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

    private fun firstValue(key: Int): Int = u24(bytes, KEYS_OFFSET + key * KEY_SIZE + 4)

    public companion object {
        private const val HEADER_SIZE = 24
        private const val BUCKETS = 1 shl 16
        private const val KEYS_OFFSET = HEADER_SIZE + (BUCKETS + 1) * 4
        private const val KEY_SIZE = 7
        private const val HASH_MASK = 0xffffffffffffuL
        private val MAGIC = "LJENT2\u0000\u0000".encodeToByteArray()

        /** Returns null for a missing, corrupt or other-format asset so ordinary metadata search remains available. */
        public fun parse(bytes: ByteArray): MusicEntityIndex? {
            if (bytes.size < KEYS_OFFSET || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            val keyCount = u32(bytes, 8)
            val valueCount = u32(bytes, 12)
            val entityCount = u32(bytes, 16)
            if (valueCount > U24_LIMIT || entityCount > U24_LIMIT) return null
            val valuesOffset = KEYS_OFFSET + keyCount * KEY_SIZE
            if (valuesOffset + valueCount * 3 != bytes.size.toLong()) return null
            val keys = keyCount.toInt()
            val values = valueCount.toInt()
            val valuesStart = valuesOffset.toInt()

            // resolve() binary-searches each bucket's hashes and reads a key's values up to the next
            // key's offset; matches() merge-scans the sorted ids. Check all of it once here, so a
            // corrupt optional asset fails closed instead of answering a query wrongly later.
            if (u32(bytes, HEADER_SIZE) != 0L || u32(bytes, HEADER_SIZE + BUCKETS * 4) != keyCount) return null
            for (bucket in 0 until BUCKETS) {
                val first = u32(bytes, HEADER_SIZE + bucket * 4)
                val last = u32(bytes, HEADER_SIZE + (bucket + 1) * 4)
                if (last < first || last > keyCount) return null
                var previousHash = -1L
                for (key in first.toInt() until last.toInt()) {
                    val hash = u32(bytes, KEYS_OFFSET + key * KEY_SIZE)
                    if (hash <= previousHash) return null
                    previousHash = hash
                }
            }
            if (keys == 0) return if (values == 0) MusicEntityIndex(bytes, 0, 0) else null
            if (u24(bytes, KEYS_OFFSET + 4) != 0) return null
            for (key in 0 until keys) {
                val start = u24(bytes, KEYS_OFFSET + key * KEY_SIZE + 4)
                val end = if (key + 1 < keys) u24(bytes, KEYS_OFFSET + (key + 1) * KEY_SIZE + 4) else values
                if (end <= start || end > values) return null
                var previousEntity = -1
                for (index in start until end) {
                    val entity = u24(bytes, valuesStart + index * 3)
                    if (entity >= entityCount || entity <= previousEntity) return null
                    previousEntity = entity
                }
            }
            return MusicEntityIndex(bytes, keys, values)
        }

        private const val U24_LIMIT = 1L shl 24

        private fun u24(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            u24(bytes, offset).toLong() or ((bytes[offset + 3].toLong() and 0xff) shl 24)

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

    /** The entity ids [name] resolves to, most popular first; empty when the pack is absent. */
    public fun resolve(name: String): IntArray = index?.resolve(name) ?: IntArray(0)
}

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
 *
 * Layout `LJENT3` holds the same keys and ids in about 70 % of the bytes, and resolves exactly alike:
 * - magic `LJENT3\0\0`, u32 key count, u32 value count, u32 entity count, four reserved bytes
 * - 65,537 u32 byte positions in the entry stream where each top-16-bit bucket starts, then its length
 * - per key, sorted by hash within its bucket: the hash's next 24 bits (bits 8–31), a varint id
 *   count, the first id as a varint and every further id as a varint step from the one before
 *
 * Forty bits name a key there, which the shipped keys never share; a stranger name would be taken
 * for a stored one with probability below one in a million per lookup. Unlike LJENT2 the entries are
 * checked as lookups read them, not all at once when the file is parsed.
 */
public class MusicEntityIndex private constructor(
    private val bytes: ByteArray,
    private val keyCount: Int,
    private val valueCount: Int,
    private val compact: Boolean = false,
    private val entityCount: Long = 0,
) {
    private val valuesOffset = KEYS_OFFSET + keyCount * KEY_SIZE

    /** Resolves a full name or a stored name token to sorted MusicBrainz entity ids. */
    public fun resolve(value: String): IntArray {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return IntArray(0)
        val hash = fnv1a64(normalized.encodeToByteArray()) and HASH_MASK
        if (compact) return resolveCompact(hash)
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

    /**
     * LJENT3: a linear walk through the hash's bucket, a dozen keys on average. The walk checks every
     * read against the bucket's end, the entity count and the sort order, and answers nothing for a
     * key it cannot read cleanly, so a damaged file fails closed one lookup at a time instead of costing
     * a full decode of nine million bytes at every start.
     */
    private fun resolveCompact(hash: ULong): IntArray {
        val bucket = (hash shr 32).toInt()
        val target = ((hash shr 8) and 0xffffffuL).toInt()
        val reader = VarintReader(bytes, KEYS_OFFSET + u32(bytes, HEADER_SIZE + bucket * 4).toInt())
        val end = KEYS_OFFSET + u32(bytes, HEADER_SIZE + (bucket + 1) * 4).toInt()
        var previousHash = -1
        while (reader.position < end) {
            if (reader.position + 3 > end) return IntArray(0)
            val found = u24(bytes, reader.position)
            reader.position += 3
            if (found <= previousHash || found > target) return IntArray(0)
            previousHash = found
            val count = reader.nextChecked(end) ?: return IntArray(0)
            if (count == 0) return IntArray(0)
            if (found == target) {
                val ids = IntArray(count)
                for (index in 0 until count) {
                    val step = reader.nextChecked(end) ?: return IntArray(0)
                    val id = if (index == 0) step.toLong() else ids[index - 1].toLong() + step
                    if ((index > 0 && step == 0) || id >= entityCount) return IntArray(0)
                    ids[index] = id.toInt()
                }
                return ids
            }
            repeat(count) { reader.nextChecked(end) ?: return IntArray(0) }
        }
        return IntArray(0)
    }

    public companion object {
        private const val HEADER_SIZE = 24
        private const val BUCKETS = 1 shl 16
        private const val KEYS_OFFSET = HEADER_SIZE + (BUCKETS + 1) * 4
        private const val KEY_SIZE = 7
        private const val HASH_MASK = 0xffffffffffffuL
        private val MAGIC = "LJENT2\u0000\u0000".encodeToByteArray()
        private val MAGIC_COMPACT = "LJENT3\u0000\u0000".encodeToByteArray()

        /** Returns null for a missing, corrupt or other-format asset so ordinary metadata search remains available. */
        public fun parse(bytes: ByteArray): MusicEntityIndex? {
            if (bytes.size >= KEYS_OFFSET && MAGIC_COMPACT.indices.all { bytes[it] == MAGIC_COMPACT[it] }) {
                return parseCompact(bytes)
            }
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

        /**
         * LJENT3: the header and the bucket directory are checked here (in order, within the stream,
         * ending exactly at the file's end); each key's own bytes are checked when a lookup reads them.
         */
        private fun parseCompact(bytes: ByteArray): MusicEntityIndex? {
            val keyCount = u32(bytes, 8)
            val valueCount = u32(bytes, 12)
            val entityCount = u32(bytes, 16)
            if (keyCount > Int.MAX_VALUE || valueCount > Int.MAX_VALUE || entityCount > U24_LIMIT) return null
            val stream = bytes.size.toLong() - KEYS_OFFSET
            if (u32(bytes, HEADER_SIZE) != 0L || u32(bytes, HEADER_SIZE + BUCKETS * 4) != stream) return null
            var previous = 0L
            for (bucket in 1..BUCKETS) {
                val start = u32(bytes, HEADER_SIZE + bucket * 4)
                if (start < previous) return null
                previous = start
            }
            return MusicEntityIndex(bytes, keyCount.toInt(), valueCount.toInt(), compact = true, entityCount = entityCount)
        }

        private const val U24_LIMIT = 1L shl 24

        private fun u24(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            u24(bytes, offset).toLong() or ((bytes[offset + 3].toLong() and 0xff) shl 24)

        /**
         * A name reduced the way the pack builder reduces it (tools/research/pack_music_entities.py):
         * lowercased, `ё` folded to `е`, every run of the other characters collapsed to one space and
         * trimmed. Whether a character is a letter or a digit is Python's `str.isalnum()` there -- the
         * categories L*, Nd, Nl and No -- so "Girls²" keeps its superscript and hashes to the key the
         * pack was built from instead of to another artist's key. The lowercasing stays Kotlin's own and
         * parts from Python's `str.lower()` on a few characters, whose names still hash differently.
         */
        internal fun normalize(value: String): String = buildString(value.length) {
            var previousSpace = true
            val lowered = value.lowercase().replace('ё', 'е')
            var index = 0
            while (index < lowered.length) {
                // Kotlin common has no codePointAt, so a surrogate pair is decoded here. Python reads
                // it as one character, and it stays one when appended.
                val pair = lowered.hasSurrogatePairAt(index)
                val width = if (pair) 2 else 1
                val codePoint = if (pair) {
                    ((lowered[index].code - 0xD800) shl 10) + (lowered[index + 1].code - 0xDC00) + 0x10000
                } else {
                    lowered[index].code
                }
                when {
                    isAlphanumeric(codePoint) -> {
                        append(lowered, index, index + width)
                        previousSpace = false
                    }
                    !previousSpace -> {
                        append(' ')
                        previousSpace = true
                    }
                }
                index += width
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

        /**
         * Whether one code point is a letter or a digit the way `str.isalnum()` reads it in the pack
         * builder. The BMP answers with its Unicode general category, the set
         * [ALPHANUMERIC_CATEGORIES]; above the BMP Kotlin common has no category table, so an area
         * decides there.
         */
        private fun isAlphanumeric(codePoint: Int): Boolean = when (codePoint) {
            in 0..0xFFFF -> codePoint.toChar().category in ALPHANUMERIC_CATEGORIES
            // Byzantine and musical notation, Tai Xuan Jing symbols: only the numerals inside those
            // blocks (Kaktovik, Mayan, counting rods and tally marks) are letters or digits to Python.
            in 0x1D000..0x1D3FF -> codePoint in 0x1D2C0..0x1D2D3 || codePoint in 0x1D2E0..0x1D2F3 ||
                codePoint in 0x1D360..0x1D378
            // Pictographs, emoji and the enclosed symbols are not letters or digits, save for the
            // enclosed digits with a full stop or a comma.
            in 0x1F100..0x1F10C -> true
            in 0x1F000..0x1FAFF -> false
            // Tags and variation selectors, then the two private use planes.
            in 0xE0000..0xE01EF -> false
            in 0xF0000..0x10FFFD -> false
            // Every other area of the supplementary planes is letters and numbers (CJK extensions,
            // historic scripts, mathematical alphanumerics). An unassigned code point and the few
            // symbols inside letter blocks cannot be told apart here and are kept.
            else -> true
        }

        /** The categories `str.isalnum()` covers: letters, then the numbers Nd, Nl and No. */
        private val ALPHANUMERIC_CATEGORIES: Set<CharCategory> = setOf(
            CharCategory.UPPERCASE_LETTER,
            CharCategory.LOWERCASE_LETTER,
            CharCategory.TITLECASE_LETTER,
            CharCategory.MODIFIER_LETTER,
            CharCategory.OTHER_LETTER,
            CharCategory.DECIMAL_DIGIT_NUMBER,
            CharCategory.LETTER_NUMBER,
            CharCategory.OTHER_NUMBER,
        )
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

    /**
     * Whether an index loaded to answer from; reading it loads the index. A caller that treats
     * "not found" as "unknown artist" must check this first, since without an index nothing is known.
     */
    public val isAvailable: Boolean get() = index != null

    /** The entity ids [name] resolves to, most popular first; empty when the pack is absent. */
    public fun resolve(name: String): IntArray = index?.resolve(name) ?: IntArray(0)
}

/** Unsigned LEB128 varints, as tools/research/compact_music_entities.py writes them. */
private class VarintReader(private val bytes: ByteArray, var position: Int) {
    /** The next varint, or null when it would run past [end] or past 28 bits. */
    fun nextChecked(end: Int): Int? {
        var result = 0
        var shift = 0
        while (position < end && shift <= 21) {
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if (byte < 0x80) return result
            shift += 7
        }
        return null
    }
}

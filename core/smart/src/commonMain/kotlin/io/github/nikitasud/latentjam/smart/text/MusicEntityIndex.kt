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
     * a full decode of nine million bytes at every start. An id count must fit both bounds as well: the
     * varint alone would allow an IntArray of a gigabyte for a bucket that holds a few bytes.
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
            // Every id costs at least one byte of the bucket and stays below the entity count, so a count
            // that cannot satisfy both is damage. Checked before the allocation: the varint reads 28 bits,
            // so IntArray(count) would otherwise reserve up to a gigabyte for a corrupt asset.
            if (count.toLong() > entityCount || count > end - reader.position) return IntArray(0)
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
         * The code points above the BMP that `str.isalnum()` keeps, as sorted inclusive
         * `start, end` pairs, so a lookup is a binary search instead of an area guess. Generated
         * with python3 (`unicodedata` 15.0.0) over the categories L*, Nd, Nl and No exactly as
         * tools/research/pack_music_entities.py reads them through `str.isalnum()`: the historic
         * scripts and the mathematical alphanumerics stay, while SignWriting, the legacy computing
         * symbols, the combining marks of those planes and every unassigned code point are dropped.
         */
        private val ASTRAL_ALPHANUMERIC: IntArray = intArrayOf(
            0x10000, 0x1000B, 0x1000D, 0x10026, 0x10028, 0x1003A, 0x1003C, 0x1003D, 0x1003F, 0x1004D, 0x10050, 0x1005D,
            0x10080, 0x100FA, 0x10107, 0x10133, 0x10140, 0x10178, 0x1018A, 0x1018B, 0x10280, 0x1029C, 0x102A0, 0x102D0,
            0x102E1, 0x102FB, 0x10300, 0x10323, 0x1032D, 0x1034A, 0x10350, 0x10375, 0x10380, 0x1039D, 0x103A0, 0x103C3,
            0x103C8, 0x103CF, 0x103D1, 0x103D5, 0x10400, 0x1049D, 0x104A0, 0x104A9, 0x104B0, 0x104D3, 0x104D8, 0x104FB,
            0x10500, 0x10527, 0x10530, 0x10563, 0x10570, 0x1057A, 0x1057C, 0x1058A, 0x1058C, 0x10592, 0x10594, 0x10595,
            0x10597, 0x105A1, 0x105A3, 0x105B1, 0x105B3, 0x105B9, 0x105BB, 0x105BC, 0x10600, 0x10736, 0x10740, 0x10755,
            0x10760, 0x10767, 0x10780, 0x10785, 0x10787, 0x107B0, 0x107B2, 0x107BA, 0x10800, 0x10805, 0x10808, 0x10808,
            0x1080A, 0x10835, 0x10837, 0x10838, 0x1083C, 0x1083C, 0x1083F, 0x10855, 0x10858, 0x10876, 0x10879, 0x1089E,
            0x108A7, 0x108AF, 0x108E0, 0x108F2, 0x108F4, 0x108F5, 0x108FB, 0x1091B, 0x10920, 0x10939, 0x10980, 0x109B7,
            0x109BC, 0x109CF, 0x109D2, 0x10A00, 0x10A10, 0x10A13, 0x10A15, 0x10A17, 0x10A19, 0x10A35, 0x10A40, 0x10A48,
            0x10A60, 0x10A7E, 0x10A80, 0x10A9F, 0x10AC0, 0x10AC7, 0x10AC9, 0x10AE4, 0x10AEB, 0x10AEF, 0x10B00, 0x10B35,
            0x10B40, 0x10B55, 0x10B58, 0x10B72, 0x10B78, 0x10B91, 0x10BA9, 0x10BAF, 0x10C00, 0x10C48, 0x10C80, 0x10CB2,
            0x10CC0, 0x10CF2, 0x10CFA, 0x10D23, 0x10D30, 0x10D39, 0x10E60, 0x10E7E, 0x10E80, 0x10EA9, 0x10EB0, 0x10EB1,
            0x10F00, 0x10F27, 0x10F30, 0x10F45, 0x10F51, 0x10F54, 0x10F70, 0x10F81, 0x10FB0, 0x10FCB, 0x10FE0, 0x10FF6,
            0x11003, 0x11037, 0x11052, 0x1106F, 0x11071, 0x11072, 0x11075, 0x11075, 0x11083, 0x110AF, 0x110D0, 0x110E8,
            0x110F0, 0x110F9, 0x11103, 0x11126, 0x11136, 0x1113F, 0x11144, 0x11144, 0x11147, 0x11147, 0x11150, 0x11172,
            0x11176, 0x11176, 0x11183, 0x111B2, 0x111C1, 0x111C4, 0x111D0, 0x111DA, 0x111DC, 0x111DC, 0x111E1, 0x111F4,
            0x11200, 0x11211, 0x11213, 0x1122B, 0x1123F, 0x11240, 0x11280, 0x11286, 0x11288, 0x11288, 0x1128A, 0x1128D,
            0x1128F, 0x1129D, 0x1129F, 0x112A8, 0x112B0, 0x112DE, 0x112F0, 0x112F9, 0x11305, 0x1130C, 0x1130F, 0x11310,
            0x11313, 0x11328, 0x1132A, 0x11330, 0x11332, 0x11333, 0x11335, 0x11339, 0x1133D, 0x1133D, 0x11350, 0x11350,
            0x1135D, 0x11361, 0x11400, 0x11434, 0x11447, 0x1144A, 0x11450, 0x11459, 0x1145F, 0x11461, 0x11480, 0x114AF,
            0x114C4, 0x114C5, 0x114C7, 0x114C7, 0x114D0, 0x114D9, 0x11580, 0x115AE, 0x115D8, 0x115DB, 0x11600, 0x1162F,
            0x11644, 0x11644, 0x11650, 0x11659, 0x11680, 0x116AA, 0x116B8, 0x116B8, 0x116C0, 0x116C9, 0x11700, 0x1171A,
            0x11730, 0x1173B, 0x11740, 0x11746, 0x11800, 0x1182B, 0x118A0, 0x118F2, 0x118FF, 0x11906, 0x11909, 0x11909,
            0x1190C, 0x11913, 0x11915, 0x11916, 0x11918, 0x1192F, 0x1193F, 0x1193F, 0x11941, 0x11941, 0x11950, 0x11959,
            0x119A0, 0x119A7, 0x119AA, 0x119D0, 0x119E1, 0x119E1, 0x119E3, 0x119E3, 0x11A00, 0x11A00, 0x11A0B, 0x11A32,
            0x11A3A, 0x11A3A, 0x11A50, 0x11A50, 0x11A5C, 0x11A89, 0x11A9D, 0x11A9D, 0x11AB0, 0x11AF8, 0x11C00, 0x11C08,
            0x11C0A, 0x11C2E, 0x11C40, 0x11C40, 0x11C50, 0x11C6C, 0x11C72, 0x11C8F, 0x11D00, 0x11D06, 0x11D08, 0x11D09,
            0x11D0B, 0x11D30, 0x11D46, 0x11D46, 0x11D50, 0x11D59, 0x11D60, 0x11D65, 0x11D67, 0x11D68, 0x11D6A, 0x11D89,
            0x11D98, 0x11D98, 0x11DA0, 0x11DA9, 0x11EE0, 0x11EF2, 0x11F02, 0x11F02, 0x11F04, 0x11F10, 0x11F12, 0x11F33,
            0x11F50, 0x11F59, 0x11FB0, 0x11FB0, 0x11FC0, 0x11FD4, 0x12000, 0x12399, 0x12400, 0x1246E, 0x12480, 0x12543,
            0x12F90, 0x12FF0, 0x13000, 0x1342F, 0x13441, 0x13446, 0x14400, 0x14646, 0x16800, 0x16A38, 0x16A40, 0x16A5E,
            0x16A60, 0x16A69, 0x16A70, 0x16ABE, 0x16AC0, 0x16AC9, 0x16AD0, 0x16AED, 0x16B00, 0x16B2F, 0x16B40, 0x16B43,
            0x16B50, 0x16B59, 0x16B5B, 0x16B61, 0x16B63, 0x16B77, 0x16B7D, 0x16B8F, 0x16E40, 0x16E96, 0x16F00, 0x16F4A,
            0x16F50, 0x16F50, 0x16F93, 0x16F9F, 0x16FE0, 0x16FE1, 0x16FE3, 0x16FE3, 0x17000, 0x187F7, 0x18800, 0x18CD5,
            0x18D00, 0x18D08, 0x1AFF0, 0x1AFF3, 0x1AFF5, 0x1AFFB, 0x1AFFD, 0x1AFFE, 0x1B000, 0x1B122, 0x1B132, 0x1B132,
            0x1B150, 0x1B152, 0x1B155, 0x1B155, 0x1B164, 0x1B167, 0x1B170, 0x1B2FB, 0x1BC00, 0x1BC6A, 0x1BC70, 0x1BC7C,
            0x1BC80, 0x1BC88, 0x1BC90, 0x1BC99, 0x1D2C0, 0x1D2D3, 0x1D2E0, 0x1D2F3, 0x1D360, 0x1D378, 0x1D400, 0x1D454,
            0x1D456, 0x1D49C, 0x1D49E, 0x1D49F, 0x1D4A2, 0x1D4A2, 0x1D4A5, 0x1D4A6, 0x1D4A9, 0x1D4AC, 0x1D4AE, 0x1D4B9,
            0x1D4BB, 0x1D4BB, 0x1D4BD, 0x1D4C3, 0x1D4C5, 0x1D505, 0x1D507, 0x1D50A, 0x1D50D, 0x1D514, 0x1D516, 0x1D51C,
            0x1D51E, 0x1D539, 0x1D53B, 0x1D53E, 0x1D540, 0x1D544, 0x1D546, 0x1D546, 0x1D54A, 0x1D550, 0x1D552, 0x1D6A5,
            0x1D6A8, 0x1D6C0, 0x1D6C2, 0x1D6DA, 0x1D6DC, 0x1D6FA, 0x1D6FC, 0x1D714, 0x1D716, 0x1D734, 0x1D736, 0x1D74E,
            0x1D750, 0x1D76E, 0x1D770, 0x1D788, 0x1D78A, 0x1D7A8, 0x1D7AA, 0x1D7C2, 0x1D7C4, 0x1D7CB, 0x1D7CE, 0x1D7FF,
            0x1DF00, 0x1DF1E, 0x1DF25, 0x1DF2A, 0x1E030, 0x1E06D, 0x1E100, 0x1E12C, 0x1E137, 0x1E13D, 0x1E140, 0x1E149,
            0x1E14E, 0x1E14E, 0x1E290, 0x1E2AD, 0x1E2C0, 0x1E2EB, 0x1E2F0, 0x1E2F9, 0x1E4D0, 0x1E4EB, 0x1E4F0, 0x1E4F9,
            0x1E7E0, 0x1E7E6, 0x1E7E8, 0x1E7EB, 0x1E7ED, 0x1E7EE, 0x1E7F0, 0x1E7FE, 0x1E800, 0x1E8C4, 0x1E8C7, 0x1E8CF,
            0x1E900, 0x1E943, 0x1E94B, 0x1E94B, 0x1E950, 0x1E959, 0x1EC71, 0x1ECAB, 0x1ECAD, 0x1ECAF, 0x1ECB1, 0x1ECB4,
            0x1ED01, 0x1ED2D, 0x1ED2F, 0x1ED3D, 0x1EE00, 0x1EE03, 0x1EE05, 0x1EE1F, 0x1EE21, 0x1EE22, 0x1EE24, 0x1EE24,
            0x1EE27, 0x1EE27, 0x1EE29, 0x1EE32, 0x1EE34, 0x1EE37, 0x1EE39, 0x1EE39, 0x1EE3B, 0x1EE3B, 0x1EE42, 0x1EE42,
            0x1EE47, 0x1EE47, 0x1EE49, 0x1EE49, 0x1EE4B, 0x1EE4B, 0x1EE4D, 0x1EE4F, 0x1EE51, 0x1EE52, 0x1EE54, 0x1EE54,
            0x1EE57, 0x1EE57, 0x1EE59, 0x1EE59, 0x1EE5B, 0x1EE5B, 0x1EE5D, 0x1EE5D, 0x1EE5F, 0x1EE5F, 0x1EE61, 0x1EE62,
            0x1EE64, 0x1EE64, 0x1EE67, 0x1EE6A, 0x1EE6C, 0x1EE72, 0x1EE74, 0x1EE77, 0x1EE79, 0x1EE7C, 0x1EE7E, 0x1EE7E,
            0x1EE80, 0x1EE89, 0x1EE8B, 0x1EE9B, 0x1EEA1, 0x1EEA3, 0x1EEA5, 0x1EEA9, 0x1EEAB, 0x1EEBB, 0x1F100, 0x1F10C,
            0x1FBF0, 0x1FBF9, 0x20000, 0x2A6DF, 0x2A700, 0x2B739, 0x2B740, 0x2B81D, 0x2B820, 0x2CEA1, 0x2CEB0, 0x2EBE0,
            0x2F800, 0x2FA1D, 0x30000, 0x3134A, 0x31350, 0x323AF,
        )

        /**
         * Whether one code point is a letter or a digit the way `str.isalnum()` reads it in the pack
         * builder: the BMP answers with its Unicode general category, the set
         * [ALPHANUMERIC_CATEGORIES], and the planes above it with the generated
         * [ASTRAL_ALPHANUMERIC] table.
         */
        private fun isAlphanumeric(codePoint: Int): Boolean = when (codePoint) {
            in 0..0xFFFF -> codePoint.toChar().category in ALPHANUMERIC_CATEGORIES
            else -> containsAstralAlphanumeric(codePoint)
        }

        /** Binary-searches the sorted pairs of [ASTRAL_ALPHANUMERIC]; an unassigned code point is no letter. */
        private fun containsAstralAlphanumeric(codePoint: Int): Boolean {
            var low = 0
            var high = ASTRAL_ALPHANUMERIC.size / 2 - 1
            while (low <= high) {
                val middle = (low + high).ushr(1)
                when {
                    codePoint < ASTRAL_ALPHANUMERIC[middle * 2] -> high = middle - 1
                    codePoint > ASTRAL_ALPHANUMERIC[middle * 2 + 1] -> low = middle + 1
                    else -> return true
                }
            }
            return false
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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * TrackId → the identity a vector (or a failure) was made under, kept as a 128-bit digest of the identity string rather
 * than the string: the engine only ever asks whether a song's identity is still the same, and the strings (a song's
 * whole metadata text, its URI and revision) were ~130 bytes a song in each of three maps.
 *
 * A checkpoint stores the digest's own token ([persisted]); a token read back is taken as the digest it names, and any
 * other string (a checkpoint written before digests) is digested, so old files keep matching.
 *
 * Two independent 64-bit hashes of the string's UTF-16 units: equal strings always match, and different ones collide
 * with probability ~2⁻¹²⁸. Not thread-safe; the engine's mutex serializes access.
 */
internal class IdentityDigests {
    private val cells = OrderedIdMap()
    private var high = LongArray(16)
    private var low = LongArray(16)
    private var free = IntArray(16)
    private var freeCount = 0
    private var cellCount = 0

    val size: Int get() = cells.size

    operator fun contains(id: TrackId): Boolean = id in cells

    /** Whether [id] is recorded under [identity]. */
    fun matches(id: TrackId, identity: String): Boolean {
        val cell = cells[id] ?: return false
        return high[cell] == hashHigh(identity) && low[cell] == hashLow(identity)
    }

    /** Records [identity] for [id]; true when that changed what was recorded. */
    fun put(id: TrackId, identity: String): Boolean = putDigest(id, hashHigh(identity), hashLow(identity))

    /** Records a string read from a checkpoint: a digest token as the digest it names, anything else digested. */
    fun putPersisted(id: TrackId, stored: String) {
        val token = parseToken(stored)
        if (token != null) putDigest(id, token.first, token.second) else put(id, stored)
    }

    fun remove(id: TrackId): Boolean {
        val cell = cells.remove(id) ?: return false
        if (freeCount == free.size) free = free.copyOf(free.size * 2)
        free[freeCount++] = cell
        return true
    }

    fun clear() {
        cells.clear()
        high = LongArray(16)
        low = LongArray(16)
        free = IntArray(16)
        freeCount = 0
        cellCount = 0
    }

    fun ids(): List<TrackId> {
        val out = ArrayList<TrackId>(cells.size)
        cells.forEachEntry { id, _, _ -> out += id }
        return out
    }

    /** The recorded identities of the ids [keep] accepts, as checkpoint tokens, in insertion order. */
    fun persisted(keep: (TrackId) -> Boolean): Map<TrackId, String> {
        val out = LinkedHashMap<TrackId, String>()
        cells.forEachEntry { id, cell, _ -> if (keep(id)) out[id] = token(high[cell], low[cell]) }
        return out
    }

    private fun putDigest(id: TrackId, hi: Long, lo: Long): Boolean {
        val existing = cells[id]
        if (existing != null) {
            if (high[existing] == hi && low[existing] == lo) return false
            high[existing] = hi
            low[existing] = lo
            return true
        }
        val cell = if (freeCount > 0) free[--freeCount] else {
            if (cellCount == high.size) {
                high = high.copyOf(high.size * 2)
                low = low.copyOf(low.size * 2)
            }
            cellCount++
        }
        high[cell] = hi
        low[cell] = lo
        cells.put(id, cell)
        return true
    }

    internal companion object {
        private const val TOKEN_PREFIX = "#d1:"

        fun token(hi: Long, lo: Long): String = TOKEN_PREFIX + hex(hi) + hex(lo)

        /** The digest a stored token names, or null when [stored] is not a token. */
        fun parseToken(stored: String): Pair<Long, Long>? {
            if (stored.length != TOKEN_PREFIX.length + 32 || !stored.startsWith(TOKEN_PREFIX)) return null
            val hi = stored.substring(TOKEN_PREFIX.length, TOKEN_PREFIX.length + 16).toULongOrNull(16) ?: return null
            val lo = stored.substring(TOKEN_PREFIX.length + 16).toULongOrNull(16) ?: return null
            return hi.toLong() to lo.toLong()
        }

        /** The token of [identity]'s digest, as a checkpoint would store it. */
        fun tokenOf(identity: String): String = token(hashHigh(identity), hashLow(identity))

        private fun hex(value: Long): String = value.toULong().toString(16).padStart(16, '0')

        /** FNV-1a over the UTF-16 units, 64-bit, then a final avalanche. */
        fun hashHigh(text: String): Long {
            var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
            for (c in text) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return mix(h xor text.length.toLong())
        }

        /** A second, independent hash: multiply-rotate over the units with a different seed and multiplier. */
        fun hashLow(text: String): Long {
            var h = 0x2545F4914F6CDD1DL
            for (c in text) {
                h = (h + c.code.toLong()) * -0x61c8864680b583ebL // 0x9e3779b97f4a7c15
                h = h xor (h ushr 29)
            }
            return mix(h + text.length.toLong())
        }

        private fun mix(value: Long): Long {
            var z = value
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

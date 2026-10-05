/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/** Test fixtures in the LJENT2 layout, written the way tools/research/pack_music_entities.py writes it. */
internal object EntityIndexBytes {
    const val HEADER_SIZE = 24
    const val KEYS_OFFSET = HEADER_SIZE + 65_537 * 4
    const val KEY_SIZE = 7

    /** The 48-bit key hash of an already-normalized name. */
    fun hash(key: String): ULong = MusicEntityIndex.fnv1a64(key.encodeToByteArray()) and 0xffffffffffffuL

    /** Normalized names mapped to entity ids, which are written in the order given. */
    fun of(vararg mappings: Pair<String, IntArray>, entityCount: Int = 100): ByteArray =
        hashed(mappings.map { (key, ids) -> hash(key) to ids }, entityCount)

    /** Keys given as 48-bit hashes, written in the order given, so a test can break the ordering. */
    fun hashed(keys: List<Pair<ULong, IntArray>>, entityCount: Int = 100, sort: Boolean = true): ByteArray {
        val ordered = if (sort) keys.sortedBy { it.first } else keys
        val valueCount = ordered.sumOf { it.second.size }
        val bytes = ByteArray(KEYS_OFFSET + ordered.size * KEY_SIZE + valueCount * 3)
        "LJENT2\u0000\u0000".encodeToByteArray().copyInto(bytes)
        writeInt(bytes, 8, ordered.size)
        writeInt(bytes, 12, valueCount)
        writeInt(bytes, 16, entityCount)
        val perBucket = IntArray(65_537)
        ordered.forEach { (hash, _) -> perBucket[(hash shr 32).toInt() + 1]++ }
        for (bucket in 0 until 65_536) perBucket[bucket + 1] += perBucket[bucket]
        perBucket.forEachIndexed { bucket, start -> writeInt(bytes, HEADER_SIZE + bucket * 4, start) }
        var valueOffset = 0
        val valuesStart = KEYS_OFFSET + ordered.size * KEY_SIZE
        ordered.forEachIndexed { index, (hash, ids) ->
            val key = KEYS_OFFSET + index * KEY_SIZE
            writeInt(bytes, key, (hash and 0xffffffffuL).toInt())
            writeU24(bytes, key + 4, valueOffset)
            ids.forEachIndexed { offset, id -> writeU24(bytes, valuesStart + (valueOffset + offset) * 3, id) }
            valueOffset += ids.size
        }
        return bytes
    }

    /** The same keys in the LJENT3 layout tools/research/compact_music_entities.py writes. */
    fun compact(vararg mappings: Pair<String, IntArray>, entityCount: Int = 100): ByteArray =
        compactHashed(mappings.map { (key, ids) -> hash(key) to ids }, entityCount)

    fun compactHashed(keys: List<Pair<ULong, IntArray>>, entityCount: Int = 100): ByteArray {
        val ordered = keys.sortedBy { it.first }
        val stream = ArrayList<Byte>()
        fun varint(value: Int) {
            var rest = value
            while (true) {
                val byte = rest and 0x7f
                rest = rest ushr 7
                if (rest == 0) { stream += byte.toByte(); return }
                stream += (byte or 0x80).toByte()
            }
        }
        val starts = IntArray(65_537)
        var bucket = 0
        ordered.forEach { (hash, ids) ->
            val target = (hash shr 32).toInt()
            while (bucket <= target) starts[bucket++] = stream.size
            val stored = ((hash shr 8) and 0xffffffuL).toInt()
            for (index in 0 until 3) stream += (stored ushr (index * 8)).toByte()
            varint(ids.size)
            ids.forEachIndexed { index, id -> varint(if (index == 0) id else id - ids[index - 1]) }
        }
        while (bucket <= 65_536) starts[bucket++] = stream.size
        val bytes = ByteArray(KEYS_OFFSET + stream.size)
        "LJENT3\u0000\u0000".encodeToByteArray().copyInto(bytes)
        writeInt(bytes, 8, ordered.size)
        writeInt(bytes, 12, ordered.sumOf { it.second.size })
        writeInt(bytes, 16, entityCount)
        starts.forEachIndexed { index, start -> writeInt(bytes, HEADER_SIZE + index * 4, start) }
        stream.toByteArray().copyInto(bytes, KEYS_OFFSET)
        return bytes
    }

    fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    fun writeU24(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 3) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}

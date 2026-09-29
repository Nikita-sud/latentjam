/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * CRC-32 (IEEE 802.3, reflected) — the checksum zip and PNG use. Streaming, so an audio region can
 * be checksummed in chunks without holding it in memory.
 */
public class Crc32 {
    private var crc: Int = -1

    public fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        var c = crc
        for (i in offset until offset + count) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    /** The checksum of everything passed to [update] so far, as an unsigned 32-bit value. */
    public val value: Long get() = crc.inv().toLong() and 0xFFFFFFFFL

    public companion object {
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        public fun of(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Long =
            Crc32().apply { update(bytes, offset, count) }.value
    }
}

/** The Ogg page checksum: polynomial 0x04C11DB7, initial value 0, no reflection, no final XOR. */
internal object OggCrc {
    private val TABLE = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun compute(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Int {
        var crc = 0
        for (i in offset until offset + count) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}

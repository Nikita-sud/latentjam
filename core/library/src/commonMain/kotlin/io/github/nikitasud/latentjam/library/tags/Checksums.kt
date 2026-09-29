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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Hex-of-UTF-8 text fields, the encoding every line-based store uses for names that may hold `|`
 * or a newline. One shared codec instead of a copy per store, built for 10,000-row payloads read
 * at every launch on a 48 MB heap: no substring per byte, no String per encoded byte, and no
 * strict JVM decoder per field (on Android each one is an ICU converter with a native cleaner).
 *
 * [decode] is as strict as `decodeToString(throwOnInvalidSequence = true)`: anything that is not
 * well-formed UTF-8 (a stray continuation, an overlong form, a surrogate, past U+10FFFF) is null.
 */
public object PayloadHex {
    private const val DIGITS = "0123456789abcdef"

    public fun encode(value: String): String {
        val bytes = value.encodeToByteArray()
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 0xff
            out[index * 2] = DIGITS[byte ushr 4]
            out[index * 2 + 1] = DIGITS[byte and 0x0f]
        }
        return out.concatToString()
    }

    /** [value]'s text, or null when it is not even-length hex of well-formed UTF-8. */
    public fun decode(value: String): String? = decode(value, 0, value.length)

    /** [decode] over `value[start, end)`, so a caller splitting a line needs no substring. */
    public fun decode(value: CharSequence, start: Int, end: Int): String? {
        val length = end - start
        if (length < 0 || length % 2 != 0) return null
        if (length == 0) return ""
        val bytes = ByteArray(length / 2)
        var ascii = true
        for (index in bytes.indices) {
            val high = nibble(value[start + index * 2])
            val low = nibble(value[start + index * 2 + 1])
            if (high < 0 || low < 0) return null
            val byte = (high shl 4) or low
            if (byte >= 0x80) ascii = false
            bytes[index] = byte.toByte()
        }
        if (ascii) return CharArray(bytes.size) { bytes[it].toInt().toChar() }.concatToString()
        if (!isWellFormedUtf8(bytes)) return null
        return bytes.decodeToString()
    }

    private fun nibble(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

    /** The Unicode well-formed byte sequences (Table 3-7); what a strict decoder accepts. */
    internal fun isWellFormedUtf8(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            val lead = bytes[index].toInt() and 0xff
            if (lead < 0x80) {
                index++
                continue
            }
            val count: Int
            var low = 0x80
            var high = 0xbf
            when (lead) {
                in 0xc2..0xdf -> count = 1
                0xe0 -> { count = 2; low = 0xa0 }
                in 0xe1..0xec, 0xee, 0xef -> count = 2
                0xed -> { count = 2; high = 0x9f }
                0xf0 -> { count = 3; low = 0x90 }
                in 0xf1..0xf3 -> count = 3
                0xf4 -> { count = 3; high = 0x8f }
                else -> return false
            }
            if (index + count > bytes.lastIndex) return false
            val second = bytes[index + 1].toInt() and 0xff
            if (second < low || second > high) return false
            for (offset in 2..count) {
                val next = bytes[index + offset].toInt() and 0xff
                if (next < 0x80 || next > 0xbf) return false
            }
            index += count + 1
        }
        return true
    }
}

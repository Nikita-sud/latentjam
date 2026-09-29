/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Repairs text that was UTF-8 but got decoded once (or twice) as a single-byte
 * ISO-8859-1/cp1252 codec — the classic mojibake produced when a MediaStore
 * column, an ID3 encoding-0 frame, or a lossy re-tag assumes Latin-1 for bytes
 * that were actually UTF-8. "üß" mangled this way reads back as "Ã¼ÃŸ".
 *
 * The repair is speculative: every character of the input is mapped back to
 * the single byte a Latin-1/cp1252 decoder would have produced it from, and
 * those bytes are re-decoded as strict UTF-8. If either step is impossible —
 * a character outside that byte range, or bytes that are not valid UTF-8 —
 * the input almost certainly was not mangled this way and is returned as is.
 */
public object TextRepair {

    /**
     * Undoes up to two rounds of UTF-8-decoded-as-Latin-1/cp1252 mojibake.
     *
     * A character below U+0080 can never come from this kind of mistake (a
     * UTF-8 continuation or lead byte is always ≥ 0x80), so text made only of
     * those is returned unchanged without doing any work.
     *
     * One round is tried first; if it changes the text, a second round is
     * tried too, to undo double-encoded text (the same mistake made twice in
     * a chain of tools). No more than two rounds run, and a round that
     * changes nothing stops the process immediately.
     */
    public fun repair(text: String): String {
        if (text.none { it.code >= 0x80 }) return text
        val once = repairOnce(text)
        if (once == text) return text
        val twice = repairOnce(once)
        return if (twice == once) once else twice
    }

    /**
     * Decodes `bytes[from, to)` as strict UTF-8, rejecting anything a
     * conforming decoder would reject: overlong forms, encoded surrogates
     * (the U+D800-U+DFFF range, i.e. an `ED` lead with a second byte in
     * `A0..BF`), code points above U+10FFFF, truncated sequences and stray
     * continuation bytes.
     *
     * Hand-rolled rather than [ByteArray.decodeToString] so the result is
     * identical on the JVM and Kotlin/Native, and so invalid input is
     * reported by returning null instead of silently becoming U+FFFD.
     */
    internal fun decodeUtf8Strict(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String? {
        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 < 0x80 -> {
                    sb.append(b0.toChar())
                    i += 1
                }
                b0 in 0xC2..0xDF -> {
                    if (i + 1 >= to) return null
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if (b1 !in 0x80..0xBF) return null
                    sb.append((((b0 and 0x1F) shl 6) or (b1 and 0x3F)).toChar())
                    i += 2
                }
                b0 in 0xE0..0xEF -> {
                    if (i + 2 >= to) return null
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    // E0's second byte must be >= A0 and ED's must be <= 9F: the
                    // ranges that would otherwise produce an overlong form or a
                    // surrogate code point respectively.
                    val minB1 = if (b0 == 0xE0) 0xA0 else 0x80
                    val maxB1 = if (b0 == 0xED) 0x9F else 0xBF
                    if (b1 !in minB1..maxB1) return null
                    if (b2 !in 0x80..0xBF) return null
                    val codePoint = ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
                    sb.append(codePoint.toChar())
                    i += 3
                }
                b0 in 0xF0..0xF4 -> {
                    if (i + 3 >= to) return null
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    val b3 = bytes[i + 3].toInt() and 0xFF
                    // F0's second byte must be >= 90 (else overlong) and F4's must
                    // be <= 8F (else the code point exceeds U+10FFFF).
                    val minB1 = if (b0 == 0xF0) 0x90 else 0x80
                    val maxB1 = if (b0 == 0xF4) 0x8F else 0xBF
                    if (b1 !in minB1..maxB1) return null
                    if (b2 !in 0x80..0xBF) return null
                    if (b3 !in 0x80..0xBF) return null
                    val codePoint = ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or
                        ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                    if (codePoint > 0x10FFFF) return null
                    val adjusted = codePoint - 0x10000
                    sb.append((0xD800 + (adjusted shr 10)).toChar())
                    sb.append((0xDC00 + (adjusted and 0x3FF)).toChar())
                    i += 4
                }
                // 0x80-0xC1: a stray continuation byte, or a lead byte (C0/C1)
                // that can only ever produce an overlong two-byte sequence.
                // 0xF5-0xFF: not a legal lead byte at all.
                else -> return null
            }
        }
        return sb.toString()
    }

    /** One round of the repair: every char to a byte, then strict UTF-8. */
    private fun repairOnce(text: String): String {
        val bytes = ByteArray(text.length)
        for (index in text.indices) {
            val code = text[index].code
            bytes[index] = when {
                code <= 0xFF -> code.toByte()
                else -> CP1252_SPECIALS[code]?.toByte() ?: return text
            }
        }
        return decodeUtf8Strict(bytes) ?: text
    }

    /**
     * cp1252's 27 characters above U+00FF, keyed by code point and valued by
     * the single cp1252 byte (0x80-0x9F) that decodes to them. cp1252 leaves
     * five bytes in that range undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D); a
     * lenient decoder — including Android's — emits those as the raw C1
     * control code point instead, which the 0x00-0xFF branch above already
     * covers without needing an entry here.
     */
    private val CP1252_SPECIALS: Map<Int, Int> = mapOf(
        0x20AC to 0x80, // €
        0x201A to 0x82, // ‚
        0x0192 to 0x83, // ƒ
        0x201E to 0x84, // „
        0x2026 to 0x85, // …
        0x2020 to 0x86, // †
        0x2021 to 0x87, // ‡
        0x02C6 to 0x88, // ˆ
        0x2030 to 0x89, // ‰
        0x0160 to 0x8A, // Š
        0x2039 to 0x8B, // ‹
        0x0152 to 0x8C, // Œ
        0x017D to 0x8E, // Ž
        0x2018 to 0x91, // '
        0x2019 to 0x92, // '
        0x201C to 0x93, // "
        0x201D to 0x94, // "
        0x2022 to 0x95, // •
        0x2013 to 0x96, // –
        0x2014 to 0x97, // —
        0x02DC to 0x98, // ˜
        0x2122 to 0x99, // ™
        0x0161 to 0x9A, // š
        0x203A to 0x9B, // ›
        0x0153 to 0x9C, // œ
        0x017E to 0x9E, // ž
        0x0178 to 0x9F, // Ÿ
    )
}

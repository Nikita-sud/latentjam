/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Text codec for ID3v2 text frames.
 *
 * A text frame body is one encoding byte followed by the encoded string. The
 * common Kotlin stdlib ships only a UTF-8 codec, so ISO-8859-1 and UTF-16 are
 * hand-rolled here — which is also the only way to be certain the JVM and
 * Kotlin/Native agree byte for byte.
 */
internal object Id3Text {

    /** ISO-8859-1. The only single-byte encoding ID3v2 defines. */
    const val ISO_8859_1: Int = 0

    /** UTF-16 with a mandatory byte order mark. Legal in both 2.3 and 2.4. */
    const val UTF_16_WITH_BOM: Int = 1

    /** UTF-16BE, no BOM. ID3v2.4 only. */
    const val UTF_16BE: Int = 2

    /** UTF-8. ID3v2.4 only — writing it in a 2.3 tag produces mojibake. */
    const val UTF_8: Int = 3

    /**
     * Decodes `bytes[from, to)` with an ID3 [encoding] byte.
     *
     * Returns null for an encoding byte outside 0..3, which means the caller
     * must treat the frame as opaque rather than guess at its contents.
     */
    fun decode(encoding: Int, bytes: ByteArray, from: Int, to: Int): String? {
        if (from >= to) return ""
        return when (encoding) {
            ISO_8859_1 -> decodeLatin1(bytes, from, to)
            UTF_16_WITH_BOM -> decodeUtf16WithBom(bytes, from, to)
            UTF_16BE -> decodeUtf16(bytes, from, to, bigEndian = true)
            // throwOnInvalidSequence = false: a mangled byte becomes U+FFFD.
            // Reading a tag must never throw, however bad the bytes are.
            UTF_8 -> bytes.decodeToString(from, to, throwOnInvalidSequence = false)
            else -> null
        }
    }

    /**
     * Builds a text frame body: the encoding byte followed by the encoded text.
     *
     * The encoding is chosen per version, and this choice is the whole reason
     * this file exists:
     *
     * - Text that fits ISO-8859-1 goes out as encoding 0 in both versions. It is
     *   the most compact form and the one every decoder ever written handles.
     * - Otherwise, **2.4 gets UTF-8 and 2.3 gets UTF-16 with a BOM**. UTF-8 is
     *   not a legal ID3v2.3 encoding; writing it anyway is the single most
     *   common way a tagger destroys a Cyrillic or Japanese title, because
     *   conforming readers decode those bytes as ISO-8859-1.
     *
     * No trailing terminator is written. It is optional for the final string in
     * a text frame, and omitting it stops readers that fail to strip it from
     * showing a stray NUL.
     */
    fun encodeTextFrameBody(text: String, version: Id3Version): ByteArray {
        // An embedded NUL is the ID3v2.4 value separator; letting one through
        // would silently split the field into two values on the next read.
        val clean = if (text.indexOf('\u0000') >= 0) text.replace("\u0000", "") else text

        if (isLatin1(clean)) {
            val out = ByteArray(1 + clean.length)
            out[0] = ISO_8859_1.toByte()
            for (i in clean.indices) out[i + 1] = clean[i].code.toByte()
            return out
        }

        return when (version) {
            Id3Version.V2_4 -> {
                val utf8 = clean.encodeToByteArray()
                val out = ByteArray(1 + utf8.size)
                out[0] = UTF_8.toByte()
                utf8.copyInto(out, destinationOffset = 1)
                out
            }
            // A Kotlin String is already a sequence of UTF-16 code units, so
            // surrogate pairs (and therefore anything outside the BMP) fall out
            // of this loop correctly without special handling.
            Id3Version.V2_3 -> {
                val out = ByteArray(1 + 2 + clean.length * 2)
                out[0] = UTF_16_WITH_BOM.toByte()
                out[1] = 0xFF.toByte()
                out[2] = 0xFE.toByte()
                var p = 3
                for (c in clean) {
                    out[p++] = (c.code and 0xFF).toByte()
                    out[p++] = ((c.code shr 8) and 0xFF).toByte()
                }
                out
            }
        }
    }

    /** True when every character has a single-byte ISO-8859-1 representation. */
    fun isLatin1(text: String): Boolean = text.all { it.code <= 0xFF }

    /**
     * Reverses ID3 unsynchronisation: every `$FF $00` pair becomes a lone `$FF`.
     *
     * Used only on the read path, for ID3v2.4 frames that carry the frame-level
     * unsynchronisation flag. Such frames are still copied to the output
     * verbatim; this only makes their text legible.
     */
    fun deunsynchronise(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var n = 0
        var i = from
        while (i < to) {
            val b = bytes[i]
            out[n++] = b
            i++
            if (b == 0xFF.toByte() && i < to && bytes[i] == 0.toByte()) i++
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun decodeLatin1(bytes: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        for (i in from until to) sb.append(((bytes[i].toInt() and 0xFF)).toChar())
        return sb.toString()
    }

    private fun decodeUtf16WithBom(bytes: ByteArray, from: Int, to: Int): String {
        if (to - from < 2) return ""
        val a = bytes[from].toInt() and 0xFF
        val b = bytes[from + 1].toInt() and 0xFF
        return when {
            a == 0xFF && b == 0xFE -> decodeUtf16(bytes, from + 2, to, bigEndian = false)
            a == 0xFE && b == 0xFF -> decodeUtf16(bytes, from + 2, to, bigEndian = true)
            // The BOM is mandatory but plenty of tags omit it. Big-endian is the
            // Unicode default and what other readers assume in this situation.
            else -> decodeUtf16(bytes, from, to, bigEndian = true)
        }
    }

    private fun decodeUtf16(bytes: ByteArray, from: Int, to: Int, bigEndian: Boolean): String {
        val sb = StringBuilder((to - from) / 2)
        var i = from
        while (i + 1 < to) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            sb.append((if (bigEndian) (a shl 8) or b else (b shl 8) or a).toChar())
            i += 2
        }
        return sb.toString()
    }
}

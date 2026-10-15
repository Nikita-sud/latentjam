/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * Text of a document the listener picked. A `.m3u` has no declared encoding: other players and
 * Windows tools write UTF-8, UTF-16 with a byte order mark, or the ANSI code page. A UTF-16 mark
 * decides; otherwise valid UTF-8 is UTF-8, and anything else is read as Windows-1252 (a superset
 * of Latin-1) instead of turning every accented name into U+FFFD and silently missing its song.
 * A UTF-8 mark is kept for the caller, as a UTF-8 reader would.
 */
internal fun decodeImportedText(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first == 0xff && second == 0xfe) return bytes.decodeUtf16(2, littleEndian = true)
        if (first == 0xfe && second == 0xff) return bytes.decodeUtf16(2, littleEndian = false)
    }
    return bytes.decodeUtf8OrWindows1252()
}

/** Strict UTF-8 when the bytes are UTF-8, otherwise Windows-1252. */
internal fun ByteArray.decodeUtf8OrWindows1252(start: Int = 0, end: Int = size): String =
    try {
        decodeToString(start, end, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        decodeWindows1252(start, end)
    }

private fun ByteArray.decodeUtf16(start: Int, littleEndian: Boolean): String {
    val chars = CharArray((size - start) / 2)
    for (index in chars.indices) {
        val a = this[start + index * 2].toInt() and 0xff
        val b = this[start + index * 2 + 1].toInt() and 0xff
        chars[index] = (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar()
    }
    return chars.concatToString()
}

// 0x80..0x9F of Windows-1252; its five unassigned bytes keep their C1 code point, as browsers do.
private const val WINDOWS_1252_HIGH =
    "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F" +
        "\u0090‘’“”•–—˜™š›œ\u009DžŸ"

private fun ByteArray.decodeWindows1252(start: Int, end: Int): String {
    val chars = CharArray(end - start)
    for (index in chars.indices) {
        val byte = this[start + index].toInt() and 0xff
        chars[index] = if (byte in 0x80..0x9f) WINDOWS_1252_HIGH[byte - 0x80] else byte.toChar()
    }
    return chars.concatToString()
}

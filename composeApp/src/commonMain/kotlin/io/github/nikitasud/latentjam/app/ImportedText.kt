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
internal fun decodeImportedText(bytes: ByteArray): String = readImportedText(bytes).text

/**
 * The text of a picked document, and whether it had to be read as the ANSI code page ([ansi]): it is neither
 * UTF-16 nor UTF-8, so Windows-1252 is a guess, and [otherCodePageReadings] gives the others.
 */
internal class ImportedText(val text: String, val ansi: Boolean)

/** [decodeImportedText] with how the text was read. */
internal fun readImportedText(bytes: ByteArray): ImportedText {
    if (bytes.size >= 2) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first == 0xff && second == 0xfe) return ImportedText(bytes.decodeUtf16(2, littleEndian = true), ansi = false)
        if (first == 0xfe && second == 0xff) return ImportedText(bytes.decodeUtf16(2, littleEndian = false), ansi = false)
    }
    return try {
        ImportedText(bytes.decodeToString(throwOnInvalidSequence = true), ansi = false)
    } catch (_: CharacterCodingException) {
        ImportedText(bytes.decodeWindows1252(0, bytes.size), ansi = true)
    }
}

/**
 * The same bytes as [ansiText] read in the other one-byte code pages Windows players write playlists in:
 * Windows-1251 (Russian, Ukrainian, Bulgarian) and Windows-1250 (Romanian, Polish, Czech, Hungarian), in that
 * order. [ansiText] must be a Windows-1252 reading (see [ImportedText.ansi]), which keeps every byte as one
 * character; for any other text the list is empty. A byte a code page does not define reads as U+FFFD.
 */
internal fun otherCodePageReadings(ansiText: String): List<String> {
    val bytes = IntArray(ansiText.length)
    for (index in ansiText.indices) {
        val char = ansiText[index]
        bytes[index] = when {
            char.code < 0x80 || char.code in 0xa0..0xff -> char.code
            else -> WINDOWS_1252_HIGH.indexOf(char).takeIf { it >= 0 }?.plus(0x80) ?: return emptyList()
        }
    }
    return listOf(WINDOWS_1251_HIGH, WINDOWS_1250_HIGH).map { high ->
        CharArray(bytes.size) { index -> bytes[index].let { if (it < 0x80) it.toChar() else high[it - 0x80] } }.concatToString()
    }
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

// 0x80..0xFF of Windows-1251 and Windows-1250.
private const val WINDOWS_1251_HIGH =
    "\u0402\u0403\u201A\u0453\u201E\u2026\u2020\u2021\u20AC\u2030\u0409\u2039\u040A\u040C\u040B\u040F" +
        "\u0452\u2018\u2019\u201C\u201D\u2022\u2013\u2014\uFFFD\u2122\u0459\u203A\u045A\u045C\u045B\u045F" +
        "\u00A0\u040E\u045E\u0408\u00A4\u0490\u00A6\u00A7\u0401\u00A9\u0404\u00AB\u00AC\u00AD\u00AE\u0407" +
        "\u00B0\u00B1\u0406\u0456\u0491\u00B5\u00B6\u00B7\u0451\u2116\u0454\u00BB\u0458\u0405\u0455\u0457" +
        "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F" +
        "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A\u042B\u042C\u042D\u042E\u042F" +
        "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F" +
        "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F"

private const val WINDOWS_1250_HIGH =
    "\u20AC\uFFFD\u201A\uFFFD\u201E\u2026\u2020\u2021\uFFFD\u2030\u0160\u2039\u015A\u0164\u017D\u0179" +
        "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\uFFFD\u2122\u0161\u203A\u015B\u0165\u017E\u017A" +
        "\u00A0\u02C7\u02D8\u0141\u00A4\u0104\u00A6\u00A7\u00A8\u00A9\u015E\u00AB\u00AC\u00AD\u00AE\u017B" +
        "\u00B0\u00B1\u02DB\u0142\u00B4\u00B5\u00B6\u00B7\u00B8\u0105\u015F\u00BB\u013D\u02DD\u013E\u017C" +
        "\u0154\u00C1\u00C2\u0102\u00C4\u0139\u0106\u00C7\u010C\u00C9\u0118\u00CB\u011A\u00CD\u00CE\u010E" +
        "\u0110\u0143\u0147\u00D3\u00D4\u0150\u00D6\u00D7\u0158\u016E\u00DA\u0170\u00DC\u00DD\u0162\u00DF" +
        "\u0155\u00E1\u00E2\u0103\u00E4\u013A\u0107\u00E7\u010D\u00E9\u0119\u00EB\u011B\u00ED\u00EE\u010F" +
        "\u0111\u0144\u0148\u00F3\u00F4\u0151\u00F6\u00F7\u0159\u016F\u00FA\u0171\u00FC\u00FD\u0163\u02D9"

private fun ByteArray.decodeWindows1252(start: Int, end: Int): String {
    val chars = CharArray(end - start)
    for (index in chars.indices) {
        val byte = this[start + index].toInt() and 0xff
        chars[index] = if (byte in 0x80..0x9f) WINDOWS_1252_HIGH[byte - 0x80] else byte.toChar()
    }
    return chars.concatToString()
}

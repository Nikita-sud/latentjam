/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Repairs text that was UTF-8 but got decoded once (or twice) as a single-byte
 * Windows codepage — the classic mojibake produced when a MediaStore column, an
 * ID3 encoding-0 frame, or a lossy re-tag assumes a legacy charset for bytes
 * that were actually UTF-8. "üß" mangled this way reads back as "Ã¼ÃŸ" through
 * ISO-8859-1/cp1252, "ĂĽĂź" through Windows-1250 and "ГјГџ" through
 * Windows-1251; Android picks among these by device locale.
 *
 * The repair is speculative: every character of the input is mapped back to
 * the single byte that codepage would have produced it from, and those bytes
 * are re-decoded as strict UTF-8. If either step is impossible — a character
 * the codepage has no byte for, or bytes that are not valid UTF-8 — the input
 * almost certainly was not mangled that way and is returned as is.
 *
 * Valid UTF-8 is not proof on its own. Correctly spelled Central-European,
 * Turkish and Czech capitals often map back to a valid pair: "PÓŁNOC" through
 * Windows-1250 is the bytes of "PӣNOC", "KÖŞE" of "K֪E", and "PETR SEPÉŠI"
 * through cp1252 itself of "PETR SEPɊI". So a candidate that decodes is still
 * refused when [isImplausible] says it looks like one of those accidents. The
 * rule was measured on 111,069 non-ASCII MusicBrainz artist names and aliases,
 * as written and uppercased: it changes none of the genuine ones (the only
 * names it changes are nine that MusicBrainz itself stores as mojibake), and
 * of the same names mangled once it gives up 497 (cp1252), 1,139 (Windows-1250)
 * and 1,622 (Windows-1251) that a bare decode would have repaired — mostly
 * single CJK characters and one-letter words such as "à", which read the same
 * as genuine short Cyrillic words to the fallbacks' guards.
 */
public object TextRepair {

    /**
     * Undoes up to two rounds of UTF-8-decoded-as-a-Windows-codepage mojibake.
     *
     * A character below U+0080 can never come from this kind of mistake (a
     * UTF-8 continuation or lead byte is always ≥ 0x80), so text made only of
     * those is returned unchanged without doing any work.
     *
     * cp1252 (with Latin-1) is tried first, then Windows-1250, then
     * Windows-1251; the first that gives an acceptable repair wins. A second
     * round then undoes double-encoded text (the same mistake made twice in a
     * chain of tools), but only through the codepage that won the first: the
     * first round's result is real text, and letting another codepage at it
     * could garble a correct repair again ("PÃ“Å\u0081NOC" repairs to
     * "PÓŁNOC", which Windows-1250 would then read as "PӣNOC").
     */
    public fun repair(text: String): String {
        if (text.none { it.code >= 0x80 }) return text
        for (codepage in CODEPAGES) {
            val once = repairWith(codepage, text) ?: continue
            return repairWith(codepage, once) ?: once
        }
        return text
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

    /** One round through [codepage]: the repaired text, or null if it does not apply. */
    private fun repairWith(codepage: Codepage, text: String): String? {
        val bytes = codepage.encode(text) ?: return null
        val repaired = decodeUtf8Strict(bytes) ?: return null
        if (isImplausible(codepage, text, bytes, repaired)) return null
        if (codepage.isFallback && (!isPlausibleFallback(text, repaired) || isStandaloneSequence(text, bytes))) {
            return null
        }
        return repaired
    }

    /**
     * Whether a repair that decodes is still more likely an accident of genuine
     * text than real mojibake. Any one of these refuses it:
     *
     * - a C1 control (U+0080-U+009F) in the result: no real name has one;
     * - a decoded character in U+0250-U+07FF (IPA, Greek, Cyrillic, Armenian,
     *   Hebrew, Arabic, …) other than the modifier letters U+02B0-U+02FF, with
     *   an ASCII letter directly beside it in the result: "κi face loc" from
     *   "Îşi face loc", "PӣNOC" from "PÓŁNOC". Real names mix scripts at word
     *   boundaries, not inside a word;
     * - a two-byte sequence spelled by two capitals, or a capital and a right
     *   single quote, inside an all-caps word ([isCapsWord]): "ÉŠ" in
     *   "SEPÉŠI". Mojibake of a capital is usually a capital followed by a
     *   symbol ("Ó" reads "Ã“", "Ă“" or "Г“"), so two capitals are far more
     *   likely the genuine word. Exempt through cp1252 are pairs led by C3, C4
     *   or C5 ("Ã", "Ä", "Å"): those lead every Latin-1 and Latin Extended-A
     *   letter, and "Ã"/"Ä"/"Å" plus "Š", "Œ", "Ž", "Ÿ" or "’" is uppercase
     *   mojibake ("FÃŠTE" for "FÊTE", "ÄŒESKÃ" for "ČESKÁ") far more often than
     *   a genuine word. Not through Windows-1250, where "ĂŞ" is Romanian "PĂŞUNE";
     * - a three-byte sequence spelled by three letters with an ASCII letter
     *   directly beside them in the input: Czech "Déšť" through Windows-1250
     *   is the bytes of "D隝". Exempt are sequences that decode to Latin
     *   Extended Additional (U+1E00-U+1EFF, Vietnamese) or to punctuation and
     *   symbols (U+2000-U+2BFF), which do sit inside Latin words.
     */
    private fun isImplausible(codepage: Codepage, text: String, bytes: ByteArray, repaired: String): Boolean {
        if (repaired.any { it.code in 0x80..0x9F }) return true
        for (index in repaired.indices) {
            val code = repaired[index].code
            if (code !in 0x0250..0x07FF || code in 0x02B0..0x02FF) continue
            if (repaired.isAsciiLetterAt(index - 1) || repaired.isAsciiLetterAt(index + 1)) return true
        }
        for (index in 0 until bytes.size - 1) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = bytes[index + 1].toInt() and 0xFF
            if (b0 !in 0xC2..0xDF || b1 !in 0x80..0xBF) continue
            if (codepage === CP1252 && b0 in 0xC3..0xC5) continue
            val first = text[index]
            val second = text[index + 1]
            if (first.isUpperCase() && (second.isUpperCase() || second == RIGHT_QUOTE) &&
                isCapsWord(text, index, index + 2)
            ) {
                return true
            }
        }
        for (index in 0 until bytes.size - 2) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = bytes[index + 1].toInt() and 0xFF
            val b2 = bytes[index + 2].toInt() and 0xFF
            if (b0 !in 0xE0..0xEF || b1 !in 0x80..0xBF || b2 !in 0x80..0xBF) continue
            val code = ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
            if (code in 0x1E00..0x1EFF || code in 0x2000..0x2BFF) continue
            if (text[index].isLetter() && text[index + 1].isLetter() && text[index + 2].isLetter() &&
                (text.isAsciiLetterAt(index - 1) || text.isAsciiLetterAt(index + 3))
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Whether `text[from, to)` lies in an all-caps word: the run of letters and
     * right single quotes around it holds an ASCII capital and no lowercase letter.
     */
    private fun isCapsWord(text: String, from: Int, to: Int): Boolean {
        var start = from
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == RIGHT_QUOTE)) start--
        var end = to
        while (end < text.length && (text[end].isLetter() || text[end] == RIGHT_QUOTE)) end++
        var hasAsciiCapital = false
        for (index in start until end) {
            val char = text[index]
            if (char.isLowerCase()) return false
            if (char in 'A'..'Z') hasAsciiCapital = true
        }
        return hasAsciiCapital
    }

    private fun String.isAsciiLetterAt(index: Int): Boolean =
        index in indices && this[index].let { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * Guards the Windows-1250/1251 fallbacks against correctly spelled short words
     * (with [isStandaloneSequence]).
     * Cyrillic is almost entirely Windows-1251 bytes C0-FF (UTF-8 lead bytes) and
     * Ukrainian, Belarusian and Serbian letters such as і, ї, ё, ў, ђ sit in 80-BF
     * (UTF-8 continuation bytes), so a genuine short word can map to one valid
     * pair: "Ні" would become "ͳ", "Ві" "³", "Её" "Ÿ". The fallback is refused
     * when the whole repair is a single two-byte pair with no ASCII letter or
     * digit anywhere in the text — "BjĂ¶rk" still repairs, a bare "Я" read as
     * "РЇ" is the price.
     */
    private fun isPlausibleFallback(text: String, repaired: String): Boolean =
        text.length - repaired.length >= 2 ||
            text.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /**
     * The other half of the short-word guard for the Windows-1250/1251 fallbacks:
     * whether [bytes] hold exactly one multi-byte UTF-8 sequence and the characters of
     * [text] it was read from stand as a word of their own, with no letter of any script
     * directly before or after them. Genuine "Ні" beside a number or a bracket ("Ні 2",
     * "Ні (Live)") is that shape and would read "ͳ 2"; mojibake of a single accented
     * letter inside a Latin word ("MГјller") has letters beside it and still repairs, and
     * so does anything holding two or more sequences ("GrĂĽĂźe").
     */
    private fun isStandaloneSequence(text: String, bytes: ByteArray): Boolean {
        var start = -1
        var end = -1
        var index = 0
        while (index < bytes.size) {
            val lead = bytes[index].toInt() and 0xFF
            val length = when {
                lead < 0x80 -> 1
                lead < 0xE0 -> 2
                lead < 0xF0 -> 3
                else -> 4
            }
            if (length > 1) {
                if (start >= 0) return false
                start = index
                end = index + length
            }
            index += length
        }
        if (start < 0) return false
        val letterBefore = start > 0 && text[start - 1].isLetter()
        val letterAfter = end < text.length && text[end].isLetter()
        return !letterBefore && !letterAfter
    }

    /**
     * A single-byte codepage: [byteOf] gives the byte (80-FF) a non-ASCII char
     * decodes from, or null if the codepage has none; bytes below 80 are ASCII.
     * [isFallback] marks the codepages tried only after cp1252, which must also
     * pass [isPlausibleFallback].
     */
    private class Codepage(val isFallback: Boolean, private val byteOf: (Char) -> Int?) {

        /** Every char to its byte in this codepage, or null if one has none. */
        fun encode(text: String): ByteArray? {
            val bytes = ByteArray(text.length)
            for (index in text.indices) {
                val char = text[index]
                bytes[index] = if (char.code < 0x80) char.code.toByte() else byteOf(char)?.toByte() ?: return null
            }
            return bytes
        }
    }

    /**
     * A fallback codepage given as its upper half: the 128 characters that bytes
     * 80-FF decode to, in byte order. A byte the codepage leaves undefined is
     * listed as the C1 control of the same value, which is what a lenient
     * decoder — including Android's — emits for it.
     */
    private fun fallback(upperHalf: String): Codepage {
        require(upperHalf.length == 128)
        val byteOf = HashMap<Char, Int>(upperHalf.length * 2)
        upperHalf.forEachIndexed { index, char -> byteOf[char] = 0x80 + index }
        return Codepage(isFallback = true) { byteOf[it] }
    }

    /** Windows-1250 (Central European); 81, 83, 88, 90 and 98 are undefined. */
    private val WINDOWS_1250 = fallback(
        "\u20AC\u0081\u201A\u0083\u201E\u2026\u2020\u2021" + // 80: € · ‚ · „ … † ‡
            "\u0088\u2030\u0160\u2039\u015A\u0164\u017D\u0179" + // 88: · ‰ Š ‹ Ś Ť Ž Ź
            "\u0090\u2018\u2019\u201C\u201D\u2022\u2013\u2014" + // 90: · ‘ ’ “ ” • – —
            "\u0098\u2122\u0161\u203A\u015B\u0165\u017E\u017A" + // 98: · ™ š › ś ť ž ź
            "\u00A0\u02C7\u02D8\u0141\u00A4\u0104\u00A6\u00A7" + // A0: nbsp ˇ ˘ Ł ¤ Ą ¦ §
            "\u00A8\u00A9\u015E\u00AB\u00AC\u00AD\u00AE\u017B" + // A8: ¨ © Ş « ¬ shy ® Ż
            "\u00B0\u00B1\u02DB\u0142\u00B4\u00B5\u00B6\u00B7" + // B0: ° ± ˛ ł ´ µ ¶ ·
            "\u00B8\u0105\u015F\u00BB\u013D\u02DD\u013E\u017C" + // B8: ¸ ą ş » Ľ ˝ ľ ż
            "\u0154\u00C1\u00C2\u0102\u00C4\u0139\u0106\u00C7" + // C0: Ŕ Á Â Ă Ä Ĺ Ć Ç
            "\u010C\u00C9\u0118\u00CB\u011A\u00CD\u00CE\u010E" + // C8: Č É Ę Ë Ě Í Î Ď
            "\u0110\u0143\u0147\u00D3\u00D4\u0150\u00D6\u00D7" + // D0: Đ Ń Ň Ó Ô Ő Ö ×
            "\u0158\u016E\u00DA\u0170\u00DC\u00DD\u0162\u00DF" + // D8: Ř Ů Ú Ű Ü Ý Ţ ß
            "\u0155\u00E1\u00E2\u0103\u00E4\u013A\u0107\u00E7" + // E0: ŕ á â ă ä ĺ ć ç
            "\u010D\u00E9\u0119\u00EB\u011B\u00ED\u00EE\u010F" + // E8: č é ę ë ě í î ď
            "\u0111\u0144\u0148\u00F3\u00F4\u0151\u00F6\u00F7" + // F0: đ ń ň ó ô ő ö ÷
            "\u0159\u016F\u00FA\u0171\u00FC\u00FD\u0163\u02D9", // F8: ř ů ú ű ü ý ţ ˙
    )

    /** Windows-1251 (Cyrillic); only 98 is undefined. */
    private val WINDOWS_1251 = fallback(
        "\u0402\u0403\u201A\u0453\u201E\u2026\u2020\u2021" + // 80: Ђ Ѓ ‚ ѓ „ … † ‡
            "\u20AC\u2030\u0409\u2039\u040A\u040C\u040B\u040F" + // 88: € ‰ Љ ‹ Њ Ќ Ћ Џ
            "\u0452\u2018\u2019\u201C\u201D\u2022\u2013\u2014" + // 90: ђ ‘ ’ “ ” • – —
            "\u0098\u2122\u0459\u203A\u045A\u045C\u045B\u045F" + // 98: · ™ љ › њ ќ ћ џ
            "\u00A0\u040E\u045E\u0408\u00A4\u0490\u00A6\u00A7" + // A0: nbsp Ў ў Ј ¤ Ґ ¦ §
            "\u0401\u00A9\u0404\u00AB\u00AC\u00AD\u00AE\u0407" + // A8: Ё © Є « ¬ shy ® Ї
            "\u00B0\u00B1\u0406\u0456\u0491\u00B5\u00B6\u00B7" + // B0: ° ± І і ґ µ ¶ ·
            "\u0451\u2116\u0454\u00BB\u0458\u0405\u0455\u0457" + // B8: ё № є » ј Ѕ ѕ ї
            "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417" + // C0: А Б В Г Д Е Ж З
            "\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F" + // C8: И Й К Л М Н О П
            "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427" + // D0: Р С Т У Ф Х Ц Ч
            "\u0428\u0429\u042A\u042B\u042C\u042D\u042E\u042F" + // D8: Ш Щ Ъ Ы Ь Э Ю Я
            "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437" + // E0: а б в г д е ж з
            "\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F" + // E8: и й к л м н о п
            "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447" + // F0: р с т у ф х ц ч
            "\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F", // F8: ш щ ъ ы ь э ю я
    )


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

    /** Latin-1 for every char up to U+00FF, plus cp1252's specials. */
    private val CP1252 = Codepage(isFallback = false) { char ->
        if (char.code <= 0xFF) char.code else CP1252_SPECIALS[char.code]
    }

    /** Tried in this order; see [repair]. */
    private val CODEPAGES = listOf(CP1252, WINDOWS_1250, WINDOWS_1251)

    private const val RIGHT_QUOTE = '\u2019'
}

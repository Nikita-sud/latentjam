/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class TextRepairTest {

    // ---------------------------------------------------------------- repair()

    @Test
    fun repairsLatin1DecodedMojibake() {
        assertEquals("üß", TextRepair.repair("Ã¼ÃŸ"))
        assertEquals("Mötley Crüe", TextRepair.repair("MÃ¶tley CrÃ¼e"))
        // 0xB6 is a plain Latin-1 char, not a cp1252 special: direct byte mapping.
        assertEquals("ö", TextRepair.repair("Ã¶"))
        // The same target byte (0x9F) as a raw C1 control character, the way a
        // strict Latin-1 decoder (rather than cp1252) emits it — repairs the
        // same as the cp1252-special spelling exercised below.
        assertEquals("ß", TextRepair.repair("Ã" + '\u009F'))
    }

    @Test
    fun repairsCp1252SpecialCharacters() {
        assertEquals("’", TextRepair.repair("â€™"))
        assertEquals("Сплин", TextRepair.repair("Ð¡Ð¿Ð»Ð¸Ð½"))
    }

    @Test
    fun leavesCorrectlyEncodedTextUnchanged() {
        for (text in listOf("Café", "Müller", "Сплин", "東京", "🎵", "Plain ASCII", "")) {
            assertEquals(text, TextRepair.repair(text))
        }
    }

    @Test
    fun leavesInvalidMojibakeCandidatesUnchanged() {
        assertEquals("Ã", TextRepair.repair("Ã"))
        // A following en dash alone is not a valid UTF-8 continuation byte.
        assertEquals("Ã©–", TextRepair.repair("Ã©–"))
        // ï's byte is a 3-byte UTF-8 lead with no continuation bytes after it.
        assertEquals("naïve Ã", TextRepair.repair("naïve Ã"))
    }

    @Test
    fun repairsDoubleEncodedTextInAtMostTwoPasses() {
        assertEquals("ü", TextRepair.repair("ÃƒÂ¼"))
    }

    @Test
    fun repairsMojibakeEmoji() {
        assertEquals("🎵", TextRepair.repair("ðŸŽµ"))
    }

    @Test
    fun repairsWindows1250DecodedMojibake() {
        // Android 16's MediaStore read these UTF-8 bytes as Windows-1250 (issue #7).
        assertEquals("Grüße", TextRepair.repair("GrĂĽĂźe"))
        assertEquals("Mötley Crüe", TextRepair.repair("MĂ¶tley CrĂĽe"))
        assertEquals("Björk", TextRepair.repair("BjĂ¶rk"))
        assertEquals("Сплин", TextRepair.repair("ĐˇĐżĐ»Đ¸Đ˝"))
        assertEquals("東京", TextRepair.repair("ćť±äş¬"))
    }

    @Test
    fun repairsWindows1251DecodedMojibake() {
        assertEquals("Grüße", TextRepair.repair("GrГјГџe"))
        assertEquals("Mötley Crüe", TextRepair.repair("MГ¶tley CrГјe"))
        assertEquals("Сплин", TextRepair.repair("РЎРїР»РёРЅ"))
        assertEquals("東京", TextRepair.repair("жќ±дє¬"))
    }

    @Test
    fun leavesCentralEuropeanNamesUnchanged() {
        val names = listOf(
            "Dvořák", "Łódź", "Žluťoučký kůň", "Kővári", "Cărăbuș", "Ștefan", "Ştefan",
            "Grüße", "Mötley Crüe", "Ábel Ősz", "Čeněk", "Źdźbło",
        )
        for (text in names) assertEquals(text, TextRepair.repair(text))
    }

    @Test
    fun leavesCyrillicNamesUnchanged() {
        val names = listOf(
            "Сплин", "Земфира", "Віктор Павлік", "Ляпис Трубецкой", "Бі-2", "Ђорђе", "Љубав",
            "Їжак", "Ў лесе", "Ґалаґан", "Ёлка",
        )
        for (text in names) assertEquals(text, TextRepair.repair(text))
    }

    @Test
    fun leavesShortCyrillicWordsUnchanged() {
        // Each of these is, byte for byte, a valid UTF-8 pair once mapped back through
        // Windows-1251 ("Ні" would become "ͳ", "Ві" "³", "Её" "Ÿ"); none is mojibake.
        for (text in listOf("Ві", "Ні", "Ті", "Её", "Ёж", "ВЁ", "ВІ", "Я…", "Т»")) {
            assertEquals(text, TextRepair.repair(text))
        }
    }

    @Test
    fun aRepairedShortWordIsNotRepairedAgain() {
        // "Ні" read as Windows-1251 is repaired; the second pass must not then turn the
        // genuine "Ні" into "ͳ".
        assertEquals("Ні", TextRepair.repair("РќС–"))
    }

    @Test
    fun aLoneTwoByteLetterWithoutLatinContextStaysAsRead() {
        // The accepted trade-off of the short-word guard: "Я" read as Windows-1251 is only
        // one two-byte pair with no ASCII letter or digit beside it, the same shape as the
        // genuine words above, so it is left alone. With context it repairs.
        assertEquals("РЇ", TextRepair.repair("РЇ"))
        assertEquals("Я 2", TextRepair.repair("РЇ 2"))
    }

    @Test
    fun leavesAllCapsCentralEuropeanAndTurkishNamesUnchanged() {
        // Each maps back to valid UTF-8 through Windows-1250: a capital pair such as "ÓŁ" is the
        // bytes D3 A3, which spell "ӣ" ("PÓŁNOC" would read "PӣNOC", "KÖŞE" "K֪E").
        val names = listOf(
            "PÓŁNOC", "RÓŻNI WYKONAWCY", "KSIĘŻYC", "MŮŽE", "TĚŽKEJ POKONDR", "KÖŞE",
            "MÜŞFIK KENTER", "ÎŞI FACE LOC",
            "Północ", "Różni wykonawcy", "Księżyc", "Může", "Těžkej Pokondr", "Köşe",
            "Müşfik Kenter", "Îşi face loc",
        )
        for (text in names) assertEquals(text, TextRepair.repair(text))
    }

    @Test
    fun leavesCapsNamesThatSpellUtf8InCp1252Unchanged() {
        // The same shape through cp1252 itself: "ÉŠ" is C9 8A, so "SEPÉŠI" would read "SEPɊI".
        val names = listOf(
            "RADEK POSPÍŠIL", "MATÚŠ", "ÚŽAS", "PETR SEPÉŠI",
            "Radek Pospíšil", "Matúš", "Úžas", "Petr Sepéši",
        )
        for (text in names) assertEquals(text, TextRepair.repair(text))
    }

    @Test
    fun leavesWordsThatSpellAThreeByteCharacterUnchanged() {
        // "éšť" is the Windows-1250 bytes E9 9A 9D, which spell "隝": "Déšť" would read "D隝".
        for (text in listOf("Déšť", "déšť", "Ještě déšť")) assertEquals(text, TextRepair.repair(text))
    }

    @Test
    fun repairsMojibakeOfCapsNamesThroughEachCodepage() {
        assertEquals("PÓŁNOC", TextRepair.repair("PÃ“Å\u0081NOC"))
        assertEquals("PÓŁNOC", TextRepair.repair("PĂ“Ĺ\u0081NOC"))
        assertEquals("KÖŞE", TextRepair.repair("KÃ–ÅžE"))
        assertEquals("Północ", TextRepair.repair("PГіЕ‚noc"))
    }

    @Test
    fun aSecondPassUsesOnlyTheCodepageOfTheFirst() {
        // cp1252 repairs these; a second pass through another codepage would garble the result
        // again ("PӣNOC", "RӯNI WYKONAWCY", and the known short-word shape "ͳ (Live)").
        assertEquals("PÓŁNOC", TextRepair.repair("PÃ“Å\u0081NOC"))
        assertEquals("RÓŻNI WYKONAWCY", TextRepair.repair("RÃ“Å»NI WYKONAWCY"))
        assertEquals("Ні (Live)", TextRepair.repair("Ð\u009DÑ– (Live)"))
    }

    @Test
    fun repairsUndefinedBytesThatArriveAsTheirC1Control() {
        // Ł is C5 81, Ő C5 90 and Ř C5 98; Windows-1250 defines none of 81, 90, 98 and
        // Windows-1251 not 98, so a lenient decoder hands them over as U+0081, U+0090, U+0098.
        assertEquals("Łódź", TextRepair.repair("Ĺ\u0081ĂłdĹş"))
        assertEquals("ŁÓDŹ", TextRepair.repair("Ĺ\u0081Ă“DĹą"))
        assertEquals("Ősz", TextRepair.repair("Ĺ\u0090sz"))
        assertEquals("Řeka", TextRepair.repair("Ĺ\u0098eka"))
        assertEquals("Řeka", TextRepair.repair("Е\u0098eka"))
    }

    @Test
    fun leavesUndefinedBytesThatArriveAsReplacementCharactersUnchanged() {
        // A decoder that substitutes U+FFFD has lost the byte: nothing maps back to it.
        val texts = listOf(
            "Ĺ�ĂłdĹş",
            "Ĺ�sz",
            "Ĺ�eka",
            "Е�eka",
        )
        for (text in texts) assertEquals(text, TextRepair.repair(text))
    }

    @Test
    fun leavesFallbackRepairsThatWouldYieldControlCharactersUnchanged() {
        // Maps back through Windows-1251 to C2 80 C2 80: valid UTF-8, but two C1 controls.
        assertEquals("ВЂВЂ", TextRepair.repair("ВЂВЂ"))
    }

    // ---------------------------------------------------------- decodeUtf8Strict()

    @Test
    fun decodeUtf8StrictRejectsOverlongEncoding() {
        assertNull(TextRepair.decodeUtf8Strict(byteArrayOf(0xC0.toByte(), 0xAF.toByte())))
    }

    @Test
    fun decodeUtf8StrictRejectsEncodedSurrogates() {
        assertNull(TextRepair.decodeUtf8Strict(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
    }

    @Test
    fun decodeUtf8StrictRejectsCodePointsAboveMax() {
        assertNull(
            TextRepair.decodeUtf8Strict(
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            ),
        )
    }

    @Test
    fun decodeUtf8StrictRejectsTruncatedSequences() {
        assertNull(TextRepair.decodeUtf8Strict(byteArrayOf(0xE2.toByte(), 0x82.toByte())))
    }

    // ---------------------------------------------------------------- Id3Text

    @Test
    fun id3EncodingZeroDecodesUtf8BytesMislabelledAsLatin1() {
        val utf8Bytes = "Grüße".encodeToByteArray()
        assertEquals("Grüße", Id3Text.decode(Id3Text.ISO_8859_1, utf8Bytes, 0, utf8Bytes.size))
    }

    @Test
    fun id3EncodingZeroTakesAdjacentLatin1LettersThatFormUtf8AsUtf8() {
        // The accepted trade-off of the heuristic: genuine Latin-1 "É" followed by a no-break
        // space is the bytes C9 A0, which are also well-formed UTF-8 for "ɠ", so an existing
        // frame like this now reads as UTF-8. LatentJam's own writer never produces such a frame
        // (see Id3TagsTest.latin1TextWhoseBytesAlsoReadAsUtf8GoesOutAsUnicode).
        val bytes = byteArrayOf(0x4C, 0xC9.toByte(), 0xA0.toByte(), 0x21)
        assertEquals("L\u0260!", Id3Text.decode(Id3Text.ISO_8859_1, bytes, 0, bytes.size))
        // One accented letter followed by an ASCII one is not UTF-8 and stays Latin-1.
        val cafe = byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte(), 0x21)
        assertEquals("Café!", Id3Text.decode(Id3Text.ISO_8859_1, cafe, 0, cafe.size))
    }

    @Test
    fun id3EncodingZeroStillDecodesGenuineLatin1Bytes() {
        val text = "Grüße"
        val latin1Bytes = ByteArray(text.length) { i -> text[i].code.toByte() }
        assertEquals("Grüße", Id3Text.decode(Id3Text.ISO_8859_1, latin1Bytes, 0, latin1Bytes.size))
    }
}

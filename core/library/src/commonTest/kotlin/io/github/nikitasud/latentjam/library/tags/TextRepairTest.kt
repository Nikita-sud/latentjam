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

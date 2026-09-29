/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.artFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.commentFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Id3FieldsTest {

    private fun file(major: Int, vararg frames: TestFrame, padding: Int = 0): ByteArray =
        Id3TestTags.build(major, frames.toList(), padding) + mp3Payload()

    private fun edit(file: ByteArray, edits: TagEdits): ByteArray = assertNotNull(updateId3Tag(file, edits))

    private fun fields(bytes: ByteArray): Id3Tags.Id3Fields = assertNotNull(Id3Tags.readFields(bytes))

    /** Latin-1 bytes, matching the encoding byte 0 the fixtures declare. */
    private fun latin1(text: String) = ByteArray(text.length) { text[it].code.toByte() }

    private fun uslt(language: String, descriptor: String, text: String) = TestFrame(
        "USLT",
        byteArrayOf(0) + latin1(language) + latin1(descriptor) + byteArrayOf(0) + latin1(text),
    )

    private fun picture(type: Int, data: ByteArray, mime: String = "image/png") = TestFrame(
        "APIC",
        byteArrayOf(0) + mime.encodeToByteArray() + byteArrayOf(0, type.toByte(), 0) + data,
    )

    private fun txxx(description: String, value: String) = TestFrame(
        "TXXX",
        byteArrayOf(0) + description.encodeToByteArray() + byteArrayOf(0) + value.encodeToByteArray(),
    )

    @Test
    fun albumArtistNumbersAndLyricsRoundTripInBothVersions() {
        for (major in listOf(3, 4)) {
            val out = edit(
                file(major, TestFrame("TIT2", latin1Body("t"))),
                TagEdits(
                    albumArtist = "Various Artists",
                    trackNumber = "3",
                    trackTotal = "12",
                    discNumber = "1",
                    discTotal = "2",
                    lyrics = "Первая строка\nSecond line",
                ),
            )
            val read = fields(out)
            assertEquals("Various Artists", read.albumArtist)
            assertEquals("3/12", read.track)
            assertEquals("1/2", read.disc)
            assertEquals("Первая строка\nSecond line", read.lyrics)
            assertEquals("t", read.title)
        }
    }

    @Test
    fun numberAndTotalEditIndependently() {
        val base = file(3, TestFrame("TRCK", latin1Body("3/12")))
        assertEquals("4/12", fields(edit(base, TagEdits(trackNumber = "4"))).track)
        assertEquals("3/10", fields(edit(base, TagEdits(trackTotal = "10"))).track)
        assertEquals("3", fields(edit(base, TagEdits(trackTotal = ""))).track)
        assertNull(fields(edit(base, TagEdits(trackNumber = ""))).track)
    }

    @Test
    fun lyricsReplaceTheReadableFrameAndKeepOtherLanguages() {
        val empty = uslt("eng", "", "")
        val german = uslt("deu", "", "Deutsch")
        val out = edit(file(3, empty, german), TagEdits(lyrics = "New"))
        val frames = Id3TestTags.framesOf(out).filter { it.id == "USLT" }
        assertEquals(2, frames.size)
        assertContentEquals(empty.body, frames[0].body)
        assertEquals("deu", frames[1].body.copyOfRange(1, 4).decodeToString())
        assertEquals("New", fields(out).lyrics)
    }

    @Test
    fun removingLyricsRemovesEveryLyricsFrame() {
        // "No lyrics" means none: another language's frame must not take the removed one's place.
        val out = edit(file(3, uslt("eng", "", "Words"), uslt("deu", "", "Wörter")), TagEdits(lyrics = ""))
        assertEquals(0, Id3TestTags.framesOf(out).count { it.id == "USLT" })
        assertNull(fields(out).lyrics)
    }

    @Test
    fun aTotalAloneIsNotWrittenOverANumberThatCannotBeRead() {
        for (position in listOf("A1", "B2/12", "1200/1300")) {
            val base = file(3, TestFrame("TRCK", latin1Body(position)), TestFrame("TPOS", latin1Body(position)))
            assertNull(updateId3Tag(base, TagEdits(trackTotal = "12")), position)
            assertNull(updateId3Tag(base, TagEdits(discTotal = "")), position)
            // Replacing the number itself is a deliberate choice, and allowed.
            assertEquals("5/12", fields(edit(base, TagEdits(trackNumber = "5", trackTotal = "12"))).track)
        }
        // Zero is no number at all: a total may join it.
        assertEquals(null, fields(edit(file(3, TestFrame("TRCK", latin1Body("0"))), TagEdits(trackTotal = "12"))).track)
    }

    @Test
    fun restatingAZeroPaddedNumberWritesNothing() {
        val base = file(3, TestFrame("TRCK", latin1Body("03/09")))
        assertContentEquals(base, edit(base, TagEdits(trackNumber = "3")))
        assertContentEquals(base, edit(base, TagEdits(trackNumber = "3", trackTotal = "9")))
        assertEquals("4/9", fields(edit(base, TagEdits(trackNumber = "4"))).track)
    }

    @Test
    fun coverReplaceActsOnTheFrontCoverAndKeepsOthers() {
        val back = picture(type = 4, data = byteArrayOf(1, 2, 3))
        val out = edit(
            file(3, back, artFrame(size = 100)),
            TagEdits(cover = CoverEdit.Replace(TestImages.jpeg(10, 10), ImageProbe.JPEG)),
        )
        val read = fields(out)
        assertEquals(CoverInfo.of(TestImages.jpeg(10, 10), ImageProbe.JPEG), read.cover)
        assertEquals(1, read.otherPictures)
        val pictures = Id3TestTags.framesOf(out).filter { it.id == "APIC" }
        assertEquals(2, pictures.size)
        assertContentEquals(back.body, pictures[0].body)
    }

    @Test
    fun typeZeroPictureIsTheCoverWhenNoFrontCoverExists() {
        val back = picture(type = 4, data = byteArrayOf(1))
        val other = picture(type = 0, data = byteArrayOf(2))
        val before = file(3, back, other)
        assertEquals(CoverInfo.of(byteArrayOf(2), "image/png"), fields(before).cover)
        val out = edit(before, TagEdits(cover = CoverEdit.Replace(TestImages.png(4, 4), ImageProbe.PNG)))
        val pictures = Id3TestTags.framesOf(out).filter { it.id == "APIC" }
        assertContentEquals(back.body, pictures[0].body)
        assertEquals(CoverInfo.of(TestImages.png(4, 4), ImageProbe.PNG), fields(out).cover)
        assertEquals(1, fields(out).otherPictures)
    }

    @Test
    fun coverRemoveDropsOnlyTheTarget() {
        val back = picture(type = 4, data = byteArrayOf(1))
        val out = edit(file(3, artFrame(size = 10), back), TagEdits(cover = CoverEdit.Remove))
        val pictures = Id3TestTags.framesOf(out).filter { it.id == "APIC" }
        assertEquals(1, pictures.size)
        assertContentEquals(back.body, pictures[0].body)
        assertNull(fields(out).cover)
    }

    @Test
    fun coverIsAddedWhenTheFileHasNone() {
        val jpeg = TestImages.jpeg(8, 8)
        val out = edit(file(4, TestFrame("TIT2", latin1Body("t"))), TagEdits(cover = CoverEdit.Replace(jpeg, ImageProbe.JPEG)))
        assertEquals(CoverInfo.of(jpeg, ImageProbe.JPEG), fields(out).cover)
        assertEquals(0, fields(out).otherPictures)
    }

    @Test
    fun creditedArtistsAreRewrittenOnArtistEdit() {
        val base = file(4, TestFrame("TPE1", latin1Body("A; B")), txxx("ARTISTS", "A\u0000B"))
        assertEquals(listOf("A", "B"), fields(base).artists)
        assertEquals(listOf("X", "Y"), fields(edit(base, TagEdits(artist = "X; Y"))).artists)
        val solo = edit(base, TagEdits(artist = "Solo"))
        assertEquals(emptyList(), fields(solo).artists)
        assertTrue(Id3TestTags.framesOf(solo).none { it.id == "TXXX" })
        val plain = file(3, TestFrame("TPE1", latin1Body("A")))
        assertEquals(emptyList(), fields(edit(plain, TagEdits(artist = "X; Y"))).artists)
    }

    @Test
    fun growingATagLeavesSixteenKibibytesOfPadding() {
        val base = file(3, TestFrame("TIT2", latin1Body("a")))
        val out = edit(base, TagEdits(title = "a".repeat(100)))
        val frameBytes = 10 + 1 + 100
        assertEquals(10 + frameBytes + TagSpace.SPARE_BYTES, Id3Tags.tagLength(out))
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        for (major in listOf(3, 4)) {
            assertEquals(lyrics, fields(edit(file(major), TagEdits(lyrics = lyrics))).lyrics)
        }
    }

    @Test
    fun unmanagedFramesSurviveEveryEdit() {
        val base = file(3, TestFrame("TIT2", latin1Body("t")), commentFrame("keep me"), TestFrame("PRIV", byteArrayOf(1, 2, 3)))
        val out = edit(
            base,
            TagEdits(title = "x", albumArtist = "y", trackNumber = "1", lyrics = "z", cover = CoverEdit.Replace(TestImages.png(2, 2), ImageProbe.PNG)),
        )
        assertEquals(fields(base).unmanaged, fields(out).unmanaged)
        assertEquals(2, fields(out).unmanaged.size)
    }

    @Test
    fun restatingAUtf16TitleKeepsTheFrame() {
        val title = TestFrame("TIT2", Id3TestTags.utf16Body("Song"))
        val base = file(3, title)
        assertFalse(Id3Tags.wouldChange(base, TagEdits(title = "Song")))
        val out = edit(base, TagEdits(album = "Record"))
        assertContentEquals(title.body, Id3TestTags.frameBody(out, "TIT2"))
    }

    @Test
    fun wouldChangeIsFalseForEditsThatRestateTheTag() {
        val base = file(3, TestFrame("TIT2", latin1Body("t")), TestFrame("TRCK", latin1Body("3/12")))
        assertFalse(Id3Tags.wouldChange(base, TagEdits(title = "t", trackNumber = "3")))
        assertTrue(Id3Tags.wouldChange(base, TagEdits(title = "u")))
        assertFalse(Id3Tags.wouldChange(mp3Payload(), TagEdits(title = "")))
        assertTrue(Id3Tags.wouldChange(mp3Payload(), TagEdits(title = "new")))
    }

    /** UTF-16, BOM-prefixed encoding of one string field, matching how USLT stores each field. */
    private fun utf16(text: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xFF.toByte())
        out.add(0xFE.toByte())
        for (c in text) {
            out.add((c.code and 0xFF).toByte())
            out.add(((c.code shr 8) and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    @Test
    fun restatingUtf16LyricsKeepsTheFrame() {
        val lyrics = TestFrame(
            "USLT",
            byteArrayOf(1) + latin1("eng") + utf16("") + byteArrayOf(0, 0) + utf16("Words"),
        )
        val base = file(3, lyrics)
        assertFalse(Id3Tags.wouldChange(base, TagEdits(lyrics = "Words")))
        val out = edit(base, TagEdits(album = "Record"))
        assertContentEquals(lyrics.body, Id3TestTags.frameBody(out, "USLT"))
    }

    @Test
    fun lyricsWithAnEmbeddedNulLoseItAndStillParse() {
        val out = edit(file(3), TagEdits(lyrics = "Line one\u0000Line two"))
        assertEquals("Line oneLine two", fields(out).lyrics)
    }
}

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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class Id3TagCodecTest {

    private val codec = Id3TagCodec

    private fun tagged(padding: Int = 0, major: Int = 3) = Id3TestTags.build(
        major,
        listOf(
            TestFrame("TIT2", latin1Body("Title")),
            TestFrame("TPE1", latin1Body("Artist")),
            TestFrame("TRCK", latin1Body("3/12")),
            commentFrame("keep"),
            artFrame(size = 200),
        ),
        padding,
    ) + mp3Payload()

    @Test
    fun recognizesTaggedAndBareMpegButNotOtherContainers() {
        assertTrue(codec.recognizes(tagged().copyOf(TagCodec.HEAD_BYTES)))
        assertTrue(codec.recognizes(mp3Payload().copyOf(TagCodec.HEAD_BYTES)))
        assertFalse(codec.recognizes("fLaC".encodeToByteArray() + ByteArray(60)))
        assertFalse(codec.recognizes("OggS".encodeToByteArray() + ByteArray(60)))
    }

    @Test
    fun readReturnsEveryField() {
        val snapshot = codec.read(ByteArraySource(tagged()))
        assertEquals(TagFormat.MP3, snapshot.format)
        assertEquals("ID3v2.3", snapshot.version)
        assertEquals("Title", snapshot.title)
        assertEquals(3, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
        assertEquals(200, snapshot.cover?.size)
        assertEquals(null, snapshot.refusal)
    }

    @Test
    fun untaggedMpegReadsAsVersionNone() {
        val snapshot = codec.read(ByteArraySource(mp3Payload()))
        assertEquals("none", snapshot.version)
        assertTrue(snapshot.editable)
    }

    @Test
    fun editInsidePaddingIsAnInPlacePatchOfTheTagOnly() {
        val file = tagged(padding = 4096)
        val tagLength = Id3Tags.tagLength(file)!!
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A longer title than before"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        assertTrue(plan.writes.all { it.offset + it.bytes.size <= tagLength })
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A longer title than before"))
    }

    @Test
    fun editBeyondPaddingIsAStreamingRewriteThatKeepsTheAudio() {
        val edits = TagEdits(lyrics = "x".repeat(5000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(tagged()), edits))
        CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), edits)
    }

    @Test
    fun secondEditAfterARewriteGoesInPlace() {
        val first = CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), TagEdits(lyrics = "x".repeat(5000)))
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(first), TagEdits(title = "Again")))
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = tagged() + Id3TestTags.v1Trailer()
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Title", trackNumber = "3")))
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits()))
    }

    @Test
    fun aRealEditDropsTheId3v1Trailer() {
        val file = tagged(padding = 512) + Id3TestTags.v1Trailer()
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "New"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size - 128L, plan.newLength)
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "New"))
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(major = 4),
            TagEdits(
                title = "Заголовок", artist = "X; Y", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004-05-06", trackNumber = "1", trackTotal = "", discNumber = "2", discTotal = "2",
                lyrics = "строка", cover = CoverEdit.Replace(TestImages.jpeg(30, 30), ImageProbe.JPEG),
            ),
        )
    }

    @Test
    fun untaggedMpegGetsANewTag() {
        CodecAssertions.assertWriteMatchesExpectation(codec, mp3Payload(), TagEdits(title = "First"))
    }

    @Test
    fun aNulPastedIntoAnyFieldIsDroppedByTheWriterAndTheExpectationAlike() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(),
            TagEdits(title = "Ti\u0000tle", album = "A\u0000B", albumArtist = "\u0000VA", lyrics = "x\u0000y"),
        )
    }

    @Test
    fun anUntaggedMpegGivenAFullDateKeepsItsYear() {
        CodecAssertions.assertWriteMatchesExpectation(codec, mp3Payload(), TagEdits(year = "2001-05-03"))
    }

    @Test
    fun aTotalAloneOverAnUnreadableNumberIsRefusedNotErased() {
        val vinyl = Id3TestTags.build(3, listOf(TestFrame("TRCK", latin1Body("A1"))), padding = 512) + mp3Payload()
        assertEquals(
            TagRefusal.UNREADABLE_NUMBER,
            (codec.plan(ByteArraySource(vinyl), TagEdits(trackTotal = "12")) as WritePlan.Refused).reason,
        )
        assertEquals(
            TagRefusal.INVALID_NUMBER,
            (codec.plan(ByteArraySource(tagged()), TagEdits(trackTotal = "1000")) as WritePlan.Refused).reason,
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, vinyl, TagEdits(trackNumber = "1", trackTotal = "12"))
    }

    @Test
    fun removingLyricsLeavesNoOtherLanguageBehind() {
        fun uslt(language: String, text: String) =
            TestFrame("USLT", byteArrayOf(0) + language.encodeToByteArray() + byteArrayOf(0) + text.encodeToByteArray())
        val file = Id3TestTags.build(3, listOf(uslt("eng", "Words"), commentFrame("keep"), uslt("deu", "Worte")), padding = 256) +
            mp3Payload()
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(lyrics = ""))
        assertEquals(null, codec.read(ByteArraySource(out)).lyrics)
        // Another edit keeps the other language's frame, and verification still pins it.
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(lyrics = "New words"))
    }

    @Test
    fun restatingAZeroPaddedTrackWritesNothing() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TRCK", latin1Body("03/12")))) + mp3Payload()
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(trackNumber = "3", trackTotal = "12")))
    }

    @Test
    fun aGrowingTagIsNotWrittenInFrontOfAnUnknownContainer() {
        val quickTime = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t"))), padding = 64) +
            Mp4Fixtures.leaf("wide", ByteArray(0)) + Mp4Fixtures.leaf("mdat", ByteArray(2000) { 7 })
        // A small edit stays inside the tag: the bytes after it are never touched.
        CodecAssertions.assertWriteMatchesExpectation(codec, quickTime, TagEdits(title = "x"))
        val plan = codec.plan(ByteArraySource(quickTime), TagEdits(lyrics = "x".repeat(5000)))
        assertEquals(TagRefusal.ID3_UNKNOWN_AUDIO, (plan as WritePlan.Refused).reason)
    }

    @Test
    fun zerosPastTheTagBeforeTheFirstFrameStillCountAsMpeg() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + ByteArray(700) + mp3Payload()
        val edits = TagEdits(lyrics = "x".repeat(5000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
    }

    @Test
    fun id3InFrontOfFlacIsRefused() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + "fLaC".encodeToByteArray() + ByteArray(100)
        assertEquals(TagRefusal.ID3_BEFORE_OTHER_CONTAINER, codec.read(ByteArraySource(file)).refusal)
        assertEquals(
            TagRefusal.ID3_BEFORE_OTHER_CONTAINER,
            (codec.plan(ByteArraySource(file), TagEdits(title = "x")) as WritePlan.Refused).reason,
        )
    }

    @Test
    fun invalidNumbersAndImagesAreRefusedBeforeAnyWork() {
        val source = ByteArraySource(tagged())
        assertEquals(TagRefusal.INVALID_NUMBER, (codec.plan(source, TagEdits(trackNumber = "3/12")) as WritePlan.Refused).reason)
        assertEquals(
            TagRefusal.UNSUPPORTED_IMAGE,
            (codec.plan(source, TagEdits(cover = CoverEdit.Replace(ByteArray(10), ImageProbe.JPEG))) as WritePlan.Refused).reason,
        )
    }

    @Test
    fun unsynchronisedTagsAreRefused() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t"))), headerFlags = 0x80) + mp3Payload()
        assertEquals(TagRefusal.ID3_UNSYNCHRONISED, codec.read(ByteArraySource(file)).refusal)
    }
}

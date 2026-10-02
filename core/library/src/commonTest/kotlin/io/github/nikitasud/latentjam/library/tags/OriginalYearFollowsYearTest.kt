/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A Year edit moves the file's original-release year along when both said the same year: the
 * original date then was never a different first release, only a copy of the year. A remaster
 * (the years differ) keeps its original, and no original-date field is ever added.
 */
internal class OriginalYearFollowsYearTest {

    // Every write goes through the plan, is applied, and must pass TagVerification, which also
    // checks that the audio bytes are identical.
    private fun write(codec: TagCodec, file: ByteArray, edits: TagEdits): ByteArray =
        CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)

    private fun id3(major: Int, vararg frames: Pair<String, String>): ByteArray =
        Id3TestTags.build(major, frames.map { (id, text) -> TestFrame(id, latin1Body(text)) }) + mp3Payload()

    /** Every text value of the frames with [id], in file order, decoded independently of the codec. */
    private fun id3Texts(file: ByteArray, id: String): List<String> =
        Id3TestTags.framesOf(file).filter { it.id == id }.map { frame ->
            Id3Text.decode(frame.body[0].toInt() and 0xFF, frame.body, 1, frame.body.size)!!.trimEnd('\u0000')
        }

    /** (description, value) of every TXXX frame, in file order; the fixtures and these values are Latin-1. */
    private fun userTexts(file: ByteArray): List<Pair<String, String>> =
        Id3TestTags.framesOf(file).filter { it.id == "TXXX" }.map { frame ->
            assertEquals(0, frame.body[0].toInt(), "Latin-1")
            val text = Id3Text.decode(0, frame.body, 1, frame.body.size)!!
            text.substringBefore('\u0000') to text.substringAfter('\u0000').trimEnd('\u0000')
        }

    /** Every Vorbis comment value with [key], as the library's scanner-side reader sees it. */
    private fun comments(file: ByteArray, key: String): List<String> =
        GenreTags.embeddedComments(Bytes(file))!!.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }

    private fun originalYear(file: ByteArray): Int? = TagFacts.embedded(Bytes(file))?.originalYear

    private class Bytes(private val bytes: ByteArray) : GenreTags.ByteSource {
        private var position = 0
        override fun read(count: Int): ByteArray? {
            if (position + count > bytes.size) return null
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        override fun readUpTo(count: Int): ByteArray {
            val end = minOf(bytes.size, position + count)
            return bytes.copyOfRange(position, end).also { position = end }
        }

        override fun skip(count: Long): Boolean {
            if (position + count > bytes.size) return false
            position += count.toInt()
            return true
        }
    }

    // ------------------------------------------------------------------------------------- ID3

    @Test
    fun id3v24OriginalThatMatchedTheYearFollowsIt() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), id3Texts(out, "TDRC"))
        assertEquals(listOf("2004"), id3Texts(out, "TDOR"))
        assertEquals(2004, originalYear(out))
    }

    @Test
    fun id3v24RemasterKeepsItsOriginal() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "2011", "TDOR" to "1973"), TagEdits(year = "2012"))
        assertEquals(listOf("2012"), id3Texts(out, "TDRC"))
        assertEquals(listOf("1973"), id3Texts(out, "TDOR"))
        assertEquals(1973, originalYear(out))
    }

    @Test
    fun id3v24FullDatesCompareByYearAndTheOriginalGetsTheValueAsTyped() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999-05-01", "TDOR" to "1999-05-01"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), id3Texts(out, "TDRC"))
        assertEquals(listOf("2004"), id3Texts(out, "TDOR"))
    }

    @Test
    fun id3v23OriginalYearFollowsTheYear() {
        val out = write(Id3TagCodec, id3(3, "TYER" to "1999", "TORY" to "1999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), id3Texts(out, "TYER"))
        assertEquals(listOf("2004"), id3Texts(out, "TORY"))
        assertEquals(2004, originalYear(out))
    }

    @Test
    fun id3v23MovesATdorItCarriesAsWell() {
        val out = write(Id3TagCodec, id3(3, "TYER" to "1999", "TORY" to "1999", "TDOR" to "1999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), id3Texts(out, "TORY"))
        assertEquals(listOf("2004"), id3Texts(out, "TDOR"))
    }

    @Test
    fun id3v23OriginalIsNarrowedToTheYearLikeTyer() {
        val out = write(Id3TagCodec, id3(3, "TYER" to "1999", "TORY" to "1999"), TagEdits(year = "2004-03-01"))
        assertEquals(listOf("2004"), id3Texts(out, "TYER"))
        assertEquals(listOf("2004"), id3Texts(out, "TORY"))
    }

    @Test
    fun id3WithoutAnOriginalGetsNoneAdded() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), id3Texts(out, "TDRC"))
        assertEquals(emptyList(), id3Texts(out, "TDOR"))
        assertEquals(emptyList(), id3Texts(out, "TORY"))
    }

    @Test
    fun clearingTheYearKeepsTheOriginal() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(year = ""))
        assertEquals(emptyList(), id3Texts(out, "TDRC"))
        assertEquals(listOf("1999"), id3Texts(out, "TDOR"))
    }

    @Test
    fun restatingTheSameYearLeavesAFullOriginalDateAlone() {
        val file = id3(4, "TDRC" to "1999", "TDOR" to "1999-05-01")
        assertIs<WritePlan.NoChange>(Id3TagCodec.plan(ByteArraySource(file), TagEdits(year = "1999")))
        val out = write(Id3TagCodec, file, TagEdits(year = "1999-06-01"))
        assertEquals(listOf("1999-05-01"), id3Texts(out, "TDOR"))
    }

    @Test
    fun aYearThatIsNotAYearIsNotCopiedIntoTheOriginal() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(year = "soon"))
        assertEquals(listOf("soon"), id3Texts(out, "TDRC"))
        assertEquals(listOf("1999"), id3Texts(out, "TDOR"))
    }

    @Test
    fun id3EditNotTouchingTheYearLeavesTheOriginal() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(title = "New"))
        assertEquals(listOf("1999"), id3Texts(out, "TDOR"))
    }

    @Test
    fun repeatingTheYearEditAfterTheMoveIsANoOp() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(year = "2004"))
        assertIs<WritePlan.NoChange>(Id3TagCodec.plan(ByteArraySource(out), TagEdits(year = "2004")))
    }

    // ---------------------------------------------------------------------------- Vorbis comments

    @Test
    fun flacMovesEveryOriginalKeyThatMatched() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999", "ORIGINALYEAR" to "1999"),
        )
        val out = write(FlacTagCodec, file, TagEdits(year = "2004"))
        assertEquals(listOf("2004"), comments(out, "DATE"))
        assertEquals(listOf("2004"), comments(out, "ORIGINALDATE"))
        assertEquals(listOf("2004"), comments(out, "ORIGINALYEAR"))
        assertEquals(2004, originalYear(out))
    }

    @Test
    fun flacRemasterKeepsItsOriginal() {
        val file = FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "2011", "ORIGINALDATE" to "1973"))
        val out = write(FlacTagCodec, file, TagEdits(year = "2012"))
        assertEquals(listOf("2012"), comments(out, "DATE"))
        assertEquals(listOf("1973"), comments(out, "ORIGINALDATE"))
        assertEquals(1973, originalYear(out))
    }

    @Test
    fun flacWithoutAnOriginalGetsNoneAdded() {
        val file = FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999"))
        val out = write(FlacTagCodec, file, TagEdits(year = "2004"))
        assertEquals(emptyList(), comments(out, "ORIGINALDATE"))
        assertEquals(emptyList(), comments(out, "ORIGINALYEAR"))
    }

    @Test
    fun flacClearingTheYearKeepsTheOriginal() {
        val file = FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999"))
        val out = write(FlacTagCodec, file, TagEdits(year = ""))
        assertEquals(emptyList(), comments(out, "DATE"))
        assertEquals(listOf("1999"), comments(out, "ORIGINALDATE"))
    }

    @Test
    fun flacOnlyTheOriginalKeyThatMatchedMoves() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999-05-01", "ORIGINALYEAR" to "1973"),
        )
        val out = write(FlacTagCodec, file, TagEdits(year = "2004"))
        assertEquals(listOf("2004"), comments(out, "ORIGINALDATE"))
        assertEquals(listOf("1973"), comments(out, "ORIGINALYEAR"))
    }

    @Test
    fun flacKeepsTheOriginalKeysOwnSpelling() {
        val file = FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "originalDate" to "1999"))
        val out = write(FlacTagCodec, file, TagEdits(year = "2004"))
        assertEquals(listOf("originalDate" to "2004"), GenreTags.embeddedComments(Bytes(out))!!.filter { it.first.startsWith("orig") })
    }

    @Test
    fun flacEditNotTouchingTheYearLeavesTheOriginal() {
        val file = FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999"))
        val out = write(FlacTagCodec, file, TagEdits(title = "New"))
        assertEquals(listOf("1999"), comments(out, "ORIGINALDATE"))
    }

    @Test
    fun opusOriginalFollowsTheYear() {
        val out = write(OggTagCodec, OggFixtures.opus("DATE" to "1999", "ORIGINALDATE" to "1999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), comments(out, "DATE"))
        assertEquals(listOf("2004"), comments(out, "ORIGINALDATE"))
    }

    @Test
    fun oggVorbisOriginalFollowsTheYear() {
        val out = write(OggTagCodec, OggFixtures.vorbis("DATE" to "1999", "ORIGINALDATE" to "1999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), comments(out, "DATE"))
        assertEquals(listOf("2004"), comments(out, "ORIGINALDATE"))
    }

    // ------------------------------------------------------- an edit that keeps every original

    @Test
    fun id3EditThatKeepsOriginalsLeavesEveryOneAlone() {
        val file = id3(4, "TDRC" to "1999", "TDOR" to "1999", "TORY" to "1999", "TXXX" to "ORIGINALYEAR\u00001999")
        val out = write(Id3TagCodec, file, TagEdits(year = "2004", originalFollowsYear = false))
        assertEquals(listOf("2004"), id3Texts(out, "TDRC"))
        assertEquals(listOf("1999"), id3Texts(out, "TDOR"))
        assertEquals(listOf("1999"), id3Texts(out, "TORY"))
        assertEquals(listOf("ORIGINALYEAR" to "1999"), userTexts(out))
        assertEquals(1999, originalYear(out))
    }

    @Test
    fun flacEditThatKeepsOriginalsLeavesEveryOneAlone() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999", "ORIGINALYEAR" to "1999"),
        )
        val out = write(FlacTagCodec, file, TagEdits(year = "2004", originalFollowsYear = false))
        assertEquals(listOf("2004"), comments(out, "DATE"))
        assertEquals(listOf("1999"), comments(out, "ORIGINALDATE"))
        assertEquals(listOf("1999"), comments(out, "ORIGINALYEAR"))
    }

    @Test
    fun opusEditThatKeepsOriginalsLeavesThemAlone() {
        val file = OggFixtures.opus("DATE" to "1999", "ORIGINALDATE" to "1999")
        val out = write(OggTagCodec, file, TagEdits(year = "2004", originalFollowsYear = false))
        assertEquals(listOf("1999"), comments(out, "ORIGINALDATE"))
    }

    @Test
    fun mp4EditThatKeepsOriginalsLeavesThemAlone() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©day", "1999"), Mp4Fixtures.freeform("ORIGINALYEAR", "1999")))
        val out = write(Mp4TagCodec, file, TagEdits(year = "2004", originalFollowsYear = false))
        assertEquals(listOf(OriginalDate("1999", yearOnly = true)), Mp4TagCodec.read(ByteArraySource(out)).originalDates)
    }

    @Test
    fun theExpectationOfAnEditThatKeepsOriginalsKeepsThem() {
        val snapshot = Id3TagCodec.read(ByteArraySource(id3(4, "TDRC" to "1999", "TDOR" to "1999")))
        assertEquals(snapshot.originalDates, snapshot.expectedAfter(TagEdits(year = "2004", originalFollowsYear = false)).originalDates)
        assertEquals(
            listOf(OriginalDate("2004", yearOnly = false)),
            snapshot.expectedAfter(TagEdits(year = "2004")).originalDates,
        )
    }

    @Test
    fun keepingOriginalsAloneIsNoEditOnItsOwn() {
        assertTrue(TagEdits(originalFollowsYear = false).isEmpty)
    }

    // ------------------------------------------------------------- Picard's TXXX original fields

    @Test
    fun id3v23PicardOriginalYearFollowsTheYearAndKeepsItsSpelling() {
        val out = write(Id3TagCodec, id3(3, "TYER" to "1999", "TORY" to "1999", "TXXX" to "originalyear\u00001999"), TagEdits(year = "2004"))
        assertEquals(listOf("2004"), id3Texts(out, "TORY"))
        assertEquals(listOf("originalyear" to "2004"), userTexts(out))
    }

    @Test
    fun id3v24PicardOriginalDateGetsTheDateAndOriginalYearOnlyTheYear() {
        val file = id3(4, "TDRC" to "1999", "TDOR" to "1999", "TXXX" to "ORIGINALDATE\u00001999-05-01", "TXXX" to "ORIGINALYEAR\u00001999")
        val out = write(Id3TagCodec, file, TagEdits(year = "2004-03-01"))
        assertEquals(listOf("2004-03-01"), id3Texts(out, "TDOR"))
        assertEquals(listOf("ORIGINALDATE" to "2004-03-01", "ORIGINALYEAR" to "2004"), userTexts(out))
    }

    @Test
    fun id3PicardOriginalThatDifferedStays() {
        val file = id3(4, "TDRC" to "2011", "TDOR" to "1973", "TXXX" to "ORIGINALYEAR\u00001973", "TXXX" to "MOOD\u00002011")
        val out = write(Id3TagCodec, file, TagEdits(year = "2012"))
        assertEquals(listOf("ORIGINALYEAR" to "1973", "MOOD" to "2011"), userTexts(out))
    }

    @Test
    fun id3OtherUserTextsAreNeverMoved() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TXXX" to "RELEASEYEAR\u00001999"), TagEdits(year = "2004"))
        assertEquals(listOf("RELEASEYEAR" to "1999"), userTexts(out))
    }

    // ------------------------------------------------------- year-only fields get only the year

    @Test
    fun id3v24ToryGetsOnlyTheYearOfAFullDate() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TORY" to "1999", "TDOR" to "1999"), TagEdits(year = "2004-03-01"))
        assertEquals(listOf("2004-03-01"), id3Texts(out, "TDRC"))
        assertEquals(listOf("2004"), id3Texts(out, "TORY"))
        assertEquals(listOf("2004-03-01"), id3Texts(out, "TDOR"))
    }

    @Test
    fun flacOriginalYearGetsOnlyTheYearOfAFullDate() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999", "ORIGINALYEAR" to "1999"),
        )
        val out = write(FlacTagCodec, file, TagEdits(year = "2004-03-01"))
        assertEquals(listOf("2004-03-01"), comments(out, "DATE"))
        assertEquals(listOf("2004-03-01"), comments(out, "ORIGINALDATE"))
        assertEquals(listOf("2004"), comments(out, "ORIGINALYEAR"))
    }

    @Test
    fun aMalformedDateGivesTheOriginalDateOnlyItsYear() {
        for (typed in listOf("2004-", "2004-13-45", "2004--", "2004-03-01 live")) {
            val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(year = typed))
            assertEquals(listOf(typed), id3Texts(out, "TDRC"))
            assertEquals(listOf("2004"), id3Texts(out, "TDOR"), typed)
        }
        val flac = FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("DATE" to "1999", "ORIGINALDATE" to "1999"))
        assertEquals(listOf("2004"), comments(write(FlacTagCodec, flac, TagEdits(year = "2004-13")), "ORIGINALDATE"))
    }

    @Test
    fun aWellFormedDateAndTimeIsWrittenAsTyped() {
        val out = write(Id3TagCodec, id3(4, "TDRC" to "1999", "TDOR" to "1999"), TagEdits(year = "2004-03-01T12:30"))
        assertEquals(listOf("2004-03-01T12:30"), id3Texts(out, "TDOR"))
    }

    // ------------------------------------------------------------------------------------- MP4

    @Test
    fun mp4OnlyChangesItsDay() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©day", "1999")))
        val out = write(Mp4TagCodec, file, TagEdits(year = "2004"))
        assertEquals("2004", Mp4TagCodec.read(ByteArraySource(out)).year)
        assertEquals(listOf("©day"), ilstTypes(out))
    }

    @Test
    fun mp4PicardOriginalsFollowTheYearAndOnlyTheYearGoesIntoOriginalYear() {
        val file = Mp4Fixtures.file(
            listOf(
                Mp4Fixtures.text("©day", "1999"),
                Mp4Fixtures.freeform("ORIGINALYEAR", "1999"),
                Mp4Fixtures.freeform("originaldate", "1999-05-01"),
            ),
        )
        val out = write(Mp4TagCodec, file, TagEdits(year = "2004-03-01"))
        assertEquals(
            listOf(OriginalDate("2004", yearOnly = true), OriginalDate("2004-03-01", yearOnly = false)),
            Mp4TagCodec.read(ByteArraySource(out)).originalDates,
        )
        assertEquals(listOf("©day", "----", "----"), ilstTypes(out))
    }

    @Test
    fun mp4RemasterKeepsItsOriginal() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©day", "2011"), Mp4Fixtures.freeform("ORIGINALDATE", "1973")))
        val out = write(Mp4TagCodec, file, TagEdits(year = "2012"))
        assertEquals(listOf(OriginalDate("1973", yearOnly = false)), Mp4TagCodec.read(ByteArraySource(out)).originalDates)
    }

    @Test
    fun mp4FreeformItemsOfAnotherNamespaceAreNotOriginals() {
        val other = Mp4Fixtures.box(
            "----",
            Mp4Fixtures.leaf("mean", ByteArray(4) + "org.example".encodeToByteArray()),
            Mp4Fixtures.leaf("name", ByteArray(4) + "ORIGINALYEAR".encodeToByteArray()),
            Mp4Fixtures.data(1, "1999".encodeToByteArray()),
        )
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©day", "1999"), other))
        assertEquals(emptyList(), Mp4TagCodec.read(ByteArraySource(file)).originalDates)
        val out = write(Mp4TagCodec, file, TagEdits(year = "2004"))
        assertTrue(out.asList().windowed(4).any { it == "1999".encodeToByteArray().asList() })
    }

    private fun ilstTypes(file: ByteArray): List<String> {
        val atom = Mp4Boxes.topLevel(ByteArraySource(file))!!.first { it.type == "moov" }
        val tree = Mp4Boxes.parse(file.copyOfRange(atom.offset.toInt(), atom.end.toInt()))!!
        return tree.child("udta")!!.child("meta")!!.child("ilst")!!.children!!.map { it.type }
    }
}

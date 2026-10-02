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

    // ------------------------------------------------------------------------------------- MP4

    @Test
    fun mp4OnlyChangesItsDay() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©day", "1999")))
        val out = write(Mp4TagCodec, file, TagEdits(year = "2004"))
        assertEquals("2004", Mp4TagCodec.read(ByteArraySource(out)).year)
        assertEquals(listOf("©day"), ilstTypes(out))
    }

    private fun ilstTypes(file: ByteArray): List<String> {
        val atom = Mp4Boxes.topLevel(ByteArraySource(file))!!.first { it.type == "moov" }
        val tree = Mp4Boxes.parse(file.copyOfRange(atom.offset.toInt(), atom.end.toInt()))!!
        return tree.child("udta")!!.child("meta")!!.child("ilst")!!.children!!.map { it.type }
    }
}

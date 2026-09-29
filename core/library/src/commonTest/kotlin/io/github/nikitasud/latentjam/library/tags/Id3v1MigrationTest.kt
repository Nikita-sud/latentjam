/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class Id3v1MigrationTest {

    /** ID3v1.2's TAG+ in front of its TAG block. */
    private fun enhanced() = Id3TestTags.v1ExtendedTrailer() + Id3TestTags.v1Trailer()

    /** One TAG block more than a rewrite removes, so the oldest would be left standing. */
    private fun overStacked() = (0..Id3v1.MAX_STACKED_TRAILERS).fold(byteArrayOf()) { acc, _ -> acc + Id3TestTags.v1Trailer() }

    private fun titled(title: String = "Old") =
        Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body(title))), padding = 256)

    /** [titled] with a stray byte in its padding: a tag refused as ID3_MALFORMED_FRAMES. */
    private fun malformed() = titled().also { it[it.size - 5] = 0x41 }

    private fun read(file: ByteArray) = Id3TagCodec.read(ByteArraySource(file))

    private fun plan(file: ByteArray, edits: TagEdits) = Id3TagCodec.plan(ByteArraySource(file), edits)

    private fun refusalOf(plan: WritePlan) = assertIs<WritePlan.Refused>(plan).reason

    @Test
    fun legacyFieldsSurviveATitleOnlyEditAndVerificationReadsThem() {
        val trailer = Id3TestTags.v1Trailer(artist = "Legacy artist", album = "Legacy album", comment = "Keep this comment")
            .also { it[125] = 0; it[126] = 7 }
        val original = Id3TestTags.mp3Payload() + trailer
        val before = Id3TagCodec.read(ByteArraySource(original))
        assertEquals("Legacy artist", before.artist)
        assertEquals(7, before.trackNumber)
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(title = "New"))
        val fields = assertNotNull(Id3Tags.readFields(edited))
        assertEquals("Legacy artist", fields.artist)
        assertEquals("Legacy album", fields.album)
        assertEquals("1999", fields.year)
        assertEquals("Rock", fields.genre)
        assertEquals("7", fields.track)
        assertEquals(1, fields.unmanaged.count { it.startsWith("COMM:") })
        assertEquals(0, Id3v1.trailerLength(edited))
    }

    /** A v2 tag holding [comment] as its only COMM, beside a v1 trailer whose comment is [legacy]. */
    private fun commented(major: Int, comment: TestFrame, legacy: String, track: Int = 0): ByteArray {
        val trailer = Id3TestTags.v1Trailer(comment = legacy).also { if (track > 0) { it[125] = 0; it[126] = track.toByte() } }
        return Id3TestTags.build(major, listOf(TestFrame("TIT2", latin1Body("Old")), comment), padding = 256) +
            mp3Payload() + trailer
    }

    /** A UTF-16 COMM frame with an empty descriptor, as most Windows taggers write it. */
    private fun utf16Comment(text: String): TestFrame {
        val text16 = Id3TestTags.utf16Body(text)
        val body = byteArrayOf(1) + "eng".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) +
            text16.copyOfRange(1, text16.size)
        return TestFrame("COMM", body)
    }

    private fun comments(file: ByteArray) = assertNotNull(Id3Tags.readFields(file)).unmanaged.count { it.startsWith("COMM:") }

    @Test
    fun aV1CommentTheV2TagAlreadyHoldsIsNotAddedTwice() {
        val long = "Ripped from the original vinyl pressing, 1987"
        val spaced = "abcdefghij abcdefghij abcdefg and more"
        for (major in listOf(3, 4)) {
            // Equal, truncated to 30 (no track) and to 28 (track byte set), and space-padded.
            val cases = listOf(
                commented(major, Id3TestTags.commentFrame("Great album"), "Great album"),
                commented(major, Id3TestTags.commentFrame(long), long.take(30)),
                commented(major, Id3TestTags.commentFrame(long), long.take(28), track = 5),
                commented(major, Id3TestTags.commentFrame("1"), "1                             "),
                // The cut lands on a space, which trimming takes off: 29 characters, still a cut.
                commented(major, Id3TestTags.commentFrame(spaced), spaced.take(30)),
                commented(major, utf16Comment("Caf\u00e9 del Mar, the long mix"), "Caf\u00e9 del Mar, the long mix"),
            )
            for ((i, original) in cases.withIndex()) {
                assertEquals(1, comments(original), "case $i starts with one comment (v2.$major)")
                val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(title = "New"))
                assertEquals(1, comments(edited), "case $i keeps one comment (v2.$major)")
                assertEquals(0, Id3v1.trailerLength(edited))
            }
        }
    }

    @Test
    fun aShortV1CommentThatOnlyBeginsTheV2OneIsADifferentCommentAndSurvives() {
        // "Great" was not cut from "Great album": it ends well before the field's width, so it is
        // its own comment, and the trailer holding it is about to be removed.
        for (legacy in listOf("Great", "Ripped from the original")) {
            val original = commented(3, Id3TestTags.commentFrame("$legacy album, remastered"), legacy)
            val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(title = "New"))
            assertEquals(2, comments(edited), legacy)
        }
    }

    @Test
    fun aV1CommentTheV2TagDoesNotHoldIsStillCarriedBesideIt() {
        val original = commented(3, Id3TestTags.commentFrame("Great album"), "Bought at the flea market")
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(title = "New"))
        assertEquals(2, comments(edited))
    }

    /** [text]'s UTF-8 bytes as one char per byte, which is how [Id3TestTags.v1Trailer] takes raw bytes. */
    private fun utf8Bytes(text: String) = text.encodeToByteArray().joinToString("") { (it.toInt() and 0xFF).toChar().toString() }

    @Test
    fun aV1FieldStoredAsUtf8IsCarriedOverAsTheTextItSpells() {
        for (major in listOf(3, 4)) {
            val original = Id3TestTags.build(major, listOf(TestFrame("TIT2", latin1Body("Old"))), padding = 256) + mp3Payload() + Id3TestTags.v1Trailer(
                artist = "Caf\u00e9 Tacvba",
                album = utf8Bytes("Gr\u00fc\u00dfe"),
                comment = utf8Bytes("\u0421\u043f\u043b\u0438\u043d"),
            )
            assertEquals("Gr\u00fc\u00dfe", read(original).album, "read before the edit (v2.$major)")
            val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(title = "New"))
            val fields = assertNotNull(Id3Tags.readFields(edited))
            assertEquals("Gr\u00fc\u00dfe", fields.album, "v2.$major")
            // A genuine Latin-1 field beside it keeps reading as Latin-1.
            assertEquals("Caf\u00e9 Tacvba", fields.artist, "v2.$major")
            val version = if (major == 3) Id3Version.V2_3 else Id3Version.V2_4
            val tag = assertIs<Id3Parse.Parsed>(Id3Codec.parse(edited)).tag
            assertEquals(listOf("\u0421\u043f\u043b\u0438\u043d"), Id3Tags.commentTexts(version, tag.frames), "v2.$major")
        }
    }

    @Test
    fun aUtf8V1FieldCutMidCharacterByTheThirtyByteWidthKeepsTheCharactersBeforeTheCut() {
        // 15 two-byte letters fill 30 bytes exactly; 16 overflow, and the cut lands inside the last.
        val cyrillic = "\u0410\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043a\u043b\u043c\u043d\u043e"
        val truncatedBytes = utf8Bytes("x" + cyrillic).take(30)
        val original = mp3Payload() + Id3TestTags.v1Trailer(title = truncatedBytes)
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(artist = "New"))
        assertEquals("x" + cyrillic.dropLast(1), assertNotNull(Id3Tags.readFields(edited)).title)
    }

    @Test
    fun aFullLatin1V1FieldEndingInWhatCouldStartAUtf8LetterStaysLatin1() {
        // 0xE9 is "é" in Latin-1 and would lead a three-byte UTF-8 sequence. Nothing before it is
        // UTF-8, so the field is not a cut UTF-8 one.
        val title = "a".repeat(29) + "\u00e9"
        val original = mp3Payload() + Id3TestTags.v1Trailer(title = title)
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(artist = "New"))
        assertEquals(title, assertNotNull(Id3Tags.readFields(edited)).title)
    }

    @Test
    fun clearingALegacyOnlyFieldIsNotMistakenForANoOp() {
        val original = Id3TestTags.mp3Payload() + Id3TestTags.v1Trailer()
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(artist = ""))
        assertNull(Id3TagCodec.read(ByteArraySource(edited)).artist)
        assertEquals("Old v1 album", Id3Tags.read(edited)?.album)
    }

    @Test
    fun aPartialV2TagKeepsItsValuesAndInheritsMissingLegacyFields() {
        val original = Id3TestTags.build(3, listOf(TestFrame("TIT2", Id3TestTags.latin1Body("V2 title"))), padding = 512) +
            Id3TestTags.mp3Payload() + Id3TestTags.v1Trailer(comment = "Legacy comment")
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(genre = "Jazz"))
        assertEquals("V2 title", Id3Tags.read(edited)?.title)
        assertEquals("Old v1 artist", Id3Tags.read(edited)?.artist)
        assertEquals("Jazz", Id3Tags.read(edited)?.genre)
    }

    @Test
    fun clearingMissingFieldsOnAnUntaggedFilePreservesItExactly() {
        val original = Id3TestTags.mp3Payload()
        assertIs<WritePlan.NoChange>(Id3TagCodec.plan(ByteArraySource(original), TagEdits(artist = "")))
        assertContentEquals(original, Id3Tags.updateTag(original, TagEdits()))
    }

    @Test
    fun enhancedLegacyTagsAreNotDiscarded() {
        val original = Id3TestTags.mp3Payload() + Id3TestTags.v1ExtendedTrailer() + Id3TestTags.v1Trailer()
        assertIs<WritePlan.Refused>(Id3TagCodec.plan(ByteArraySource(original), TagEdits(title = "New")))
        assertNull(Id3Tags.updateTag(original, TagEdits(title = "New")))
    }

    @Test
    fun aTrailerThatCannotBeMigratedReadsAsTheLegacyRefusal() {
        for (trailer in listOf(enhanced(), overStacked())) {
            assertEquals(TagRefusal.ID3_UNSUPPORTED_LEGACY_TAG, read(titled() + mp3Payload() + trailer).refusal)
            assertEquals(TagRefusal.ID3_UNSUPPORTED_LEGACY_TAG, read(mp3Payload() + trailer).refusal)
        }
    }

    @Test
    fun aMalformedTagKeepsItsOwnRefusalWhateverTheTrailer() {
        for (trailer in listOf(byteArrayOf(), Id3TestTags.v1Trailer(), enhanced(), overStacked())) {
            val file = malformed() + mp3Payload() + trailer
            assertEquals(TagRefusal.ID3_MALFORMED_FRAMES, read(file).refusal)
            assertEquals(TagRefusal.ID3_MALFORMED_FRAMES, refusalOf(plan(file, TagEdits(title = "New"))))
        }
    }

    @Test
    fun anUntaggedFileThatIsNotMpegReadsAndPlansAsItDidWithoutATrailer() {
        val riff = "RIFF".encodeToByteArray() + ByteArray(600)
        val alone = read(riff)
        for (trailer in listOf(Id3TestTags.v1Trailer(), enhanced(), overStacked())) {
            assertEquals(alone, read(riff + trailer))
            assertEquals(TagRefusal.ID3_NOT_TAGGABLE, refusalOf(plan(riff + trailer, TagEdits(title = "New"))))
        }
    }

    @Test
    fun readFieldsFailsOnlyForTheTagItselfNeverForTheTrailer() {
        // The trailer check belongs to the callers, each of which reports it as its own refusal.
        assertEquals("Old", assertNotNull(Id3Tags.readFields(titled() + mp3Payload(), enhanced())).title)
        assertEquals("Old", assertNotNull(Id3Tags.readFields(titled() + mp3Payload(), overStacked())).title)
        assertNull(Id3Tags.readFields(malformed() + mp3Payload(), Id3TestTags.v1Trailer()))
        assertNull(Id3Tags.readFields("RIFF".encodeToByteArray() + ByteArray(12), Id3TestTags.v1Trailer()))
    }

    @Test
    fun aSaveThatChangesNothingLeavesAFileWithAnUnmigratableTrailerAlone() {
        for (trailer in listOf(enhanced(), overStacked())) {
            assertIs<WritePlan.NoChange>(plan(titled("Old") + mp3Payload() + trailer, TagEdits(title = "Old")))
            assertIs<WritePlan.NoChange>(plan(mp3Payload() + trailer, TagEdits(artist = "", lyrics = "")))
        }
    }

    @Test
    fun aRealEditOnAFileWithAnUnmigratableTrailerIsRefusedAsLegacy() {
        for (trailer in listOf(enhanced(), overStacked())) {
            val tagged = titled("Old") + mp3Payload() + trailer
            assertEquals(TagRefusal.ID3_UNSUPPORTED_LEGACY_TAG, refusalOf(plan(tagged, TagEdits(title = "New"))))
            assertEquals(TagRefusal.ID3_UNSUPPORTED_LEGACY_TAG, refusalOf(plan(mp3Payload() + trailer, TagEdits(title = "New"))))
            assertNull(Id3Tags.updateTag(tagged, TagEdits(title = "New")))
        }
    }
}

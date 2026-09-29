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

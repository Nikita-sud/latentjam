/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class Id3v1MigrationTest {

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
}

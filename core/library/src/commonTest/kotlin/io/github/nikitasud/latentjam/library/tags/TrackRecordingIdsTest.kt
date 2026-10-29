/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrackRecordingIdsTest {
    private val recording = "f1de9675-0170-46e5-a05c-d4e27640aec7"
    private val expected = TrackRecordingIds(recording, setOf("QMTH31100021"))

    @Test fun readsTheRecordingIdAndIsrcFromEveryContainer() {
        val comments = arrayOf("MUSICBRAINZ_TRACKID" to recording, "ISRC" to "QMTH31100021")
        val files = listOf(
            FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments(*comments)),
            OggFixtures.vorbis(*comments),
            OggFixtures.opus(*comments),
            Mp4Fixtures.file(listOf(
                Mp4Fixtures.freeform("MusicBrainz Track Id", recording),
                Mp4Fixtures.freeform("ISRC", "QMTH31100021"),
            )),
        )
        files.forEach { assertEquals(expected, readTrackRecordingIds(ByteArraySource(it))) }
    }

    @Test fun readsTheIsrcFromTheId3TextFrame() {
        for (major in listOf(3, 4)) {
            val file = Id3TestTags.build(major = major, frames = listOf(
                TestFrame("UFID", "http://musicbrainz.org\u0000$recording".encodeToByteArray()),
                TestFrame("TSRC", Id3TestTags.latin1Body("QMTH31100021")),
            )) + Id3TestTags.mp3Payload()
            assertEquals(expected, readTrackRecordingIds(ByteArraySource(file)))
        }
    }

    @Test fun normalisesIsrcsKeepsEverySeparateOneAndDropsGarbage() {
        val ids = trackRecordingIdsOf(listOf(
            "isrc" to " qm-th3-11-00021 ",
            "ISRC" to "USRC17607839; GBAYE0601498",
            "ISRC" to "not an isrc",
            "ISRC" to "QMTH311000",
        ))
        assertEquals(setOf("QMTH31100021", "USRC17607839", "GBAYE0601498"), ids.isrcs)
        assertNull(ids.recording)
    }

    @Test fun aFileListingTooManyIsrcsIsTrustedForNone() {
        val many = (0 until 17).map { "ISRC" to "QMTH311" + it.toString().padStart(5, '0') }
        assertTrue(trackRecordingIdsOf(many).isrcs.isEmpty())
        assertEquals(16, trackRecordingIdsOf(many.take(16)).isrcs.size)
    }

    @Test fun aMalformedReleaseIdDoesNotEraseTheRecordingIdHere() {
        val ids = trackRecordingIdsOf(listOf(
            "MUSICBRAINZ_RELEASETRACKID" to "garbage",
            "MUSICBRAINZ_TRACKID" to recording.uppercase(),
        ))
        assertEquals(recording, ids.recording)
        // Relocation stays as strict as before: the same comments give it nothing.
        assertEquals(TrackIdentityTags(), TrackIdentityTags.from(listOf(
            "MUSICBRAINZ_RELEASETRACKID" to "garbage",
            "MUSICBRAINZ_TRACKID" to recording,
        )))
    }

    @Test fun conflictingRecordingIdsInOneFileAreUnknown() {
        val ids = trackRecordingIdsOf(listOf(
            "MUSICBRAINZ_TRACKID" to recording,
            "MUSICBRAINZ_TRACKID" to "b1a9c0e9-d987-4042-ae91-78d6a3267d69",
        ))
        assertNull(ids.recording)
    }

    @Test fun anUntaggedFileIsEmptyAndTruncatedMetadataIsUnknown() {
        assertTrue(readTrackRecordingIds(ByteArraySource(FlacFixtures.file()))!!.isEmpty())
        val partial = FlacFixtures.comments("ISRC" to "QMTH31100021") + byteArrayOf(1)
        assertNull(readTrackRecordingIds(ByteArraySource(FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to partial))))
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class TrackIdentityTagsTest {
    private val release = "7058f71b-a82e-32b4-99fd-15a408ecb732"
    private val recording = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    private val expected = TrackIdentityTags(release, recording)
    private val comments = arrayOf("MUSICBRAINZ_RELEASETRACKID" to release, "MUSICBRAINZ_TRACKID" to recording)

    @Test fun readsMp3Id3v23AndV24IncludingTheUfidNamespace() {
        for (major in listOf(3, 4)) {
            val file = Id3TestTags.build(major = major, frames = listOf(
                TestFrame("TXXX", Id3TestTags.latin1Body("MusicBrainz Release Track Id\u0000$release")),
                TestFrame("UFID", "http://musicbrainz.org\u0000$recording".encodeToByteArray()),
                TestFrame("UFID", "unrelated-owner\u0000not-an-mbid".encodeToByteArray()),
            )) + Id3TestTags.mp3Payload()
            assertEquals(expected, readTrackIdentityTags(ByteArraySource(file)))
        }
    }

    @Test fun readsFlacVorbisAndOpusUsingTheSameIdentityRules() {
        val files = listOf(
            FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments(*comments)),
            OggFixtures.vorbis(*comments), OggFixtures.opus(*comments),
        )
        files.forEach { assertEquals(expected, readTrackIdentityTags(ByteArraySource(it))) }
    }

    @Test fun readsM4aBeforeOrAfterAudioWithOnlyABoundedHeaderProbe() {
        for (moovFirst in listOf(true, false)) {
            val file = Mp4Fixtures.file(listOf(
                Mp4Fixtures.freeform("MusicBrainz Release Track Id", release),
                Mp4Fixtures.freeform("MusicBrainz Track Id", recording),
            ), moovFirst = moovFirst)
            val source = ByteArraySource(file)
            val audioStart = Mp4Fixtures.chunkOffsets(file).first()
            val guarded = object : RandomAccessSource {
                override val length = source.length
                override fun read(offset: Long, count: Int): ByteArray? {
                    check((offset == 0L && count <= TagCodec.HEAD_BYTES) ||
                        offset + count <= audioStart || offset >= audioStart + Mp4Fixtures.samples.size)
                    return source.read(offset, count)
                }
            }
            assertEquals(expected, readTrackIdentityTags(guarded))
        }
    }

    @Test fun rejectsConflictingAndMalformedIdsButAcceptsUuidVersionsAndCase() {
        assertEquals(expected, TrackIdentityTags.from(listOf(
            "MusicBrainz Release Track Id" to " ${release.uppercase()} ",
            "musicbrainz_releasetrackid" to release, "MUSICBRAINZ_TRACKID" to recording,
        )))
        for (bad in listOf("", "not-a-uuid", "$release;$recording", "00000000-0000-0000-0000-000000000000")) {
            assertNull(TrackIdentityTags.from(listOf("MUSICBRAINZ_RELEASETRACKID" to bad)).releaseTrack)
        }
        assertEquals(TrackIdentityTags(), TrackIdentityTags.from(comments.toList() + ("MUSICBRAINZ_TRACKID" to release)))
        assertEquals(TrackIdentityTags(), TrackIdentityTags.from(comments.toList() + ("MUSICBRAINZ_RELEASETRACKID" to recording)))
    }

    @Test fun recordingIsNotAReleaseAndUnrelatedIdsAreNotSubstituted() {
        val facts = TrackIdentityTags.from(listOf("MUSICBRAINZ_TRACKID" to recording,
            "ACOUSTID_ID" to release, "ISRC" to "GBCEE7500015"))
        assertNull(facts.releaseTrack)
        assertEquals(recording, facts.recording)
    }

    @Test fun filesWithoutIdentityTagsAreReadableEmptyEvidence() {
        val files = listOf(Id3TestTags.mp3Payload(), FlacFixtures.file(), OggFixtures.opus(),
            OggFixtures.vorbis(), Mp4Fixtures.file(emptyList()))
        files.forEach { assertEquals(TrackIdentityTags(), assertNotNull(readTrackIdentityTags(ByteArraySource(it)))) }
    }

    @Test fun truncatedCommentsAndBadOggCrcNeverSupplyIdentityEvidence() {
        val partial = FlacFixtures.comments(*comments) + byteArrayOf(1)
        assertNull(readTrackIdentityTags(ByteArraySource(FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to partial))))
        val ogg = OggFixtures.opus(*comments)
        ogg[22] = (ogg[22].toInt() xor 1).toByte()
        assertNull(readTrackIdentityTags(ByteArraySource(ogg)))
    }

    @Test fun hugeCoverIsSkippedAndHugeId3DeclarationDoesNotAllocateItsBody() {
        val covered = FlacFixtures.file(
            FlacFixtures.PICTURE to ByteArray(9 * 1024 * 1024),
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments(*comments),
        )
        assertEquals(expected, readTrackIdentityTags(ByteArraySource(covered)))
        val header = "ID3".encodeToByteArray() + byteArrayOf(3, 0, 0, 127, 127, 127, 127)
        val source = object : RandomAccessSource {
            override val length = 300_000_000L
            override fun read(offset: Long, count: Int): ByteArray {
                check(count <= TagCodec.HEAD_BYTES) { "unbounded metadata allocation: $count" }
                return header.copyOf(count)
            }
        }
        assertNull(readTrackIdentityTags(source))
    }
}

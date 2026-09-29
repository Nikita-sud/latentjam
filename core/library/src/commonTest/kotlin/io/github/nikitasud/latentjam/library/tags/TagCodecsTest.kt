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
import kotlin.test.assertNull

internal class TagCodecsTest {

    private val files = mapOf(
        TagFormat.MP3 to Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + mp3Payload(),
        TagFormat.FLAC to FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "t")),
        TagFormat.OPUS to OggFixtures.opus("TITLE" to "t"),
        TagFormat.VORBIS to OggFixtures.vorbis("TITLE" to "t"),
        TagFormat.MP4 to Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "t"))),
    )

    @Test
    fun eachFormatIsReadByItsOwnCodec() {
        for ((format, file) in files) {
            val snapshot = TagCodecs.read(ByteArraySource(file))
            assertEquals(format, snapshot?.format)
            assertEquals("t", snapshot?.title)
        }
    }

    @Test
    fun anEmptyEditIsNoChangeEverywhere() {
        for (file in files.values) assertIs<WritePlan.NoChange>(TagCodecs.plan(ByteArraySource(file), TagEdits()))
    }

    @Test
    fun anEditIsWrittenCorrectlyEverywhere() {
        for (file in files.values) {
            val codec = TagCodecs.forSource(ByteArraySource(file))!!
            CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Новое", trackNumber = "2"))
        }
    }

    @Test
    fun unknownContainersAreRefused() {
        val wav = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVEfmt ".encodeToByteArray() + ByteArray(100)
        assertNull(TagCodecs.read(ByteArraySource(wav)))
        assertEquals(TagRefusal.UNSUPPORTED_FORMAT, (TagCodecs.plan(ByteArraySource(wav), TagEdits(title = "x")) as WritePlan.Refused).reason)
        assertNull(TagCodecs.read(ByteArraySource(ByteArray(0))))
    }

    @Test
    fun id3InFrontOfFlacIsRefusedNotMisread() {
        val file = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + files.getValue(TagFormat.FLAC)
        assertEquals(TagRefusal.ID3_BEFORE_OTHER_CONTAINER, TagCodecs.read(ByteArraySource(file))?.refusal)
    }
}

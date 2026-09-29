/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds Opus and Vorbis streams: a BOS identification page, tightly laced headers, audio pages. */
internal object OggFixtures {
    const val SERIAL = 0x1234567

    fun opusHead(): ByteArray =
        "OpusHead".encodeToByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    fun opusTags(vararg pairs: Pair<String, String>, tail: ByteArray = ByteArray(0)): ByteArray =
        "OpusTags".encodeToByteArray() + comments(*pairs) + tail

    fun vorbisIdentification(): ByteArray = byteArrayOf(1) + "vorbis".encodeToByteArray() + ByteArray(23) { 1 }

    fun vorbisComment(vararg pairs: Pair<String, String>, tail: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(3) + "vorbis".encodeToByteArray() + comments(*pairs) + byteArrayOf(1) + tail

    fun vorbisSetup(): ByteArray = byteArrayOf(5) + "vorbis".encodeToByteArray() + ByteArray(300) { it.toByte() }

    fun picture(type: Int, data: ByteArray): Pair<String, String> =
        "METADATA_BLOCK_PICTURE" to Base64Codec.encode(FlacPicture(type, "image/png", "", 1, 1, 24, 0, data).encode())

    private fun comments(vararg pairs: Pair<String, String>): ByteArray =
        VorbisComments("libopus 1.5".encodeToByteArray(), pairs.map { VorbisEntry.of(it.first, it.second) }).encode()

    fun stream(first: ByteArray, headers: List<ByteArray>, audioPages: Int = 5, serial: Int = SERIAL): ByteArray {
        var out = OggPages.serialize(0x02, 0, serial, 0, OggPages.lacingOf(first.size), first)
        val laid = OggPages.layout(headers, OggPages.minimumPages(headers))!!
        var sequence = 1
        for (page in laid) {
            out += OggPages.serialize(
                if (page.continues) 1 else 0,
                if (page.completesPacket) 0L else -1L,
                serial,
                sequence++,
                page.lacing,
                page.payload,
            )
        }
        repeat(audioPages) { i ->
            val packet = ByteArray(200) { (it + i).toByte() }
            out += OggPages.serialize(
                if (i == audioPages - 1) 0x04 else 0,
                960L * (i + 1),
                serial,
                sequence++,
                OggPages.lacingOf(packet.size),
                packet,
            )
        }
        return out
    }

    fun opus(vararg pairs: Pair<String, String>, padding: Int = 500): ByteArray =
        stream(opusHead(), listOf(opusTags(*pairs, tail = ByteArray(padding))))

    fun vorbis(vararg pairs: Pair<String, String>, padding: Int = 200): ByteArray =
        stream(vorbisIdentification(), listOf(vorbisComment(*pairs, tail = ByteArray(padding)), vorbisSetup()))

    /** Every page in [file], checked: valid CRC and consecutive sequence numbers. */
    fun assertPagesAreSound(file: ByteArray) {
        val source = ByteArraySource(file)
        var offset = 0L
        var expected = 0
        while (offset < file.size) {
            val page = assertNotNull(OggPages.readAt(source, offset), "no page at $offset")
            assertTrue(page.crcValid, "bad CRC at $offset")
            assertEquals(expected++, page.sequence)
            offset += page.size
        }
    }
}

internal class OggTagCodecTest {

    private val codec = OggTagCodec

    @Test
    fun base64MatchesTheStandardAlphabet() {
        assertEquals("TWFu", Base64Codec.encode("Man".encodeToByteArray()))
        assertEquals("TWE=", Base64Codec.encode("Ma".encodeToByteArray()))
        assertEquals("TQ==", Base64Codec.encode("M".encodeToByteArray()))
        assertContentEquals("Ma".encodeToByteArray(), Base64Codec.decode("TWE"))
        assertNull(Base64Codec.decode("TW*u"))
        val bytes = ByteArray(1000) { (it * 13).toByte() }
        assertContentEquals(bytes, Base64Codec.decode(Base64Codec.encode(bytes)))
    }

    @Test
    fun readsOpusAndVorbis() {
        val opus = codec.read(ByteArraySource(OggFixtures.opus("TITLE" to "Song", "TRACKNUMBER" to "2")))
        assertEquals(TagFormat.OPUS, opus.format)
        assertEquals("Opus", opus.version)
        assertEquals("Song", opus.title)
        assertEquals(2, opus.trackNumber)
        val vorbis = codec.read(ByteArraySource(OggFixtures.vorbis("ARTIST" to "Band")))
        assertEquals(TagFormat.VORBIS, vorbis.format)
        assertEquals("Band", vorbis.artist)
    }

    @Test
    fun opusEditWithinPaddingIsInPlaceAndTouchesOnlyTheHeader() {
        val file = OggFixtures.opus("TITLE" to "Song")
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A longer song title"))
        assertIs<WritePlan.InPlacePatch>(plan)
        val firstPageSize = assertNotNull(OggPages.readAt(ByteArraySource(file), 0)).size
        // Five 200-byte audio packets, one per page: 27 header bytes + 1 lacing byte + 200 each.
        assertTrue(plan.writes.all { it.offset >= firstPageSize && it.offset + it.bytes.size <= file.size - 5 * 228 })
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A longer song title"))
        OggFixtures.assertPagesAreSound(out)
    }

    @Test
    fun vorbisEditWithinPaddingKeepsTheSetupPacket() {
        val file = OggFixtures.vorbis("TITLE" to "Song")
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), TagEdits(album = "Record")))
        OggFixtures.assertPagesAreSound(CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(album = "Record")))
    }

    @Test
    fun growthPastPaddingRewritesAndRenumbersEveryLaterPage() {
        val file = OggFixtures.opus("TITLE" to "Song")
        val edits = TagEdits(lyrics = "слово ".repeat(15_000).trim())
        val plan = codec.plan(ByteArraySource(file), edits)
        assertIs<WritePlan.StreamingRewrite>(plan)
        assertTrue(plan.segments.any { it is OutputSegment.Transformed })
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        OggFixtures.assertPagesAreSound(out)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(out), TagEdits(title = "Again")))
    }

    @Test
    fun headerOneBytePastItsPaddingIsRewritten() {
        val file = OggFixtures.opus("TITLE" to "Song", padding = 10)
        val fits = TagEdits(title = "Song" + "0123456789")
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), fits))
        val tooBig = TagEdits(title = "Song" + "01234567890")
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), tooBig))
        OggFixtures.assertPagesAreSound(CodecAssertions.assertWriteMatchesExpectation(codec, file, tooBig))
    }

    @Test
    fun opusBinaryTailIsPreservedNeverUsedAsPadding() {
        val file = OggFixtures.stream(
            OggFixtures.opusHead(),
            listOf(OggFixtures.opusTags("TITLE" to "Abc", tail = byteArrayOf(1, 7, 7))),
        )
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), TagEdits(title = "Xyz")))
        val grown = TagEdits(title = "Abcd")
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), grown))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, grown)
    }

    @Test
    fun nonZeroVorbisTailIsPreserved() {
        val file = OggFixtures.stream(
            OggFixtures.vorbisIdentification(),
            listOf(OggFixtures.vorbisComment("TITLE" to "Abc", tail = byteArrayOf(0, 5)), OggFixtures.vorbisSetup()),
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Longer title"))
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = OggFixtures.opus("TITLE" to "Song")
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Song")))
    }

    @Test
    fun typeZeroPictureIsTheCoverWhenNoFrontCoverExists() {
        val file = OggFixtures.opus(OggFixtures.picture(4, byteArrayOf(1)), OggFixtures.picture(0, byteArrayOf(2)), padding = 3000)
        assertEquals(CoverInfo.of(byteArrayOf(2), "image/png"), codec.read(ByteArraySource(file)).cover)
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(6, 6), ImageProbe.PNG)),
        )
    }

    @Test
    fun coverIsAddedAndRemoved() {
        val file = OggFixtures.opus("TITLE" to "t", padding = 3000)
        val added = CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.jpeg(9, 9), ImageProbe.JPEG)),
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, added, TagEdits(cover = CoverEdit.Remove))
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            OggFixtures.vorbis("TITLE" to "t", "ARTIST" to "A; B", "ARTISTS" to "A", "ARTISTS" to "B"),
            TagEdits(
                title = "Заголовок", artist = "X; Y", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004", trackNumber = "1", trackTotal = "9", discNumber = "", discTotal = "",
                lyrics = "строка",
            ),
        )
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        OggFixtures.assertPagesAreSound(
            CodecAssertions.assertWriteMatchesExpectation(codec, OggFixtures.opus("TITLE" to "t"), TagEdits(lyrics = lyrics)),
        )
    }

    @Test
    fun aSecondStreamInTheHeaderIsRefused() {
        val first = OggPages.serialize(0x02, 0, 1, 0, OggPages.lacingOf(19), OggFixtures.opusHead())
        val other = OggPages.serialize(0x02, 0, 2, 0, OggPages.lacingOf(19), OggFixtures.opusHead())
        assertEquals(TagRefusal.OGG_MULTIPLE_STREAMS, codec.read(ByteArraySource(first + other)).refusal)
    }

    @Test
    fun aCorruptHeaderPageIsRefused() {
        val file = OggFixtures.opus("TITLE" to "t")
        val firstPageSize = assertNotNull(OggPages.readAt(ByteArraySource(file), 0)).size
        file[firstPageSize + 40] = (file[firstPageSize + 40].toInt() xor 1).toByte()
        assertEquals(TagRefusal.OGG_BAD_PAGE_CRC, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun anUnknownCodecIsRefused() {
        val file = OggFixtures.stream(byteArrayOf(0x7F) + "FLAC".encodeToByteArray() + ByteArray(20), listOf(ByteArray(10)))
        assertEquals(TagRefusal.OGG_UNKNOWN_CODEC, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun aChainedStreamIsRefusedDuringARewriteBeforeAnythingIsReplaced() {
        val chained = OggFixtures.opus("TITLE" to "a") +
            OggFixtures.stream(OggFixtures.opusHead(), listOf(OggFixtures.opusTags("TITLE" to "b")), serial = 77)
        val plan = codec.plan(ByteArraySource(chained), TagEdits(lyrics = "x".repeat(70_000)))
        assertIs<WritePlan.StreamingRewrite>(plan)
        val error = assertFailsWith<StreamRefusedException> { WritePlans.applyInMemory(chained, plan) }
        assertEquals(TagRefusal.OGG_MULTIPLE_STREAMS, error.reason)
    }
}

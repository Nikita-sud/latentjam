/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Builds FLAC files block by block. Shares no code with the codec's serializer. */
internal object FlacFixtures {
    const val STREAMINFO = 0
    const val PADDING = 1
    const val APPLICATION = 2
    const val SEEKTABLE = 3
    const val VORBIS_COMMENT = 4
    const val PICTURE = 6

    val streamInfo = ByteArray(34) { (it + 1).toByte() }
    val audio = byteArrayOf(0xFF.toByte(), 0xF8.toByte()) + ByteArray(3000) { (it * 7 % 256).toByte() }

    fun block(type: Int, body: ByteArray, last: Boolean): ByteArray =
        byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        ) + body

    fun file(vararg blocks: Pair<Int, ByteArray>, first: Pair<Int, ByteArray> = STREAMINFO to streamInfo): ByteArray {
        val all = listOf(first) + blocks
        var out = "fLaC".encodeToByteArray()
        all.forEachIndexed { i, (type, body) -> out += block(type, body, last = i == all.lastIndex) }
        return out + audio
    }

    fun comments(vararg pairs: Pair<String, String>): ByteArray =
        VorbisComments("reference libFLAC".encodeToByteArray(), pairs.map { VorbisEntry.of(it.first, it.second) }).encode()

    fun picture(type: Int, data: ByteArray, mime: String = "image/png"): ByteArray =
        FlacPicture(type, mime, "", 1, 1, 24, 0, data).encode()

    fun audioStart(file: ByteArray): Int {
        var position = 4
        while (true) {
            val last = file[position].toInt() and 0x80 != 0
            val length = ((file[position + 1].toInt() and 0xFF) shl 16) or
                ((file[position + 2].toInt() and 0xFF) shl 8) or (file[position + 3].toInt() and 0xFF)
            position += 4 + length
            if (last) return position
        }
    }

    fun blockTypes(file: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var position = 4
        while (true) {
            val header = file[position].toInt() and 0xFF
            types += header and 0x7F
            val length = ((file[position + 1].toInt() and 0xFF) shl 16) or
                ((file[position + 2].toInt() and 0xFF) shl 8) or (file[position + 3].toInt() and 0xFF)
            position += 4 + length
            if (header and 0x80 != 0) return types
        }
    }
}

internal class FlacTagCodecTest {

    private val codec = FlacTagCodec

    private fun tagged(padding: Int = 1000) = FlacFixtures.file(
        FlacFixtures.SEEKTABLE to ByteArray(18) { 5 },
        FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments(
            "TITLE" to "Title",
            "ARTIST" to "Artist",
            "REPLAYGAIN_TRACK_GAIN" to "-7.1 dB",
            "TRACKNUMBER" to "3",
            "TRACKTOTAL" to "12",
        ),
        FlacFixtures.PICTURE to FlacFixtures.picture(4, byteArrayOf(9, 9)),
        FlacFixtures.PICTURE to FlacFixtures.picture(3, byteArrayOf(1, 2, 3)),
        FlacFixtures.APPLICATION to "riff".encodeToByteArray() + ByteArray(20) { 1 },
        FlacFixtures.PADDING to ByteArray(padding),
    )

    @Test
    fun recognizesOnlyFlac() {
        assertTrue(codec.recognizes(tagged().copyOf(TagCodec.HEAD_BYTES)))
        assertTrue(!codec.recognizes("ID3".encodeToByteArray() + ByteArray(61)))
    }

    @Test
    fun readReturnsFieldsCoverAndOtherPictures() {
        val snapshot = codec.read(ByteArraySource(tagged()))
        assertEquals(TagFormat.FLAC, snapshot.format)
        assertEquals("Title", snapshot.title)
        assertEquals(3, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
        assertEquals(CoverInfo.of(byteArrayOf(1, 2, 3), "image/png"), snapshot.cover)
        assertEquals(1, snapshot.otherPictures)
    }

    @Test
    fun editWithinPaddingIsInPlaceAndTouchesOnlyMetadata() {
        val file = tagged()
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A somewhat longer title"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        val audioStart = FlacFixtures.audioStart(file)
        assertTrue(plan.writes.all { it.offset + it.bytes.size <= audioStart })
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A somewhat longer title"))
    }

    @Test
    fun growthWithoutPaddingRewritesWithSpareSpaceAndTheNextEditGoesInPlace() {
        val file = tagged(padding = 0) // a padding block with an empty body: only its header is spare
        val edits = TagEdits(lyrics = "x".repeat(2000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertTrue(FlacFixtures.audioStart(out) - FlacFixtures.audioStart(file) >= TagSpace.SPARE_BYTES)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(out), TagEdits(title = "Again")))
        assertContentEquals(FlacFixtures.audio, out.copyOfRange(FlacFixtures.audioStart(out), out.size))
    }

    @Test
    fun aRemainderTooSmallForAPaddingHeaderForcesARewrite() {
        // Padding block: 4-byte header + 10 bytes = 14 spare. Growing the comment by 12 leaves 2.
        val file = tagged(padding = 10)
        val edits = TagEdits(title = "Title" + "123456789012")
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
    }

    @Test
    fun aRemainderOfExactlyZeroFitsWithoutPadding() {
        // Growing the comment by exactly the padding block's 14 bytes consumes it entirely.
        val file = tagged(padding = 10)
        val edits = TagEdits(title = "Title" + "12345678901234")
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertTrue(FlacFixtures.PADDING !in FlacFixtures.blockTypes(out))
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = FlacFixtures.file(
            FlacFixtures.PADDING to ByteArray(100),
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "Title"),
        )
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Title")))
    }

    @Test
    fun coverReplaceKeepsOtherPicturesInOrder() {
        val jpeg = TestImages.jpeg(40, 40)
        val out = CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(padding = 5000),
            TagEdits(cover = CoverEdit.Replace(jpeg, ImageProbe.JPEG)),
        )
        assertEquals(1, codec.read(ByteArraySource(out)).otherPictures)
    }

    @Test
    fun typeZeroPictureIsTheCoverWhenNoFrontCoverExists() {
        val file = FlacFixtures.file(
            FlacFixtures.PICTURE to FlacFixtures.picture(4, byteArrayOf(1)),
            FlacFixtures.PICTURE to FlacFixtures.picture(0, byteArrayOf(2)),
            FlacFixtures.PADDING to ByteArray(2000),
        )
        assertEquals(CoverInfo.of(byteArrayOf(2), "image/png"), codec.read(ByteArraySource(file)).cover)
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(3, 3), ImageProbe.PNG)),
        )
    }

    @Test
    fun coverIsAddedAfterTheCommentsAndRemovedAgain() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "t"),
            FlacFixtures.PADDING to ByteArray(3000),
        )
        val added = CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(5, 5), ImageProbe.PNG)),
        )
        assertEquals(
            listOf(FlacFixtures.STREAMINFO, FlacFixtures.VORBIS_COMMENT, FlacFixtures.PICTURE, FlacFixtures.PADDING),
            FlacFixtures.blockTypes(added),
        )
        CodecAssertions.assertWriteMatchesExpectation(codec, added, TagEdits(cover = CoverEdit.Remove))
    }

    @Test
    fun aFileWithoutCommentsGetsABlock() {
        val file = FlacFixtures.file(FlacFixtures.PADDING to ByteArray(500))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Fresh", trackNumber = "1"))
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            tagged(),
            TagEdits(
                title = "Заголовок", artist = "X", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004", trackNumber = "", trackTotal = "10", discNumber = "1", discTotal = "2",
                lyrics = "строка", cover = CoverEdit.Remove,
            ),
        )
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), TagEdits(lyrics = lyrics))
    }

    @Test
    fun theVendorStringIsPreserved() {
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, tagged(), TagEdits(title = "x"))
        val start = 4 + 4 + 34 + 4 + 18 // magic, STREAMINFO block, SEEKTABLE block
        val header = out[start].toInt() and 0x7F
        assertEquals(FlacFixtures.VORBIS_COMMENT, header)
        val body = out.copyOfRange(start + 4, out.size)
        val (comments, _) = kotlin.test.assertNotNull(VorbisComments.decode(body, 0))
        assertContentEquals("reference libFLAC".encodeToByteArray(), comments.vendor)
    }

    @Test
    fun streamInfoNotFirstIsRefused() {
        val file = FlacFixtures.file(first = FlacFixtures.PADDING to ByteArray(10))
        assertEquals(TagRefusal.FLAC_STREAMINFO_NOT_FIRST, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun truncatedMetadataIsRefused() {
        val file = tagged()
        val cut = file.copyOf(FlacFixtures.audioStart(file) - 5)
        assertEquals(TagRefusal.TRUNCATED, codec.read(ByteArraySource(cut)).refusal)
        assertIs<WritePlan.Refused>(codec.plan(ByteArraySource(cut), TagEdits(title = "x")))
    }

    @Test
    fun aSecondCommentBlockIsRefused() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "a"),
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "b"),
        )
        assertEquals(TagRefusal.FLAC_MALFORMED_METADATA, codec.read(ByteArraySource(file)).refusal)
    }
}

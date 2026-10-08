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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [TagCodecs.readCover]: the picture [TagSnapshot.cover] describes, its bytes and type, in every format. */
internal class CoverPictureReadTest {

    private val front = TestImages.jpeg(300, 300)
    private val other = TestImages.png(40, 40)

    private fun apic(type: Int, data: ByteArray, mime: String) = TestFrame(
        "APIC",
        byteArrayOf(0) + mime.encodeToByteArray() + byteArrayOf(0, type.toByte(), 0) + data,
    )

    private fun assertCover(file: ByteArray, expected: ByteArray, mime: String) {
        val picture = assertNotNull(TagCodecs.readCover(ByteArraySource(file)))
        assertContentEquals(expected, picture.bytes)
        assertEquals(mime, picture.mime)
        // The same picture the snapshot describes, so a CRC check against it agrees.
        assertEquals(Crc32.of(expected), TagCodecs.read(ByteArraySource(file))?.cover?.crc32)
    }

    @Test
    fun id3TakesTheFrontCoverApicOverAnOtherPicture() {
        val file = Id3TestTags.build(3, listOf(apic(4, other, "image/png"), apic(3, front, "image/jpeg"))) + mp3Payload()
        assertCover(file, front, "image/jpeg")
    }

    @Test
    fun id3NamesTheImageByItsBytesWhenTheFrameMisnamesIt() {
        val file = Id3TestTags.build(4, listOf(apic(3, other, "image/jpg"))) + mp3Payload()
        assertCover(file, other, "image/png")
    }

    @Test
    fun flacTakesTheFrontCoverPictureBlock() {
        val file = FlacFixtures.file(
            FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "t"),
            FlacFixtures.PICTURE to FlacFixtures.picture(4, other),
            FlacFixtures.PICTURE to FlacFixtures.picture(3, front, mime = "image/jpeg"),
        )
        assertCover(file, front, "image/jpeg")
    }

    @Test
    fun opusAndVorbisTakeTheMetadataBlockPicture() {
        for (file in listOf(
            OggFixtures.opus("TITLE" to "t", OggFixtures.picture(3, other)),
            OggFixtures.vorbis("TITLE" to "t", OggFixtures.picture(3, other)),
        )) {
            assertCover(file, other, "image/png")
        }
    }

    @Test
    fun mp4TakesTheFirstCovrImage() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "t"), Mp4Fixtures.covers(13 to front, 14 to other)))
        assertCover(file, front, "image/jpeg")
    }

    @Test
    fun mp4TakesTheFirstReadableCovrBoxOverAStubOne() {
        // Payload under the eight-byte data header is a header with no value: the cover is the first
        // box read() shows, whether the stub is its own covr item or precedes the picture inside one.
        val stub = Mp4Fixtures.leaf("data", ByteArray(4))
        for (file in listOf(
            Mp4Fixtures.file(
                listOf(Mp4Fixtures.text("©nam", "t"), Mp4Fixtures.box("covr", stub), Mp4Fixtures.covers(13 to front)),
            ),
            Mp4Fixtures.file(
                listOf(Mp4Fixtures.text("©nam", "t"), Mp4Fixtures.box("covr", stub, Mp4Fixtures.data(13, front))),
            ),
        )) {
            assertCover(file, front, "image/jpeg")
        }
    }

    @Test
    fun mp4WithOnlyAStubCovrBoxHasNoCover() {
        val file = Mp4Fixtures.file(
            listOf(Mp4Fixtures.text("©nam", "t"), Mp4Fixtures.box("covr", Mp4Fixtures.leaf("data", ByteArray(4)))),
        )
        assertNull(TagCodecs.readCover(ByteArraySource(file)))
        // read() agrees: neither path counts a box shorter than its own header as a picture.
        assertNull(TagCodecs.read(ByteArraySource(file))?.cover)
    }

    @Test
    fun aFileWithoutAPictureHasNoCover() {
        for (file in listOf(
            Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + mp3Payload(),
            FlacFixtures.file(FlacFixtures.VORBIS_COMMENT to FlacFixtures.comments("TITLE" to "t")),
            OggFixtures.opus("TITLE" to "t"),
            Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "t"))),
        )) {
            assertNull(TagCodecs.readCover(ByteArraySource(file)))
        }
    }

    @Test
    fun mp4FirstCovrOverTheLimitIsTheSamePictureReadDescribes() {
        // The first covr is over the caller's ceiling: readCover hands out no bytes there, while
        // read() still names that picture — it returns a type, size and checksum, never the bytes, so
        // no size is too big for it. Both readers agree on which picture is the cover; the sizes are
        // set here so the ceiling can be probed from either side (the extra picture is far smaller).
        val cover = TestImages.jpeg(300, 300, filler = 256)
        val extra = TestImages.png(40, 40, filler = 16)
        val file = Mp4Fixtures.file(
            listOf(Mp4Fixtures.text("©nam", "t"), Mp4Fixtures.covers(13 to cover, 14 to extra)),
        )
        val snapshot = assertNotNull(TagCodecs.read(ByteArraySource(file)))
        val described = assertNotNull(snapshot.cover)
        assertEquals(cover.size, described.size)
        assertEquals(Crc32.of(cover), described.crc32)
        // The smaller second covr is an extra picture, not a fallback for a cover over the ceiling.
        assertEquals(1, snapshot.otherPictures)

        assertNull(TagCodecs.readCover(ByteArraySource(file), maxBytes = cover.size - 1))
        // A limit that would fit only the extra picture buys no substitution either.
        assertNull(TagCodecs.readCover(ByteArraySource(file), maxBytes = extra.size))

        val picture = assertNotNull(TagCodecs.readCover(ByteArraySource(file), maxBytes = cover.size))
        assertContentEquals(cover, picture.bytes)
        assertEquals(described.mime, picture.mime)
        // The default ceiling is MAX_COVER_BYTES, above this picture: the plain call agrees as well.
        assertContentEquals(cover, assertNotNull(TagCodecs.readCover(ByteArraySource(file))).bytes)
    }

    @Test
    fun aPictureOverTheLimitIsNotRead() {
        val file = FlacFixtures.file(FlacFixtures.PICTURE to FlacFixtures.picture(3, front, mime = "image/jpeg"))
        assertNull(TagCodecs.readCover(ByteArraySource(file), maxBytes = front.size - 1))
        assertNotNull(TagCodecs.readCover(ByteArraySource(file), maxBytes = front.size))
    }

    @Test
    fun anUnknownContainerHasNoCover() {
        val wav = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVEfmt ".encodeToByteArray() + ByteArray(100)
        assertNull(TagCodecs.readCover(ByteArraySource(wav)))
    }
}

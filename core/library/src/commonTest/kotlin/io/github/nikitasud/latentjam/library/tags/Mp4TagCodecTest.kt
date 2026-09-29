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

internal class Mp4TagCodecTest {

    private val codec = Mp4TagCodec

    private val items = listOf(
        Mp4Fixtures.text("©nam", "Song"),
        Mp4Fixtures.text("©ART", "A; B"),
        Mp4Fixtures.pair("trkn", 3, 12),
        Mp4Fixtures.pair("disk", 1, 2, length = 6),
        Mp4Fixtures.freeform("ARTISTS", "A", "B"),
        Mp4Fixtures.freeform("MusicBrainz Track Id", "abc"),
        Mp4Fixtures.covers(13 to TestImages.jpeg(4, 4), 14 to TestImages.png(2, 2)),
    )

    private fun assertSamplesStillAddressed(file: ByteArray) {
        val offsets = Mp4Fixtures.chunkOffsets(file)
        assertEquals(Mp4Fixtures.samples[0], file[offsets[0].toInt()])
        assertEquals(Mp4Fixtures.samples[1000], file[offsets[1].toInt()])
    }

    @Test
    fun recognizesFtyp() {
        assertTrue(codec.recognizes(Mp4Fixtures.file(items).copyOf(TagCodec.HEAD_BYTES)))
        assertTrue(!codec.recognizes("fLaC".encodeToByteArray() + ByteArray(60)))
    }

    @Test
    fun readsTextNumbersCoverAndCreditedArtists() {
        val snapshot = codec.read(ByteArraySource(Mp4Fixtures.file(items)))
        assertEquals(TagFormat.MP4, snapshot.format)
        assertEquals("Song", snapshot.title)
        assertEquals(3, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
        assertEquals(1, snapshot.discNumber)
        assertEquals(2, snapshot.discTotal)
        assertEquals(listOf("A", "B"), snapshot.artists)
        assertEquals(CoverInfo.of(TestImages.jpeg(4, 4), ImageProbe.JPEG), snapshot.cover)
        assertEquals(CoverInfo.of(TestImages.png(2, 2), ImageProbe.PNG), snapshot.nextCover)
        assertEquals(1, snapshot.otherPictures)
    }

    @Test
    fun editWithinIlstFreeIsInPlaceAndMovesNothing() {
        val file = Mp4Fixtures.file(items, freeAfterIlst = 2000)
        val plan = codec.plan(ByteArraySource(file), TagEdits(title = "A much longer song title"))
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "A much longer song title"))
        assertEquals(Mp4Fixtures.chunkOffsets(file), Mp4Fixtures.chunkOffsets(out))
    }

    @Test
    fun moovGrowsIntoTheFreeBoxAfterIt() {
        val file = Mp4Fixtures.file(items, freeAfterMoov = 20_000)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        val plan = codec.plan(ByteArraySource(file), edits)
        assertIs<WritePlan.InPlacePatch>(plan)
        assertEquals(file.size.toLong(), plan.newLength)
        assertSamplesStillAddressed(CodecAssertions.assertWriteMatchesExpectation(codec, file, edits))
    }

    @Test
    fun moovLastIsRewrittenAtTheEndWithoutTouchingTheAudio() {
        val file = Mp4Fixtures.file(items, moovFirst = false)
        val moovOffset = Mp4Boxes.topLevel(ByteArraySource(file))!!.first { it.type == "moov" }.offset
        val edits = TagEdits(lyrics = "x".repeat(1000))
        val plan = codec.plan(ByteArraySource(file), edits)
        assertIs<WritePlan.InPlacePatch>(plan)
        assertTrue(plan.newLength > file.size)
        assertTrue(plan.writes.all { it.offset >= moovOffset })
        assertSamplesStillAddressed(CodecAssertions.assertWriteMatchesExpectation(codec, file, edits))
    }

    @Test
    fun moovFirstWithoutRoomIsRewrittenAndChunkOffsetsFollowTheAudio() {
        val file = Mp4Fixtures.file(items)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertSamplesStillAddressed(out)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(out), TagEdits(title = "Again")))
    }

    @Test
    fun offsetsBeforeMoovStayPut() {
        val ftyp = Mp4Fixtures.ftyp()
        val first = Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        val second = Mp4Fixtures.leaf("mdat", ByteArray(500) { 42 })
        fun moov(after: Int) = Mp4Fixtures.moov(listOf(ftyp.size + 8, after + 8), items)
        val moovSize = moov(0).size
        val file = ftyp + first + moov(ftyp.size + first.size + moovSize) + second
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(lyrics = "y".repeat(1000)))
        val offsets = Mp4Fixtures.chunkOffsets(out)
        assertEquals((ftyp.size + 8).toLong(), offsets[0])
        assertEquals(Mp4Fixtures.samples[0], out[offsets[0].toInt()])
        assertEquals(42.toByte(), out[offsets[1].toInt()])
        assertTrue(offsets[1] > (ftyp.size + first.size + moovSize + 8).toLong())
    }

    @Test
    fun settingTheSameValuesWritesNothing() {
        val file = Mp4Fixtures.file(items)
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(title = "Song", trackNumber = "3")))
    }

    @Test
    fun aFileWithoutTagsGetsThem() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            Mp4Fixtures.file(null),
            TagEdits(title = "First", trackNumber = "1", cover = CoverEdit.Replace(TestImages.png(3, 3), ImageProbe.PNG)),
        )
    }

    @Test
    fun quickTimeStyleMetaIsEditedToo() {
        val file = Mp4Fixtures.file(items, freeAfterIlst = 1000, iso = false)
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), TagEdits(album = "Record")))
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(album = "Record"))
    }

    @Test
    fun coverReplaceAndRemoveActOnTheFirstImageOnly() {
        val file = Mp4Fixtures.file(items, freeAfterIlst = 5000)
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            file,
            TagEdits(cover = CoverEdit.Replace(TestImages.png(6, 6), ImageProbe.PNG)),
        )
        val removed = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(cover = CoverEdit.Remove))
        assertEquals(CoverInfo.of(TestImages.png(2, 2), ImageProbe.PNG), codec.read(ByteArraySource(removed)).cover)
    }

    @Test
    fun aTextGenreReplacesTheNumericOne() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.box("gnre", Mp4Fixtures.data(0, byteArrayOf(0, 18)))), freeAfterIlst = 500)
        assertEquals("Rock", codec.read(ByteArraySource(file)).genre)
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(genre = "Jazz"))
        assertEquals("Jazz", codec.read(ByteArraySource(out)).genre)
    }

    @Test
    fun removingANumberKeepsTheTotal() {
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, Mp4Fixtures.file(items), TagEdits(trackNumber = ""))
        val snapshot = codec.read(ByteArraySource(out))
        assertEquals(null, snapshot.trackNumber)
        assertEquals(12, snapshot.trackTotal)
    }

    @Test
    fun everyFieldAtOnceMatchesTheExpectation() {
        CodecAssertions.assertWriteMatchesExpectation(
            codec,
            Mp4Fixtures.file(items),
            TagEdits(
                title = "Заголовок", artist = "X; Y", album = "Альбом", albumArtist = "Various", genre = "Rock",
                year = "2004-05-06", trackNumber = "1", trackTotal = "9", discNumber = "2", discTotal = "",
                lyrics = "строка", cover = CoverEdit.Replace(TestImages.jpeg(7, 7), ImageProbe.JPEG),
            ),
        )
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = "Ночь, улица, фонарь, аптека. 夜の街を歩く。\n".repeat(400).trim()
        assertSamplesStillAddressed(
            CodecAssertions.assertWriteMatchesExpectation(codec, Mp4Fixtures.file(items), TagEdits(lyrics = lyrics)),
        )
    }

    @Test
    fun fragmentedFilesAreRefused() {
        val file = Mp4Fixtures.file(items) + Mp4Fixtures.leaf("moof", ByteArray(16))
        assertEquals(TagRefusal.MP4_FRAGMENTED, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun protectedFilesAreRefused() {
        val file = Mp4Fixtures.file(items, sampleEntry = "drms")
        assertEquals(TagRefusal.MP4_DRM_PROTECTED, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun unknownOffsetBoxesAreRefused() {
        val file = Mp4Fixtures.file(items, extraStbl = Mp4Fixtures.leaf("saio", ByteArray(12)))
        assertEquals(TagRefusal.MP4_UNKNOWN_OFFSET_BOX, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun aChunkOffsetInsideMoovRefusesTheRewrite() {
        val ftyp = Mp4Fixtures.ftyp()
        val moov = Mp4Fixtures.moov(listOf(ftyp.size + 20, ftyp.size + 30), items)
        val file = ftyp + moov + Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        val plan = codec.plan(ByteArraySource(file), TagEdits(lyrics = "x".repeat(1000)))
        assertEquals(TagRefusal.MP4_OFFSET_INSIDE_REWRITE, (plan as WritePlan.Refused).reason)
    }

    @Test
    fun aSampleEntryWithAHugeSizeIsRefusedWithoutCrashing() {
        val file = Mp4Fixtures.file(items)
        val entry = (0 until file.size - 4).first { file.copyOfRange(it, it + 4).decodeToString() == "mp4a" }
        Mp4Boxes.putBe32(file, entry - 8, 2) // two sample entries...
        Mp4Boxes.putBe32(file, entry - 4, 0x8000_0000L) // ...the first claiming 2 GiB
        assertEquals(TagRefusal.MP4_MALFORMED_ATOMS, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun sampleEntriesThatDoNotTileTheirBoxAreRefused() {
        // A second, unreadable entry could be the "enca" that marks the file protected.
        val file = Mp4Fixtures.file(items)
        val entry = (0 until file.size - 4).first { file.copyOfRange(it, it + 4).decodeToString() == "mp4a" }
        Mp4Boxes.putBe32(file, entry - 8, 2) // two entries claimed, one present
        assertEquals(TagRefusal.MP4_MALFORMED_ATOMS, codec.read(ByteArraySource(file)).refusal)
        Mp4Boxes.putBe32(file, entry - 8, 65) // more entries than are ever looked at
        assertEquals(TagRefusal.MP4_MALFORMED_ATOMS, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun aCompressedMoovIsRefused() {
        // cmov hides every chunk offset inside zlib data: none of them could be moved.
        val cmov = Mp4Fixtures.box("cmov", Mp4Fixtures.leaf("dcom", "zlib".encodeToByteArray()), Mp4Fixtures.leaf("cmvd", ByteArray(12)))
        val file = Mp4Fixtures.ftyp() + Mp4Fixtures.box("moov", cmov) + Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        assertEquals(TagRefusal.MP4_UNKNOWN_OFFSET_BOX, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun aTopLevelMetaIsRefused() {
        val meta = Mp4Fixtures.leaf("meta", ByteArray(4) + Mp4Fixtures.leaf("iloc", ByteArray(16)))
        val file = Mp4Fixtures.file(items, freeAfterIlst = 2000) + meta
        assertEquals(TagRefusal.MP4_UNKNOWN_OFFSET_BOX, codec.read(ByteArraySource(file)).refusal)
    }

    @Test
    fun anUnknownTopLevelAtomRefusesOnlyTheRewriteThatWouldMoveIt() {
        val unknown = Mp4Fixtures.leaf("xyzw", ByteArray(40))
        val file = Mp4Fixtures.file(items, freeAfterIlst = 2000, extraTopLevel = unknown)
        // Nothing moves: allowed.
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Short"))
        // The audio would move, and the unknown atom might address it.
        val plan = codec.plan(ByteArraySource(file), TagEdits(lyrics = "x".repeat(5000)))
        assertEquals(TagRefusal.MP4_UNKNOWN_OFFSET_BOX, (plan as WritePlan.Refused).reason)
    }

    @Test
    fun knownTopLevelAtomsDoNotStopTheRewrite() {
        val known = Mp4Fixtures.leaf("wide", ByteArray(0)) + Mp4Fixtures.leaf("pdin", ByteArray(12)) +
            Mp4Fixtures.leaf("uuid", ByteArray(24)) + Mp4Fixtures.leaf("skip", ByteArray(8))
        val file = Mp4Fixtures.file(items, extraTopLevel = known)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        assertSamplesStillAddressed(CodecAssertions.assertWriteMatchesExpectation(codec, file, edits))
    }

    @Test
    fun aChunkOffsetInsideTheFreeSpaceMoovGrowsIntoRefusesTheEdit() {
        val ftyp = Mp4Fixtures.ftyp()
        val free = Mp4Fixtures.free(20_000)
        val mdat = Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        fun moov(freeStart: Int, mdatStart: Int) = Mp4Fixtures.moov(listOf(freeStart + 100, mdatStart + 8), items)
        val moovSize = moov(0, 0).size
        val freeStart = ftyp.size + moovSize
        val file = ftyp + moov(freeStart, freeStart + free.size) + free + mdat
        val plan = codec.plan(ByteArraySource(file), TagEdits(lyrics = "x".repeat(1000)))
        assertEquals(TagRefusal.MP4_OFFSET_INSIDE_REWRITE, (plan as WritePlan.Refused).reason)
    }

    @Test
    fun moovGrowingOverSeveralFreeAtomsStillVerifies() {
        val ftyp = Mp4Fixtures.ftyp()
        val frees = Mp4Fixtures.free(9_000) + Mp4Fixtures.leaf("skip", ByteArray(9_000))
        val mdat = Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        fun moov(mdatStart: Int) = Mp4Fixtures.moov(listOf(mdatStart + 8, mdatStart + 1008), items)
        val file = ftyp + moov(ftyp.size + moov(0).size + frees.size) + frees + mdat
        val edits = TagEdits(lyrics = "x".repeat(1000))
        assertIs<WritePlan.InPlacePatch>(codec.plan(ByteArraySource(file), edits))
        assertSamplesStillAddressed(CodecAssertions.assertWriteMatchesExpectation(codec, file, edits))
    }

    @Test
    fun theInventoryPinsWhereEveryChunkOffsetPoints() {
        val file = Mp4Fixtures.file(items)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        val out = WritePlans.applyInMemory(file, codec.plan(ByteArraySource(file), edits))!!
        val atom = Mp4Boxes.topLevel(ByteArraySource(out))!!.first { it.type == "moov" }
        val stco = (atom.offset.toInt() until atom.end.toInt()).first { out.copyOfRange(it, it + 4).decodeToString() == "stco" }
        // One entry left unshifted, as a buggy rewrite would leave it.
        val entry = stco + 4 + 8
        Mp4Boxes.putBe32(out, entry, Mp4Boxes.be32(out, entry) - 1)
        val failures = TagVerification.verify(codec, ByteArraySource(file), ByteArraySource(out), edits)
        assertEquals(listOf(TagVerification.Check.INVENTORY), failures.map { it.check })
    }

    @Test
    fun co64OffsetsFollowTheAudioToo() {
        val file = Mp4Fixtures.file(items, co64 = true)
        val edits = TagEdits(lyrics = "x".repeat(1000))
        assertIs<WritePlan.StreamingRewrite>(codec.plan(ByteArraySource(file), edits))
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, edits)
        assertSamplesStillAddressed(out)
        assertTrue(Mp4Fixtures.chunkOffsets(out)[0] > Mp4Fixtures.chunkOffsets(file)[0])
    }

    @Test
    fun aChunkOffsetThatWouldPassFourGibibytesRefusesTheRewrite() {
        val ftyp = Mp4Fixtures.ftyp()
        val mdat = Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        fun moov(mdatStart: Int) = Mp4Fixtures.moov(listOf(mdatStart + 8, 0xFFFF_FF00.toInt()), items)
        val file = ftyp + moov(ftyp.size + moov(0).size) + mdat
        val plan = codec.plan(ByteArraySource(file), TagEdits(lyrics = "x".repeat(1000)))
        assertEquals(TagRefusal.MP4_OFFSET_OVERFLOW, (plan as WritePlan.Refused).reason)
    }

    @Test
    fun userDataInsideATrackIsPinnedLikeEverythingElse() {
        fun file(name: String) = Mp4Fixtures.file(
            items,
            trakExtra = Mp4Fixtures.box("udta", Mp4Fixtures.leaf("name", name.encodeToByteArray())),
        )
        assertTrue(codec.inventory(ByteArraySource(file("Left"))) != codec.inventory(ByteArraySource(file("Rght"))))
    }

    @Test
    fun imagesInASecondCovrItemAreCountedAndPromoted() {
        val jpeg = TestImages.jpeg(4, 4)
        val png = TestImages.png(2, 2)
        val file = Mp4Fixtures.file(
            listOf(Mp4Fixtures.text("©nam", "Song"), Mp4Fixtures.covers(13 to jpeg), Mp4Fixtures.covers(14 to png)),
            freeAfterIlst = 2000,
        )
        val snapshot = codec.read(ByteArraySource(file))
        assertEquals(CoverInfo.of(png, ImageProbe.PNG), snapshot.nextCover)
        assertEquals(1, snapshot.otherPictures)
        assertEquals(listOf(Crc32.of(jpeg), Crc32.of(png)).sorted(), snapshot.pictures)
        val removed = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(cover = CoverEdit.Remove))
        assertEquals(CoverInfo.of(png, ImageProbe.PNG), codec.read(ByteArraySource(removed)).cover)
        CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(cover = CoverEdit.Replace(TestImages.png(6, 6), ImageProbe.PNG)))
    }

    @Test
    fun restatingLyricsStoredWithSurroundingWhitespaceWritesNothing() {
        val file = Mp4Fixtures.file(listOf(Mp4Fixtures.text("©lyr", "\n  la la  \n")), freeAfterIlst = 500)
        assertEquals("la la", codec.read(ByteArraySource(file)).lyrics)
        assertIs<WritePlan.NoChange>(codec.plan(ByteArraySource(file), TagEdits(lyrics = "la la")))
    }

    @Test
    fun aQuickTimeKeysMetaIsNotMistakenForTheTags() {
        val keysMeta = Mp4Fixtures.leaf(
            "meta",
            Mp4Fixtures.leaf("hdlr", ByteArray(8) + "mdta".encodeToByteArray() + ByteArray(13)) +
                Mp4Fixtures.leaf("keys", ByteArray(4) + Mp4Fixtures.be32(1) + Mp4Fixtures.leaf("mdta", "com.apple.quicktime.title".encodeToByteArray())) +
                Mp4Fixtures.box("ilst", Mp4Fixtures.box("\u0000\u0000\u0000\u0001", Mp4Fixtures.data(1, "Keyed".encodeToByteArray()))),
        )
        val ftyp = Mp4Fixtures.ftyp()
        fun moov(mdat: Int) =
            Mp4Fixtures.box("moov", Mp4Fixtures.leaf("mvhd", ByteArray(100)), Mp4Fixtures.trak(listOf(mdat + 8, mdat + 1008)), keysMeta)
        val file = ftyp + moov(ftyp.size + moov(0).size) + Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        assertEquals(null, codec.read(ByteArraySource(file)).title)
        val out = CodecAssertions.assertWriteMatchesExpectation(codec, file, TagEdits(title = "Named"))
        assertSamplesStillAddressed(out)
        val atom = Mp4Boxes.topLevel(ByteArraySource(out))!!.first { it.type == "moov" }
        val tree = Mp4Boxes.parse(out.copyOfRange(atom.offset.toInt(), atom.end.toInt()))!!
        assertContentEquals(keysMeta, tree.child("meta")!!.serialize())
        assertEquals(listOf("©nam"), tree.child("udta")!!.child("meta")!!.child("ilst")!!.children!!.map { it.type })
    }

    @Test
    fun aHugeFreeBoxAfterMoovIsNotReadIntoMemory() {
        val ftyp = Mp4Fixtures.ftyp()
        val freeSize = 3L shl 30
        val mdat = Mp4Fixtures.leaf("mdat", Mp4Fixtures.samples)
        fun moov(mdatStart: Long) =
            Mp4Fixtures.moov(listOf((mdatStart + 8).toInt(), (mdatStart + 1008).toInt()), items)
        val moovSize = moov(0).size
        val head = ftyp + moov(ftyp.size + moovSize + freeSize) + Mp4Fixtures.be32(freeSize.toInt()) + Mp4Fixtures.type("free")
        val source = SparseSource(head, head.size - 8 + freeSize, mdat)
        val plan = codec.plan(source, TagEdits(lyrics = "x".repeat(1000)))
        assertIs<WritePlan.StreamingRewrite>(plan)
        assertTrue(source.largestRead <= 64 shl 20, "read ${source.largestRead} bytes at once")
    }

    /** [head], zeros up to [tailAt], then [tail]; remembers the largest single read. */
    private class SparseSource(val head: ByteArray, val tailAt: Long, val tail: ByteArray) : RandomAccessSource {
        var largestRead = 0
        override val length: Long get() = tailAt + tail.size

        override fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0 || count < 0 || offset + count > length) return null
            largestRead = maxOf(largestRead, count)
            return ByteArray(count) { i ->
                val at = offset + i
                when {
                    at < head.size -> head[at.toInt()]
                    at >= tailAt -> tail[(at - tailAt).toInt()]
                    else -> 0
                }
            }
        }
    }
}

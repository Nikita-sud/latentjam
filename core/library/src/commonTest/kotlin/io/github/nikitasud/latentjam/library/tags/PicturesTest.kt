/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Header-accurate test images: enough for [ImageProbe], filler after. Shared by the codec tests. */
internal object TestImages {
    fun png(width: Int, height: Int, filler: Int = 64): ByteArray {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            be32(13) + "IHDR".encodeToByteArray() + be32(width) + be32(height) +
            byteArrayOf(8, 6, 0, 0, 0) + be32(0)
        return header + ByteArray(filler) { (it % 97).toByte() }
    }

    fun jpeg(width: Int, height: Int, filler: Int = 64): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".encodeToByteArray() +
            byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        val sof0 = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8) + be16(height) + be16(width) +
            byteArrayOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof0 +
            ByteArray(filler) { (it % 89).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}

internal class PicturesTest {

    @Test
    fun probeReadsPngDimensionsAndDepth() {
        assertEquals(ImageInfo(ImageProbe.PNG, 600, 400, 32), ImageProbe.probe(TestImages.png(600, 400)))
    }

    @Test
    fun probeReadsJpegDimensionsPastLeadingSegments() {
        assertEquals(ImageInfo(ImageProbe.JPEG, 1000, 750, 24), ImageProbe.probe(TestImages.jpeg(1000, 750)))
    }

    @Test
    fun probeRejectsEverythingElse() {
        assertNull(ImageProbe.probe(ByteArray(100)))
        assertNull(ImageProbe.probe("GIF89a".encodeToByteArray() + ByteArray(40)))
        assertNull(ImageProbe.probe(TestImages.png(600, 400).copyOf(20)))
    }

    @Test
    fun flacPictureRoundTripsByteExactly() {
        val picture = FlacPicture(3, "image/png", "Обложка", 600, 400, 32, 0, TestImages.png(600, 400))
        val encoded = picture.encode()
        val decoded = assertNotNull(FlacPicture.decode(encoded))
        assertEquals(3, decoded.type)
        assertEquals("image/png", decoded.mime)
        assertEquals("Обложка", decoded.description)
        assertEquals(600, decoded.width)
        assertContentEquals(picture.data, decoded.data)
        assertContentEquals(encoded, decoded.encode())
    }

    @Test
    fun flacPictureDecodeRejectsLengthsPastTheEnd() {
        val encoded = FlacPicture(3, "image/png", "", 1, 1, 32, 0, ByteArray(10)).encode()
        assertNull(FlacPicture.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun frontCoverRequiresTheDeclaredFormat() {
        val png = TestImages.png(10, 20)
        val cover = assertNotNull(FlacPicture.frontCover(png, ImageProbe.PNG))
        assertEquals(FlacPicture.FRONT_COVER, cover.type)
        assertEquals(10, cover.width)
        assertEquals(20, cover.height)
        assertNull(FlacPicture.frontCover(png, ImageProbe.JPEG))
    }

    @Test
    fun coverTargetPrefersTheFrontCoverThenOther() {
        assertEquals(1, CoverTarget.index(listOf(4, 3, 3)))
        assertEquals(1, CoverTarget.index(listOf(4, 0, 5)))
        assertEquals(1, CoverTarget.index(listOf(0, 3)))
        assertNull(CoverTarget.index(listOf(4, 5)))
        assertNull(CoverTarget.index(emptyList()))
    }

    @Test
    fun replacementNeedsARealImageOfTheNamedType() {
        assertNotNull(CoverTarget.replacement(CoverEdit.Replace(TestImages.jpeg(5, 5), ImageProbe.JPEG)))
        assertNull(CoverTarget.replacement(CoverEdit.Replace(TestImages.jpeg(5, 5), ImageProbe.PNG)))
        assertNull(CoverTarget.replacement(CoverEdit.Replace(ByteArray(50), ImageProbe.JPEG)))
    }
}

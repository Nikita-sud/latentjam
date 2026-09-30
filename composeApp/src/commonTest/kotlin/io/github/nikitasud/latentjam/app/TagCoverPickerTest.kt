/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TagCoverPickerTest {

    /** A PNG signature and IHDR chunk stating [width]×[height]. */
    private fun pngHead(width: Int, height: Int): ByteArray {
        val head = ByteArray(PNG_HEAD_BYTES)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(head)
        byteArrayOf(0, 0, 0, 13).copyInto(head, 8)
        "IHDR".encodeToByteArray().copyInto(head, 12)
        for (i in 0 until 4) {
            head[16 + i] = (width ushr (24 - 8 * i)).toByte()
            head[20 + i] = (height ushr (24 - 8 * i)).toByte()
        }
        return head
    }

    @Test
    fun aPngStatesItsSizeInItsHeader() {
        assertEquals(1000 to 640, pngSize(pngHead(1000, 640)))
        assertNull(pngSize(pngHead(1000, 640).copyOf(20)))
        assertNull(pngSize(ByteArray(PNG_HEAD_BYTES)))
    }

    @Test
    fun onlyASmallPngWithinTheEdgeIsKeptAsItIs() {
        assertTrue(keepsPickedPng(1000, 1000, TAG_COVER_PNG_KEEP_BYTES - 1))
        assertFalse(keepsPickedPng(1001, 1000, 1000))
        assertFalse(keepsPickedPng(1000, 1001, 1000))
        assertFalse(keepsPickedPng(800, 800, TAG_COVER_PNG_KEEP_BYTES))
        assertFalse(keepsPickedPng(0, 800, 1000))
    }

    @Test
    fun aPngIsKeptOnlyWhenItsBytesAreTheLengthPromisedAndUnderTheCap() {
        val png = pngHead(800, 800).copyOf(4096)
        assertTrue(keepsPickedPngBytes(png, 4096))
        assertFalse(keepsPickedPngBytes(png, 4095))
        assertFalse(keepsPickedPngBytes(png, 4097))
        // A read capped at the limit from a longer file than the provider promised.
        val capped = pngHead(800, 800).copyOf(TAG_COVER_PNG_KEEP_BYTES.toInt())
        assertFalse(keepsPickedPngBytes(capped, TAG_COVER_PNG_KEEP_BYTES))
        assertFalse(keepsPickedPngBytes(ByteArray(4096), 4096))
        assertFalse(keepsPickedPngBytes(pngHead(1001, 800).copyOf(4096), 4096))
    }

    @Test
    fun tagCoverReferencesCannotEscapeTheirDirectory() {
        val jpeg = "f08f8630-6120-4aa9-9580-973044632c42.jpg"
        val png = "f08f8630-6120-4aa9-9580-973044632c42.png"
        assertTrue(isTagCoverReference(jpeg))
        assertTrue(isTagCoverReference(png))
        assertEquals("image/png", tagCoverMime(png))
        assertEquals("image/jpeg", tagCoverMime(jpeg))
        listOf(null, "", "../$jpeg", "/tmp/$jpeg", jpeg.uppercase(), "f08f8630-6120-4aa9-9580-973044632c42.gif")
            .forEach { assertFalse(isTagCoverReference(it)) }
    }

    @Test
    fun tagCoversDecodeAtMostTwiceTheirEdge() {
        assertEquals(1, coverDecodeSample(1000, 800, TAG_COVER_MAX_EDGE))
        assertEquals(4, coverDecodeSample(4032, 3024, TAG_COVER_MAX_EDGE))
        assertEquals(playlistCoverDecodeSample(4032, 3024), coverDecodeSample(4032, 3024, PLAYLIST_COVER_MAX_EDGE))
    }
}

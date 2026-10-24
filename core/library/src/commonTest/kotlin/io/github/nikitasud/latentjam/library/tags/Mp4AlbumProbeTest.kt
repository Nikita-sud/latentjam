/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class Mp4AlbumProbeTest {

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { all, child -> all + child }
        val size = 8 + body.size
        val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            ByteArray(4) { (type[it].code and 0xFF).toByte() }
        return header + body
    }

    private fun data(text: String, type: Int = 1) =
        box("data", byteArrayOf(0, 0, 0, type.toByte(), 0, 0, 0, 0), text.encodeToByteArray())

    private val ftyp = box("ftyp", "M4A ".encodeToByteArray(), ByteArray(4))
    private val hdlr = box("hdlr", ByteArray(8), "mdirappl".encodeToByteArray(), ByteArray(9))

    private fun file(vararg items: ByteArray, isoMeta: Boolean = true, before: ByteArray = ByteArray(0)): ByteArray {
        val metaBody = (if (isoMeta) ByteArray(4) else ByteArray(0)) + hdlr + box("ilst", *items)
        return ftyp + before + box("moov", box("mvhd", ByteArray(100)), box("udta", box("meta", metaBody)))
    }

    /** Counts the bytes a probe pulls, so a skipped cover is seen to stay unread. */
    private class Counting(bytes: ByteArray) : RandomAccessSource {
        private val inner = ByteArraySource(bytes)
        var read = 0L
        override val length: Long get() = inner.length
        override fun read(offset: Long, count: Int): ByteArray? = inner.read(offset, count)?.also { read += count }
    }

    @Test
    fun anAlbumTheFileNamesIsRead() {
        val facts = assertNotNull(Mp4AlbumProbe.read(ByteArraySource(file(box("©nam", data("Song")), box("©alb", data(" Abbey Road "))))))
        assertEquals("Abbey Road", facts.album)
    }

    @Test
    fun aWellFormedFileWithoutAnAlbumNamesNone() {
        val facts = assertNotNull(Mp4AlbumProbe.read(ByteArraySource(file(box("©nam", data("Song"))))))
        assertNull(facts.album)
        assertNull(assertNotNull(Mp4AlbumProbe.read(ByteArraySource(file(box("©alb", data("  ")))))).album)
    }

    @Test
    fun aCoverIsSkippedUnread() {
        val source = Counting(file(box("covr", box("data", ByteArray(1 shl 20))), box("©alb", data("Help!"))))
        assertEquals("Help!", Mp4AlbumProbe.read(source)?.album)
        assertTrue(source.read < 1024, "read ${source.read} bytes")
    }

    @Test
    fun quickTimeMetaAndAMoovAfterTheMediaAreFollowed() {
        assertEquals("Let It Be", Mp4AlbumProbe.read(ByteArraySource(file(box("©alb", data("Let It Be")), isoMeta = false)))?.album)
        val afterMedia = file(box("©alb", data("Revolver")), before = box("mdat", ByteArray(4096)))
        assertEquals("Revolver", Mp4AlbumProbe.read(ByteArraySource(afterMedia))?.album)
    }

    @Test
    fun utf16AlbumsAreRead() {
        val utf16 = box("data", byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0), byteArrayOf(0, 'O'.code.toByte(), 0, 'K'.code.toByte()))
        assertEquals("OK", Mp4AlbumProbe.read(ByteArraySource(file(box("©alb", utf16))))?.album)
    }

    @Test
    fun whatCannotBeFollowedIsUnknownNotAlbumless() {
        val whole = file(box("©alb", data("Abbey Road")))
        // Truncated inside moov: its declared size runs past the file.
        assertNull(Mp4AlbumProbe.read(ByteArraySource(whole.copyOf(whole.size - 10))))
        // Not MP4 at all.
        assertNull(Mp4AlbumProbe.read(ByteArraySource("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".encodeToByteArray())))
        // No moov: nothing to say about the file.
        assertNull(Mp4AlbumProbe.read(ByteArraySource(ftyp + box("mdat", ByteArray(16)))))
    }

    private fun largeBox(type: String, body: ByteArray): ByteArray {
        val size = 16L + body.size
        return byteArrayOf(0, 0, 0, 1) + ByteArray(4) { (type[it].code and 0xFF).toByte() } +
            ByteArray(8) { (size ushr (56 - 8 * it)).toByte() } + body
    }

    @Test
    fun largeSizesZeroSizesAndTheUdtaTerminatorAreFollowed() {
        val ilst = box("ilst", box("\u00A9alb", data("Abbey Road")))
        val meta = box("meta", ByteArray(4), hdlr, ilst)
        // A 64-bit size, a terminator after meta, and a last box sized 0 ("to its parent's end").
        val udta = box("udta", meta, ByteArray(4))
        val moov = box("moov", largeBox("mvhd", ByteArray(100)), udta, byteArrayOf(0, 0, 0, 0) + "free".encodeToByteArray())
        assertEquals("Abbey Road", Mp4AlbumProbe.read(ByteArraySource(ftyp + moov))?.album)
    }

    @Test
    fun theFirstNonBlankDataAndEveryMetaAreConsulted() {
        val twoData = file(box("\u00A9alb", data("First"), data("Second")))
        assertEquals("First", Mp4AlbumProbe.read(ByteArraySource(twoData))?.album)
        // udta/meta's ilst names no album, moov/meta's does: the album is found.
        val udtaMeta = box("udta", box("meta", ByteArray(4), hdlr, box("ilst", box("\u00A9nam", data("Song")))))
        val moovMeta = box("meta", ByteArray(4), hdlr, box("ilst", box("\u00A9alb", data("Help!"))))
        assertEquals("Help!", Mp4AlbumProbe.read(ByteArraySource(ftyp + box("moov", udtaMeta, moovMeta)))?.album)
    }

    @Test
    fun a3gppAlbumBoxNamesTheAlbum() {
        val albm = box("albm", ByteArray(4), byteArrayOf(0x15, 0xC7.toByte()), "Revolver".encodeToByteArray(), byteArrayOf(0))
        assertEquals("Revolver", Mp4AlbumProbe.read(ByteArraySource(ftyp + box("moov", box("udta", albm))))?.album)
    }

    @Test
    fun theFactsReaderAnswersForMp4() {
        assertEquals("Abbey Road", TagFacts.embedded(ByteArraySource(file(box("©alb", data("Abbey Road")))))?.album)
    }
}

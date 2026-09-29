/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SidecarLyricsTest {

    private val lrc = "[ar:Artist]\n[00:01.00]Ce seară\n[00:05.50]Пой со мной"

    private fun utf16(text: String, bigEndian: Boolean): ByteArray {
        val bom = if (bigEndian) byteArrayOf(0xFE.toByte(), 0xFF.toByte()) else byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val body = ByteArray(text.length * 2)
        text.forEachIndexed { index, ch ->
            val high = (ch.code shr 8).toByte()
            val low = (ch.code and 0xFF).toByte()
            body[index * 2] = if (bigEndian) high else low
            body[index * 2 + 1] = if (bigEndian) low else high
        }
        return bom + body
    }

    private fun assertTheSong(lyrics: Lyrics?) {
        assertNotNull(lyrics)
        assertEquals(listOf(1_000L, 5_500L), lyrics.lines.map { it.timeMs })
        assertEquals("Ce seară\nПой со мной", lyrics.text)
    }

    @Test
    fun plainUtf8IsRead() {
        assertTheSong(SidecarLyrics.decode(lrc.encodeToByteArray()))
    }

    @Test
    fun utf8ByteOrderMarkIsDropped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + lrc.encodeToByteArray()
        val lyrics = SidecarLyrics.decode(bytes)
        assertTheSong(lyrics)
        // A BOM glued to the first stamp would have turned it into text.
        assertEquals(1_000L, lyrics?.lines?.first()?.timeMs)
    }

    @Test
    fun utf16LittleEndianIsRead() {
        assertTheSong(SidecarLyrics.decode(utf16(lrc, bigEndian = false)))
    }

    @Test
    fun utf16BigEndianIsRead() {
        assertTheSong(SidecarLyrics.decode(utf16(lrc, bigEndian = true)))
    }

    @Test
    fun windowsAndClassicMacLineEndingsBecomeLines() {
        assertTheSong(SidecarLyrics.decode(lrc.replace("\n", "\r\n").encodeToByteArray()))
        assertTheSong(SidecarLyrics.decode(lrc.replace("\n", "\r").encodeToByteArray()))
    }

    @Test
    fun legacyCodePageIsDeclinedRatherThanGuessed() {
        // "Café" saved as Windows-1252: a lone 0xE9 is not UTF-8.
        val bytes = "[00:01.00]Caf".encodeToByteArray() + byteArrayOf(0xE9.toByte())
        assertNull(SidecarLyrics.decode(bytes))
    }

    @Test
    fun emptyOrTagOnlyFilesHaveNoLyrics() {
        assertNull(SidecarLyrics.decode(ByteArray(0)))
        assertNull(SidecarLyrics.decode("[ar:Artist]\r\n[ti:Title]\r\n".encodeToByteArray()))
    }

    @Test
    fun filesOverTheSizeCapAreDeclined() {
        val line = "[00:01.00]la\n".encodeToByteArray()
        val atCap = ByteArray(SidecarLyrics.MAX_BYTES) { ' '.code.toByte() }
        line.copyInto(atCap)
        assertNotNull(SidecarLyrics.decode(atCap))
        val overCap = atCap + ' '.code.toByte()
        assertNull(SidecarLyrics.decode(overCap))
    }

    @Test
    fun candidateNamesSwapTheExtensionForLrcInBothCases() {
        assertEquals(listOf("Song.lrc", "Song.LRC"), SidecarLyrics.candidateNames("Song.mp3"))
        assertEquals(listOf("A.B side.lrc", "A.B side.LRC"), SidecarLyrics.candidateNames("A.B side.flac"))
        assertEquals(listOf("Song.lrc", "Song.LRC"), SidecarLyrics.candidateNames("Song"))
        assertEquals(emptyList(), SidecarLyrics.candidateNames(""))
        assertEquals(emptyList(), SidecarLyrics.candidateNames("Song.lrc"))
    }

    private val plainEmbedded = Lyrics(listOf(LyricLine(null, "embedded plain")))
    private val syncedEmbedded = Lyrics(listOf(LyricLine(1_000L, "embedded synced")))
    private val plainSidecar = Lyrics(listOf(LyricLine(null, "sidecar plain")))
    private val syncedSidecar = Lyrics(listOf(LyricLine(1_000L, "sidecar synced")))

    @Test
    fun chooseReturnsNullOnlyWhenBothAreMissing() {
        assertNull(SidecarLyrics.choose(null, null))
        assertSame(plainEmbedded, SidecarLyrics.choose(plainEmbedded, null))
        assertSame(syncedEmbedded, SidecarLyrics.choose(syncedEmbedded, null))
        assertSame(plainSidecar, SidecarLyrics.choose(null, plainSidecar))
        assertSame(syncedSidecar, SidecarLyrics.choose(null, syncedSidecar))
    }

    @Test
    fun chooseTakesSyncedOverPlain() {
        assertSame(syncedEmbedded, SidecarLyrics.choose(syncedEmbedded, plainSidecar))
        assertSame(syncedSidecar, SidecarLyrics.choose(plainEmbedded, syncedSidecar))
    }

    @Test
    fun chooseTakesTheSidecarOnATie() {
        assertSame(plainSidecar, SidecarLyrics.choose(plainEmbedded, plainSidecar))
        assertSame(syncedSidecar, SidecarLyrics.choose(syncedEmbedded, syncedSidecar))
    }
}

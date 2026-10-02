/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.LyricLine
import io.github.nikitasud.latentjam.library.tags.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsReadingTest {
    private val songA = listOf("a.mp3")
    private val songB = listOf("b.mp3")

    @Test
    fun aSongThatComesBackWhileThePlayerIsHiddenKeepsItsLyrics() {
        val lyricsA = Lyrics(lines = listOf(LyricLine(timeMs = null, text = "la la")))
        // Shown on A: read once.
        var reading: LyricsReading? = null
        assertTrue(reading.needsReading(songA, "rev1", active = true))
        reading = LyricsReading(songA, "rev1", lyricsA)
        assertEquals(lyricsA, reading.lyricsFor(songA))
        // Hidden while B plays: B is not read and shows nothing, A's read is untouched.
        assertFalse(reading.needsReading(songB, "rev1", active = false))
        assertNull(reading.lyricsFor(songB))
        assertFalse(reading.readFor(songB))
        // Back to A and shown again: the button is there at once, and nothing is read again.
        assertEquals(lyricsA, reading.lyricsFor(songA))
        assertTrue(reading.readFor(songA))
        assertFalse(reading.needsReading(songA, "rev1", active = true))
    }

    @Test
    fun onlyACompletedReadForThisSongAndRevisionCountsAsDone() {
        // Nothing stored yet (a read that was cut off stores nothing): read when shown.
        assertTrue((null as LyricsReading?).needsReading(songA, "rev1", active = true))
        assertFalse((null as LyricsReading?).needsReading(songA, "rev1", active = false))
        val noLyrics = LyricsReading(songA, "rev1", null)
        assertTrue(noLyrics.readFor(songA))
        assertNull(noLyrics.lyricsFor(songA))
        assertFalse(noLyrics.needsReading(songA, "rev1", active = true))
        // A newly granted lyrics folder re-reads the same song; the old answer shows meanwhile.
        assertTrue(noLyrics.needsReading(songA, "rev2", active = true))
        // A different song is read when shown.
        assertTrue(noLyrics.needsReading(songB, "rev1", active = true))
        // No song, nothing to read.
        assertFalse(noLyrics.needsReading(null, "rev1", active = true))
    }
}

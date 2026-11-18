/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonalTrackAlbumTest {

    @Test
    fun thePersonalEvidenceKeysASongByTheAlbumTheScannerReported() {
        // A song whose file names no album: the scan files it under its folder's name, the library shows it
        // without an album and keeps the scanner's beside it. SMART's personal evidence reads the scanner's.
        val scanned = TrackDescriptor(
            TrackId("7"), title = "Chapter 3", artist = "A Reader", album = "Audiobooks", albumArtist = "A Reader",
            folderPath = "Audiobooks", trackNumber = 3, discNumber = 1, durationMs = 1_800_000,
        )
        val shown = scanned.copy(album = null, scannerAlbum = scanned.album)
        assertNull(shown.album)
        val fromScan = personalTrack(scanned, listOf("a reader"))
        val fromShown = personalTrack(shown, listOf("a reader"))
        assertEquals("audiobooks|a reader", fromShown.album)
        assertEquals(
            listOf(fromScan.id, fromScan.key, fromScan.artists, fromScan.album, fromScan.trackNumber, fromScan.discNumber, fromScan.durationMs),
            listOf(fromShown.id, fromShown.key, fromShown.artists, fromShown.album, fromShown.trackNumber, fromShown.discNumber, fromShown.durationMs),
        )
        // A song nobody corrected, and one with no album at all, are keyed as they always were.
        assertEquals("audiobooks|a reader", personalTrack(scanned, emptyList()).album)
        assertNull(personalTrack(scanned.copy(album = null), emptyList()).album)
    }
}

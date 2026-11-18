/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryWithoutScreensTest {

    @Test
    fun aResumeWithNoScreenGetsTheLibraryTheScreensShow() = runTest {
        // A song as the scan reports one whose file names no album: filed under its folder, with that folder's cover.
        val scanned = TrackDescriptor(
            id = TrackId("42"),
            title = "Loose",
            album = "Music",
            folderPath = "Music",
            audioUri = "content://media/external/audio/media/42",
            artworkUri = MediaStoreArtwork.versioned(MediaStoreArtwork.trackCover(42, 7), "folder"),
            albumArtworkUri = MediaStoreArtwork.versioned(MediaStoreArtwork.albumCover(7), "folder"),
            sourceRevision = "android-mediastore-v1:5123:1699:12",
        )
        val unplayable = TrackDescriptor(id = TrackId("43"), title = "No file", audioUri = " ")
        val settings = GenreEnrichmentTest.MemorySettings()
        // The screens read the file once; what they learned is what a later resume has to apply.
        GenreEnrichment(settings) { EmbeddedTagFacts() }.backfill(listOf(scanned))

        val library = libraryWithoutScreens(
            scan = { listOf(scanned, unplayable) },
            enrich = { GenreEnrichment(settings) { error("a resume reads no file") }.apply(it) },
        )

        val resumed = library.single()
        assertEquals(TrackId("42"), resumed.id)
        assertNull(resumed.album)
        // Its own cover or none: no album to fall back to, for the notification, the lock screen and the car.
        assertNull(MediaStoreArtwork.albumFallback(resumed.artworkUri!!))
        assertEquals("content://media/external/audio/media/42", MediaStoreArtwork.audioFallback(resumed.artworkUri!!))
    }

    @Test
    fun aControllerThatCannotConnectLeavesTheProcessAliveAndSuppliesNothing() = runTest {
        var supplied = 0
        // The scope this runs in has no handler: anything thrown from it would end the process.
        connectWithoutScreens(connect = { throw IllegalStateException("the service went away") }, supply = { supplied++ })
        connectWithoutScreens(connect = { throw OutOfMemoryError("no room for the controller") }, supply = { supplied++ })
        assertEquals(0, supplied)
        connectWithoutScreens(connect = {}, supply = { supplied++ })
        assertEquals(1, supplied)
    }

    @Test
    fun aCancelledConnectionStaysACancellation() = runTest {
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            connectWithoutScreens(connect = { throw kotlinx.coroutines.CancellationException("left") }, supply = {})
        }
    }
}

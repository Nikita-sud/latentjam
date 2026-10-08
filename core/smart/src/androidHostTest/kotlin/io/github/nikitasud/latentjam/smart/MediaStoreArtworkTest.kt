/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class MediaStoreArtworkTest {

    @Test
    fun aSongsCoverIsItsOwnAndNamesItsAlbum() {
        assertEquals("content://media/external/audio/media/42/albumart?album=7", MediaStoreArtwork.trackCover(42, 7))
        assertEquals("content://media/external/audio/albumart/7", MediaStoreArtwork.albumCover(7))
    }

    @Test
    fun aSongsCoverFallsBackToItsAlbumsAtTheSameVersion() {
        val song = MediaStoreArtwork.versioned(MediaStoreArtwork.trackCover(42, 7), "1f")
        assertEquals("content://media/external/audio/media/42/albumart?album=7&v=1f", song)
        assertEquals("content://media/external/audio/albumart/7?v=1f", MediaStoreArtwork.albumFallback(song))
        assertEquals(
            "content://media/external/audio/albumart/7",
            MediaStoreArtwork.albumFallback(MediaStoreArtwork.trackCover(42, 7)),
        )
    }

    @Test
    fun theFallbackDoesNotDependOnParameterOrder() {
        assertEquals(
            "content://media/external/audio/albumart/7?v=1f",
            MediaStoreArtwork.albumFallback("content://media/external/audio/media/42/albumart?v=1f&album=7"),
        )
    }

    @Test
    fun anyOtherLocatorHasNoFallback() {
        listOf(
            "content://media/external/audio/albumart/7?v=1f",
            "content://media/external/audio/media/42/albumart",
            "content://media/external/audio/media/42/albumart?v=1f",
            "content://media/external/audio/media/42?album=7",
            "content://media/external/audio/media/x/albumart?album=7",
            "content://media/external/audio/media//albumart?album=7",
            "content://media/external/audio/media/42/albumart?album=",
            "content://media/external/audio/media/42/albumart?album=7x",
            "content://media/external/audio/media/42/albumart?album=٧",
            "content://other/external/audio/media/42/albumart?album=7",
            "file:///data/user/0/app/files/track-covers/0a1b2c3d.jpg",
            "",
        ).forEach { uri -> assertNull(MediaStoreArtwork.albumFallback(uri), uri) }
    }

    @Test
    fun aVersionJoinsAnyQueryTheLocatorAlreadyHas() {
        assertEquals("content://media/external/audio/albumart/7?v=2", MediaStoreArtwork.versioned(MediaStoreArtwork.albumCover(7), "2"))
        assertEquals(
            "content://media/external/audio/media/42/albumart?album=7&v=2",
            MediaStoreArtwork.versioned(MediaStoreArtwork.trackCover(42, 7), "2"),
        )
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaStorePathTest {

    @Test
    fun `legacy primary and removable paths return folders without the filename`() {
        assertEquals(
            "Music/Album",
            mediaStoreFolderPath("/storage/emulated/0/Music/Album/song.flac", relativePath = false),
        )
        assertEquals(
            "Music/Album",
            mediaStoreFolderPath("/storage/1234-ABCD/Music/Album/song.flac", relativePath = false),
        )
        assertEquals(
            "mnt/media_rw/1234-ABCD/Music",
            mediaStoreFolderPath("/mnt/media_rw/1234-ABCD/Music/song.flac", relativePath = false),
        )
    }

    @Test
    fun `modern relative path is normalized but not treated as a filename`() {
        assertEquals("Music/Album", mediaStoreFolderPath("Music/Album/", relativePath = true))
    }

    @Test
    fun `media revision changes for size mtime or provider generation`() {
        val original = androidMediaSourceRevision(10, 20, 30)
        assertEquals(original, androidMediaSourceRevision(10, 20, 30))
        kotlin.test.assertNotEquals(original, androidMediaSourceRevision(11, 20, 30))
        kotlin.test.assertNotEquals(original, androidMediaSourceRevision(10, 21, 30))
        kotlin.test.assertNotEquals(original, androidMediaSourceRevision(10, 20, 31))
    }

    @Test
    fun `null MediaStore cursor is an incomplete empty scan`() {
        val scan = mediaStoreLibraryScan { null }

        assertEquals(emptyList(), scan.tracks)
        assertFalse(scan.complete)
    }

    @Test
    fun `MediaStore security failure is an incomplete empty scan`() {
        val scan = mediaStoreLibraryScan { throw SecurityException("permission changed") }

        assertEquals(emptyList(), scan.tracks)
        assertFalse(scan.complete)
    }

    @Test
    fun `successful empty MediaStore cursor is a complete scan`() {
        val scan = mediaStoreLibraryScan { emptyList() }

        assertEquals(emptyList(), scan.tracks)
        assertTrue(scan.complete)
    }
}

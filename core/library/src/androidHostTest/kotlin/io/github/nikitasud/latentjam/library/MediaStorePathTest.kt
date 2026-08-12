/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals

internal class LibraryCatalogAlbumOrderTest {

    private fun track(id: String, title: String, album: String?) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = "Artist",
        album = album,
    )

    @Test
    fun realAlbumsComeBeforeSingleTrackAlbums() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "One", album = "Aardvark Single"),
                track("2", "Two", album = "Zebra Album"),
                track("3", "Three", album = "Zebra Album"),
                track("4", "Four", album = "Beta Album"),
                track("5", "Five", album = "Beta Album"),
                track("6", "Six", album = "Middle Single"),
            ),
        )
        // Multi-track albums first, alphabetical inside each block.
        assertContentEquals(
            listOf("Beta Album", "Zebra Album", "Aardvark Single", "Middle Single"),
            catalog.albums.map { it.title },
        )
    }
}

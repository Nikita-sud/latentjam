/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class LibraryCatalogTest {

    private fun track(
        id: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        artworkUri: String? = null,
        folderPath: String? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        artworkUri = artworkUri,
        folderPath = folderPath,
    )

    @Test
    fun groupsAlbumsByArtworkUriKeepingSameNamedAlbumsApart() {
        // Two "Greatest Hits" by different artists, distinct artwork ids.
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "b", artist = "Queen", album = "Greatest Hits", artworkUri = "art://1"),
                track("2", title = "a", artist = "Queen", album = "Greatest Hits", artworkUri = "art://1"),
                track("3", title = "c", artist = "ABBA", album = "Greatest Hits", artworkUri = "art://2"),
            ),
        )
        assertEquals(2, catalog.albums.size)
        val queen = catalog.albums.first { it.artist == "Queen" }
        assertContentEquals(listOf("a", "b"), queen.tracks.map { it.title }, "album tracks title-sorted")
    }

    @Test
    fun artworklessTracksFallBackToAlbumArtistKey() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", album = "Demo", artist = "X"),
                track("2", album = "Demo", artist = "X"),
                track("3", album = "Demo", artist = "Y"),
            ),
        )
        assertEquals(2, catalog.albums.size)
    }

    @Test
    fun artistsCarryTrackAndAlbumCounts() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", artist = "Queen", album = "A", artworkUri = "art://1"),
                track("2", artist = "Queen", album = "B", artworkUri = "art://2"),
                track("3", artist = "abba", album = "C", artworkUri = "art://3"),
            ),
        )
        assertEquals(listOf("abba", "Queen"), catalog.artists.map { it.name }, "case-insensitive sort")
        assertEquals(2, catalog.artists.first { it.name == "Queen" }.albumCount)
    }

    @Test
    fun genresBucketNullsLast() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", genre = "Rock"),
                track("2", genre = "Ambient"),
                track("3", genre = null),
            ),
        )
        assertEquals(listOf("Ambient", "Rock", null), catalog.genres.map { it.name })
        assertNull(catalog.genres.last().name)
        assertEquals(1, catalog.genres.last().tracks.size)
    }

    @Test
    fun foldersUseTheFullPathAsIdentityAndTheLastSegmentAsLabel() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "B", folderPath = "Music/Telegram"),
                track("2", title = "A", folderPath = "Downloads/Telegram"),
                track("3", title = "C", folderPath = null),
            ),
        )

        assertEquals(listOf("Music", "Telegram", "Telegram"), catalog.folders.map { it.name })
        assertEquals(
            listOf("Music", "Downloads/Telegram", "Music/Telegram"),
            catalog.folders.map { it.path },
        )
        assertContentEquals(
            listOf("A"),
            catalog.folders.first { it.path == "Downloads/Telegram" }.tracks.map { it.title },
        )
    }
}

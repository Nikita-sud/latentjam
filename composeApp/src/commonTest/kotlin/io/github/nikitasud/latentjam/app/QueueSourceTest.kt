/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.FolderGroup
import io.github.nikitasud.latentjam.library.GenreGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class QueueSourceTest {
    private val firstAlbum = AlbumGroup("first", "Shared", "One", null, emptyList())
    private val secondAlbum = AlbumGroup("second", "Shared", "Two", null, emptyList())
    private val artist = ArtistGroup("Shared", emptyList(), 1)
    private val genre = GenreGroup("Shared", emptyList())
    private val firstFolder = FolderGroup("/first/Shared", "Shared", emptyList())
    private val secondFolder = FolderGroup("/second/Shared", "Shared", emptyList())
    private val catalog = LibraryCatalog(
        emptyList(), listOf(firstAlbum, secondAlbum), listOf(artist), listOf(genre),
        listOf(firstFolder, secondFolder),
    )

    private fun source(route: String, title: String = "Shared") = CollectionSelection(
        title, null, null, emptyList(), routeId = route,
    ).queueSource()

    @Test
    fun sameNamedAlbumsAndFoldersOpenTheOriginalIdentity() {
        assertEquals(QueueSourceGroup.Album(secondAlbum), source("album:second").resolveGroup(catalog))
        assertEquals(
            QueueSourceGroup.Folder(secondFolder),
            source("folder:/second/Shared").resolveGroup(catalog),
        )
    }

    @Test
    fun matchingNamesAcrossDifferentKindsDoNotRedirectToAnAlbum() {
        assertEquals(QueueSourceGroup.Artist(artist), source("artist:Shared").resolveGroup(catalog))
        assertEquals(QueueSourceGroup.Genre(genre), source("genre:Shared").resolveGroup(catalog))
    }

    @Test
    fun sourceStillResolvesWhenItsDisplayLabelChanges() {
        assertEquals(
            QueueSourceGroup.Album(secondAlbum),
            source("album:second", "Translated label").resolveGroup(catalog),
        )
    }

    @Test
    fun unknownMetadataGroupsUseTheirStableRouteInsteadOfLocalizedTitles() {
        val unknown = ArtistGroup(null, emptyList(), 1)
        assertEquals(
            QueueSourceGroup.Artist(unknown),
            source("artist:", "Unknown artist").resolveGroup(catalog.copy(artists = listOf(unknown))),
        )
    }

    @Test
    fun deletedAndLegacySourcesFallBackToTheQueue() {
        assertNull(source("album:deleted").resolveGroup(catalog))
        assertNull(QueueSource(QueueSourceKind.COLLECTION, "Shared").resolveGroup(catalog))
        assertNull(QueueSource(QueueSourceKind.COLLECTION, "Shared", "deleted-playlist").resolveGroup(catalog))
        assertNull(source("album:second").resolveGroup(null))
        assertNull(QueueSource(QueueSourceKind.LIBRARY_GROUP, "Shared").resolveGroup(catalog))
    }

    @Test
    fun autoPlaylistsNeverResolveThroughTheirDisplayName() {
        assertNull(source("auto:NEVER_PLAYED").resolveGroup(catalog))
    }

    @Test
    fun playlistReferencesRetainTheirExistingPersistenceFormat() {
        val selection = CollectionSelection("Shared", null, null, emptyList(), playlistId = "saved-id")
        assertEquals(QueueSource(QueueSourceKind.COLLECTION, "Shared", "saved-id"), selection.queueSource())
    }
}

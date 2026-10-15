/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultFavorites
import io.github.nikitasud.latentjam.history.FavoritesStore
import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.AutoPlaylists
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BulkFavoritesTest {

    private class FavoritesMemory(var ids: List<String> = emptyList()) : FavoritesStore {
        var writes = 0
        override suspend fun read() = ids
        override suspend fun write(ids: List<String>) {
            this.ids = ids
            writes++
        }
    }

    private fun track(id: String, title: String = "Song $id", artist: String = "Artist $id") =
        TrackDescriptor(id = TrackId(id), title = title, artist = artist, artworkUri = "art:$id")

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    @Test
    fun aSelectionWithAnyTrackNotHeartedAddsAndAFullyHeartedOneRemoves() {
        val hearted = listOf(a.id, b.id)
        assertEquals(SelectionFavoriteAction.ADD, selectionFavoriteAction(listOf(c), hearted))
        assertEquals(SelectionFavoriteAction.ADD, selectionFavoriteAction(listOf(a, c, b), hearted))
        assertEquals(SelectionFavoriteAction.REMOVE, selectionFavoriteAction(listOf(b, a), hearted))
        assertEquals(SelectionFavoriteAction.REMOVE, selectionFavoriteAction(listOf(a), hearted))
        // Nothing selected (the bar's exit frames) never offers a removal.
        assertEquals(SelectionFavoriteAction.ADD, selectionFavoriteAction(emptyList(), hearted))
        assertEquals(SelectionFavoriteAction.ADD, selectionFavoriteAction(listOf(a), emptyList()))
    }

    @Test
    fun anyNotFavoriteSaysWhetherAddingWouldChangeAnything() {
        assertTrue(anyNotFavorite(listOf(a, c), listOf(a.id)))
        assertFalse(anyNotFavorite(listOf(a), listOf(b.id, a.id)))
        assertFalse(anyNotFavorite(emptyList(), emptyList()))
    }

    @Test
    fun theSelectionsAddHeartsTheRestInDisplayOrderAndUndoRestoresTheList() = runTest {
        val store = FavoritesMemory(listOf("b"))
        val favorites = DefaultFavorites(store)
        val shown = listOf(c, b, a)

        val change = favorites.applyBulk(selectionFavoriteAction(shown, favorites.all()), shown.map { it.id })

        assertEquals(listOf(c.id, a.id, b.id), favorites.all())
        assertEquals(2, change.addedCount)
        assertEquals(1, store.writes)

        assertTrue(favorites.undoBulk(change))
        assertEquals(listOf(b.id), favorites.all())
    }

    @Test
    fun aFullyHeartedSelectionIsRemovedAndUndoPutsEachBackInItsPlace() = runTest {
        val favorites = DefaultFavorites(FavoritesMemory(listOf("a", "x", "b")))
        val selected = listOf(b, a)
        val action = selectionFavoriteAction(selected, favorites.all())
        assertEquals(SelectionFavoriteAction.REMOVE, action)

        val change = favorites.applyBulk(action, selected.map { it.id })

        assertEquals(listOf(TrackId("x")), favorites.all())
        assertEquals(2, change.removedCount)
        assertTrue(favorites.undoBulk(change))
        assertEquals(listOf(a.id, TrackId("x"), b.id), favorites.all())
    }

    @Test
    fun undoIsRefusedOnceANewerHeartChangedTheList() = runTest {
        val favorites = DefaultFavorites(FavoritesMemory())
        val change = favorites.applyBulk(SelectionFavoriteAction.ADD, listOf(a.id, b.id))
        favorites.toggle(c.id)

        assertFalse(favorites.undoBulk(change))
        assertEquals(listOf(c.id, a.id, b.id), favorites.all())
    }

    @Test
    fun anEditThatChangedNothingHasNothingToUndo() = runTest {
        val store = FavoritesMemory(listOf("a"))
        val favorites = DefaultFavorites(store)
        val change = favorites.applyBulk(SelectionFavoriteAction.ADD, listOf(a.id))

        assertFalse(change.changed)
        assertFalse(favorites.undoBulk(change))
        assertEquals(0, store.writes)
    }

    @Test
    fun anImportedFileHeadsFavoritesInItsOwnOrderAndUndoRemovesIt() = runTest {
        val library = listOf(
            track("1", title = "Freestyler", artist = "Bomfunk MC's"),
            track("2", title = "Sandstorm", artist = "Darude"),
            track("3", title = "Blue", artist = "Eiffel 65"),
            track("4", title = "Kept", artist = "Already"),
        )
        val text = """
            #EXTM3U
            #PLAYLIST:From Musicolet
            #EXTINF:225,Darude - Sandstorm
            /storage/emulated/0/Music/Sandstorm.mp3
            #EXTINF:306,Bomfunk MC's - Freestyler
            /storage/emulated/0/Music/Freestyler.mp3
            #EXTINF:180,Nobody - Not On This Phone
            /storage/emulated/0/Music/missing.mp3
            #EXTINF:200,Already - Kept
            /storage/emulated/0/Music/Kept.mp3
            #EXTINF:225,Darude - Sandstorm
            /storage/emulated/0/Music/Sandstorm.mp3
        """.trimIndent()
        // The same reading App's import does before it asks where the file goes.
        val entries = parseM3u(text)
        val rows = matchM3uEntries(entries, library)
        val offer = M3uImportOffer(
            M3uImport(
                name = parseM3uName(text),
                entryCount = entries.size,
                matchedEntryCount = rows.count { it != null },
                tracks = rows.filterNotNull().distinctBy { it.id },
            ),
        )
        assertEquals("From Musicolet", offer.name)
        assertEquals(5, offer.entryCount)
        assertEquals(listOf(TrackId("2"), TrackId("1"), TrackId("4")), offer.favoriteIds)

        val store = FavoritesMemory(listOf("4", "3"))
        val favorites = DefaultFavorites(store)
        val change = favorites.applyBulk(SelectionFavoriteAction.ADD, offer.favoriteIds)

        // New hearts lead in the file's order; the one already hearted keeps its place.
        assertEquals(listOf("2", "1", "4", "3"), store.ids)
        assertEquals(2, change.addedCount)
        assertEquals(1, store.writes)
        // The Favorites auto playlist shows them exactly as a single heart's would be shown.
        val shown = AutoPlaylists.build(library, emptyMap(), emptyMap(), favorites.all())
            .single { it.kind == AutoPlaylistKind.FAVORITES }.tracks.map { it.id.value }
        assertEquals(listOf("2", "1", "4", "3"), shown)

        assertTrue(favorites.undoBulk(change))
        assertEquals(listOf("4", "3"), store.ids)
    }

    @Test
    fun anOpenFavoritesPageFollowsTheHearts() {
        val page = CollectionSelection(
            title = "Favorites",
            subtitle = "2 tracks",
            artworkUri = "art:a",
            tracks = listOf(a, b),
            allowsTrackSelection = true,
            routeId = FAVORITES_ROUTE_ID,
        )

        val followed = page.followingFavorites(listOf(c, a, b))

        assertEquals(listOf(c, a, b), followed?.tracks)
        assertEquals("art:c", followed?.artworkUri)
        assertEquals(FAVORITES_ROUTE_ID, followed?.routeId)
        assertEquals(emptyList(), page.followingFavorites(emptyList())?.tracks)
        assertNull(page.followingFavorites(listOf(a, b)), "unchanged hearts leave the page alone")
    }

    @Test
    fun aSortedFavoritesPageIsLeftAloneWhileItsHeartsAreUnchanged() {
        val byTitle: (CollectionSelection) -> CollectionSelection = { page ->
            page.copy(tracks = page.tracks.sortedBy { it.title })
        }
        val hearted = listOf(c, a, b)
        val page = CollectionSelection(
            title = "Favorites",
            subtitle = "3 tracks",
            artworkUri = "art:c",
            tracks = hearted.sortedBy { it.title },
            allowsTrackSelection = true,
            routeId = FAVORITES_ROUTE_ID,
        )

        assertNull(page.followingFavorites(hearted, byTitle), "same hearts, already in the chosen order")
        assertEquals(listOf(a, c).sortedBy { it.title }, page.followingFavorites(listOf(c, a), byTitle)?.tracks)
    }

    @Test
    fun anyOtherPageIsLeftAlone() {
        val playlist = CollectionSelection(
            title = "Mix",
            subtitle = null,
            artworkUri = null,
            tracks = listOf(a),
            playlistId = "p1",
        )
        val recentlyPlayed = playlist.copy(playlistId = null, routeId = "auto:${AutoPlaylistKind.RECENTLY_PLAYED.name}")

        assertNull(playlist.followingFavorites(listOf(b)))
        assertNull(recentlyPlayed.followingFavorites(listOf(b)))
    }
    /** A library that knows where each track's file is, as Android's media index does. */
    private class PathLibrary(private val all: List<TrackDescriptor>, private val paths: Map<TrackId, String>) : MusicLibrary {
        override suspend fun tracks(): List<TrackDescriptor> = all
        override suspend fun filePaths(ids: List<TrackId>): Map<TrackId, String> = paths.filterKeys { it in ids }
        override suspend fun hide(trackId: TrackId) = Unit
        override suspend fun unhide(trackId: TrackId) = Unit
        override suspend fun hiddenTracks(): List<TrackDescriptor> = emptyList()
        override suspend fun hasHiddenTracks(): Boolean = false
        override suspend fun unhideAll() = Unit
        override suspend fun sources(): List<LibrarySource> = emptyList()
        override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
    }

    /** What the picker was handed: the suggested name, and the file as written into [folder]. */
    private class CapturedExport(private val folder: String?) {
        var name: String? = null
        var text: String? = null
        val exchange = LocalBackupFileExchange(
            exportTo = { encode, suggestedName ->
                name = suggestedName
                text = encode(folder)
            },
            import = {},
        )
    }

    @Test
    fun favoritesExportNewestFirstAndComeBackIntoFavoritesInThatOrder() = runTest {
        val library = listOf(a, b, c)
        val paths = library.associate { it.id to "/storage/emulated/0/Music/Unsorted/${it.title}.mp3" }
        // Hearted a, then b, then c: the store reads c, b, a, newest first.
        val hearts = DefaultFavorites(FavoritesMemory())
        library.forEach { hearts.toggle(it.id) }
        val favoriteTracks = AutoPlaylists.build(library, emptyMap(), emptyMap(), hearts.all()).favoriteTracks()
        assertEquals(listOf(c, b, a), favoriteTracks)

        val export = CapturedExport(folder = "/storage/emulated/0/Music/Playlists")
        exportTracksAsM3u("Favorites", favoriteTracks, PathLibrary(library, paths), relativePaths = true, export.exchange)

        assertEquals("Favorites.m3u8", export.name)
        val text = assertNotNull(export.text)
        val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        // The relative-paths setting applies as it does to a playlist, where the platform has it.
        val expectedRows = if (relativePlaylistPathsAvailable) {
            listOf("../Unsorted/Song c.mp3", "../Unsorted/Song b.mp3", "../Unsorted/Song a.mp3")
        } else {
            listOf(c, b, a).map { paths.getValue(it.id) }
        }
        assertEquals(expectedRows, rows)
        assertEquals("Favorites", parseM3uName(text))

        // Imported back into Favorites on a device with no hearts: the same list, the same order.
        val entries = parseM3u(text)
        val matchedRows = matchM3uEntries(entries, library)
        val offer = M3uImportOffer(
            M3uImport(
                name = parseM3uName(text),
                entryCount = entries.size,
                matchedEntryCount = matchedRows.count { it != null },
                tracks = matchedRows.filterNotNull().distinctBy { it.id },
            ),
        )
        val restored = DefaultFavorites(FavoritesMemory())
        restored.addAll(offer.favoriteIds)
        assertEquals(listOf(c.id, b.id, a.id), restored.all())
    }

    @Test
    fun withoutTheRelativeSettingFavoritesExportTheirAbsolutePaths() = runTest {
        val paths = mapOf(a.id to "/storage/emulated/0/Music/Song a.mp3")
        val export = CapturedExport(folder = "/storage/emulated/0/Music/Playlists")

        exportTracksAsM3u("Favorites", listOf(a), PathLibrary(listOf(a), paths), relativePaths = false, export.exchange)

        assertEquals(
            listOf("/storage/emulated/0/Music/Song a.mp3"),
            assertNotNull(export.text).lines().filter { it.isNotBlank() && !it.startsWith("#") },
        )
    }

    @Test
    fun theFavoritesListIsEmptyBeforeTheAutoPlaylistsExist() {
        assertEquals(emptyList(), emptyList<AutoPlaylist>().favoriteTracks())
    }
}

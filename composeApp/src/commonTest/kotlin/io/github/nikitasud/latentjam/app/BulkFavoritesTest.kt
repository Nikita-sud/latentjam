/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultFavorites
import io.github.nikitasud.latentjam.history.FavoritesStore
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.AutoPlaylists
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}

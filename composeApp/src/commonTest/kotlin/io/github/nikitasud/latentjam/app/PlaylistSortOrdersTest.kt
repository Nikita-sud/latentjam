/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.defaultDirection
import io.github.nikitasud.latentjam.library.hasDirection
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

internal class PlaylistSortOrdersTest {

    private val alpha = TrackDescriptor(TrackId("a"), title = "Alpha", artist = "Zed", durationMs = 300_000)
    private val bravo = TrackDescriptor(TrackId("b"), title = "Bravo", artist = "Yan", durationMs = 100_000)
    private val charlie = TrackDescriptor(TrackId("c"), title = "Charlie", artist = "Xu", durationMs = 200_000)

    /** Stored oldest first, the way a playlist appends: charlie, then alpha, then bravo. */
    private val playlist = Playlist(id = "pl-1", name = "Mix", trackIds = listOf("c", "a", "b"))
    private val playlistPage = CollectionSelection(
        title = "Mix",
        subtitle = null,
        artworkUri = null,
        tracks = listOf(charlie, alpha, bravo),
        allowsTrackSelection = true,
        playlistId = playlist.id,
    )

    /** Favorites are stored newest first: bravo was hearted last. */
    private val favorites = AutoPlaylist(AutoPlaylistKind.FAVORITES, listOf(bravo, charlie, alpha))
    private val favoritesPage = CollectionSelection(
        title = "Favorites",
        subtitle = null,
        artworkUri = null,
        tracks = favorites.tracks,
        allowsTrackSelection = true,
        routeId = FAVORITES_ROUTE_ID,
    )

    private fun ids(tracks: List<TrackDescriptor>) = tracks.map { it.id.value }

    private fun CollectionSelection.shownWith(orders: PlaylistSortOrders): CollectionSelection =
        inChosenOrder(orders, storedTrackOrder(listOf(playlist), listOf(favorites)))

    @Test
    fun aPlaylistOpensInItsOwnOrderAndFavoritesNewestFirst() {
        val orders = PlaylistSortOrders()
        val shownPlaylist = playlistPage.shownWith(orders)
        assertEquals(listOf("c", "a", "b"), ids(shownPlaylist.tracks))
        assertEquals(SortChoice(PlaylistSort.CUSTOM, SongSortDirection.ASCENDING), shownPlaylist.trackSort)
        val shownFavorites = favoritesPage.shownWith(orders)
        assertEquals(listOf("b", "c", "a"), ids(shownFavorites.tracks))
        assertEquals(SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING), shownFavorites.trackSort)
    }

    @Test
    fun eachOrderIsShownOnAPlaylistPage() {
        val expected = mapOf(
            SortChoice(PlaylistSort.CUSTOM, SongSortDirection.ASCENDING) to listOf("c", "a", "b"),
            SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING) to listOf("b", "a", "c"),
            SortChoice(PlaylistSort.RECENT, SongSortDirection.ASCENDING) to listOf("c", "a", "b"),
            SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING) to listOf("a", "b", "c"),
            SortChoice(PlaylistSort.TITLE, SongSortDirection.DESCENDING) to listOf("c", "b", "a"),
            SortChoice(PlaylistSort.ARTIST, SongSortDirection.ASCENDING) to listOf("c", "b", "a"),
            SortChoice(PlaylistSort.DURATION, SongSortDirection.ASCENDING) to listOf("b", "c", "a"),
            SortChoice(PlaylistSort.DURATION, SongSortDirection.DESCENDING) to listOf("a", "c", "b"),
        )
        for ((choice, order) in expected) {
            val shown = playlistPage.shownWith(PlaylistSortOrders().withPlaylist(playlist.id, choice))
            assertEquals(order, ids(shown.tracks), "$choice")
            assertEquals(choice, shown.trackSort)
        }
    }

    @Test
    fun customOrderAndRecentlyAddedComeBackFromTheStoreAfterAnotherSort() {
        val byTitle = playlistPage.shownWith(
            PlaylistSortOrders().withPlaylist(playlist.id, SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING)),
        )
        assertEquals(listOf("a", "b", "c"), ids(byTitle.tracks))
        // The page now holds A to Z; Custom order is read from the store again, not from the page.
        assertEquals(listOf("c", "a", "b"), ids(byTitle.shownWith(PlaylistSortOrders()).tracks))
        val recent = PlaylistSortOrders().withPlaylist(
            playlist.id,
            SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING),
        )
        assertEquals(listOf("b", "a", "c"), ids(byTitle.shownWith(recent).tracks))

        val favoritesByTitle = favoritesPage.shownWith(
            PlaylistSortOrders(favorites = SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING)),
        )
        assertEquals(listOf("a", "b", "c"), ids(favoritesByTitle.tracks))
        assertEquals(listOf("b", "c", "a"), ids(favoritesByTitle.shownWith(PlaylistSortOrders()).tracks))
    }

    @Test
    fun sortingNeverRewritesTheStoredPlaylist() {
        val stored = playlist.copy()
        playlistPage.shownWith(
            PlaylistSortOrders().withPlaylist(playlist.id, SortChoice(PlaylistSort.TITLE, SongSortDirection.DESCENDING)),
        )
        assertEquals(stored, playlist)
        assertEquals(listOf("c", "a", "b"), playlist.trackIds)
    }

    @Test
    fun theShownOrderIsThePlayedOrder() {
        // Every play action on the page (Play, a tapped row, Shuffle's pool, SMART's seed, a
        // selection's Play) reads selection.tracks: row k of the page is queue index k.
        val orders = PlaylistSortOrders().withPlaylist(
            playlist.id,
            SortChoice(PlaylistSort.DURATION, SongSortDirection.ASCENDING),
        )
        val shown = playlistPage.shownWith(orders)
        assertEquals(listOf(bravo, charlie, alpha), shown.tracks)
        assertEquals(charlie, shown.tracks[1])
        // A page the library reconciles or refreshes keeps playing in the same order.
        val reconciled = checkNotNull(shown.filterTracksForCollection { it.id != alpha.id }).shownWith(orders)
        assertEquals(listOf("b", "c"), ids(reconciled.tracks))
        // A playlist rebuilt away from its page (a restart, Android Auto) plays in that order too.
        assertEquals(
            listOf("b", "c", "a"),
            ids(playlist.tracksInChosenOrder(listOf(charlie, alpha, bravo), orders)),
        )
        assertEquals(
            listOf("b", "c", "a"),
            ids(
                checkNotNull(
                    resolveResumeSourceQueue(
                        saved = ResumePlayback(
                            trackId = "a",
                            shuffleMode = "OFF",
                            positionMs = 0,
                            sourceKind = QueueSourceKind.COLLECTION.name,
                            sourceReference = playlist.id,
                        ),
                        library = listOf(alpha, bravo, charlie),
                        playlists = listOf(playlist),
                        playlistOrders = orders,
                    ),
                ),
            ),
        )
    }

    @Test
    fun aPageAlreadyInItsOrderIsReturnedAsIs() {
        val orders = PlaylistSortOrders()
        val shown = playlistPage.shownWith(orders)
        assertSame(shown, shown.shownWith(orders))
    }

    @Test
    fun onlyAnAToZTitleOrderGetsTheLetterRail() {
        val titles = PlaylistSortOrders().withPlaylist(
            playlist.id,
            SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING),
        )
        assertEquals(CollectionRailMode.TRACK_TITLES, playlistPage.shownWith(titles).railMode)
        val reversed = titles.withPlaylist(playlist.id, SortChoice(PlaylistSort.TITLE, SongSortDirection.DESCENDING))
        assertEquals(CollectionRailMode.NONE, playlistPage.shownWith(reversed).railMode)
        assertEquals(CollectionRailMode.NONE, playlistPage.shownWith(PlaylistSortOrders()).railMode)
    }

    @Test
    fun otherPagesHaveNoOrderToChoose() {
        val album = CollectionSelection(
            title = "Album",
            subtitle = null,
            artworkUri = null,
            tracks = listOf(charlie, alpha),
            routeId = "album:x",
        )
        val orders = PlaylistSortOrders(favorites = SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING))
        assertSame(album, album.inChosenOrder(orders, listOf(alpha.id, charlie.id)))
        assertNull(album.trackSort)
        assertNull(orders.choosing(album, SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING)))
        assertNull(album.storedTrackOrder(listOf(playlist), listOf(favorites)))
    }

    @Test
    fun favoritesOfferNoCustomOrder() {
        assertEquals(PlaylistSort.entries.toList(), playlistPage.trackSortOptions())
        assertEquals(PlaylistSort.entries.filter { it != PlaylistSort.CUSTOM }, favoritesPage.trackSortOptions())
        val orders = PlaylistSortOrders()
        assertSame(orders, orders.withFavorites(SortChoice(PlaylistSort.CUSTOM, SongSortDirection.ASCENDING)))
    }

    @Test
    fun choosingAnOrderIsRememberedPerPage() {
        val title = SortChoice(PlaylistSort.TITLE, SongSortDirection.DESCENDING)
        val chosen = checkNotNull(PlaylistSortOrders().choosing(playlistPage, title))
        assertEquals(title, chosen.forPlaylist(playlist.id))
        assertEquals(DEFAULT_PLAYLIST_TRACK_SORT, chosen.forPlaylist("another"))
        assertEquals(DEFAULT_FAVORITES_TRACK_SORT, chosen.favorites)
        val favoritesChosen = checkNotNull(chosen.choosing(favoritesPage, title))
        assertEquals(title, favoritesChosen.favorites)
        assertEquals(title, favoritesChosen.forPlaylist(playlist.id))
        // Back to Custom order leaves no entry behind; Custom order keeps no direction.
        val back = favoritesChosen.withPlaylist(playlist.id, SortChoice(PlaylistSort.CUSTOM, SongSortDirection.DESCENDING))
        assertEquals(emptyMap(), back.playlists)
        assertEquals(DEFAULT_PLAYLIST_TRACK_SORT, back.forPlaylist(playlist.id))
    }

    @Test
    fun aSecondTapOnCustomOrderChangesNothing() {
        val custom = SortChoice(PlaylistSort.CUSTOM, SongSortDirection.ASCENDING)
        assertEquals(
            custom,
            custom.afterSelecting(PlaylistSort.CUSTOM, PlaylistSort.CUSTOM.defaultDirection, PlaylistSort.CUSTOM.hasDirection),
        )
        val title = SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING)
        assertEquals(
            SortChoice(PlaylistSort.TITLE, SongSortDirection.DESCENDING),
            title.afterSelecting(PlaylistSort.TITLE, PlaylistSort.TITLE.defaultDirection, PlaylistSort.TITLE.hasDirection),
        )
        assertEquals(
            SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING),
            title.afterSelecting(PlaylistSort.RECENT, PlaylistSort.RECENT.defaultDirection, PlaylistSort.RECENT.hasDirection),
        )
        assertEquals(
            custom,
            SortChoice(PlaylistSort.DURATION, SongSortDirection.DESCENDING)
                .afterSelecting(PlaylistSort.CUSTOM, PlaylistSort.CUSTOM.defaultDirection, PlaylistSort.CUSTOM.hasDirection),
        )
    }

    @Test
    fun ordersSurviveTheSettingsCodec() {
        val orders = PlaylistSortOrders(
            favorites = SortChoice(PlaylistSort.ALBUM, SongSortDirection.DESCENDING),
            playlists = mapOf(
                "pl-1" to SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING),
                // Ids are opaque: separators inside one must not split it.
                "odd;id=with:all|separators" to SortChoice(PlaylistSort.DURATION, SongSortDirection.DESCENDING),
                "ünïcødé 名前" to SortChoice(PlaylistSort.RECENT, SongSortDirection.ASCENDING),
            ),
        )
        assertEquals(orders, playlistSortOrdersFromPersisted(encodePlaylistSortOrders(orders)))
        assertEquals(PlaylistSortOrders(), playlistSortOrdersFromPersisted(encodePlaylistSortOrders(PlaylistSortOrders())))
    }

    @Test
    fun damagedOrUnknownValuesFallBackToDefaults() {
        for (value in listOf(null, "", "garbage", "LJPS1|", "LJPS1|title:ascending|4:pl-1", "LJPS1|title:ascending|9:pl-1=title:ascending;")) {
            assertEquals(PlaylistSortOrders(), playlistSortOrdersFromPersisted(value), "$value")
        }
        // A field this build does not know (or Custom order for Favorites) resets just that page.
        val partly = playlistSortOrdersFromPersisted("LJPS1|custom:ascending|4:pl-1=shuffle:ascending;4:pl-2=artist:descending;")
        assertEquals(DEFAULT_FAVORITES_TRACK_SORT, partly.favorites)
        assertEquals(mapOf("pl-2" to SortChoice(PlaylistSort.ARTIST, SongSortDirection.DESCENDING)), partly.playlists)
    }

    @Test
    fun deletedPlaylistsLeaveNoOrderBehind() {
        val orders = PlaylistSortOrders()
            .withPlaylist("kept", SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING))
            .withPlaylist("deleted", SortChoice(PlaylistSort.ARTIST, SongSortDirection.ASCENDING))
        val retained = orders.retainingPlaylists(listOf("kept", "new"))
        assertEquals(setOf("kept"), retained.playlists.keys)
        assertSame(retained, retained.retainingPlaylists(listOf("kept")))
        // An order left by a deleted playlist could only ever be ignored: no page asks for it.
        assertEquals(DEFAULT_PLAYLIST_TRACK_SORT, retained.forPlaylist("deleted"))
    }
}

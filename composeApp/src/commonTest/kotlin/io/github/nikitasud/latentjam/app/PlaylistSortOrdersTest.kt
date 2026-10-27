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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        inChosenOrder(orders, listOf(playlist), listOf(favorites))

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
    fun playsOrderTheShownAndPlayedPageByListens() {
        val orders = PlaylistSortOrders().withPlaylist(playlist.id, SortChoice(PlaylistSort.PLAYS, SongSortDirection.DESCENDING))
        val plays = mapOf(TrackId("a") to 3, TrackId("b") to 12)
        val shown = playlistPage.inChosenOrder(orders, listOf(playlist), listOf(favorites), plays)
        // b 12, a 3, c never: most first; the page's tracks are what play.
        assertEquals(listOf("b", "a", "c"), ids(shown.tracks))
        assertEquals(listOf("b", "a", "c"), ids(playlist.tracksInChosenOrder(shown.tracks, orders, plays)))
        // The order survives a restart: it is written and read back like any other.
        assertEquals(orders, playlistSortOrdersFromPersisted(encodePlaylistSortOrders(orders)))
    }

    @Test
    fun customOrderReturnsAfterAnotherSortAndRecentUsesAdditionOrder() {
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
    fun reorderingDoesNotChangeRecentAndResumeUsesTheChosenOrder() {
        val moved = playlist.copy(trackIds = listOf("b", "c", "a"))
        val recent = PlaylistSortOrders().withPlaylist(playlist.id,
            SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING))
        val shown = playlistPage.inChosenOrder(recent, listOf(moved), emptyList())
        assertEquals(listOf("b", "a", "c"), ids(shown.tracks))
        assertEquals(listOf("b", "c", "a"), ids(shown.inChosenOrder(PlaylistSortOrders(), listOf(moved), emptyList()).tracks))
        assertEquals(listOf("b", "a", "c"), ids(moved.tracksInChosenOrder(listOf(bravo, charlie, alpha), recent)))
        assertEquals(listOf("b", "a", "c"), ids(checkNotNull(resolveResumeSourceQueue(
            saved = ResumePlayback(trackId = "b", shuffleMode = "OFF", positionMs = 0,
                sourceKind = QueueSourceKind.COLLECTION.name, sourceReference = moved.id),
            library = listOf(alpha, bravo, charlie), playlists = listOf(moved), playlistOrders = recent,
        ))))
        assertEquals(recent, playlistSortOrdersFromPersisted(encodePlaylistSortOrders(recent)))
    }

    @Test
    fun aPlaylistSortedByPlaysResumesInThatOrder() {
        val byPlays = PlaylistSortOrders().withPlaylist(playlist.id,
            SortChoice(PlaylistSort.PLAYS, SongSortDirection.DESCENDING))
        val plays = mapOf(TrackId("c") to 5, TrackId("a") to 2)
        val saved = ResumePlayback(trackId = "a", shuffleMode = "OFF", positionMs = 0,
            sourceKind = QueueSourceKind.COLLECTION.name, sourceReference = playlist.id)
        assertEquals(
            ids(playlist.tracksInChosenOrder(listOf(alpha, bravo, charlie), byPlays, plays)),
            ids(checkNotNull(resolveResumeSourceQueue(saved = saved, library = listOf(alpha, bravo, charlie),
                playlists = listOf(playlist), playlistOrders = byPlays, plays = plays))),
        )
        assertEquals(listOf("c", "a"), ids(checkNotNull(resolveResumeSourceQueue(saved = saved,
            library = listOf(alpha, bravo, charlie), playlists = listOf(playlist), playlistOrders = byPlays,
            plays = plays))).take(2))
    }

    @Test
    fun aFavoritesPageSortedByPlaysSettlesOnlyAfterARefreshWithLoadedCounts() = runTest {
        val state = PlaylistsState()
        suspend fun refresh(): Boolean = async { state.favoritesPlaysOrderSettled() }.await()
        assertEquals(false, refresh(), "opened before the counts: sorted with every count at 0")
        state.publishStats(mapOf(TrackId("a") to 1), emptyMap())
        assertEquals(false, refresh(), "the counts arrive: the page re-sorts once")
        assertEquals(true, refresh(), "a listen after that keeps the order")
        // A refresh cancelled before it sorted records nothing.
        val other = PlaylistsState()
        other.publishStats(mapOf(TrackId("a") to 1), emptyMap())
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            other.favoritesPlaysOrderSettled()
            awaitCancellation()
        }
        cancelled.cancelAndJoin()
        assertEquals(false, async { other.favoritesPlaysOrderSettled() }.await())
    }

    @Test
    fun aFirstReadWithNoListensSettlesTheFavoritesOrderAtOnce() = runTest {
        // No listens yet: the empty counts re-run nothing, and every page was sorted with them already.
        val state = PlaylistsState()
        val before = launch(start = CoroutineStart.UNDISPATCHED) {
            state.favoritesPlaysOrderSettled()
            yield()
        }
        state.publishStats(emptyMap(), emptyMap())
        before.join()
        assertEquals(true, async { state.favoritesPlaysOrderSettled() }.await(), "a run from before the read cannot unsettle it")
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
        assertEquals(PlaylistSort.entries, playlistPage.trackSortOptions())
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
                "ünïcødé 名前" to SortChoice(PlaylistSort.ARTIST, SongSortDirection.ASCENDING),
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

    @Test
    fun aFailedPlaylistReadKeepsEveryOrderAndALaterReadForgetsOnlyDeletedPlaylists() {
        val byTitle = SortChoice(PlaylistSort.TITLE, SongSortDirection.ASCENDING)
        val byArtist = SortChoice(PlaylistSort.ARTIST, SongSortDirection.DESCENDING)
        val orders = PlaylistSortOrders()
            .withPlaylist("kept", byTitle)
            .withPlaylist("deleted", byArtist)
        val state = PlaylistsState()

        // The launch read failed: the tab shows no playlists and the saved player may restore,
        // but that empty list is not the store's, and no order may be forgotten against it.
        state.launchReadFailed()
        assertTrue(state.playlistsLoaded)
        assertNull(state.completePlaylists)
        assertSame(orders, orders.forgettingDeletedPlaylists(state.completePlaylists))

        // A playlist made on the tab meanwhile does not make the list complete either.
        state.playlists = listOf(Playlist(id = "made-meanwhile", name = "New", trackIds = emptyList()))
        assertSame(orders, orders.forgettingDeletedPlaylists(state.completePlaylists))

        // A later read succeeds: only the playlist really gone loses its order.
        state.published(
            listOf(
                Playlist(id = "kept", name = "Kept", trackIds = emptyList()),
                Playlist(id = "made-meanwhile", name = "New", trackIds = emptyList()),
            ),
        )
        assertEquals(mapOf("kept" to byTitle), orders.forgettingDeletedPlaylists(state.completePlaylists).playlists)
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class PlaylistSortingTest {

    private fun track(
        id: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        durationMs: Long? = null,
        disc: Int? = null,
        number: Int? = null,
        albumArtist: String? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        discNumber = disc,
        trackNumber = number,
        albumArtist = albumArtist,
    )

    private fun ids(tracks: List<TrackDescriptor>): List<String> = tracks.map { it.id.value }

    @Test
    fun customOrderIsTheStoredOrderInEitherDirection() {
        val stored = listOf(track("b", title = "Beta"), track("a", title = "Alpha"), track("c", title = "Gamma"))
        assertSame(stored, PlaylistSorting.sort(stored, PlaylistSort.CUSTOM))
        assertSame(stored, PlaylistSorting.sort(stored, PlaylistSort.CUSTOM, SongSortDirection.DESCENDING))
    }

    @Test
    fun recentlyAddedReadsAPlaylistsAppendOrder() {
        // A playlist appends: the last stored track is the newest.
        val stored = listOf(track("first"), track("second"), track("third"))
        assertEquals(listOf("third", "second", "first"), ids(PlaylistSorting.sort(stored, PlaylistSort.RECENT)))
        assertEquals(
            listOf("first", "second", "third"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.RECENT, SongSortDirection.ASCENDING)),
        )
    }

    @Test
    fun recentlyAddedReadsFavoritesNewestFirstOrder() {
        // A heart puts its track on top: the first stored favourite is the newest.
        val stored = listOf(track("newest"), track("older"), track("oldest"))
        assertEquals(
            listOf("newest", "older", "oldest"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.RECENT, storedNewestFirst = true)),
        )
        assertEquals(
            listOf("oldest", "older", "newest"),
            ids(
                PlaylistSorting.sort(
                    stored,
                    PlaylistSort.RECENT,
                    SongSortDirection.ASCENDING,
                    storedNewestFirst = true,
                ),
            ),
        )
    }

    @Test
    fun titleOrderShelvesLikeTheTracksTabAndKeepsBlanksLast() {
        val stored = listOf(
            track("blank", title = null),
            track("zebra", title = "zebra"),
            track("battle", title = "The Battle of Los Angeles"),
            track("africa", title = "...Africa"),
            track("cyrillic", title = "Кафель"),
        )
        assertEquals(
            listOf("africa", "battle", "zebra", "cyrillic", "blank"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.TITLE)),
        )
        assertEquals(
            listOf("cyrillic", "zebra", "battle", "africa", "blank"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.TITLE, SongSortDirection.DESCENDING)),
        )
    }

    @Test
    fun equalTitlesKeepTheStoredOrderInBothDirections() {
        val stored = listOf(track("1", title = "Home"), track("2", title = "home"), track("3", title = "HOME"))
        assertEquals(listOf("1", "2", "3"), ids(PlaylistSorting.sort(stored, PlaylistSort.TITLE)))
        assertEquals(
            listOf("1", "2", "3"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.TITLE, SongSortDirection.DESCENDING)),
        )
    }

    @Test
    fun artistOrderFallsBackToTitleWithinAnArtist() {
        val stored = listOf(
            track("toto-b", title = "Rosanna", artist = "Toto"),
            track("abba", title = "Waterloo", artist = "ABBA"),
            track("toto-a", title = "Africa", artist = "Toto"),
            track("none", title = "Untagged", artist = null),
        )
        assertEquals(listOf("abba", "toto-a", "toto-b", "none"), ids(PlaylistSorting.sort(stored, PlaylistSort.ARTIST)))
        assertEquals(
            listOf("toto-b", "toto-a", "abba", "none"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.ARTIST, SongSortDirection.DESCENDING)),
        )
    }

    @Test
    fun albumOrderPlaysEachAlbumInItsOwnTrackOrder() {
        val stored = listOf(
            track("b2", album = "Blue", number = 2),
            track("a-disc2", album = "Abbey Road", disc = 2, number = 1),
            track("b1", album = "Blue", number = 1),
            track("a1", album = "Abbey Road", disc = 1, number = 1),
            track("loose", album = null),
            track("b-unnumbered", album = "Blue"),
        )
        assertEquals(
            listOf("a1", "a-disc2", "b1", "b2", "b-unnumbered", "loose"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.ALBUM)),
        )
        // Reversed, the albums run Z–A, but each still plays from its first track.
        assertEquals(
            listOf("b1", "b2", "b-unnumbered", "a1", "a-disc2", "loose"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.ALBUM, SongSortDirection.DESCENDING)),
        )
    }

    @Test
    fun sameNamedAlbumsByDifferentArtistsStayApart() {
        val stored = listOf(
            track("queen-2", album = "Greatest Hits", artist = "Queen", number = 2),
            track("abba-2", album = "Greatest Hits", artist = "ABBA", number = 2),
            track("queen-1", album = "Greatest Hits", artist = "Queen", number = 1),
            track("abba-1", album = "Greatest Hits", albumArtist = "ABBA", artist = "ABBA feat. Someone", number = 1),
        )
        // "abba-1" joins ABBA's album by its album artist; its own credit names a guest too.
        assertEquals(
            listOf("abba-1", "abba-2", "queen-1", "queen-2"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.ALBUM)),
        )
    }

    @Test
    fun anUntaggedCompilationSharingItsCoverStaysOneAlbum() {
        fun hit(id: String, artist: String, number: Int) = TrackDescriptor(
            id = TrackId(id),
            album = "Hits of the Year",
            artist = artist,
            trackNumber = number,
            albumArtworkUri = "cover://hits",
        )
        val stored = listOf(hit("3", "Zed", 3), hit("1", "Mona", 1), hit("2", "Abe", 2))
        assertEquals(listOf("1", "2", "3"), ids(PlaylistSorting.sort(stored, PlaylistSort.ALBUM)))
    }

    @Test
    fun oneArtistsTwoReleasesOfOneTitleStayApart() {
        fun weezer(id: String, folder: String, number: Int) = TrackDescriptor(
            id = TrackId(id),
            album = "Weezer",
            artist = "Weezer",
            trackNumber = number,
            folderPath = folder,
        )
        val stored = listOf(
            weezer("blue-2", "Music/Blue", 2),
            weezer("green-1", "Music/Green", 1),
            weezer("blue-1", "Music/Blue", 1),
            weezer("green-2", "Music/Green", 2),
        )
        // Each release plays through rather than interleaving track by track.
        assertEquals(
            listOf("blue-1", "blue-2", "green-1", "green-2"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.ALBUM)),
        )
    }

    @Test
    fun albumsWhoseTitlesShelveAlikeStayTheTwoAlbumsTheAlbumsTabShows() {
        // "The Wall" and "Wall" shelve under W alike, but they are two albums: the article is part
        // of the title the Albums tab tells them apart by.
        val stored = listOf(
            track("the-wall-1", album = "The Wall", artist = "Pink Floyd", number = 1),
            track("wall-2", album = "Wall", artist = "Pink Floyd", number = 2),
            track("the-wall-3", album = "The Wall", artist = "Pink Floyd", number = 3),
        )
        assertEquals(2, LibraryCatalog.build(stored).albums.size)
        for (direction in SongSortDirection.entries) {
            assertEquals(
                listOf("the-wall-1", "the-wall-3", "wall-2"),
                ids(PlaylistSorting.sort(stored, PlaylistSort.ALBUM, direction)),
                "$direction",
            )
        }
    }

    @Test
    fun albumOrderGroupsTracksExactlyAsTheAlbumsTabDoes() {
        val stored = listOf(
            track("wall-1", album = "The Wall", artist = "Pink Floyd", number = 1),
            track("animals-1", album = "Animals", albumArtist = "The Beatles", number = 1),
            track("wall-b", album = "Wall", artist = "Pink Floyd", number = 1),
            track("animals-2", album = "Animals", albumArtist = "Beatles", number = 2),
            track("wall-2", album = "the  wall", artist = "pink floyd", number = 2),
            track("loose", album = "  "),
        )
        val tabAlbums = LibraryCatalog.build(stored).albums.map { album -> album.tracks.map { it.id.value }.toSet() }
        val played = PlaylistSorting.sort(stored, PlaylistSort.ALBUM)
        // Each album the tab shows plays as one unbroken run.
        val runs = played.filter { it.id.value != "loose" }.fold(mutableListOf<MutableSet<String>>()) { runs, track ->
            val album = tabAlbums.single { track.id.value in it }
            if (runs.lastOrNull()?.let { run -> run.first() in album } == true) {
                runs.last() += track.id.value
            } else {
                runs += mutableSetOf(track.id.value)
            }
            runs
        }
        assertEquals(tabAlbums.filter { "loose" !in it }.toSet(), runs.toSet())
        assertEquals("loose", played.last().id.value)
    }

    @Test
    fun durationRunsShortestFirstAndUnknownLengthsStayLast() {
        val stored = listOf(
            track("long", durationMs = 400_000),
            track("unknown", durationMs = null),
            track("short", durationMs = 90_000),
            track("mid-a", durationMs = 200_000),
            track("mid-b", durationMs = 200_000),
        )
        assertEquals(
            listOf("short", "mid-a", "mid-b", "long", "unknown"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.DURATION)),
        )
        assertEquals(
            listOf("long", "mid-a", "mid-b", "short", "unknown"),
            ids(PlaylistSorting.sort(stored, PlaylistSort.DURATION, SongSortDirection.DESCENDING)),
        )
    }

    @Test
    fun everyOrderIsAPermutationOfTheStoredTracks() {
        val stored = (0 until 40).map { n ->
            track(
                id = "t$n",
                title = listOf("Alpha", "beta", null, "Gamma")[n % 4],
                artist = listOf("X", null, "y")[n % 3],
                album = listOf("One", "Two", null)[n % 3],
                durationMs = if (n % 5 == 0) null else (n % 7) * 1_000L,
                number = if (n % 6 == 0) null else n % 9,
            )
        }
        for (sort in PlaylistSort.entries) {
            for (direction in SongSortDirection.entries) {
                for (newestFirst in listOf(false, true)) {
                    val sorted = PlaylistSorting.sort(stored, sort, direction, newestFirst)
                    assertEquals(stored.map { it.id }.toSet(), sorted.map { it.id }.toSet(), "$sort $direction")
                    assertEquals(stored.size, sorted.size, "$sort $direction")
                }
            }
        }
    }

    @Test
    fun onlyCustomOrderHasNoDirection() {
        assertFalse(PlaylistSort.CUSTOM.hasDirection)
        PlaylistSort.entries.filter { it != PlaylistSort.CUSTOM }.forEach { assertTrue(it.hasDirection, it.name) }
        assertEquals(SongSortDirection.DESCENDING, PlaylistSort.RECENT.defaultDirection)
        assertEquals(SongSortDirection.ASCENDING, PlaylistSort.TITLE.defaultDirection)
        assertEquals(SongSortDirection.ASCENDING, PlaylistSort.DURATION.defaultDirection)
    }
}

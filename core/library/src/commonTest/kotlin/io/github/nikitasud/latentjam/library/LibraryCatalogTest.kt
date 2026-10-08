/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.TextRepair
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class LibraryCatalogTest {

    private fun track(
        id: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        artworkUri: String? = null,
        folderPath: String? = null,
        artists: List<String> = emptyList(),
        albumArtist: String? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        album = album,
        artists = artists,
        genre = genre,
        artworkUri = artworkUri,
        folderPath = folderPath,
        albumArtist = albumArtist,
    )

    @Test
    fun aCompilationTaggedWithAnAlbumArtistIsOneAlbum() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "a", artist = "Queen", album = "Hits 1985", artworkUri = "art://1", albumArtist = "Various Artists"),
                track("2", title = "b", artist = "ABBA", album = "Hits 1985", artworkUri = "art://2", albumArtist = "Various Artists"),
                track("3", title = "c", artist = "Sade", album = "Hits 1985", albumArtist = "Various Artists"),
            ),
        )
        val album = catalog.albums.single()
        assertEquals("Various Artists", album.artist)
        assertEquals(3, album.tracks.size)
    }

    @Test
    fun theAlbumArtistIsTheAlbumsSubtitleOverAFeaturedCredit() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "a", artist = "Singer feat. Guest", album = "Record", albumArtist = "Singer"),
                track("2", title = "b", artist = "Singer", album = "Record", albumArtist = "Singer"),
            ),
        )
        assertEquals("Singer", catalog.albums.single().artist)
    }

    @Test
    fun withoutAlbumArtistsGroupingAndKeysAreUnchanged() {
        val tracks = listOf(
            track("1", title = "b", artist = "Queen", album = "Greatest Hits", artworkUri = "art://1"),
            track("2", title = "c", artist = "ABBA", album = "Greatest Hits", artworkUri = "art://2"),
            track("3", title = "d", artist = "X", album = "Demo"),
        )
        val plain = LibraryCatalog.build(tracks)
        // An album artist equal to the artist is the same identity: tagging it changes nothing.
        val tagged = LibraryCatalog.build(tracks.map { it.copy(albumArtist = it.artist) })
        assertEquals(plain.albums.map { it.key }, tagged.albums.map { it.key })
        assertEquals(3, plain.albums.size)
    }

    /** A song whose cover LatentJam saved on Android: its own cover, and its album's kept beside it. */
    private fun TrackDescriptor.withOwnCover(uri: String?) = copy(artworkUri = uri, albumArtworkUri = artworkUri)

    @Test
    fun oneSongsOwnCoverDoesNotSplitItsAlbumInAnyGrouping() {
        val shapes = mapOf(
            "one artist" to listOf(
                track("1", title = "a", artist = "Queen", album = "Hits", artworkUri = "art://1"),
                track("2", title = "b", artist = "Queen", album = "Hits", artworkUri = "art://1"),
                track("3", title = "c", artist = "Queen", album = "Hits", artworkUri = "art://1"),
            ),
            // No album artist: the shared album art is what holds these artists together.
            "same title, several artists" to listOf(
                track("1", title = "a", artist = "Queen", album = "Hits 1985", artworkUri = "art://9"),
                track("2", title = "b", artist = "ABBA", album = "Hits 1985", artworkUri = "art://9"),
                track("3", title = "c", artist = "Sade", album = "Hits 1985", artworkUri = "art://9"),
            ),
            "no album title" to listOf(
                track("1", title = "a", artist = "Queen", artworkUri = "art://5"),
                track("2", title = "b", artist = "ABBA", artworkUri = "art://5"),
                track("3", title = "c", artist = "Sade", artworkUri = "art://5"),
            ),
        )
        for ((shape, tracks) in shapes) {
            val before = LibraryCatalog.build(tracks)
            assertEquals(1, before.albums.size, shape)
            // A new cover, and a removed one.
            for (cover in listOf("file:///data/files/track-covers/0a1b2c3d.jpg", null)) {
                val after = LibraryCatalog.build(tracks.mapIndexed { i, t -> if (i == 1) t.withOwnCover(cover) else t })
                assertEquals(before.albums.map { it.key }, after.albums.map { it.key }, "$shape, cover $cover")
                assertEquals(
                    before.albums.map { album -> album.tracks.map { it.id } },
                    after.albums.map { album -> album.tracks.map { it.id } },
                    "$shape, cover $cover",
                )
            }
        }
    }

    @Test
    fun songsCarryingCoversOfTheirOwnGroupExactlyAsTheirAlbumsCoverDid() {
        // Android's scan: every song's own MediaStore cover, its album's beside it (issue #12).
        val albumShapes = listOf(
            listOf("Queen" to "Hits", "Queen" to "Hits", "ABBA" to "Hits"),
            listOf("Queen" to "Hits 1985", "ABBA" to "Hits 1985", "Sade" to "Hits 1985"),
            listOf("Queen" to null, "ABBA" to null, "Sade" to null),
        )
        for (shape in albumShapes) {
            val byAlbumCover = shape.mapIndexed { i, (artist, album) ->
                track("${i + 1}", title = "t$i", artist = artist, album = album, artworkUri = "art://album-$album")
            }
            val byOwnCover = byAlbumCover.map { it.copy(artworkUri = "art://song-${it.id.value}", albumArtworkUri = it.artworkUri) }
            val before = LibraryCatalog.build(byAlbumCover)
            val after = LibraryCatalog.build(byOwnCover)
            assertEquals(before.albums.map { it.key }, after.albums.map { it.key }, "$shape")
            assertEquals(
                before.albums.map { album -> album.tracks.map { it.id } },
                after.albums.map { album -> album.tracks.map { it.id } },
                "$shape",
            )
        }
    }

    @Test
    fun anAlbumOfSongsWithOwnCoversShowsItsFirstTracksCover() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "a", artist = "Queen", album = "Hits", artworkUri = "art://song-1")
                    .copy(trackNumber = 2, albumArtworkUri = "art://album"),
                track("2", title = "b", artist = "Queen", album = "Hits", artworkUri = "art://song-2")
                    .copy(trackNumber = 1, albumArtworkUri = "art://album"),
            ),
        )
        assertEquals("art://song-2", catalog.albums.single().artworkUri)
    }

    @Test
    fun anAlbumWhoseFirstSongHasItsOwnCoverShowsThatCover() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "a", artist = "Queen", album = "Hits", artworkUri = "art://1")
                    .withOwnCover("file:///data/files/track-covers/0a1b2c3d.jpg"),
                track("2", title = "b", artist = "Queen", album = "Hits", artworkUri = "art://1"),
            ),
        )
        assertEquals("file:///data/files/track-covers/0a1b2c3d.jpg", catalog.albums.single().artworkUri)
    }

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
    fun mojibakeRepairedArtistNamesDoNotSplitAnAlbumInTwo() {
        // MediaStore delivers these verbatim: one row's artist tag was UTF-8
        // read correctly, the other's identical UTF-8 bytes were decoded as
        // Latin-1. Unrepaired, that is two different artist strings and the
        // album splits in two (issue #7); repaired through the same helper
        // the Android path uses, both read "Grüße" and the album is one.
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "A", artist = TextRepair.repair("Grüße"), album = "Live"),
                track("2", title = "B", artist = TextRepair.repair("GrÃ¼ÃŸe"), album = "Live"),
            ),
        )

        assertEquals(1, catalog.albums.size)
        assertContentEquals(listOf("A", "B"), catalog.albums.single().tracks.map { it.title })
    }

    @Test
    fun perTrackArtworkCacheUrisDoNotDuplicateOneArtistsAlbum() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "A", artist = "GSPD", album = "Leningrad Electroclub", artworkUri = "file://art-1"),
                track("2", title = "B", artist = "GSPD", album = "Leningrad Electroclub", artworkUri = "file://art-2"),
            ),
        )

        assertEquals(1, catalog.albums.size)
        assertContentEquals(listOf("A", "B"), catalog.albums.single().tracks.map { it.title })
    }

    @Test
    fun invisibleFormattingAndWhitespaceDoNotDuplicateAnAlbum() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", artist = "Artist", album = "My  Album"),
                track("2", artist = "Artist", album = " My\u200B Album "),
            ),
        )

        assertEquals(1, catalog.albums.size)
    }

    @Test
    fun albumMetadataContainingTheOldDelimiterCannotCollide() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("left", artist = "c", album = "a::b"),
                track("right", artist = "b::c", album = "a"),
            ),
        )

        assertEquals(2, catalog.albums.size)
        assertEquals(
            setOf(setOf("left"), setOf("right")),
            catalog.albums.map { album -> album.tracks.map { it.id.value }.toSet() }.toSet(),
        )
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

    @Test
    fun aCollaborationBelongsToEveryCreditedArtist() {
        val catalog = LibraryCatalog.build(
            listOf(
                track(
                    "1",
                    title = "Dirty Harry",
                    artist = "Gorillaz feat. Bootie Brown",
                    artists = listOf("Gorillaz", "Bootie Brown"),
                ),
                track("2", title = "Feel Good Inc", artist = "Gorillaz", artists = listOf("Gorillaz")),
            ),
        )
        val names = catalog.artists.map { it.name }
        assertTrue("Gorillaz" in names, "$names")
        assertTrue("Bootie Brown" in names, "$names")
        assertEquals(
            2,
            catalog.artists.first { it.name == "Gorillaz" }.tracks.size,
            "the collaboration must count for the primary credit too",
        )
    }

    @Test
    fun aSemicolonJoinedDisplayStringIsAListByConvention() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", title = "Dreaming", artist = "William Davies; Edward Nutbrown")),
        )
        val names = catalog.artists.map { it.name }
        assertTrue("William Davies" in names && "Edward Nutbrown" in names, "$names")
    }

    @Test
    fun aCollaborationCreditIsListedUnderEveryArtistItNames() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", title = "The Pink Phantom", artist = "Gorillaz, Elton John"),
                track("2", title = "Feel Good Inc", artist = "Gorillaz"),
            ),
            isKnownArtist = { false },
        )
        assertEquals(listOf("Elton John", "Gorillaz"), catalog.artists.map { it.name })
        assertEquals(2, catalog.artists.first { it.name == "Gorillaz" }.tracks.size)
    }

    @Test
    fun aBandTheArtistListKnowsKeepsItsComma() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", title = "Song", artist = "Crosby, Stills & Nash")),
            isKnownArtist = { it == "Crosby, Stills & Nash" },
        )
        assertEquals(listOf("Crosby, Stills & Nash"), catalog.artists.map { it.name })
    }

    @Test
    fun aLoneDisplayStringStaysWholeWithoutTagFacts() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", title = "Song", artist = "Crosby, Stills & Nash")),
        )
        assertEquals(listOf("Crosby, Stills & Nash"), catalog.artists.map { it.name })
    }
}

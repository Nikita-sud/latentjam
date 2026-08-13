/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaylistM3uTest {

    private val track = TrackDescriptor(
        id = TrackId("1"),
        title = "Freestyler",
        artist = "Bomfunk MC's",
        durationMs = 306_000,
        folderPath = "Music",
    )

    @Test
    fun encodeWritesHeaderMetadataAndRealPathWhenKnown() {
        val text = encodeM3u(
            name = "Party",
            tracks = listOf(track),
            paths = mapOf(track.id to "/storage/emulated/0/Music/Freestyler.mp3"),
        )
        assertEquals(
            listOf(
                "#EXTM3U",
                "#PLAYLIST:Party",
                "#EXTINF:306,Bomfunk MC's - Freestyler",
                "/storage/emulated/0/Music/Freestyler.mp3",
            ),
            text.trim().lines(),
        )
    }

    @Test
    fun encodeFallsBackToFolderAndTitleWithoutARealPath() {
        val text = encodeM3u(name = "Party", tracks = listOf(track), paths = emptyMap())
        assertEquals("Music/Freestyler", text.trim().lines().last())
    }

    @Test
    fun playlistNameRoundTrips() {
        val text = encodeM3u(name = "Party", tracks = listOf(track), paths = emptyMap())
        assertEquals("Party", parseM3uName(text))
        assertNull(parseM3uName("#EXTM3U\n/just/a/path.mp3"))
    }

    @Test
    fun parseReadsExtinfAndPathsAndIgnoresBlankLines() {
        val entries = parseM3u(
            """
            #EXTM3U

            #EXTINF:306,Bomfunk MC's - Freestyler
            /music/Freestyler.mp3
            #EXTINF:-1,No Artist Dash Title
            relative/dir/other song.flac
            /bare/path/NoExtinf.mp3
            """.trimIndent(),
        )
        assertEquals(3, entries.size)
        assertEquals("/music/Freestyler.mp3", entries[0].path)
        assertEquals("Bomfunk MC's", entries[0].artist)
        assertEquals("Freestyler", entries[0].title)
        assertNull(entries[1].artist)
        assertEquals("No Artist Dash Title", entries[1].title)
        assertNull(entries[2].title)
    }

    @Test
    fun parseRejectsTheWholePlaylistWhenItsEntryLimitIsExceeded() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(MAX_M3U_ENTRIES + 1) { index ->
                append("track-").append(index).append(".mp3\n")
            }
        }

        assertTrue(parseM3u(text).isEmpty())
    }

    @Test
    fun matchingPrefersFilenameStemThenExtinfTitle() {
        val other = TrackDescriptor(id = TrackId("2"), title = "Other Song", artist = "Someone")
        val library = listOf(track, other)

        val matched = matchM3uEntries(
            entries = parseM3u(
                """
                #EXTM3U
                #EXTINF:1,X - Y
                /anywhere/freestyler.mp3
                #EXTINF:1,Someone - Other Song
                /renamed/file123.flac
                #EXTINF:1,Unknown - Missing
                /gone/missing.mp3
                """.trimIndent(),
            ),
            library = library,
        )
        assertEquals(listOf(track.id, other.id), matched.filterNotNull().map { it.id })
        assertNull(matched[2])
    }

    @Test
    fun exactArtistAndTitleWinWhenSeveralTracksShareTheTitle() {
        val first = TrackDescriptor(
            id = TrackId("first"),
            title = "Intro",
            artist = "Artist A",
            durationMs = 61_000,
        )
        val intended = TrackDescriptor(
            id = TrackId("intended"),
            title = "Intro",
            artist = "Artist B",
            durationMs = 62_000,
        )

        val matched = matchM3uEntries(
            entries = parseM3u(
                """
                #EXTM3U
                #EXTINF:62,Artist B - Intro
                /a/path/intro.mp3
                """.trimIndent(),
            ),
            library = listOf(first, intended),
        )

        assertEquals(intended.id, matched.single()?.id)
    }

    @Test
    fun anAmbiguousBareTitleNeverPicksTheFirstLibraryRow() {
        val duplicates = listOf(
            TrackDescriptor(id = TrackId("a"), title = "Intro", artist = "Artist A"),
            TrackDescriptor(id = TrackId("b"), title = "Intro", artist = "Artist B"),
        )

        val matched = matchM3uEntries(
            entries = parseM3u("#EXTM3U\n/somewhere/intro.mp3"),
            library = duplicates,
        )

        assertNull(matched.single())
    }

    @Test
    fun aWholeFilenameStemTitleWinsBeforeArtistTitleFilenameInterpretation() {
        val wholeTitle = TrackDescriptor(
            id = TrackId("whole-title"),
            title = "Artist - Song",
        )
        val conventionPair = TrackDescriptor(
            id = TrackId("artist-title-pair"),
            title = "Song",
            artist = "Artist",
        )

        val matched = matchM3uEntries(
            entries = parseM3u("#EXTM3U\nArtist - Song.mp3"),
            library = listOf(conventionPair, wholeTitle),
        )

        assertEquals(wholeTitle.id, matched.single()?.id)
    }

    @Test
    fun exactDurationConservativelyDisambiguatesIdenticalTags() {
        val short = TrackDescriptor(
            id = TrackId("short"),
            title = "Live",
            artist = "Band",
            durationMs = 181_900,
        )
        val long = TrackDescriptor(
            id = TrackId("long"),
            title = "Live",
            artist = "Band",
            durationMs = 245_100,
        )

        val matched = matchM3uEntries(
            entries = parseM3u(
                """
                #EXTM3U
                #EXTINF:245,Band - Live
                renamed-file.flac
                """.trimIndent(),
            ),
            library = listOf(short, long),
        )

        assertEquals(long.id, matched.single()?.id)
    }

    @Test
    fun ambiguousExactTagsDoNotFallThroughToAnUnrelatedSameTitleTrack() {
        val library = listOf(
            TrackDescriptor(
                id = TrackId("band-a"),
                title = "Live",
                artist = "Band",
                durationMs = 180_000,
            ),
            TrackDescriptor(
                id = TrackId("band-b"),
                title = "Live",
                artist = "Band",
                durationMs = 180_000,
            ),
            TrackDescriptor(
                id = TrackId("unrelated"),
                title = "Live",
                artist = "Someone Else",
                durationMs = 245_000,
            ),
        )

        val matched = matchM3uEntries(
            entries = parseM3u("#EXTINF:245,Band - Live\nLive.mp3"),
            library = library,
        )

        assertNull(matched.single())
    }

    @Test
    fun metadataLessTrackUsesItsPlayableUriAndRoundTrips() {
        val local = TrackDescriptor(
            id = TrackId("local"),
            audioUri = "content://media/external/audio/media/42",
        )

        val encoded = encodeM3u("Untitled", listOf(local), emptyMap())
        assertEquals(local.audioUri, encoded.trim().lines().last())
        assertEquals(local.id, matchM3uEntries(parseM3u(encoded), listOf(local)).single()?.id)
    }

    @Test
    fun metadataLessTrackKeepsItsRealPathAndRoundTripsThroughAPrivateIdentityHint() {
        val local = TrackDescriptor(
            id = TrackId("opaque/pathless 🎧"),
            audioUri = "content://media/external/audio/media/42",
        )
        val realPath = "/storage/emulated/0/Music/unknown-file.bin"

        val encoded = encodeM3u(
            name = "Untitled",
            tracks = listOf(local),
            paths = mapOf(local.id to realPath),
        )
        val lines = encoded.trim().lines()

        assertEquals(realPath, lines.last(), "Other M3U players must retain the real locator")
        assertTrue(lines[lines.lastIndex - 1].startsWith("#LATENTJAM-TRACK-ID:"))
        assertEquals(local.id, parseM3u(encoded).single().localTrackIdHint)
        assertEquals(local.id, matchM3uEntries(parseM3u(encoded), listOf(local)).single()?.id)
    }

    @Test
    fun unknownPrivateIdentityHintFallsBackToOrdinarySafeMatching() {
        val known = TrackDescriptor(
            id = TrackId("known"),
            title = "Freestyler",
            artist = "Bomfunk MC's",
        )
        val encodedUnknownId = "missing".encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

        val entries = parseM3u(
            "#EXTINF:306,Bomfunk MC's - Freestyler\n" +
                "#LATENTJAM-TRACK-ID:$encodedUnknownId\n" +
                "/elsewhere/freestyler.mp3",
        )

        assertEquals(known.id, matchM3uEntries(entries, listOf(known)).single()?.id)
    }

    @Test
    fun metadataLessTrackWithoutUriUsesALosslessOpaqueIdLocator() {
        // A folder by itself is not a media locator. With no title/file/URI, the opaque id is the
        // only row that can represent this exact track without pretending the folder is a song.
        val local = TrackDescriptor(
            id = TrackId("opaque/\nUnicode 🎧"),
            folderPath = "Downloads",
        )

        val encoded = encodeM3u("Untitled", listOf(local), emptyMap())
        val locator = encoded.trim().lines().last()

        assertTrue(locator.startsWith("latentjam:track-id:"))
        assertEquals(4, encoded.trim().lines().size)
        assertEquals(local.id, matchM3uEntries(parseM3u(encoded), listOf(local)).single()?.id)
    }
}

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

        // The first label names no artist and agrees on the length; one saying "X - Y", 1 s would
        // contradict the 306-second Bomfunk MC's track, and the stem would not override it.
        val matched = matchM3uEntries(
            entries = parseM3u(
                """
                #EXTM3U
                #EXTINF:306,Y
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

    /** 20 seconds by "Another Artist", with no file name: the iOS Music library's kind of track. */
    private val intro = TrackDescriptor(
        id = TrackId("intro"),
        title = "Intro",
        artist = "Another Artist",
        durationMs = 20_400,
    )

    private fun matchIds(m3u: String, library: List<TrackDescriptor>): List<String?> =
        matchM3uEntries(parseM3u(m3u), library).map { it?.id?.value }

    @Test
    fun noTagStageAcceptsATrackTheRowsDurationContradicts() {
        assertEquals(
            listOf(null, null, null, null),
            matchIds(
                """
                #EXTINF:200,Another Artist - Intro
                renamed-file.flac
                #EXTINF:200,Intro
                /x/Intro.m4a
                #EXTINF:200,Intro
                renamed-file.flac
                #EXTINF:200,Something
                /x/Another Artist - Intro.m4a
                """.trimIndent(),
                listOf(intro),
            ),
        )
    }

    @Test
    fun aTitleMatchIsRejectedWhenTheRowNamesAnotherArtist() {
        // -1 carries no duration, so only the artist speaks.
        assertEquals(
            listOf(null, null, null),
            matchIds(
                """
                #EXTINF:-1,Other Artist - Intro
                /x/Intro.m4a
                #EXTINF:-1,Other Artist - Intro
                renamed-file.flac
                #EXTINF:-1,Other Artist - Something
                /x/Another Artist - Intro.m4a
                """.trimIndent(),
                listOf(intro),
            ),
        )
    }

    @Test
    fun aTitleMatchStandsWhenTheRowAgreesOrSaysNothing() {
        assertEquals(
            listOf("intro", "intro", "intro", "intro"),
            matchIds(
                """
                #EXTINF:21,ANOTHER ARTIST - intro
                /x/Intro.m4a
                #EXTINF:-1,Intro
                renamed-file.flac
                /x/Intro.m4a
                #EXTINF:18,Another Artist - Something
                /x/Another Artist - Intro.m4a
                """.trimIndent(),
                listOf(intro),
            ),
        )
    }

    @Test
    fun aContradictedCandidateLetsALaterStageFindTheRightTrack() {
        val real = TrackDescriptor(id = TrackId("real"), title = "Real Song", artist = "Band", durationMs = 200_300)
        // The file name says "Intro", but the label says "Real Song", 200 seconds.
        assertEquals(listOf("real"), matchIds("#EXTINF:200,Real Song\n/x/Intro.m4a", listOf(intro, real)))
    }

    @Test
    fun decomposedRowsMatchComposedTagsAndTheArtistCheckAgrees() {
        // "o\u0301" is decomposed (NFD, a file name from a Mac), "\u00f3" composed (NFC).
        val joga = TrackDescriptor(
            id = TrackId("joga"),
            title = "J\u00f3ga",
            artist = "Bj\u00f6rk",
            durationMs = 305_000,
        )
        assertEquals(
            listOf("joga", "joga", "joga", "joga"),
            matchIds(
                "#EXTINF:305,Bjo\u0308rk - Jo\u0301ga\nrenamed-file.flac\n" +
                    "/Users/me/Music/Jo\u0301ga.m4a\n" +
                    "#EXTINF:-1,Bjo\u0308rk - Something\n/Users/me/Music/Jo\u0301ga.m4a\n" +
                    "#EXTINF:-1,Jo\u0301ga\nrenamed-file.flac",
                listOf(joga),
            ),
        )
    }

    @Test
    fun theNormaliserComposesWhatIsDecomposedAndKeepsTheRest() {
        assertEquals("\u00e9", nfcNormalized("e\u0301"))
        assertEquals("\uac00", nfcNormalized("\u1100\u1161"))
        assertEquals(
            "Bj\u00f6rk \u2013 J\u00f3ga \uD83C\uDFA7",
            nfcNormalized("Bjo\u0308rk \u2013 Jo\u0301ga \uD83C\uDFA7"),
        )
        assertEquals("plain ascii", nfcNormalized("plain ascii"))
    }

    @Test
    fun composedRowsMatchDecomposedTags() {
        val hoppipolla = TrackDescriptor(
            id = TrackId("hoppipolla"),
            title = "Hoppi\u0301polla",
            artist = "Sigur Ro\u0301s",
            durationMs = 268_000,
        )
        assertEquals(
            listOf("hoppipolla", "hoppipolla"),
            matchIds(
                "#EXTINF:268,Sigur R\u00f3s - Hopp\u00edpolla\nrenamed-file.flac\n/x/Hopp\u00edpolla.mp3",
                listOf(hoppipolla),
            ),
        )
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

    @Test
    fun relativePathClimbsFromThePlaylistFolderToASiblingFolder() {
        assertEquals(
            "../Unsorted/track.mp3",
            relativeM3uPath(
                playlistDirectory = "/storage/emulated/0/Music/Playlists",
                target = "/storage/emulated/0/Music/Unsorted/track.mp3",
            ),
        )
    }

    @Test
    fun relativePathInThePlaylistsOwnFolderIsTheFileName() {
        assertEquals(
            "track.mp3",
            relativeM3uPath("/storage/emulated/0/Music", "/storage/emulated/0/Music/track.mp3"),
        )
        assertEquals(
            "Album/track.mp3",
            relativeM3uPath("/storage/emulated/0/Music", "/storage/emulated/0/Music/Album/track.mp3"),
        )
    }

    @Test
    fun relativePathClimbsAsManyFoldersAsThePlaylistIsDeep() {
        assertEquals(
            "../Music/a/b.flac",
            relativeM3uPath("/storage/emulated/0/Download", "/storage/emulated/0/Music/a/b.flac"),
        )
        assertEquals(
            "../../../Music/b.flac",
            relativeM3uPath("/storage/emulated/0/Documents/x/y", "/storage/emulated/0/Music/b.flac"),
        )
    }

    @Test
    fun pathsOnDifferentVolumesKeepTheirAbsolutePath() {
        assertNull(
            relativeM3uPath("/storage/emulated/0/Music/Playlists", "/storage/1234-ABCD/Music/track.mp3"),
        )
        assertNull(relativeM3uPath("/sdcard/Playlists", "/storage/emulated/0/Music/track.mp3"))
        assertNull(relativeM3uPath("/", "/storage/emulated/0/Music/track.mp3"))
    }

    @Test
    fun onlyTwoAbsolutePathsHaveARelativePath() {
        assertNull(relativeM3uPath("storage/emulated/0/Music", "/storage/emulated/0/Music/track.mp3"))
        assertNull(relativeM3uPath("/storage/emulated/0/Music", "Music/track.mp3"))
        assertNull(relativeM3uPath("", "/storage/emulated/0/Music/track.mp3"))
        assertNull(relativeM3uPath("/storage/emulated/0/Music", ""))
    }

    @Test
    fun dotSegmentsAndRepeatedSlashesAreResolvedFirst() {
        assertEquals(
            "../Unsorted/track.mp3",
            relativeM3uPath(
                playlistDirectory = "/storage/emulated/0/Music/./Playlists//",
                target = "/storage//emulated/0/Music/Rock/../Unsorted/./track.mp3",
            ),
        )
        assertEquals(
            "track.mp3",
            relativeM3uPath("/storage/emulated/0/Music/Playlists/..", "/../storage/emulated/0/Music/track.mp3"),
        )
    }

    @Test
    fun aTrailingSlashOnTheFolderChangesNothing() {
        assertEquals(
            relativeM3uPath("/storage/emulated/0/Music/Playlists", "/storage/emulated/0/Music/Unsorted/track.mp3"),
            relativeM3uPath("/storage/emulated/0/Music/Playlists/", "/storage/emulated/0/Music/Unsorted/track.mp3"),
        )
    }

    @Test
    fun theFolderItselfOrOneAboveItIsNoFileToPointAt() {
        assertNull(relativeM3uPath("/storage/emulated/0/Music", "/storage/emulated/0/Music"))
        assertNull(relativeM3uPath("/storage/emulated/0/Music/Playlists", "/storage/emulated/0/Music"))
    }

    @Test
    fun encodeWritesRelativePathsWhereItCanAndKeepsEveryOtherRowAsBefore() {
        val onSdCard = TrackDescriptor(id = TrackId("2"), title = "Elsewhere", artist = "Someone")
        val pathless = TrackDescriptor(id = TrackId("3"), title = "Imported", folderPath = "Inbox")
        val text = encodeM3u(
            name = "Party",
            tracks = listOf(track, onSdCard, pathless),
            paths = mapOf(
                track.id to "/storage/emulated/0/Music/Unsorted/Freestyler.mp3",
                onSdCard.id to "/storage/1234-ABCD/Music/Elsewhere.mp3",
            ),
            relativeTo = "/storage/emulated/0/Music/Playlists",
        )
        assertEquals(
            listOf(
                "#EXTM3U",
                "#PLAYLIST:Party",
                "#EXTINF:306,Bomfunk MC's - Freestyler",
                "../Unsorted/Freestyler.mp3",
                "#EXTINF:-1,Someone - Elsewhere",
                "/storage/1234-ABCD/Music/Elsewhere.mp3",
                "#EXTINF:-1,Imported",
                "Inbox/Imported",
            ),
            text.trim().lines(),
        )
    }

    @Test
    fun encodeWithoutAPlaylistFolderWritesTheSameAbsolutePathsAsBefore() {
        val paths = mapOf(track.id to "/storage/emulated/0/Music/Freestyler.mp3")
        assertEquals(
            encodeM3u("Party", listOf(track), paths),
            encodeM3u("Party", listOf(track), paths, relativeTo = null),
        )
    }

    @Test
    fun aRelativeNameThatWouldReadAsACommentIsAnchoredToItsFolder() {
        val hit = TrackDescriptor(id = TrackId("hit"), title = "Number One", artist = "Band")
        val encoded = encodeM3u(
            name = "Charts",
            tracks = listOf(hit),
            paths = mapOf(hit.id to "/storage/emulated/0/Music/#1 Hit.mp3"),
            relativeTo = "/storage/emulated/0/Music",
        )

        assertEquals("./#1 Hit.mp3", encoded.trim().lines().last())
        assertEquals("./#1 Hit.mp3", parseM3u(encoded).single().path)
    }

    @Test
    fun relativeRowsComeBackOnImport() {
        val untitled = TrackDescriptor(
            id = TrackId("untitled"),
            audioUri = "content://media/external/audio/media/7",
        )
        val encoded = encodeM3u(
            name = "Party",
            tracks = listOf(track, untitled),
            paths = mapOf(
                track.id to "/storage/emulated/0/Music/Unsorted/Freestyler.mp3",
                untitled.id to "/storage/emulated/0/Music/Unsorted/unknown-file.bin",
            ),
            relativeTo = "/storage/emulated/0/Music/Playlists",
        )

        val lines = encoded.trim().lines()
        assertEquals("../Unsorted/unknown-file.bin", lines.last())
        assertTrue(lines[lines.lastIndex - 1].startsWith("#LATENTJAM-TRACK-ID:"))
        assertEquals(
            listOf(track.id, untitled.id),
            matchM3uEntries(parseM3u(encoded), listOf(untitled, track)).map { it?.id },
        )
    }
}

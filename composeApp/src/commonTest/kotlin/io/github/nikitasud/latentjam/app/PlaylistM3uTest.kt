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
}

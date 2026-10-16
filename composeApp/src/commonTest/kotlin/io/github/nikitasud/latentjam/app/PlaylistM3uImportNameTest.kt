/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PlaylistM3uImportNameTest {
    private val track = TrackDescriptor(
        id = TrackId("song"),
        audioUri = "content://media/external/audio/media/42",
    )

    @Test
    fun embeddedNameWinsOverTheSelectedFileName() = runTest {
        val imported = readPlaylist("Other file.m3u8", "\uFEFF#EXTM3U\n#PLAYLIST:  Road trip  \n")

        assertEquals("Road trip", imported.name)
    }

    @Test
    fun plainAndExtendedPlaylistsUseTheFileNameWithoutEitherM3uExtension() = runTest {
        for ((fileName, expected) in listOf(
            "Вечер.2026.m3u" to "Вечер.2026",
            "Вечер.2026.M3U8" to "Вечер.2026",
            "Road trip.m3U" to "Road trip",
            "Road trip.M3u8" to "Road trip",
            // #21: emoji names from a phone's file manager.
            "2000s rock💥💥.m3u" to "2000s rock💥💥",
        )) {
            for (header in listOf("", "#EXTM3U\n")) {
                val imported = readPlaylist(fileName, header)
                assertEquals(expected, imported.name)
                assertEquals(listOf(track), imported.tracks)
                assertEquals(1, imported.matchedEntryCount)
            }
        }
    }

    @Test
    fun blankEmbeddedNameFallsBackToTheSelectedFileName() = runTest {
        assertEquals("Road trip", readPlaylist("Road trip.m3u8", "#EXTM3U\n#PLAYLIST:   \n").name)
    }

    @Test
    fun providerNamesContainingPathsUseOnlyTheBaseName() = runTest {
        assertEquals("Road trip", readPlaylist("/Playlists/Road trip.m3u8").name)
        assertEquals("Road trip", readPlaylist("C:\\Playlists\\Road trip.m3u").name)
    }

    @Test
    fun otherSuffixesAndDotsRemainPartOfThePlaylistName() = runTest {
        for (fileName in listOf("Road.trip", "Road.trip.txt", "Road.trip.m3u8.bak", "Road trip")) {
            assertEquals(fileName, readPlaylist(fileName).name)
        }
        assertEquals("Road trip", readPlaylist("  Road trip .m3u8  ").name)
    }

    @Test
    fun missingOrEmptyFileNamesLeaveTheLocalizedDefaultToTheCaller() = runTest {
        for (fileName in listOf(null, "", "   ", ".M3U", ".m3u8", "/Playlists/")) {
            assertNull(readPlaylist(fileName).name)
        }
    }

    private suspend fun readPlaylist(fileName: String?, header: String = "#EXTM3U\n"): M3uImport = importM3u(
        text = header + track.audioUri,
        library = listOf(track),
        displayName = fileName,
        filePaths = { emptyMap() },
    )
}

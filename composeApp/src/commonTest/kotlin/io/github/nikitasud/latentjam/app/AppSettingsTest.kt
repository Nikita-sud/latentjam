/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppSettingsTest {

    @Test
    fun `start page has stable persisted values and a safe fallback`() {
        assertEquals(StartPage.FOR_YOU, startPageFromPersisted("for_you"))
        assertEquals(StartPage.MAP, startPageFromPersisted("map"))
        assertEquals(StartPage.PLAYLISTS, startPageFromPersisted("playlists"))
        assertEquals(StartPage.TRACKS, startPageFromPersisted("tracks"))
        assertEquals(StartPage.ALBUMS, startPageFromPersisted("albums"))
        assertEquals(StartPage.ARTISTS, startPageFromPersisted("artists"))
        assertEquals(StartPage.GENRES, startPageFromPersisted("genres"))
        assertEquals(StartPage.FOLDERS, startPageFromPersisted("folders"))
        assertEquals(StartPage.TRACKS, startPageFromPersisted(null))
        assertEquals(StartPage.TRACKS, startPageFromPersisted("renamed-or-corrupt"))
    }

    @Test
    fun `track colour mode has stable persisted values and a safe fallback`() {
        assertEquals(TrackColorMode.DYNAMIC, trackColorModeFromPersisted("dynamic"))
        assertEquals(TrackColorMode.SMART, trackColorModeFromPersisted("smart"))
        assertEquals(TrackColorMode.THEME, trackColorModeFromPersisted("theme"))
        assertEquals(TrackColorMode.DYNAMIC, trackColorModeFromPersisted(null))
        assertEquals(TrackColorMode.DYNAMIC, trackColorModeFromPersisted("renamed-or-corrupt"))
    }

    @Test
    fun `queue length keeps supported values unchanged`() {
        assertEquals(listOf(10, 20, 40), SMART_QUEUE_LENGTH_OPTIONS)
        SMART_QUEUE_LENGTH_OPTIONS.forEach { option ->
            assertEquals(option, sanitizeSmartQueueLength(option))
        }
    }

    @Test
    fun `queue length chooses nearest supported size and prefers default on a tie`() {
        assertEquals(10, sanitizeSmartQueueLength(Int.MIN_VALUE))
        assertEquals(10, sanitizeSmartQueueLength(14))
        assertEquals(DEFAULT_SMART_QUEUE_LENGTH, sanitizeSmartQueueLength(15))
        assertEquals(DEFAULT_SMART_QUEUE_LENGTH, sanitizeSmartQueueLength(30))
        assertEquals(40, sanitizeSmartQueueLength(31))
        assertEquals(40, sanitizeSmartQueueLength(Int.MAX_VALUE))
    }

    @Test
    fun `missing persisted queue length uses the default`() {
        assertEquals(DEFAULT_SMART_QUEUE_LENGTH, smartQueueLengthFromPersisted(null))
    }

    @Test
    fun `missing privacy preference preserves the historical opt-in default`() {
        assertEquals(true, recordingPreferenceFromPersisted(null))
        assertEquals(true, recordingPreferenceFromPersisted(true))
        assertEquals(false, recordingPreferenceFromPersisted(false))
    }

    @Test
    fun `novelty mixes require an explicit persisted opt-in`() {
        assertEquals(false, noveltyMixPreferenceFromPersisted(null))
        assertEquals(true, noveltyMixPreferenceFromPersisted(true))
        assertEquals(false, noveltyMixPreferenceFromPersisted(false))
    }

    @Test
    fun `resume queue codec round trips opaque live and source ids atomically`() {
        val state = ResumeQueueState(
            queueTrackIds = listOf("plain", "imported,file|with:delimiters", "音楽/曲"),
            sourceQueueTrackIds = listOf("playlist,first", "playlist:second|tail"),
            queueIndex = 1,
            sourceQueuePersisted = true,
        )

        assertEquals(state, decodeResumeQueueState(encodeResumeQueueState(state)))
    }

    @Test
    fun `resume queue codec distinguishes a known empty source from legacy absence`() {
        val knownEmpty = ResumeQueueState(
            queueTrackIds = listOf("generated-current"),
            sourceQueueTrackIds = emptyList(),
            queueIndex = 0,
            sourceQueuePersisted = true,
        )
        val legacyAbsent = knownEmpty.copy(sourceQueuePersisted = false)

        assertEquals(knownEmpty, decodeResumeQueueState(encodeResumeQueueState(knownEmpty)))
        assertEquals(legacyAbsent, decodeResumeQueueState(encodeResumeQueueState(legacyAbsent)))
    }

    @Test
    fun `resume queue codec rejects legacy versions and truncated payloads`() {
        assertNull(decodeResumeQueueState("a,b,c"))
        assertNull(decodeResumeQueueState("LJQ2|0|1|4:abc"))
        assertNull(decodeResumeQueueState("LJQ2|0|10001||1|0|"))
        assertNull(decodeResumeQueueState("LJQ2|0|0||0|1|1:a"))
    }

    @Test
    fun `omitted oversized source reconstructs tracks or stable user playlist`() {
        val a = TrackDescriptor(TrackId("a"))
        val b = TrackDescriptor(TrackId("b"))
        val library = listOf(a, b)

        assertEquals(
            library,
            resolveResumeSourceQueue(
                saved = ResumePlayback(
                    trackId = "a",
                    shuffleMode = "SMART",
                    positionMs = 0,
                    sourceKind = QueueSourceKind.TRACKS.name,
                ),
                library = library,
                playlists = emptyList(),
            ),
        )
        assertEquals(
            listOf(b),
            resolveResumeSourceQueue(
                saved = ResumePlayback(
                    trackId = "a",
                    shuffleMode = "SMART",
                    positionMs = 0,
                    sourceKind = QueueSourceKind.COLLECTION.name,
                    sourceReference = "playlist-id",
                ),
                library = library,
                playlists = listOf(
                    Playlist(id = "playlist-id", name = "Huge", trackIds = listOf("b")),
                ),
            ),
        )
        assertEquals(
            emptyList(),
            resolveResumeSourceQueue(
                saved = ResumePlayback(
                    trackId = "a",
                    shuffleMode = "SMART",
                    positionMs = 0,
                    sourceKind = QueueSourceKind.COLLECTION.name,
                    sourceReference = "deleted-playlist",
                ),
                library = library,
                playlists = emptyList(),
            ),
        )
        assertNull(
            resolveResumeSourceQueue(
                saved = ResumePlayback(
                    trackId = "a",
                    shuffleMode = "SMART",
                    positionMs = 0,
                    sourceKind = QueueSourceKind.COLLECTION.name,
                    sourceReference = "temporarily-unreadable",
                ),
                library = library,
                playlists = emptyList(),
                playlistsAvailable = false,
            ),
        )
    }

    @Test
    fun `missing legacy live queue never disguises a source as a smart future`() {
        val current = TrackDescriptor(TrackId("current"))
        val a = TrackDescriptor(TrackId("a"))
        val b = TrackDescriptor(TrackId("b"))
        val source = listOf(a, current, b)

        assertEquals(
            ResumeFallbackQueue(listOf(current), 0),
            fallbackResumeQueue(ShuffleMode.SMART, current, source),
        )
        assertEquals(
            ResumeFallbackQueue(listOf(current, b, a), 0),
            fallbackResumeQueue(ShuffleMode.ON, current, source) { it.reversed() },
        )
        assertEquals(
            ResumeFallbackQueue(source, 1),
            fallbackResumeQueue(ShuffleMode.OFF, current, source),
        )
    }
}

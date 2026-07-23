/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    @Test
    fun `start page has stable persisted values and a safe fallback`() {
        assertEquals(StartPage.FOR_YOU, startPageFromPersisted("for_you"))
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
}

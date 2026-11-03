/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalPlaylistRequestTest {

    @Test
    fun statedPlaylistTypesArePlaylistsWhateverTheName() {
        for (type in listOf(
            "audio/x-mpegurl",
            "audio/mpegurl",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "Audio/X-MpegURL",
            "audio/x-mpegurl; charset=utf-8",
        )) {
            assertTrue(isPlaylistRequest(type, "content://media/external/file/114"), type)
        }
    }

    @Test
    fun aStatedTrackTypeIsATrackEvenWithAPlaylistName() {
        assertFalse(isPlaylistRequest("audio/mpeg", "content://downloads/mix.m3u"))
        assertFalse(isPlaylistRequest("audio/flac", "file:///sdcard/Music/a.flac"))
        assertFalse(isPlaylistRequest("application/ogg", "content://media/external/audio/media/9"))
        assertFalse(isPlaylistRequest("application/ogg", "content://x/odd.m3u"))
        assertFalse(isPlaylistRequest("application/x-flac", "file:///sdcard/odd.m3u8"))
    }

    @Test
    fun withoutAUsefulTypeTheNameDecides() {
        assertTrue(isPlaylistRequest(null, "file:///sdcard/Download/ep.m3u"))
        assertTrue(isPlaylistRequest("", "content://x/Road%20Trip.M3U8"))
        // The VIEW filter does not admit octet-stream today; the rule still reads the name.
        assertTrue(isPlaylistRequest("application/octet-stream", "content://x/list.m3u?ts=1"))
        assertTrue(isPlaylistRequest("audio/*", "content://x/list.m3u#top"))
        assertFalse(isPlaylistRequest(null, "content://media/external/audio/media/42"))
        assertFalse(isPlaylistRequest(null, "file:///sdcard/Music/song.mp3"))
        assertFalse(isPlaylistRequest(null, "content://x/notes.m3u.txt"))
    }
}

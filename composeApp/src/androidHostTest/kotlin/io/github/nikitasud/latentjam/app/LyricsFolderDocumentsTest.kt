/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A wrong id here means a granted folder silently never yields its lyrics — or, worse, a lookup
 * outside the grant, which the provider answers with the SecurityException that drops the grant.
 */
class LyricsFolderDocumentsTest {

    @Test
    fun primaryStorageMapsToPrimary() {
        assertEquals(
            "primary:Music/Album/Song.mp3",
            externalStorageDocumentId("/storage/emulated/0/Music/Album/Song.mp3"),
        )
    }

    @Test
    fun secondaryUserStorageIsThatUsersPrimary() {
        assertEquals("primary:Music/Song.mp3", externalStorageDocumentId("/storage/emulated/10/Music/Song.mp3"))
    }

    @Test
    fun sdCardMapsToItsUpperCaseVolumeId() {
        assertEquals("1A2B-3C4D:Music/Song.flac", externalStorageDocumentId("/storage/1a2b-3c4d/Music/Song.flac"))
        assertEquals("1A2B-3C4D:Song.flac", externalStorageDocumentId("/storage/1A2B-3C4D/Song.flac"))
    }

    @Test
    fun pathsTheProviderDoesNotServeHaveNoId() {
        assertNull(externalStorageDocumentId("/data/user/0/app/files/Song.mp3"))
        assertNull(externalStorageDocumentId("/storage/emulated/legacy/Song.mp3"))
        assertNull(externalStorageDocumentId("/storage/emulated/0"))
        assertNull(externalStorageDocumentId("/storage/self/primary/Song.mp3"))
        assertNull(externalStorageDocumentId("/storage/1A2B-3C4D"))
        assertNull(externalStorageDocumentId("storage/emulated/0/Song.mp3"))
    }

    @Test
    fun aTreeCoversItsOwnFolder() {
        assertEquals(
            listOf("primary:Music/Song.lrc", "primary:Music/Song.LRC"),
            sidecarDocumentIds("primary:Music", "primary:Music/Song.mp3"),
        )
    }

    @Test
    fun aTreeCoversFoldersBelowIt() {
        assertEquals(
            listOf("primary:Music/Album/Song.lrc", "primary:Music/Album/Song.LRC"),
            sidecarDocumentIds("primary:Music", "primary:Music/Album/Song.mp3"),
        )
    }

    @Test
    fun aWholeVolumeTreeCoversEveryFolderOnIt() {
        assertEquals(
            listOf("primary:Music/Song.lrc", "primary:Music/Song.LRC"),
            sidecarDocumentIds("primary:", "primary:Music/Song.mp3"),
        )
        assertEquals(
            listOf("1A2B-3C4D:Song.lrc", "1A2B-3C4D:Song.LRC"),
            sidecarDocumentIds("1A2B-3C4D:", "1A2B-3C4D:Song.flac"),
        )
    }

    @Test
    fun aTreeThatOnlySharesTheNamePrefixDoesNotMatch() {
        assertEquals(emptyList(), sidecarDocumentIds("primary:Music", "primary:MusicVideos/Song.mp3"))
    }

    @Test
    fun songsOutsideTheTreeDoNotMatch() {
        assertEquals(emptyList(), sidecarDocumentIds("primary:Music/Album", "primary:Music/Song.mp3"))
        assertEquals(emptyList(), sidecarDocumentIds("primary:Music", "1A2B-3C4D:Music/Song.mp3"))
        assertEquals(emptyList(), sidecarDocumentIds("primary:", "1A2B-3C4D:Song.mp3"))
        assertEquals(emptyList(), sidecarDocumentIds("primary:Music", "Song.mp3"))
    }
}

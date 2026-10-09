/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A wrong folder here writes relative paths that resolve to nothing, on every device. A null
 * only costs the listener absolute paths, so anything uncertain must come out null.
 */
class PlaylistExportFolderTest {

    private val primaryRoot = "/storage/emulated/0"

    @Test
    fun aDocumentOnThePhonesOwnStorageSitsUnderThePrimaryRoot() {
        assertEquals(
            "/storage/emulated/0/Music/Playlists",
            documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Playlists/Mix.m3u8", primaryRoot),
        )
        assertEquals(
            "/storage/emulated/0",
            documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "primary:Mix.m3u8", primaryRoot),
        )
    }

    @Test
    fun thePrimaryRootComesFromTheDeviceNotFromTheId() {
        assertEquals(
            "/storage/emulated/10/Music",
            documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Mix.m3u8", "/storage/emulated/10/"),
        )
        assertNull(documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "primary:Music/Mix.m3u8", ""))
    }

    @Test
    fun aDocumentOnAnSdCardSitsUnderItsVolume() {
        assertEquals(
            "/storage/1A2B-3C4D/Playlists",
            documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "1A2B-3C4D:Playlists/Mix.m3u8", primaryRoot),
        )
    }

    @Test
    fun idsThatAreNoPathOnTheStorageProviderHaveNoFolder() {
        assertNull(documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "primary:", primaryRoot))
        assertNull(documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "Mix.m3u8", primaryRoot))
        assertNull(documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, ":Mix.m3u8", primaryRoot))
        assertNull(documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, "home:Mix.m3u8", primaryRoot))
        assertNull(documentDirectoryPath(EXTERNAL_STORAGE_AUTHORITY, null, primaryRoot))
    }

    @Test
    fun aRawDownloadsIdCarriesItsOwnPath() {
        assertEquals(
            "/storage/emulated/0/Download",
            documentDirectoryPath(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/Mix.m3u8", primaryRoot),
        )
    }

    @Test
    fun otherDownloadsIdsHaveNoFolder() {
        assertNull(documentDirectoryPath(DOWNLOADS_AUTHORITY, "msf:1234", primaryRoot))
        assertNull(documentDirectoryPath(DOWNLOADS_AUTHORITY, "1234", primaryRoot))
        assertNull(documentDirectoryPath(DOWNLOADS_AUTHORITY, "raw:Download/Mix.m3u8", primaryRoot))
        assertNull(documentDirectoryPath(DOWNLOADS_AUTHORITY, "raw:/Mix.m3u8", primaryRoot))
    }

    @Test
    fun cloudAndOtherProvidersHaveNoFolder() {
        assertNull(
            documentDirectoryPath("com.google.android.apps.docs.storage", "doc=encoded=abc123", primaryRoot),
        )
        assertNull(documentDirectoryPath("com.android.providers.media.documents", "audio:42", primaryRoot))
        assertNull(documentDirectoryPath(null, "primary:Music/Mix.m3u8", primaryRoot))
    }
}

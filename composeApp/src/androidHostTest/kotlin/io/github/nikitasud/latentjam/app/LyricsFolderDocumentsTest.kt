/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    @Test
    fun aCandidatesFolderIsTheOneAChildrenQueryLists() {
        assertEquals("primary:Music/Album", parentDocumentId("primary:Music/Album/Song.lrc"))
        assertEquals("primary:", parentDocumentId("primary:Song.lrc"))
        assertEquals("1A2B-3C4D:", parentDocumentId("1A2B-3C4D:Song.LRC"))
    }

    @Test
    fun aListingFingerprintMatchesCandidatesIgnoringCaseAsTheProviderOpensThem() {
        val listing = sidecarListing(
            listOf(
                Triple("primary:Music/Song.Lrc", 12L, 100L),
                Triple("primary:Music/Other.lrc", 5L, 100L),
                Triple("primary:Music/Song.mp3", 9_000L, 100L),
            ),
        )
        val candidates = sidecarDocumentIds("primary:Music", "primary:Music/Song.mp3")
        assertEquals("primary:Music/Song.Lrc:12:100", listingFingerprint(candidates, listing))
        assertEquals("", listingFingerprint(sidecarDocumentIds("primary:Music", "primary:Music/None.mp3"), listing))
    }

    @Test
    fun aSiblingFingerprintFollowsTheLrcBesideTheSongWithoutReadingIt() {
        val folder = createTempDirectory("lrc").toFile()
        try {
            val song = File(folder, "Song.mp3").apply { writeBytes(ByteArray(64)) }
            assertEquals("", siblingFingerprint(song.path))
            val lrc = File(folder, "Song.lrc").apply { writeText("[00:01.00]one") }
            lrc.setLastModified(1_000_000L)
            val added = siblingFingerprint(song.path)
            assertNotEquals("", added)
            assertEquals(added, siblingFingerprint(song.path), "nothing changed")
            lrc.writeText("[00:01.00]one, and two")
            lrc.setLastModified(1_000_000L)
            assertNotEquals(added, siblingFingerprint(song.path), "a size change alone is seen")
            val resized = siblingFingerprint(song.path)
            lrc.setLastModified(2_000_000L)
            assertNotEquals(resized, siblingFingerprint(song.path), "a time change alone is seen")
            lrc.delete()
            assertEquals("", siblingFingerprint(song.path))
        } finally {
            folder.deleteRecursively()
        }
    }
}

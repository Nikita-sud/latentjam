/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaylistArtworkTest {
    private class FakeStore : PlaylistStore {
        var lines: List<String> = emptyList()
        var writes = 0
        var failWrites = false
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) {
            if (failWrites) error("disk full")
            this.lines = lines
            writes++
        }
    }

    @Test
    fun customCoverSurvivesRestartRenameMembershipAndOrderingChanges() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val original = playlists.create("Mix", listOf(TrackId("one")))
        playlists.create("Other")

        assertTrue(playlists.setArtwork(original.id, "b174a44e-3e93-42f5-a26a-0f4ad2577591.jpg"))
        playlists.rename(original.id, "Renamed")
        playlists.addTracks(original.id, listOf(TrackId("two")))
        playlists.removeTrack(original.id, TrackId("one"))
        playlists.toggleIncludeInSmart(original.id)
        playlists.move(original.id, 0)

        val restored = DefaultPlaylists(store).all().first()
        assertEquals(original.id, restored.id)
        assertEquals(original.createdAtMs, restored.createdAtMs)
        assertEquals("Renamed", restored.name)
        assertEquals(listOf("two"), restored.trackIds)
        assertTrue(restored.includeInSmart)
        assertEquals("b174a44e-3e93-42f5-a26a-0f4ad2577591.jpg", restored.customArtworkRef)
    }

    @Test
    fun restoringAutomaticCoverPersistsForAnEmptyPlaylist() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Empty")
        assertTrue(playlists.setArtwork(created.id, "cover.jpg"))
        assertEquals("cover.jpg", DefaultPlaylists(store).all().single().customArtworkRef)

        assertTrue(playlists.setArtwork(created.id, null))
        assertNull(DefaultPlaylists(store).all().single().customArtworkRef)
        assertTrue(playlists.all().single().trackIds.isEmpty())
    }

    @Test
    fun missingPlaylistAndUnchangedCoverDoNotWrite() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mix")
        playlists.setArtwork(created.id, "cover.jpg")
        val savedWrites = store.writes

        assertTrue(playlists.setArtwork(created.id, "cover.jpg"))
        assertFalse(playlists.setArtwork("deleted", "unused-cover.jpg"))
        assertEquals(savedWrites, store.writes)
        assertEquals("cover.jpg", playlists.all().single().customArtworkRef)
    }

    @Test
    fun failedCoverChangeOrResetLeavesMemoryAndDiskUntouched() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mix")
        playlists.setArtwork(created.id, "original.jpg")
        val savedLines = store.lines
        store.failWrites = true

        assertFailsWith<IllegalStateException> { playlists.setArtwork(created.id, "new.jpg") }
        assertFailsWith<IllegalStateException> { playlists.setArtwork(created.id, null) }

        assertEquals(savedLines, store.lines)
        assertEquals("original.jpg", playlists.all().single().customArtworkRef)
        assertEquals("original.jpg", DefaultPlaylists(store).all().single().customArtworkRef)
    }

    @Test
    fun cancellationAfterDurableWriteStillPublishesTheCommittedCover() = runTest {
        val durable = FakeStore()
        val committed = CompletableDeferred<Unit>()
        val returnFromWrite = CompletableDeferred<Unit>()
        var pauseAfterCommit = false
        val store = object : PlaylistStore {
            override suspend fun read(): List<String> = durable.read()
            override suspend fun write(lines: List<String>) {
                durable.write(lines)
                if (pauseAfterCommit) {
                    committed.complete(Unit)
                    returnFromWrite.await()
                }
            }
        }
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mix")
        pauseAfterCommit = true

        val caller = launch { playlists.setArtwork(created.id, "committed.jpg") }
        committed.await()
        caller.cancel()
        returnFromWrite.complete(Unit)
        caller.join()

        assertEquals("committed.jpg", playlists.all().single().customArtworkRef)
        assertEquals(playlists.all(), DefaultPlaylists(store).all())
    }

    @Test
    fun replacementKeepsCoverAndBlankReferencesRestoreAutomatic() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        playlists.replaceAll(listOf(Playlist(
            id = "restored",
            name = "Mix",
            trackIds = listOf("one", "one"),
            customArtworkRef = " cover.jpg ",
        )))
        val restored = DefaultPlaylists(store).all().single()
        assertEquals("cover.jpg", restored.customArtworkRef)
        assertEquals(listOf("one"), restored.trackIds)

        assertTrue(playlists.setArtwork(restored.id, "  "))
        assertNull(DefaultPlaylists(store).all().single().customArtworkRef)
    }

    @Test
    fun legacyFormatsContinueToUseAutomaticArtwork() {
        val separator = '\u001f'
        val v1 = listOf("legacy", "Old Mix", "7", "a,b")
        val v2 = listOf("v2", "6c6567616379", "4f6c64204d6978", "7", "61,62")
        val v3 = listOf("v3") + v2.drop(1) + "1"
        for (fields in listOf(v1, v2, v3)) {
            val parsed = requireNotNull(PlaylistSerializer.parse(fields.joinToString(separator.toString())))
            assertEquals("legacy", parsed.id)
            assertEquals("Old Mix", parsed.name)
            assertEquals(listOf("a", "b"), parsed.trackIds)
            assertNull(parsed.customArtworkRef)
            assertEquals(fields.first() == "v3", parsed.includeInSmart)
        }
    }

    @Test
    fun damagedOptionalCoverDoesNotDiscardPlaylistMembership() {
        val original = Playlist("saved", "Mix", listOf("one"), includeInSmart = true,
            customArtworkRef = "cover.jpg")
        val serialized = PlaylistSerializer.serialize(original)
        val damaged = serialized.replaceAfterLast('\u001f', "broken-hex")

        assertEquals(original.copy(customArtworkRef = null), PlaylistSerializer.parse(damaged))
        assertEquals(original.copy(customArtworkRef = null),
            PlaylistSerializer.parse(serialized.substringBeforeLast('\u001f')))
    }
}

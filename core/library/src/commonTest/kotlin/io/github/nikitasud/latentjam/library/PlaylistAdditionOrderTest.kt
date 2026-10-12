/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal class PlaylistAdditionOrderTest {
    private fun ids(vararg values: String) = values.map(::TrackId)
    private class Store(var lines: List<String> = emptyList()) : PlaylistStore {
        var fail = false
        override suspend fun read() = lines
        override suspend fun write(lines: List<String>) {
            if (fail) error("disk full")
            this.lines = lines
        }
    }

    @Test fun dragAndLaterAdditionsKeepTwoOrdersAcrossReloads() = runTest {
        val store = Store()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mix", ids("a", "b", "c"))
        assertTrue(playlists.replaceTracksIfUnchanged(created.id, ids("a", "b", "c"), ids("c", "a", "b")))
        val reloaded = DefaultPlaylists(store)
        reloaded.addTracks(created.id, ids("b", "d", "d", "e"))
        val saved = DefaultPlaylists(store).all().single()
        assertEquals(listOf("c", "a", "b", "d", "e"), saved.trackIds)
        assertEquals(listOf("a", "b", "c", "d", "e"), saved.addedTrackIds)
    }

    @Test fun removalUndoRestoresChronologyAndARealReadditionIsNew() = runTest {
        val store = Store()
        val playlists = DefaultPlaylists(store)
        playlists.replaceAll(listOf(Playlist("p", "Mix", listOf("c", "a", "b"), addedTrackIds = listOf("a", "b", "c"))))
        val before = playlists.all().single()
        val change = assertNotNull(playlists.removeTrack("p", TrackId("a")))
        assertEquals(listOf("b", "c"), playlists.all().single().addedTrackIds)
        assertTrue(playlists.restoreTracksIfUnchanged("p", change))
        assertEquals(before, DefaultPlaylists(store).all().single())
        playlists.removeTrack("p", TrackId("a"))
        playlists.addTracks("p", ids("a"))
        val after = DefaultPlaylists(store).all().single()
        assertEquals(listOf("c", "b", "a"), after.trackIds)
        assertEquals(listOf("b", "c", "a"), after.addedTrackIds)
        assertFalse(playlists.restoreTracksIfUnchanged("p", change))
    }

    @Test fun repeatedOccurrencesAndMissingTracksSurviveReorderAndUndo() = runTest {
        val store = Store()
        val playlists = DefaultPlaylists(store)
        playlists.replaceAll(listOf(Playlist("p", "Mix", listOf("a", "missing", "b", "a"))))
        assertTrue(playlists.replaceTracksIfUnchanged("p", ids("a", "missing", "b", "a"), ids("a", "a", "missing", "b")))
        val moved = playlists.all().single()
        assertEquals(listOf("a", "missing", "b", "a"), moved.addedTrackIds)
        val change = assertNotNull(playlists.removeTracks("p", ids("a", "missing")))
        assertTrue(playlists.restoreTracksIfUnchanged("p", change))
        assertEquals(moved, DefaultPlaylists(store).all().single())
    }

    @Test fun legacyStoresUseTheirExistingOrderAsTheChronologyBaseline() = runTest {
        val expected = Playlist("p", "Mix", listOf("a", "b", "a"), createdAtMs = 7)
        val modern = PlaylistSerializer.serialize(expected).split('\u001f')
        val legacy = listOf(
            listOf("p", "Mix", "7", "a,b,a").joinToString("\u001f"),
            modern.take(5).toMutableList().also { it[0] = "v2" }.joinToString("\u001f"),
            modern.take(6).toMutableList().also { it[0] = "v3" }.joinToString("\u001f"),
            modern.take(7).toMutableList().also { it[0] = "v4" }.joinToString("\u001f"),
        )
        for (line in legacy) {
            val store = Store(listOf(line))
            val playlists = DefaultPlaylists(store)
            assertEquals(expected, playlists.all().single())
            assertTrue(playlists.replaceTracksIfUnchanged("p", ids("a", "b", "a"), ids("b", "a", "a")))
            assertEquals(expected.addedTrackIds, DefaultPlaylists(store).all().single().addedTrackIds)
        }
    }

    @Test fun aFailedWriteAndStaleUndoCannotReplaceEitherOrder() = runTest {
        val store = Store()
        val playlists = DefaultPlaylists(store)
        playlists.replaceAll(listOf(Playlist("p", "Mix", listOf("c", "a", "b"), addedTrackIds = listOf("a", "b", "c"))))
        val before = playlists.all().single()
        store.fail = true
        assertFailsWith<IllegalStateException> { playlists.removeTrack("p", TrackId("a")) }
        assertEquals(before, playlists.all().single())
        assertEquals(before, DefaultPlaylists(store).all().single())
        store.fail = false
        val change = assertNotNull(playlists.removeTrack("p", TrackId("a")))
        val current = playlists.all().single()
        playlists.replaceAll(listOf(current.copy(addedTrackIds = current.addedTrackIds.reversed())))
        assertFalse(playlists.restoreTracksIfUnchanged("p", change))
        assertEquals(listOf("c", "b"), playlists.all().single().trackIds)
    }

    @Test fun storeRoundTripEscapesBothOrdersAndRepairsDamagedChronology() {
        val playlist = Playlist("p", "Mix", listOf("b,\u001f", "Кино", "Кино"),
            customArtworkRef = "cover.jpg", addedTrackIds = listOf("Кино", "b,\u001f", "Кино"))
        val encoded = PlaylistSerializer.serialize(playlist)
        assertEquals(playlist, PlaylistSerializer.parse(encoded))
        val fallback = assertNotNull(PlaylistSerializer.parse(encoded.replaceAfterLast('\u001f', "bad-hex")))
        assertEquals(playlist.trackIds, fallback.trackIds)
        assertEquals(playlist.trackIds, fallback.addedTrackIds)
        assertEquals(playlist.customArtworkRef, fallback.customArtworkRef)
        assertEquals(listOf("a", "b", "c", "a"),
            reconcilePlaylistAddedOrder(listOf("a", "unknown", "b", "b"), listOf("c", "a", "b", "a")))
    }
}

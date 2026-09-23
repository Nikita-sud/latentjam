/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal class PlaylistSmartOptInTest {

    private class FakeStore : PlaylistStore {
        var lines: List<String> = emptyList()
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) {
            this.lines = lines
        }
    }

    @Test
    fun toggleFlipsAndSurvivesReload() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mood")

        assertTrue(playlists.toggleIncludeInSmart(created.id))
        assertTrue(DefaultPlaylists(store).all().single().includeInSmart)

        assertFalse(playlists.toggleIncludeInSmart(created.id))
        assertFalse(DefaultPlaylists(store).all().single().includeInSmart)
    }

    @Test
    fun v2LinesReadAsNotOptedIn() = runTest {
        // A playlist saved before the flag existed must parse as opted OUT, not fail. Keep only
        // the original five fields so this fixture remains v2 as new optional fields are added.
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mood", trackIds = listOf(io.github.nikitasud.latentjam.smart.TrackId("a")))
        playlists.toggleIncludeInSmart(created.id)

        val separator = ''
        val v2Line = store.lines.single().split(separator).take(5).toMutableList()
            .apply { this[0] = "v2" }.joinToString(separator.toString())
        store.lines = listOf(v2Line)

        val reloaded = DefaultPlaylists(store).all().single()
        assertFalse(reloaded.includeInSmart)
        assertEquals(created.id, reloaded.id)
        assertEquals(listOf("a"), reloaded.trackIds)
    }

    @Test
    fun v3RejectsCorruptSmartFlag() {
        val valid = PlaylistSerializer.serialize(
            Playlist(id = "id", name = "Mood", includeInSmart = true),
        ).split('\u001f').take(6).toMutableList()
            .apply { this[0] = "v3" }.joinToString("\u001f")

        assertNull(PlaylistSerializer.parse(valid.replaceAfterLast('\u001f', "yes")))
    }
}

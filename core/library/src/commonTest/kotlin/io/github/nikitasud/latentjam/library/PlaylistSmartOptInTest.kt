/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // A playlist saved before the flag existed must parse as opted OUT, not fail. Recreate
        // a v2 line from the v3 one: drop the trailing flag field, restore the version tag.
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Mood", trackIds = listOf(io.github.nikitasud.latentjam.smart.TrackId("a")))
        playlists.toggleIncludeInSmart(created.id)

        val separator = ''
        val v3Line = store.lines.single()
        assertTrue(v3Line.startsWith("v3$separator"))
        val v2Line = "v2" + v3Line.removePrefix("v3").substringBeforeLast(separator)
        store.lines = listOf(v2Line)

        val reloaded = DefaultPlaylists(store).all().single()
        assertFalse(reloaded.includeInSmart)
        assertEquals(created.id, reloaded.id)
        assertEquals(listOf("a"), reloaded.trackIds)
    }
}

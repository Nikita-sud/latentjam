/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

internal class PlaylistsMoveTest {

    private class FakeStore : PlaylistStore {
        var lines: List<String> = emptyList()
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) {
            this.lines = lines
        }
    }

    @Test
    fun moveReordersAndPersists() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val a = playlists.create("A")
        val b = playlists.create("B")
        val c = playlists.create("C")
        // create() puts the newest first: [C, B, A].
        assertEquals(listOf(c.id, b.id, a.id), playlists.all().map { it.id })

        playlists.move(a.id, 0)
        assertEquals(listOf(a.id, c.id, b.id), playlists.all().map { it.id })

        // The order survives a fresh instance reading the same store.
        val reloaded = DefaultPlaylists(store)
        assertEquals(listOf(a.id, c.id, b.id), reloaded.all().map { it.id })
    }

    @Test
    fun outOfRangeTargetClampsAndUnknownIdIsANoOp() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val a = playlists.create("A")
        val b = playlists.create("B")

        playlists.move(b.id, 99)
        assertEquals(listOf(a.id, b.id), playlists.all().map { it.id })

        playlists.move("missing", 0)
        assertEquals(listOf(a.id, b.id), playlists.all().map { it.id })
    }
}

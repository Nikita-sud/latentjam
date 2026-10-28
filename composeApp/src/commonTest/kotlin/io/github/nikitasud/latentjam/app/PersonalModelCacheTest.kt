/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PersonalModelCacheTest {
    @Test
    fun aBuildThatDoesNotFitPlansWithoutThePersonalTermAndWaitsForMoreHistory() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val library = listOf("a", "b").map { TrackDescriptor(id = TrackId(it), artist = "Artist", title = it, durationMs = 200_000L) }
        suspend fun listen(at: Long) = history.record(ListenEvent(TrackId("a"), at, 200_000L, 200_000L, completed = true, skipped = false))
        repeat(8) { listen(it * 1_000_000L) }
        var fits = false
        var builds = 0
        val cache = PersonalModelCache(history) {
            builds++
            if (!fits) throw Error("out of memory") // as OutOfMemoryError, which common Kotlin cannot name
            listOf("artist")
        }

        assertNull(cache.model(library), "a heap too small: CORE, not a dead app")
        val tried = builds
        listen(9_000_000L)
        assertNull(cache.model(library), "an eighth more history is not tried again")
        assertEquals(tried, builds)

        fits = true
        listen(10_000_000L)
        assertNotNull(cache.model(library), "a quarter more history is tried again")
    }

    private class MemoryHistoryStore : HistoryStore {
        private val lines = mutableListOf<String>()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun replaceAll(lines: List<String>) {
            this.lines.clear()
            this.lines += lines
        }
        override suspend fun clear() { lines.clear() }
    }
}

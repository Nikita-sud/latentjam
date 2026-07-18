/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ListeningHistoryTest {

    private class FakeStore(initial: List<String> = emptyList()) : HistoryStore {
        val lines = initial.toMutableList()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun clear() { lines.clear() }
    }

    private fun event(
        id: String,
        startedAt: Long,
        played: Long = 60_000,
        completed: Boolean = false,
        skipped: Boolean = false,
    ) = ListenEvent(
        trackId = TrackId(id),
        startedAtMs = startedAt,
        playedMs = played,
        trackDurationMs = 200_000,
        completed = completed,
        skipped = skipped,
        shuffleMode = "SMART",
    )

    @Test
    fun serializationRoundTrips() {
        val original = event("42", startedAt = 1_234, completed = true)
        assertEquals(original, ListenEvent.parse(original.serialize()))
        // Null duration + null mode round-trip too.
        val bare = ListenEvent(TrackId("7"), 5, 10, null, false, true, null)
        assertEquals(bare, ListenEvent.parse(bare.serialize()))
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() = runTest {
        val store = FakeStore(
            initial = listOf(
                event("1", startedAt = 100).serialize(),
                "garbage|line",
                "v9|future|format|x|x|x|x|x",
                event("1", startedAt = 200, completed = true).serialize(),
            ),
        )
        val history = DefaultListeningHistory(store)
        val stats = history.stats()
        assertEquals(1, stats.size)
        assertEquals(2, stats[TrackId("1")]?.plays)
    }

    @Test
    fun statsAggregateAcrossEvents() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 100, played = 30_000, skipped = true))
        history.record(event("1", startedAt = 200, played = 190_000, completed = true))
        history.record(event("2", startedAt = 300, played = 60_000))

        val stats = history.stats()
        val one = stats[TrackId("1")]!!
        assertEquals(2, one.plays)
        assertEquals(1, one.completions)
        assertEquals(1, one.skips)
        assertEquals(220_000, one.totalPlayedMs)
        assertEquals(200, one.lastPlayedAtMs)
        assertEquals(1, stats[TrackId("2")]?.plays)
    }

    @Test
    fun recordPersistsAndReloadsThroughStore() = runTest {
        val store = FakeStore()
        DefaultListeningHistory(store).record(event("9", startedAt = 1))

        // Fresh instance over the same store = process restart.
        val reloaded = DefaultListeningHistory(store)
        assertEquals(1, reloaded.stats()[TrackId("9")]?.plays)
        assertEquals(TrackId("9"), reloaded.recentEvents(5).single().trackId)
    }

    @Test
    fun recentEventsNewestFirst() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 100))
        history.record(event("2", startedAt = 200))
        history.record(event("3", startedAt = 300))
        assertEquals(
            listOf("3", "2"),
            history.recentEvents(2).map { it.trackId.value },
        )
        assertNull(history.recentEvents(0).firstOrNull())
    }
}

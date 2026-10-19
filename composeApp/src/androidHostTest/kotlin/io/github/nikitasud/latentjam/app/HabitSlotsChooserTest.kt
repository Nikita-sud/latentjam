/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.SmartChoice
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Learn from my habits" in the chooser: personal slots only in a queue SMART starts from a seed. */
class HabitSlotsChooserTest {
    private fun track(id: String) = TrackDescriptor(TrackId(id), title = id, artist = id)

    private val seed = track("seed")
    private val habits = (1..6).map { track("habit-$it") }
    private val planned = (1..24).map { track("planned-$it") }
    private val library = listOf(seed) + habits + planned

    /** Three days ago (outside the novelty window): seed → habit-1 twice, then habit-2..6 once each, newest last. */
    private fun log(): List<ListenEvent> {
        val base = epochMillis() - 3L * 86_400_000L
        val oldestFirst = mutableListOf<ListenEvent>()
        var at = base
        fun play(track: TrackDescriptor, start: ListenStart?) {
            oldestFirst += ListenEvent(
                trackId = track.id, startedAtMs = at, playedMs = 180_000, trackDurationMs = 200_000,
                completed = true, skipped = false, listenedMs = 180_000, origin = ListenOrigin(start = start),
            )
            at += 4 * 60_000L
        }
        repeat(2) {
            play(seed, ListenStart.USER_PICK)
            play(habits[0], ListenStart.USER_PICK)
            at += 3_600_000L
        }
        habits.drop(1).forEach { play(it, ListenStart.USER_PICK); at += 3_600_000L }
        return oldestFirst
    }

    private fun history(events: List<ListenEvent>) = object : ListeningHistory {
        override suspend fun record(event: ListenEvent) = Unit
        override suspend fun stats(): Map<TrackId, TrackStats> = emptyMap()
        override suspend fun recentEvents(limit: Int): List<ListenEvent> = events.takeLast(limit).reversed()
        override suspend fun replace(events: List<ListenEvent>) = Unit
        override suspend fun clear() = Unit
    }

    private fun engine(): SimilarityEngine {
        var calls = 0
        return Proxy.newProxyInstance(
            SimilarityEngine::class.java.classLoader, arrayOf(SimilarityEngine::class.java),
        ) { _, method, _ ->
            check(method.name == "continueSmartQueue") { "Unexpected engine call: ${method.name}" }
            planned.drop(12 * calls++).take(12).map { it.id }
        } as SimilarityEngine
    }

    /** Topped up as both controllers do: the last row seeds, the rows before it are the recent window. */
    private suspend fun queue(enabled: Boolean, events: List<ListenEvent>, rows: Int): List<SmartChoice> {
        val chooser = EngineNextTrackChooser(
            engine(), history(events), learnFromHabits = { enabled }, library = { library },
        )
        val queue = mutableListOf(seed)
        val served = mutableListOf<SmartChoice>()
        repeat(rows) {
            val queued = queue.mapTo(HashSet()) { it.id }
            val choice = chooser.choose(
                queue.last(), queue.dropLast(1).takeLast(10).map { it.id }, library.filter { it.id !in queued },
            ) ?: return served
            queue += choice.track
            served += choice
        }
        return served
    }

    @Test
    fun `a queue started from a seed gives slots 1 3 5 7 9 to the habit list`() = runTest {
        val served = queue(enabled = true, events = log(), rows = 20).map { it.track.id.value }
        // habit-1 followed the seed twice; the rest follow by recency (ACT-R), newest first.
        val personal = listOf("habit-1", "habit-6", "habit-5", "habit-4", "habit-3")
        assertEquals(personal, listOf(0, 2, 4, 6, 8).map(served::get))
        assertEquals((1..12).map { "planned-$it" }, served.take(17).filterNot { it.startsWith("habit") })
        // The next plan tops up a running queue: SMART's own order, nothing personal.
        assertEquals((13..15).map { "planned-$it" }, served.drop(17))
    }

    @Test
    fun `turned off or without history the queue is SMART's own`() = runTest {
        val own = (1..12).map { "planned-$it" }
        assertEquals(own, queue(enabled = false, events = log(), rows = 12).map { it.track.id.value })
        assertEquals(own, queue(enabled = true, events = emptyList(), rows = 12).map { it.track.id.value })
    }

    @Test
    fun `plays that were not the listener's own picks teach no transition`() = runTest {
        val auto = log().map { event ->
            if (event.trackId == habits[0].id) event.copy(origin = ListenOrigin(ListenStart.AUTO_ADVANCE, parentId = "album:x")) else event
        }
        val listens = habitListens(auto, library.mapTo(HashSet()) { it.id }, epochMillis())
        val (byTrack, byArtist) = HabitSlots.transitionCounts(listens, seed.id) { it.value }
        assertTrue(byTrack.isEmpty() && byArtist.isEmpty())
        // habit-1 still ranks as a listen (two plays outweigh one newer play in ACT-R).
        val served = queue(enabled = true, events = auto, rows = 10).map { it.track.id.value }
        assertEquals(listOf("habit-1", "habit-6", "habit-5", "habit-4", "habit-3"), listOf(0, 2, 4, 6, 8).map(served::get))
    }
}

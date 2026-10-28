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
import io.github.nikitasud.latentjam.smart.HabitFlow
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
    // One genre family everywhere, no pack: P = (0 + 0.5 + 0.5) / 3 for every row.
    private fun track(id: String) = TrackDescriptor(TrackId(id), title = id, artist = id, genre = "Rock")

    private val seed = track("seed")
    private val habits = (1..6).map { track("habit-$it") }
    private val planned = (1..24).map { track("planned-$it") }
    private val library = listOf(seed) + habits + planned

    /**
     * Three days ago (outside the novelty window): seed → habit-1 [habitOneRepeats] times, seed → habit-2 once, then
     * habit-3..6 alone (frequent and recent, but never after the seed), newest last.
     */
    private fun log(habitOneRepeats: Int = 3): List<ListenEvent> {
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
        repeat(habitOneRepeats) {
            play(seed, ListenStart.USER_PICK)
            play(habits[0], ListenStart.USER_PICK)
            at += 3_600_000L
        }
        play(seed, ListenStart.USER_PICK)
        play(habits[1], ListenStart.USER_PICK)
        at += 3_600_000L
        habits.drop(2).forEach { play(it, ListenStart.USER_PICK); at += 3_600_000L }
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
        ) { _, method, args ->
            // The habit list asks the engine whose artist a track counts under: the display-string key, as without the artist list.
            if (method.name == "artistKeyOf") return@newProxyInstance io.github.nikitasud.latentjam.smart.HabitSlots.artistKey(args[0] as io.github.nikitasud.latentjam.smart.TrackDescriptor)
            check(method.name == "continueSmartQueue") { "Unexpected engine call: ${method.name}" }
            planned.drop(12 * calls++).take(12).map { it.id }
        } as SimilarityEngine
    }

    /** Topped up as both controllers do: the last row seeds, the rows before it are the recent window. */
    private suspend fun queue(
        enabled: Boolean,
        events: List<ListenEvent>,
        rows: Int,
        flow: HabitFlow? = HabitFlow(null),
    ): List<SmartChoice> {
        val chooser = EngineNextTrackChooser(
            engine(), history(events), learnFromHabits = { enabled }, library = { library }, habitFlow = flow,
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
    fun `a queue started from a seed gives its first slots to habits that outweigh their incoherence`() = runTest {
        val served = queue(enabled = true, events = log(), rows = 20).map { it.track.id.value }
        // habit-1 followed the seed three times: a habit strong enough for slot 1 (1 - 2/3 > 0 against its incoherence);
        // habit-2 followed it once: no habit; habit-3..6 never followed.
        assertEquals("habit-1", served[0])
        assertEquals((1..12).map { "planned-$it" }, served.subList(1, 13))
        // The next plan tops up a running queue: SMART's own order, nothing personal.
        assertEquals((13..19).map { "planned-$it" }, served.drop(13))
    }

    @Test
    fun `without a flow scorer nothing is placed`() = runTest {
        assertEquals((1..12).map { "planned-$it" }, queue(enabled = true, events = log(), rows = 12, flow = null).map { it.track.id.value })
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
        assertTrue(habits[0].id !in byTrack && habits[0].id !in byArtist)
        // habit-1 lost its transitions and its slot; habit-2 followed once, which is no habit.
        val served = queue(enabled = true, events = auto, rows = 10).map { it.track.id.value }
        assertEquals((1..10).map { "planned-$it" }, served)
    }

    @Test
    fun `a habit of two repeats gets a slot but the queue opens with SMART's pick`() = runTest {
        val served = queue(enabled = true, events = log(habitOneRepeats = 2), rows = 12).map { it.track.id.value }
        assertEquals("planned-1", served[0])
        assertEquals("habit-1", served[1])
    }

    @Test
    fun `a pick after skipped plays is no transition`() = runTest {
        val bridged = log().flatMap { event ->
            if (event.trackId != habits[0].id) listOf(event)
            else listOf(event.copy(trackId = planned[23].id, startedAtMs = event.startedAtMs - 1_000, skipped = true, completed = false), event)
        }
        val listens = habitListens(bridged, library.mapTo(HashSet()) { it.id }, epochMillis())
        val (byTrack, _) = HabitSlots.transitionCounts(listens, seed.id) { it.value }
        assertTrue(habits[0].id !in byTrack)
    }
}

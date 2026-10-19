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
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.ranker.RankerModel
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The learned ranker in the chooser: it plans instead of the engine, and leaves out what was just heard. */
class RankerChooserTest {
    private fun track(id: String, artist: String) = TrackDescriptor(TrackId(id), title = id, artist = artist)

    private val seed = track("seed", "A")
    private val library = listOf(track("a1", "A"), track("a2", "A"), track("b1", "B"), track("c1", "C"))
    private val model = RankerModel.parse(
        """{"features": ["same_artist"], "mean": [0], "sd": [1], "weights": [1], "runPenalty": 0, "anchor": "seed"}""",
    )!!

    private val engineCalls = ArrayList<String>()
    private val engine = Proxy.newProxyInstance(
        SimilarityEngine::class.java.classLoader, arrayOf(SimilarityEngine::class.java),
    ) { _, method, _ -> engineCalls += method.name; emptyList<TrackId>() } as SimilarityEngine

    private fun history(events: List<ListenEvent>) = object : ListeningHistory {
        override suspend fun record(event: ListenEvent) = Unit
        override suspend fun stats(): Map<TrackId, TrackStats> = emptyMap()
        override suspend fun recentEvents(limit: Int): List<ListenEvent> = events.takeLast(limit).reversed()
        override suspend fun replace(events: List<ListenEvent>) = Unit
        override suspend fun clear() = Unit
    }

    @Test
    fun `the ranker plans the queue and the engine is not asked`() = runTest {
        // a2 was heard an hour ago: the 24-hour rule leaves it out.
        val heard = ListenEvent(
            trackId = TrackId("a2"), startedAtMs = epochMillis() - 3_600_000L, playedMs = 180_000, trackDurationMs = 200_000,
            completed = true, skipped = false, origin = ListenOrigin(ListenStart.USER_PICK),
        )
        val chooser = EngineNextTrackChooser(
            engine, history(listOf(heard)), learnFromHabits = { true }, library = { library },
            useRanker = { true }, ranker = { SmartRanker(model, null, null) },
        )
        val served = ArrayList<String>()
        val queue = mutableListOf(seed)
        repeat(3) {
            val queued = queue.mapTo(HashSet()) { it.id }
            val choice = chooser.choose(queue.last(), queue.dropLast(1).map { it.id }, library.filter { it.id !in queued })!!
            queue += choice.track
            served += choice.track.id.value
        }
        assertEquals(listOf("a1", "b1", "c1"), served)
        assertEquals(emptyList(), engineCalls)
    }
}

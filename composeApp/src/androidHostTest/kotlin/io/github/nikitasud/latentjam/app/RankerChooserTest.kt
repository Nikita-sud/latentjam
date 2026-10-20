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

    @Test
    fun `every plan reaches the impression log with one row per track and the served rows marked`() = runTest {
        class Planned(val queue: String, val plan: Int, val rankOffset: Int, val model: String, val rows: List<SmartPlanRow>)
        val plans = ArrayList<Planned>()
        val servedIds = ArrayList<TrackId>()
        val log = object : SmartPlanLog {
            override suspend fun planned(queue: String, plan: Int, rankOffset: Int, model: String, rows: List<SmartPlanRow>) {
                plans += Planned(queue, plan, rankOffset, model, rows)
            }
            override suspend fun served(id: TrackId) { servedIds += id }
        }
        val chooser = EngineNextTrackChooser(
            engine, history(emptyList()), library = { library },
            useRanker = { true }, ranker = { SmartRanker(model, null, null) }, smartLog = log,
        )
        val queue = mutableListOf(seed)
        repeat(4) {
            val queued = queue.mapTo(HashSet()) { it.id }
            val choice = chooser.choose(queue.last(), queue.dropLast(1).map { it.id }, library.filter { it.id !in queued })!!
            queue += choice.track
        }
        val first = plans.first()
        assertEquals(0, first.plan)
        assertEquals(0, first.rankOffset)
        assertEquals("ranker", first.model)
        // A whole small library is scored; four eligible tracks repeat to the plan's length (§7.2b).
        assertEquals(20, first.rows.size)
        assertEquals(List(4) { "whole" }, first.rows.take(4).map { it.source })
        kotlin.test.assertTrue(first.rows.drop(4).all { it.source == "ladder:repeat" })
        kotlin.test.assertTrue(first.rows.all { it.trackKey.length == 16 && it.probability > 0.0 && it.probability <= 1.0 })
        kotlin.test.assertTrue(first.queue.startsWith(io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentHash(
            io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentKey(seed)).take(12) + ".0."))
        assertEquals(queue.drop(1).map { it.id }, servedIds)
    }
}

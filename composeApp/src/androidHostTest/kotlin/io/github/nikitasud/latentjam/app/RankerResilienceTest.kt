/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.SmartPressStore
import io.github.nikitasud.latentjam.history.SmartPresses
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.ranker.AudioSource
import io.github.nikitasud.latentjam.smart.ranker.RankerModel
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The chooser around the ranker: presses survive a restart, failures trip the breaker, nothing leaves the queue empty. */
class RankerResilienceTest {
    private val library = (0 until 40).map { TrackDescriptor(TrackId("t$it"), title = "Song $it", artist = "Artist ${it % 9}", genre = "Rock") }
    private val model = RankerModel.parse(
        """{"name": "m", "features": ["same_artist", "same_family"], "mean": [0, 0], "sd": [1, 1], "weights": [1, 0.5],
           "runPenalty": 2, "anchor": "mix", "epsilon": 0.01}""",
    )!!
    private val engine = Proxy.newProxyInstance(
        SimilarityEngine::class.java.classLoader, arrayOf(SimilarityEngine::class.java),
    ) { _, method, _ -> error("the engine was asked: ${method.name}") } as SimilarityEngine
    private val history = object : ListeningHistory {
        override suspend fun record(event: ListenEvent) = Unit
        override suspend fun stats(): Map<TrackId, TrackStats> = emptyMap()
        override suspend fun recentEvents(limit: Int): List<ListenEvent> = emptyList()
        override suspend fun replace(events: List<ListenEvent>) = Unit
        override suspend fun clear() = Unit
    }
    private class Disk : SmartPressStore {
        var lines: List<String> = emptyList()
        override suspend fun read() = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    private suspend fun firstRows(chooser: EngineNextTrackChooser, rows: Int = 5): List<String> {
        val queue = mutableListOf(library[0])
        repeat(rows) {
            val queued = queue.mapTo(HashSet()) { it.id }
            val choice = chooser.choose(queue.last(), queue.dropLast(1).map { it.id }, library.filter { it.id !in queued }) ?: return queue.drop(1).map { it.id.value }
            queue += choice.track
        }
        return queue.drop(1).map { it.id.value }
    }

    private fun chooser(presses: SmartPresses, ranker: SmartRanker, healthy: Boolean = true) = EngineNextTrackChooser(
        engine, history, library = { library }, useRanker = { true }, ranker = { ranker }, rankerHealthy = { healthy }, presses = presses,
    )

    @Test
    fun aSecondStartFromTheSameSeedGivesAFreshQueueAlsoAfterARestart() = runTest {
        val disk = Disk()
        val first = firstRows(chooser(SmartPresses(disk), SmartRanker(model, null, null)))
        val second = firstRows(chooser(SmartPresses(disk), SmartRanker(model, null, null))) // a new process: the press comes from disk
        assertNotEquals(first, second)
        assertEquals(5, second.size)
    }

    @Test
    fun anUnhealthyManifestStillGivesAQueue() = runTest {
        val rows = firstRows(chooser(SmartPresses(Disk()), SmartRanker(model, null, null), healthy = false))
        assertEquals(5, rows.size)
    }

    @Test
    fun anAudioIndexThatThrowsNeverEmptiesTheQueue() = runTest {
        val broken = object : AudioSource {
            override suspend fun vector(id: TrackId): FloatArray? = throw IllegalStateException("closed")
            override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = throw IllegalStateException("closed")
        }
        val rows = firstRows(chooser(SmartPresses(Disk()), SmartRanker(model, null, null, audio = broken)), rows = 25)
        assertEquals(25, rows.size)
        assertTrue(rows.toSet().size == rows.size)
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.playback.SmartChoice
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineNextTrackChooserTest {
    @Test
    fun `excluded cached plan is rebuilt in the same call`() = runTest {
        val seed = track("seed")
        val a = track("a")
        val b = track("b")
        val other = track("other")
        val planner = Planner(listOf(listOf(a.id, b.id), listOf(other.id)))
        val chooser = EngineNextTrackChooser(planner.engine, history)
        assertEquals(a, chooser.choose(seed, emptyList(), listOf(a, b, other))?.track)
        assertEquals(other, chooser.choose(a, emptyList(), listOf(other))?.track)
        assertEquals(2, planner.calls)
    }

    @Test
    fun `usable cached entries preserve the original plan`() = runTest {
        val seed = track("seed")
        val a = track("a")
        val removed = track("removed")
        val b = track("b")
        val planner = Planner(listOf(listOf(a.id, removed.id, b.id)))
        val chooser = EngineNextTrackChooser(planner.engine, history)
        assertEquals(a, chooser.choose(seed, emptyList(), listOf(a, removed, b))?.track)
        assertEquals(b, chooser.choose(a, emptyList(), listOf(b))?.track)
        assertEquals(1, planner.calls)
    }

    @Test
    fun `an unusable new plan is requested only once and an empty pool never invokes the engine`() = runTest {
        for (plan in listOf(emptyList(), listOf(TrackId("unavailable")))) {
            val planner = Planner(listOf(plan))
            val chooser = EngineNextTrackChooser(planner.engine, history)
            assertNull(chooser.choose(track("seed"), emptyList(), emptyList()))
            assertEquals(0, planner.calls)
            assertNull(chooser.choose(track("seed"), emptyList(), listOf(track("available"))))
            assertEquals(1, planner.calls)
        }
    }

    @Test
    fun `each pick carries its slot in the plan it was served from`() = runTest {
        val seed = track("seed")
        val a = track("a")
        val removed = track("removed")
        val b = track("b")
        val c = track("c")
        val planner = Planner(listOf(listOf(a.id, removed.id, b.id), listOf(c.id)))
        val chooser = EngineNextTrackChooser(planner.engine, history)

        assertEquals(SmartChoice(a, planPosition = 1), chooser.choose(seed, emptyList(), listOf(a, removed, b, c)))
        // The slot that became ineligible still counts: b was planned third.
        assertEquals(SmartChoice(b, planPosition = 3), chooser.choose(a, emptyList(), listOf(b, c)))
        // That plan ran out, so the next one counts again from its own seed.
        assertEquals(SmartChoice(c, planPosition = 1), chooser.choose(b, emptyList(), listOf(c)))
    }

    @Test
    fun `a plan the app plays as an ordinary queue is numbered the way the chooser numbers its own`() {
        // "gone" could not be resolved to a track; its slot still counts, as in the chooser.
        val plan = listOf(TrackId("a"), TrackId("gone"), TrackId("b"))

        assertEquals(
            mapOf(TrackId("a") to 1, TrackId("gone") to 2, TrackId("b") to 3),
            smartPlanPositions(plan),
        )
    }

    private class Planner(private val plans: List<List<TrackId>>) {
        var calls = 0
        val engine = Proxy.newProxyInstance(
            SimilarityEngine::class.java.classLoader, arrayOf(SimilarityEngine::class.java),
        ) { _, method, _ ->
            check(method.name == "smartQueue") { "Unexpected engine call: ${method.name}" }
            plans[calls++]
        } as SimilarityEngine
    }

    private fun track(id: String) = TrackDescriptor(TrackId(id), title = id, artist = id)
    private val history = object : ListeningHistory {
        override suspend fun record(event: ListenEvent) = Unit
        override suspend fun stats(): Map<TrackId, TrackStats> = emptyMap()
        override suspend fun recentEvents(limit: Int): List<ListenEvent> = emptyList()
        override suspend fun replace(events: List<ListenEvent>) = Unit
        override suspend fun clear() = Unit
    }
}

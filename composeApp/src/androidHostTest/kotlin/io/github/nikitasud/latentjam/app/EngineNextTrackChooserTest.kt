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
        assertEquals(other, chooser.choose(a, listOf(seed.id), listOf(other))?.track)
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
        assertEquals(b, chooser.choose(a, listOf(seed.id), listOf(b))?.track)
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
        assertEquals(SmartChoice(b, planPosition = 3), chooser.choose(a, listOf(seed.id), listOf(b, c)))
        // That plan ran out, so the next one counts again from its own seed.
        assertEquals(SmartChoice(c, planPosition = 1), chooser.choose(b, listOf(seed.id, a.id), listOf(c)))
    }

    @Test
    fun `restarting from the expected track discards its unserved cached plan`() = runTest {
        val seed = track("seed")
        val expected = track("expected")
        val stale = track("stale")
        val fresh = track("fresh")
        for (preceding in listOf(emptyList(), listOf(TrackId("different-queue")))) {
            val planner = Planner(listOf(listOf(expected.id, stale.id), listOf(fresh.id)))
            val chooser = EngineNextTrackChooser(planner.engine, history)
            assertEquals(expected, chooser.choose(seed, emptyList(), listOf(expected, stale, fresh))?.track)

            // A new queue begins at exactly the track expected by the old plan. Its absent or
            // different prefix must invalidate the still-eligible cached next track.
            assertEquals(fresh, chooser.choose(expected, preceding, listOf(stale, fresh))?.track)
            assertEquals(2, planner.calls)
            assertEquals(preceding, planner.precedingIds.last())
        }
    }

    @Test
    fun `automatic topups explicitly continue the preceding engine plan`() = runTest {
        val seed = track("seed")
        val a = track("a")
        val b = track("b")
        val planner = Planner(listOf(listOf(a.id), listOf(b.id)))
        val chooser = EngineNextTrackChooser(planner.engine, history)

        assertEquals(a, chooser.choose(seed, emptyList(), listOf(a, b))?.track)
        assertEquals(b, chooser.choose(a, listOf(seed.id), listOf(b))?.track)
        assertEquals(listOf("continueSmartQueue", "continueSmartQueue"), planner.methods)
        assertEquals(listOf(emptyList(), listOf(seed.id)), planner.precedingIds)
    }

    @Test
    fun `an unexpected seed discards the cached plan and forwards its queue context`() = runTest {
        val seed = track("seed")
        val a = track("a")
        val elsewhere = track("elsewhere")
        val unserved = track("unserved")
        val b = track("b")
        val planner = Planner(listOf(listOf(a.id, unserved.id), listOf(b.id)))
        val chooser = EngineNextTrackChooser(planner.engine, history)

        assertEquals(a, chooser.choose(seed, emptyList(), listOf(a, unserved, b))?.track)
        assertEquals(b, chooser.choose(elsewhere, emptyList(), listOf(unserved, b))?.track)
        assertEquals(listOf("continueSmartQueue", "continueSmartQueue"), planner.methods)
        assertEquals(listOf(emptyList(), emptyList()), planner.precedingIds)
    }

    @Test
    fun `a plan started by the UI is continued after replacing the chooser's previous queue`() = runTest {
        val oldSeed = track("old-seed")
        val oldFirst = track("old-first")
        val oldUnserved = track("old-unserved")
        val seed = track("new-seed")
        val first = track("new-first")
        val tail = track("new-tail")
        val next = track("next")
        val planner = Planner(listOf(
            listOf(oldFirst.id, oldUnserved.id),
            listOf(first.id, tail.id),
            listOf(next.id),
        ))
        val chooser = EngineNextTrackChooser(planner.engine, history)
        assertEquals(oldFirst, chooser.choose(oldSeed, emptyList(), listOf(oldFirst, oldUnserved))?.track)

        // The UI prepares and starts another queue directly, leaving the chooser's expected
        // track and unserved plan stale. Its next request still continues the UI's valid walk.
        assertEquals(listOf(first.id, tail.id), planner.engine.smartQueue(seed, listOf(first, tail, next), 2))
        val preceding = listOf(seed.id, first.id)
        assertEquals(next, chooser.choose(tail, preceding, listOf(oldUnserved, next))?.track)
        assertEquals(listOf("continueSmartQueue", "smartQueue", "continueSmartQueue"), planner.methods)
        assertEquals(preceding, planner.precedingIds.last())
    }

    @Test
    fun `removing an upcoming track keeps serving the plan`() = runTest {
        // Twenty upcoming rows: the first plan's twelve, then eight of the second.
        val (planner, queue) = smartQueue(upcoming = 20)
        queue.rows.remove(planned[13])
        assertEquals(SmartChoice(planned[20], planPosition = 9), queue.topUp())
        assertEquals(2, planner.calls)
    }

    @Test
    fun `moving an upcoming track keeps serving the plan`() = runTest {
        // A track among the ten rows before the tail moves later, still among them.
        val (planner, queue) = smartQueue(upcoming = 20)
        queue.rows.remove(planned[12])
        queue.rows.add(queue.rows.indexOf(planned[18]), planned[12])
        assertEquals(SmartChoice(planned[20], planPosition = 9), queue.topUp())
        assertEquals(2, planner.calls)
    }

    @Test
    fun `Play next in a queue of ten keeps serving the plan`() = runTest {
        // Play next lands right after the playing track, and so short a queue's window reaches it.
        val (planner, queue) = smartQueue(upcoming = 10)
        queue.rows.add(1, track("picked"))
        assertEquals(SmartChoice(planned[10], planPosition = 11), queue.topUp())
        assertEquals(1, planner.calls)
    }

    @Test
    fun `a planned track queued by hand is skipped and the plan goes on`() = runTest {
        // The plan's next track, played next by hand, is no longer eligible: its slot is skipped.
        val (planner, queue) = smartQueue(upcoming = 10)
        queue.rows.add(1, planned[10])
        assertEquals(SmartChoice(planned[11], planPosition = 12), queue.topUp())
        assertEquals(1, planner.calls)
    }

    @Test
    fun `removing the row served last keeps serving the rest of the plan`() = runTest {
        // Mid-plan: the queue ends where that row was served from, and the plan goes on after it.
        val (planner, queue) = smartQueue(upcoming = 5)
        queue.rows.remove(planned[4])
        assertEquals(SmartChoice(planned[5], planPosition = 6), queue.topUp())
        assertEquals(1, planner.calls)
    }

    @Test
    fun `moving the row served last up keeps serving the rest of the plan`() = runTest {
        val (planner, queue) = smartQueue(upcoming = 5)
        queue.rows.remove(planned[4])
        queue.rows.add(1, planned[4])
        assertEquals(SmartChoice(planned[5], planPosition = 6), queue.topUp())
        assertEquals(1, planner.calls)
    }

    @Test
    fun `removing the plan's last track asks the engine to go on from the row before it`() = runTest {
        // Nothing of the plan is left to serve. The engine resumes its walk at the new last row,
        // which takes that row and the queue's window before it.
        val (planner, queue) = smartQueue(upcoming = 12)
        queue.rows.remove(planned[11])
        assertEquals(SmartChoice(planned[12], planPosition = 1), queue.topUp())
        assertEquals(planned[10].id, planner.seeds.last())
        assertEquals(planned.take(10).map { it.id }, planner.precedingIds.last())
    }

    @Test
    fun `starting SMART on the track served before the expected one plans afresh`() = runTest {
        val (planner, queue) = smartQueue(upcoming = 5)
        queue.rows.retainAll(listOf(planned[3]))
        assertEquals(SmartChoice(planned[12], planPosition = 1), queue.topUp())
        assertEquals(2, planner.calls)
        assertEquals(emptyList(), planner.precedingIds.last())
    }

    @Test
    fun `starting SMART on the expected track plans afresh`() = runTest {
        // Playing a track in SMART leaves it alone in a new queue, even the one the plan expected.
        val (planner, queue) = smartQueue(upcoming = 10)
        queue.rows.retainAll(listOf(planned[9]))
        assertEquals(SmartChoice(planned[12], planPosition = 1), queue.topUp())
        assertEquals(2, planner.calls)
        assertEquals(emptyList(), planner.precedingIds.last())
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
        val methods = mutableListOf<String>()
        val seeds = mutableListOf<TrackId>()
        val precedingIds = mutableListOf<List<TrackId>>()
        val engine = Proxy.newProxyInstance(
            SimilarityEngine::class.java.classLoader, arrayOf(SimilarityEngine::class.java),
        ) { _, method, arguments ->
            check(method.name in setOf("smartQueue", "continueSmartQueue")) { "Unexpected engine call: ${method.name}" }
            methods += method.name
            seeds += (arguments[0] as TrackDescriptor).id
            if (method.name == "continueSmartQueue") {
                @Suppress("UNCHECKED_CAST")
                precedingIds += arguments[5] as List<TrackId>
            }
            plans[calls++]
        } as SimilarityEngine
    }

    /** The engine's answers to the first two top-ups, twelve tracks each. */
    private val planned = (1..24).map { track("planned-$it") }

    /**
     * A SMART queue started from a seed and topped up to [upcoming] tracks, as that queue length
     * setting keeps it. A third plan would come from the chooser throwing the second one away.
     */
    private suspend fun smartQueue(upcoming: Int): Pair<Planner, SmartQueue> {
        val replanned = track("replanned")
        val planner = Planner(listOf(
            planned.take(12).map { it.id },
            planned.drop(12).map { it.id },
            listOf(replanned.id),
        ))
        val queue = SmartQueue(EngineNextTrackChooser(planner.engine, history), planned + replanned, track("seed"))
        repeat(upcoming) { queue.topUp() }
        return planner to queue
    }

    /**
     * Rows topped up the way both playback controllers do it: seeded with the last row, the ten
     * rows before it as the recent window, everything already queued kept out of the candidates.
     */
    private class SmartQueue(
        private val chooser: EngineNextTrackChooser,
        private val library: List<TrackDescriptor>,
        seed: TrackDescriptor,
    ) {
        val rows = mutableListOf(seed)

        suspend fun topUp(): SmartChoice? {
            val queued = rows.mapTo(HashSet()) { it.id }
            val recentIds = rows.dropLast(1).takeLast(10).map { it.id }
            return chooser.choose(rows.last(), recentIds, library.filter { it.id !in queued })
                ?.also { rows += it.track }
        }
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

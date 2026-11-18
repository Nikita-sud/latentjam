/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumePlaybackPersistenceTest {
    private val session = ResumePlayback("track", "ON", 19_537)

    @Test
    fun `startup leaves the durable session untouched until restoration`() = runTest {
        assertEquals(emptyList(), listOf<ResumePlayback?>(null, null).asFlow().resumePlaybackWrites().toList())
        assertEquals(listOf(session), listOf(null, null, session).asFlow().resumePlaybackWrites().toList())
    }

    @Test
    fun `empty queue clears saved session once and a new queue can be saved`() = runTest {
        val next = session.copy(trackId = "another")
        assertEquals(
            listOf(session, null, next),
            listOf(null, session, session, null, null, next).asFlow().resumePlaybackWrites().toList(),
        )
    }

    @Test
    fun `playback ticks are bucketed but pause and paused seek keep exact positions`() = runTest {
        val playing = (10_000L..19_500L step 500).map {
            session.copy(positionMs = resumePositionMs(it, isPlaying = true))
        }
        val paused = session.copy(positionMs = resumePositionMs(19_537, isPlaying = false))
        val sought = session.copy(positionMs = resumePositionMs(24_813, isPlaying = false))
        assertEquals(
            listOf(session.copy(positionMs = 10_000), paused, sought),
            (playing + paused + paused + sought).asFlow().resumePlaybackWrites().toList(),
        )
    }

    private val stored = ResumePlayback(
        trackId = "a",
        shuffleMode = "SMART",
        positionMs = 5_000,
        sourceKind = "COLLECTION",
        sourceName = "Road",
        sourceReference = "pl-1",
        queueTrackIds = listOf("a", "card-1", "b", "card-2", "c"),
        queueIndex = 0,
        sourceQueueTrackIds = listOf("a", "card-1", "b"),
        sourceQueuePersisted = true,
        smartContinuationIds = setOf("card-2", "c"),
        smartPlanPositions = mapOf("card-2" to 1, "c" to 2),
    )

    /** What the writer sees once the player holds only the rows at [indices] of [stored]. */
    private fun live(ids: List<String>, index: Int, positionMs: Long = 0) = ResumePlayback(
        trackId = ids[index],
        shuffleMode = "SMART",
        positionMs = positionMs,
        sourceKind = "COLLECTION",
        sourceName = "Road",
        sourceReference = "pl-1",
        queueTrackIds = ids,
        queueIndex = index,
        sourceQueueTrackIds = listOf("a", "b"),
        sourceQueuePersisted = true,
        smartContinuationIds = setOf("c"),
        smartPlanPositions = mapOf("c" to 2),
    )

    private fun onInternalStorageOnly() = heldResumeQueue(
        saved = stored,
        restoredIndices = listOf(0, 2, 4),
        known = { !it.startsWith("card") },
        authoritative = false,
    )

    @Test
    fun `a queue restored while a card is out is not written over the stored one`() = runTest {
        val hold = MutableStateFlow(onInternalStorageOnly())
        val restored = listOf("a", "b", "c")
        val written = listOf(
            null,
            live(restored, 0, positionMs = 5_000),
            live(restored, 1, positionMs = 10_000),
            live(restored, 2, positionMs = 20_000),
        ).asFlow().resumePlaybackWrites(hold).toList()

        // Every write keeps the stored rows, the card's among them; the track and position follow the player.
        assertEquals(
            listOf(
                stored,
                stored.copy(trackId = "b", queueIndex = 2, positionMs = 10_000),
                stored.copy(trackId = "c", queueIndex = 4, positionMs = 20_000),
            ),
            written,
        )
        assertEquals(true, hold.value != null)
    }

    @Test
    fun `rows SMART adds after a held queue are stored after the stored rows`() = runTest {
        val hold = MutableStateFlow(onInternalStorageOnly())
        val toppedUp = live(listOf("a", "b", "c", "x", "y"), 3).copy(
            smartContinuationIds = setOf("c", "x", "y"),
            smartPlanPositions = mapOf("c" to 2, "x" to 3, "y" to 4),
        )
        val written = listOf(null, toppedUp).asFlow().resumePlaybackWrites(hold).toList().single()

        assertEquals(listOf("a", "card-1", "b", "card-2", "c", "x", "y"), written?.queueTrackIds)
        assertEquals(5, written?.queueIndex)
        assertEquals("x", written?.trackId)
        assertEquals(setOf("card-2", "c", "x", "y"), written?.smartContinuationIds)
        assertEquals(mapOf("card-2" to 1, "c" to 2, "x" to 3, "y" to 4), written?.smartPlanPositions)
        assertEquals(stored.sourceQueueTrackIds, written?.sourceQueueTrackIds)
    }

    @Test
    fun `a new queue or an emptied one ends the hold and is stored as it is`() = runTest {
        val another = live(listOf("b", "a"), 0)
        val newQueue = MutableStateFlow(onInternalStorageOnly())
        assertEquals(
            listOf(stored, another, another.copy(queueTrackIds = listOf("a", "b", "c"), trackId = "a")),
            // The last one is the restored rows again, but by the listener's hand: no hold is left to widen it.
            listOf(null, live(listOf("a", "b", "c"), 0, 5_000), another, another.copy(queueTrackIds = listOf("a", "b", "c"), trackId = "a"))
                .asFlow().resumePlaybackWrites(newQueue).toList(),
        )
        assertEquals(null, newQueue.value)

        val emptied = MutableStateFlow(onInternalStorageOnly())
        assertEquals(
            listOf(stored, null),
            listOf(live(listOf("a", "b", "c"), 0, 5_000), null).asFlow().resumePlaybackWrites(emptied).toList(),
        )
        assertEquals(null, emptied.value)
    }

    @Test
    fun `no hold is made when the scan vouches for itself or shows every row`() {
        assertEquals(null, heldResumeQueue(stored, listOf(0, 2, 4), { !it.startsWith("card") }, authoritative = true))
        assertEquals(null, heldResumeQueue(stored, listOf(0, 1, 2, 3, 4), { true }, authoritative = false))
        assertEquals(null, heldResumeQueue(stored, emptyList(), { false }, authoritative = false))
        // Every queue row is there, but a row of the source it came from is not.
        val sourceOnly = stored.copy(queueTrackIds = listOf("a", "b"), smartContinuationIds = emptySet(), smartPlanPositions = emptyMap())
        val held = heldResumeQueue(sourceOnly, listOf(0, 1), { !it.startsWith("card") }, authoritative = false)
        assertEquals(listOf("a", "b"), held?.restoredIds)
        assertEquals(true, held?.holds(listOf("a", "b", "x")))
        assertEquals(false, held?.holds(listOf("a")))
        assertEquals(false, held?.holds(listOf("b", "a")))
    }

    @Test
    fun `the stored queue coming back whole is not taken for the restored one grown`() = runTest {
        // The card's rows were the last ones: the full queue starts with the restored rows.
        val saved = stored.copy(queueTrackIds = listOf("a", "b", "card-1"), smartContinuationIds = emptySet(), smartPlanPositions = emptyMap())
        val hold = MutableStateFlow(heldResumeQueue(saved, listOf(0, 1), { it != "card-1" }, authoritative = false))
        val whole = live(listOf("a", "b", "card-1"), 0).copy(smartContinuationIds = emptySet(), smartPlanPositions = emptyMap())

        assertEquals(listOf(whole), listOf(whole).asFlow().resumePlaybackWrites(hold).toList())
        assertEquals(null, hold.value)
    }

    @Test
    fun `a full scan puts a held queue back only when it shows rows the player lacks`() {
        val everything = stored.queueTrackIds.toSet()
        assertEquals(true, regainsRows(stored, everything, liveIds = listOf("a", "b", "c"), liveSourceIds = listOf("a", "b")))
        // The rows are gone for good: nothing to put back.
        assertEquals(false, regainsRows(stored, setOf("a", "b", "c"), liveIds = listOf("a", "b", "c"), liveSourceIds = listOf("a", "b")))
        // Only a row of the source the queue came from is back.
        val sourceOnly = stored.copy(queueTrackIds = listOf("a", "b"))
        assertEquals(true, regainsRows(sourceOnly, everything, liveIds = listOf("a", "b"), liveSourceIds = listOf("a", "b")))
    }

    @Test
    fun `negative native position cannot persist an invalid resume offset`() {
        assertEquals(0L, resumePositionMs(-1, isPlaying = true))
        assertEquals(0L, resumePositionMs(-1, isPlaying = false))
    }
}

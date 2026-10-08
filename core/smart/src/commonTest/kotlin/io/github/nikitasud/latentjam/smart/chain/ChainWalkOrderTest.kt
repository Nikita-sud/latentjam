/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class ChainWalkOrderTest {
    @Test
    fun `a twelve track journey carries the artist run that will actually play`() {
        val selected = listOf(1, 7, 2, 8, 3, 9, 4, 10, 5, 11, 6, 12)
        val geometry = circle(emptySet())
        val ordered = JourneySequencer.order(geometry, selected)
        assertNotEquals(selected[selected.lastIndex - 1], ordered[ordered.lastIndex - 1])
        // Reordering places two A tracks at the tail, where the selection order had only one.
        val snapshot = circle(ordered.takeLast(2).toSet() + 13)
        assertEquals(ordered, JourneySequencer.order(snapshot, selected))
        val ids = ordered.map { snapshot.tracks[it].id }
        val selectedWalk = ChainWalk(TrackId("0"), selected.map { snapshot.tracks[it].id }, selected.size)
        val playedWalk = selectedWalk.followingOrder(ids)
        assertEquals(ids, playedWalk.picks)
        assertEquals(1, artistRun(snapshot, selectedWalk))
        assertEquals(2, artistRun(snapshot, playedWalk))

        val eligible = BooleanArray(snapshot.size) { it != 0 && it !in selected }
        val tuning = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            artistRunPenalty = 0.5f,
            // Keep A's next track the winner so its recorded repeat/run penalty can be inspected.
            personalAffinity = { row -> if (row == 13) 1f else 0f },
            personalWeight = 100f,
        )
        fun firstTrace(walk: ChainWalk): PickTrace {
            val traces = mutableListOf<PickTrace>()
            SmartChain(snapshot, null, eligible, tuning = tuning)
                .build(ids.last(), 1, FloatArray(5), resume = walk, trace = traces::add)
            return traces.single()
        }
        val stale = firstTrace(selectedWalk)
        val actual = firstTrace(playedWalk)
        assertEquals(13, stale.row)
        assertEquals(13, actual.row)
        assertTrue(actual.terms[5] < stale.terms[5], "the actual two-track run must pay the stronger penalty")

        // The default chain does not consume continuation history at all.
        val plain = SmartChain(snapshot, null, eligible)
        assertEquals(
            plain.build(ids.last(), 12, FloatArray(5), resume = selectedWalk),
            plain.build(ids.last(), 12, FloatArray(5), resume = playedWalk),
        )
    }

    @Test
    fun `reordering across an intent boundary preserves the seed genre prefix guard`() {
        val snapshot = requireNotNull(SmartSnapshot.build(circle(emptySet()).tracks.mapIndexed { row, track ->
            track.copy(meta = track.meta.copy(genre = if (row in 10..13) "House" else "Rock"))
        }))
        val selected = (1..12).map { TrackId(it.toString()) }
        val reordered = selected.take(4) + selected.takeLast(4) + selected.subList(4, 8)
        val before = ChainWalk(TrackId("0"), selected, picksUnderIntent = 4)
        val after = before.followingOrder(reordered)
        // Only row9 of the four picks under the final intent is Rock. The reordered last four
        // are all Rock: using that suffix would incorrectly declare the four-track prefix done.
        val eligible = BooleanArray(snapshot.size) { it >= 13 }
        val planner = SmartChain(snapshot, null, eligible, tuning = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            personalAffinity = { row -> if (row == 13) 1f else 0f },
            personalWeight = 100f,
        ))
        fun trace(walk: ChainWalk): PickTrace {
            val traces = mutableListOf<PickTrace>()
            planner.build(reordered.last(), 1, FloatArray(5), resume = walk, trace = traces::add)
            return traces.single()
        }
        val expected = trace(before)
        val actual = trace(after)
        assertEquals(13, actual.row)
        kotlin.test.assertContentEquals(expected.terms, actual.terms)
        val mistakenSuffix = trace(after.copy(intentPicks = after.picks.takeLast(4).toSet()))
        assertTrue(actual.terms[4] < mistakenSuffix.terms[4] - 0.2f, "the cross-genre guard still applies")
    }

    @Test
    fun `a reordered refill retains the older prefix and trims in playback order`() {
        val prefix = (100 until 140).map { TrackId(it.toString()) }
        val selected = (0 until 12).map { TrackId(it.toString()) }
        val reordered = selected.reversed()
        val walk = ChainWalk(TrackId("seed"), (prefix + selected).takeLast(ChainWalk.WINDOW), 40)
        val actual = walk.followingOrder(reordered)
        assertEquals(prefix.takeLast(28) + reordered, actual.picks)
        assertEquals(ChainWalk.WINDOW, actual.picks.size)
        assertEquals(actual.picks.toSet(), actual.intentPicks)
        assertEquals(40, actual.picksUnderIntent)
    }

    @Test
    fun `a plan longer than the carried window keeps its actual final rows and intent membership`() {
        val selected = (0 until 52).map { TrackId(it.toString()) }
        val actualOrder = selected.reversed()
        val underIntent = selected.takeLast(20).toSet()
        val walk = ChainWalk(
            TrackId("intent"), selected.takeLast(ChainWalk.WINDOW), 20,
            ring = 0.25f, intentPicks = underIntent,
        ).followingOrder(actualOrder)
        assertEquals(actualOrder.takeLast(ChainWalk.WINDOW), walk.picks)
        assertEquals(underIntent.intersect(walk.picks.toSet()), walk.intentPicks)
        assertEquals(8, walk.picksUnderIntent)
        assertEquals(TrackId("intent"), walk.intent)
        assertEquals(0.25f, walk.ring)
        // An all-one-intent long plan may move rows outside the selected tail into the played tail.
        val sameIntent = ChainWalk(
            TrackId("intent"), selected.takeLast(ChainWalk.WINDOW), 40,
            intentPicks = selected.toSet(),
        ).followingOrder(actualOrder)
        assertEquals(sameIntent.picks.toSet(), sameIntent.intentPicks)
        assertEquals(40, sameIntent.picksUnderIntent)
    }

    @Test
    fun `journey ordering across an intent boundary retains identities rather than the old suffix`() {
        val selected = (0 until 12).map { TrackId(it.toString()) }
        val finalIntent = selected.takeLast(4).toSet()
        val reordered = selected.take(4) + selected.takeLast(4) + selected.subList(4, 8)
        val actual = ChainWalk(TrackId("intent"), selected, 4).followingOrder(reordered)
        assertEquals(finalIntent, actual.intentPicks)
        assertEquals(4, actual.picksUnderIntent)
        assertNotEquals(actual.intentPicks, actual.picks.takeLast(4).toSet())
        assertEquals(actual, actual.followingOrder(reordered))
    }

    @Test
    fun `a walk resumed at an earlier pick plays on as if its plan had ended there`() {
        // The listener removed the plan's last row, row 4: the queue ends at row 3, which closes a
        // run of A with row 2. The removed row must not break that run; the controller keeps it out
        // of the candidates, as row 4 is here.
        val snapshot = circle(setOf(2, 3, 13))
        val picks = listOf(1, 2, 3, 4).map { TrackId(it.toString()) }
        val eligible = BooleanArray(snapshot.size) { it > 4 }
        val tuning = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            artistRunPenalty = 0.5f,
            // Keep A's next track the winner so its recorded repeat/run penalty can be inspected.
            personalAffinity = { row -> if (row == 13) 1f else 0f },
            personalWeight = 100f,
        )
        fun resume(walk: ChainWalk): Pair<ChainResult, PickTrace> {
            val traces = mutableListOf<PickTrace>()
            val result = SmartChain(snapshot, null, eligible, tuning = tuning)
                .build(TrackId("3"), 1, FloatArray(5), resume = walk, trace = traces::add)
            return result to traces.single()
        }
        val (cut, cutPick) = resume(ChainWalk(TrackId("0"), picks, picks.size))
        // The same walk had its plan ended at row 3; row 4 is unavailable in both.
        val (_, endedPick) = resume(ChainWalk(TrackId("0"), picks.dropLast(1), picks.size - 1))
        assertEquals(13, cutPick.row)
        assertEquals(endedPick.row, cutPick.row)
        kotlin.test.assertContentEquals(endedPick.terms, cutPick.terms)
        kotlin.test.assertContentEquals(endedPick.candidates, cutPick.candidates)
        // The removed row, not offered again, stays spent and remembered, ahead of that run.
        assertEquals(listOf(4, 1, 2, 3, 13).map { TrackId(it.toString()) }, cut.walk?.picks)
    }

    @Test
    fun `a pick moved above the seed still counts as played in queue order`() {
        // The plan served rows 1-4; the listener dragged row 4 above row 3, so the queue now ends at
        // row 3 with row 4 right before it, and the next top-up is seeded with row 3.
        val ids = { rows: List<Int> -> rows.map { TrackId(it.toString()) } }
        val queuedBefore = ids(listOf(0, 1, 2, 4))
        // As the controllers do, nothing queued is a candidate.
        val offered = { id: TrackId -> id.value.toInt() > 4 }
        val moved = ChainWalk(TrackId("0"), ids(listOf(1, 2, 3, 4)), 4)
            .resumedAt(TrackId("3"), queuedBefore, offered)
        assertEquals(ChainWalk(TrackId("0"), ids(listOf(1, 2, 4, 3)), 4), moved)

        // Its song stays heard: row 13, another release of row 4, is not planned, though the
        // listener's taste would put it first. Released instead, row 4 would let it straight in.
        val snapshot = circle(setOf(3, 4), sameSong = mapOf(13 to 4))
        val tuning = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            personalAffinity = { row -> if (row == 13) 1f else 0f },
            personalWeight = 100f,
        )
        val eligible = BooleanArray(snapshot.size) { it > 4 }
        fun firstPick(walk: ChainWalk): Int = SmartChain(snapshot, null, eligible, tuning = tuning)
            .build(TrackId("3"), 1, FloatArray(5), resume = walk).rows.single()
        assertNotEquals(13, firstPick(moved))
        assertEquals(13, firstPick(ChainWalk(TrackId("0"), ids(listOf(1, 2, 3)), 3)))
    }

    @Test
    fun `a pick the queue no longer holds is released and may be planned again`() {
        // The walk spends row 2's neighbourhood. The app discarded the queue's future after row 2 to
        // replan it (a new artist variety, a newly marked playlist): rows 3 and 4 were never removed.
        val ids = { rows: List<Int> -> rows.map { TrackId(it.toString()) } }
        // Neither queued nor removed, they are candidates again.
        val replanned = ChainWalk(TrackId("2"), ids(listOf(1, 2, 3, 4)), 2)
            .resumedAt(TrackId("2"), ids(listOf(0, 1))) { id -> id.value.toInt() > 2 }
        assertEquals(ChainWalk(TrackId("2"), ids(listOf(1, 2)), 0), replanned)

        // Released, they are the closest tracks to row 2 again, and the replan takes them.
        val snapshot = circle(emptySet())
        val eligible = BooleanArray(snapshot.size) { it > 2 }
        val plan = SmartChain(
            snapshot, null, eligible, tuning = ChainTuning(continueAfterExhaustion = true, neighbourhoodBonus = 0f),
        ).build(TrackId("2"), 2, FloatArray(5), resume = replanned)
        assertEquals(setOf(3, 4), plan.rows.toSet())
    }

    @Test
    fun `a pick moved up beyond the queue rows a top-up sees still counts as played`() {
        // The plan served rows 1-14; the listener dragged rows 13 and 14 up right after the playing
        // row 0, so the queue ends at row 12 and the ten rows a top-up sends before it are rows 2-11.
        val ids = { rows: List<Int> -> rows.map { TrackId(it.toString()) } }
        val walk = ChainWalk(TrackId("0"), ids((1..14).toList()), 14)
        val queuedBefore = ids((2..11).toList())
        val offered = { id: TrackId -> id.value.toInt() > 14 }
        val moved = walk.resumedAt(TrackId("12"), queuedBefore, offered)
        assertEquals(ids(listOf(13, 14) + (1..12)), moved.picks)

        // Row 20, another release of row 13, stays out though the listener's taste would put it first.
        val snapshot = circle(emptySet(), sameSong = mapOf(20 to 13))
        val tuning = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            personalAffinity = { row -> if (row == 20) 1f else 0f },
            personalWeight = 100f,
        )
        val eligible = BooleanArray(snapshot.size) { it > 14 }
        fun firstPick(resume: ChainWalk): Int = SmartChain(snapshot, null, eligible, tuning = tuning)
            .build(TrackId("12"), 1, FloatArray(5), resume = resume).rows.single()
        assertNotEquals(20, firstPick(moved))
        // Released as a discarded future would be, row 13 would let its other release straight in.
        assertEquals(20, firstPick(walk.resumedAt(TrackId("12"), queuedBefore) { true }))
    }

    private fun artistRun(snapshot: SmartSnapshot, walk: ChainWalk): Int {
        val artists = walk.picks.map { snapshot.tracks[snapshot.rowOf(it)].meta.artistKey }
        return artists.asReversed().takeWhile { it == artists.last() }.size
    }

    /** Rows on a circle; [sameSong] makes a row another release of a row's song (same title and artist). */
    private fun circle(sameArtist: Set<Int>, sameSong: Map<Int, Int> = emptyMap()): SmartSnapshot =
        requireNotNull(SmartSnapshot.build(
            (0 until 24).map { row ->
                val angle = 2 * PI * row / 24
                val song = sameSong[row] ?: row
                SmartTrack(
                    TrackId(row.toString()),
                    FloatArray(SmartSnapshot.AUDIO_DIM).also {
                        it[0] = cos(angle).toFloat()
                        it[1] = sin(angle).toFloat()
                    },
                    meta = TrackMeta("Song $song", if (song in sameArtist) "A" else "Artist $song", null, null, null),
                )
            },
        ))
}

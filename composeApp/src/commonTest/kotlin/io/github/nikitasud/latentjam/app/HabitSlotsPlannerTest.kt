/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitSlotsPlannerTest {
    private fun event(id: String, at: Long, skipped: Boolean = false, origin: ListenOrigin? = null) = ListenEvent(
        trackId = TrackId(id), startedAtMs = at, playedMs = if (skipped) 5_000 else 180_000, trackDurationMs = 200_000,
        completed = !skipped, skipped = skipped, origin = origin,
    )

    @Test
    fun `only picks count as what followed while skips and other tracks and the future are not listens`() {
        val library = setOf("a", "b", "c", "d").mapTo(HashSet(), ::TrackId)
        val listens = habitListens(
            listOf(
                event("a", 1_000, origin = ListenOrigin(ListenStart.USER_PICK)),
                event("b", 2_000, origin = ListenOrigin(ListenStart.AUTO_ADVANCE, parentId = "playlist:1")),
                event("b", 2_500, skipped = true, origin = ListenOrigin(ListenStart.USER_PICK)),
                event("c", 3_000, origin = ListenOrigin(ListenStart.USER_PICK, smartPlanPosition = null)),
                event("d", 4_000, origin = ListenOrigin(ListenStart.AUTO_ADVANCE, smartPlanPosition = 2)),
                event("gone", 5_000, origin = ListenOrigin(ListenStart.USER_PICK)),
                event("a", 6_000), // a legacy line: no origin
                event("c", 9_000, origin = ListenOrigin(ListenStart.USER_PICK)), // at or after the seed
            ),
            library,
            seedMs = 9_000,
        )
        assertEquals(
            listOf(
                HabitListen(TrackId("a"), 1_000, true),
                HabitListen(TrackId("b"), 2_000, false),
                HabitListen(TrackId("c"), 3_000, true),
                HabitListen(TrackId("d"), 4_000, false),
                HabitListen(TrackId("a"), 6_000, false),
            ),
            listens,
        )
    }

    @Test
    fun `only tracks that really followed the seed or its artist take a personal slot`() {
        val day = 86_400_000L
        val listens = listOf(
            HabitListen(TrackId("favourite"), 0L), // played often, never after the seed
            HabitListen(TrackId("favourite"), 1_000L),
            HabitListen(TrackId("seed"), day, countsAsNext = true),
            HabitListen(TrackId("after"), day + 60_000L, countsAsNext = true),
            HabitListen(TrackId("other-by-seed-artist"), day + 3_600_000L, countsAsNext = true),
            HabitListen(TrackId("after-artist"), day + 3_660_000L, countsAsNext = true),
        )
        val artists = mapOf("seed" to "a", "other-by-seed-artist" to "a")
        val rows = habitRows(
            listens, seed = TrackId("seed"), seedMs = 3 * day,
            candidates = listOf("x", "favourite", "after-artist", "after").map(::TrackId),
            artistKey = { artists[it.value].orEmpty() }, salt = 1L,
        )
        assertEquals(listOf(TrackId("after"), TrackId("after-artist")), rows)
        assertTrue(habitRows(emptyList(), TrackId("seed"), 3 * day, listOf(TrackId("x")), { "" }, 1L).isEmpty())
    }

    @Test
    fun `an artist transition weighs half a track transition`() {
        val byTrack = mapOf(TrackId("direct") to 2)
        val byArtist = mapOf(TrackId("direct") to 2, TrackId("artist-only") to 3)
        assertEquals(3.0, habitCount(TrackId("direct"), byTrack, byArtist))
        assertEquals(1.5, habitCount(TrackId("artist-only"), byTrack, byArtist))
        assertEquals(0.0, habitCount(TrackId("never"), byTrack, byArtist))
    }

    @Test
    fun `the tie key is uniform in 0 to 1 and depends on the salt`() {
        val keys = (0 until 1_000).map { saltedTieKey(TrackId("t$it"), 42L) }
        assertTrue(keys.all { it >= 0.0 && it < 1.0 })
        assertTrue(keys.average() in 0.45..0.55)
        assertTrue(saltedTieKey(TrackId("t1"), 1L) != saltedTieKey(TrackId("t1"), 2L))
    }

    @Test
    fun `the narrow vc21 arm drops pairs into a picked track that was skipped`() {
        val library = setOf("a", "b", "c").mapTo(HashSet(), ::TrackId)
        val pick = ListenOrigin(ListenStart.USER_PICK)
        val events = listOf(
            event("a", 0, origin = pick),
            event("c", 190_000, skipped = true, origin = pick), // picked after a, skipped: the bridge pair a to c
            event("b", 200_000, origin = pick), // after the skipped c: c to b; a to b is a normal pair
        )
        val all = habitSkipTransitions(events, library, seedMs = 1_000_000)
        assertEquals(listOf(TrackId("a") to TrackId("c"), TrackId("c") to TrackId("b")), all)
        assertEquals(listOf(TrackId("c") to TrackId("b")), habitSkipTransitions(events, library, seedMs = 1_000_000, unskippedOnly = true))
    }

    private fun play(id: String, at: Long, heardMs: Long, start: ListenStart, completed: Boolean = false) = ListenEvent(
        trackId = TrackId(id), startedAtMs = at, playedMs = heardMs, trackDurationMs = 200_000, completed = completed,
        skipped = !completed && heardMs < 30_000, listenedMs = heardMs, origin = ListenOrigin(start),
    )

    @Test
    fun `listen credit is zero up to thirty seconds and full from the ListenBrainz threshold`() {
        val pick = ListenStart.USER_PICK
        assertEquals(0.0, listenCredit(play("b", 0, 20_000, pick), 30_000))
        assertEquals(0.0, listenCredit(play("b", 0, 30_000, pick), 30_000))
        assertEquals(0.5, listenCredit(play("b", 0, 65_000, pick), 30_000), 1e-9) // threshold min of D over 2 and 240 s is 100 s
        assertEquals(1.0, listenCredit(play("b", 0, 100_000, pick), 30_000))
        assertEquals(1.0, listenCredit(play("b", 0, 170_000, pick, completed = true), 30_000))
        val imported = ListenEvent(TrackId("b"), 0, 200_000, 200_000, completed = true, skipped = false)
        assertEquals(1.0, listenCredit(imported, 30_000)) // no listen time: neutral
    }

    @Test
    fun `an early abandon lowers only that pair and a long listen does not`() {
        val library = setOf("a", "b", "c", "d").mapTo(HashSet(), ::TrackId)
        val lw = io.github.nikitasud.latentjam.smart.ranker.RankerModel.ListenWeight(negWeight = 1.5)
        val events = listOf(
            play("a", 0, 200_000, ListenStart.USER_PICK, completed = true),
            play("b", 201_000, 5_000, ListenStart.USER_PICK), // picked after a and left in 5 s
            play("c", 207_000, 60_000, ListenStart.AUTO_ADVANCE), // after a again: the last listen before c is a
            play("d", 268_000, 150_000, ListenStart.USER_PICK), // past the threshold: no negative
        )
        val neg = abandonNegatives(events, library, seedMs = 1_000_000, lw)
        assertEquals(setOf(TrackId("a") to TrackId("b"), TrackId("a") to TrackId("c")), neg.keys)
        assertTrue(neg.getValue(TrackId("a") to TrackId("b")).first > 0.95)
        assertTrue(neg.getValue(TrackId("a") to TrackId("c")).first < 0.02)
    }

    @Test
    fun `credited counts halve partial listens and two rejections make the pair value negative`() {
        val library = setOf("a", "b", "c").mapTo(HashSet(), ::TrackId)
        val lw = io.github.nikitasud.latentjam.smart.ranker.RankerModel.ListenWeight(negWeight = 1.5)
        val pick = ListenStart.USER_PICK
        val events = ArrayList<ListenEvent>()
        var t = 0L
        repeat(2) { // a then b heard 65 s (credit 0.5), a then c left in 5 s
            events += play("a", t, 200_000, pick, completed = true); t += 201_000
            events += play("b", t, 65_000, pick); t += 66_000
            events += play("a", t, 200_000, pick, completed = true); t += 201_000
            events += play("c", t, 5_000, pick); t += 3_600_000
        }
        fun history(w: io.github.nikitasud.latentjam.smart.ranker.RankerModel.ListenWeight?) =
            personalHistoryOf(events, library, t, emptyMap(), skipPairs = true, unskippedOnly = false, lw = w) { "" }
        assertEquals(2, history(null).followCount(TrackId("a"), TrackId("b")))
        assertEquals(1, history(lw).followCount(TrackId("a"), TrackId("b")))
        assertTrue(history(null).row(TrackId("c"), TrackId("a"), "").transValue.isNaN()) // counts mode: unchanged
        assertTrue(history(lw).row(TrackId("c"), TrackId("a"), "").transValue < 0.0)
        assertTrue(history(lw).row(TrackId("b"), TrackId("a"), "").transValue > 0.0)
    }
}

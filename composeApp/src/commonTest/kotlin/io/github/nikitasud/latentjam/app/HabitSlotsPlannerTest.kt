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
}

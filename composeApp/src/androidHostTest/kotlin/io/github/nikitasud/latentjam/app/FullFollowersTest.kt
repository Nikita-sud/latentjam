/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

/** PLAN_NOVELTY (3): a full listen right after the seed, once, picked or autoplayed, makes a full follower. */
class FullFollowersTest {
    private val ids = (listOf("A", "B", "C")).map { TrackId(it) }.toSet()

    private fun play(id: String, at: Long, heardMs: Long, completed: Boolean = false, skipped: Boolean = false, start: ListenStart = ListenStart.USER_PICK) =
        ListenEvent(TrackId(id), at, heardMs, 200_000, completed, skipped, listenedMs = heardMs, origin = ListenOrigin(start))

    private fun of(vararg e: ListenEvent) = fullFollowers(e.toList(), ids, TrackId("A"), seedMs = 10_000_000)

    @Test
    fun oneFullListenRightAfterTheSeedCounts() {
        assertEquals(setOf(TrackId("B")), of(play("A", 0, 200_000, completed = true), play("B", 200_000, 200_000, completed = true)))
        // autoplay too, and past the ListenBrainz threshold (D / 2) without completing
        assertEquals(setOf(TrackId("B")), of(play("A", 0, 10_000), play("B", 60_000, 120_000, start = ListenStart.AUTO_ADVANCE)))
    }

    @Test
    fun aShortSkipBetweenIsIgnored() {
        assertEquals(setOf(TrackId("B")), of(play("A", 0, 200_000), play("C", 200_000, 5_000, skipped = true), play("B", 205_000, 200_000, completed = true)))
    }

    @Test
    fun anAbandonALateNextOrASkipBetweenDoesNot() {
        assertEquals(emptySet(), of(play("A", 0, 200_000), play("B", 200_000, 40_000)))                    // abandoned
        assertEquals(emptySet(), of(play("A", 0, 200_000), play("B", 200_000 + 6 * 60_000L, 200_000, completed = true)))   // > 5 min after A's end
        assertEquals(emptySet(), of(play("A", 0, 200_000), play("C", 200_000, 60_000, skipped = true), play("B", 260_000, 200_000, completed = true)))
        assertEquals(emptySet(), of(play("A", 0, 200_000), play("A", 200_000, 200_000, completed = true)))   // repeat-one: not its own follower
        assertEquals(emptySet(), of(play("B", 0, 200_000, completed = true), play("A", 200_000, 200_000)))   // before, not after
    }
}

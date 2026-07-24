/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the affinity score behind within-tier search ordering. */
class SearchAffinityTest {
    private val now = 1_000L * 24 * 60 * 60 * 1000 // day 1000 in ms
    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun affinity_isZeroForUnplayed() {
        assertEquals(0.0, SearchAffinity.affinity(0, now, now), 0.0)
        assertTrue(SearchAffinity.affinity(1, now, now) > 0.0)
    }

    @Test
    fun affinity_growsWithPlays() {
        assertTrue(
            SearchAffinity.affinity(50, now, now) > SearchAffinity.affinity(1, now, now),
        )
    }

    @Test
    fun recencyDecay_favorsRecentPlays() {
        val fresh = SearchAffinity.affinity(10, now, now)
        val stale = SearchAffinity.affinity(10, now - 365 * dayMs, now)
        assertTrue(fresh > stale)
    }

    @Test
    fun halfLife_halvesAtThirtyDays() {
        val today = SearchAffinity.affinity(10, now, now)
        val thirtyDaysOld = SearchAffinity.affinity(10, now - 30 * dayMs, now)
        // A play 30 days old is worth half of one today.
        assertTrue(kotlin.math.abs(thirtyDaysOld - today / 2.0) < 1e-9)
    }
}

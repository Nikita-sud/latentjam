/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ForYouImpressionsTest {

    private class InMemoryStore : ForYouImpressionStore {
        var lines = listOf<String>()
        var writes = 0
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) {
            this.lines = lines
            writes++
        }
    }

    private fun impression(id: String, day: Long, section: String = "daypart") =
        ForYouImpression(trackId = TrackId(id), section = section, epochDay = day)

    @Test
    fun recordsOncePerTrackAndDay() = runTest {
        val store = InMemoryStore()
        val impressions = ForYouImpressions(store)
        impressions.record(listOf(impression("a", 10), impression("b", 10)))
        // The page rebuilds many times a day; the second record of the same offers is a no-op
        // that must not even touch the disk.
        impressions.record(listOf(impression("a", 10), impression("b", 10)))
        assertEquals(1, store.writes)
        impressions.record(listOf(impression("a", 11)))
        assertEquals(3, store.lines.size)
    }

    @Test
    fun lastShownExcludesTodayOnPurpose() = runTest {
        val impressions = ForYouImpressions(InMemoryStore())
        impressions.record(
            listOf(impression("old", 8), impression("old", 9), impression("today", 10)),
        )
        val shown = impressions.lastShownDays(beforeEpochDay = 10)
        // Same-day offers never cool anything — the page must stay stable within its day.
        assertEquals(mapOf(TrackId("old") to 9L), shown)
    }

    @Test
    fun boundsTheFileByDroppingTheOldest() = runTest {
        val store = InMemoryStore()
        val impressions = ForYouImpressions(store)
        // Well past the bound, one offer per day.
        impressions.record((0 until 4500L).map { impression("t$it", it) })
        assertTrue(store.lines.size <= 4000)
        val shown = impressions.lastShownDays(beforeEpochDay = 5000)
        assertTrue(TrackId("t0") !in shown, "oldest lines must be the ones dropped")
        assertTrue(TrackId("t4499") in shown)
    }

    @Test
    fun survivesItsOwnSerializationAndForeignJunk() = runTest {
        val store = InMemoryStore()
        store.lines = listOf("garbage", "v9|zz|x|1")
        val impressions = ForYouImpressions(store)
        impressions.record(listOf(impression("трек|с|разделителями", 12, section = "wildcard")))
        val reloaded = ForYouImpressions(store)
        val shown = reloaded.lastShownDays(beforeEpochDay = 13)
        assertEquals(mapOf(TrackId("трек|с|разделителями") to 12L), shown)
    }
}

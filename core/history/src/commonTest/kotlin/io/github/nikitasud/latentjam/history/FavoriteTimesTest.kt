/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteTimesTest {
    private class Memory(var lines: List<String> = emptyList()) : FavoriteTimeStore {
        override suspend fun read() = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    @Test
    fun heartsKeepTheirTimeAndReloadFromTheFile() = runTest {
        val store = Memory()
        val times = FavoriteTimes(store)
        times.update(listOf(TrackId("a"), TrackId("b|é")), emptyList(), 10)
        times.update(listOf(TrackId("c")), listOf(TrackId("a")), 20)
        assertEquals(mapOf(TrackId("b|é") to 10L, TrackId("c") to 20L), times.all())
        assertEquals(mapOf(TrackId("b|é") to 10L, TrackId("c") to 20L), FavoriteTimes(store).all())
        assertEquals(emptyMap(), FavoriteTimes(Memory(listOf("ft1|zz|1", "junk", "ft1|61|x"))).all())
    }

    @Test
    fun aReadThatThrowsNeverWritesOverTheStoredTimes() = runTest {
        val good = Memory()
        FavoriteTimes(good).update(listOf(TrackId("a")), emptyList(), 10)
        val stored = good.lines
        var failReads = 1
        val store = object : FavoriteTimeStore {
            override suspend fun read(): List<String> = if (failReads-- > 0) throw OutOfMemoryError("simulated") else good.lines
            override suspend fun write(lines: List<String>) { good.lines = lines }
        }
        val times = FavoriteTimes(store)
        times.update(listOf(TrackId("b")), emptyList(), 20)
        assertEquals(stored, good.lines)
        times.update(listOf(TrackId("c")), emptyList(), 30)
        assertEquals(mapOf(TrackId("a") to 10L, TrackId("c") to 30L), FavoriteTimes(good).all())
    }

    @Test
    fun heartEventsRoundTripInTheSmartLog() {
        val e = SmartFavoriteEvent(7, "0011223344556677", true)
        assertEquals(e, SmartImpressionLog.parse(SmartImpressionLog.serialize(e)))
        assertEquals(e.copy(favourite = false), SmartImpressionLog.parse(SmartImpressionLog.serialize(e.copy(favourite = false))))
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SmartPressesTest {
    private class Memory(var lines: List<String> = emptyList(), val failWrites: Boolean = false) : SmartPressStore {
        override suspend fun read() = lines
        override suspend fun write(lines: List<String>) { if (failWrites) error("disk full") else this.lines = lines }
    }

    @Test
    fun pressesCountPerSeedAndSurviveARestart() = runTest {
        val store = Memory()
        val presses = SmartPresses(store)
        val key = "v1|external_primary|Music/a|b.mp3|10|200"
        assertEquals(listOf(0L, 1L, 2L), List(3) { presses.next(key) })
        assertEquals(0L, presses.next("v1||other|0|0"))
        assertEquals(3L, SmartPresses(store).next(key))
    }

    @Test
    fun anOldOrDamagedFileStartsAtZeroAndAFailingDiskDoesNotStopSmart() = runTest {
        assertEquals(0L, SmartPresses(Memory(listOf("garbage", "p1|zz|3", "p1|61|-2", "v9|61|4"))).next("a"))
        val flaky = SmartPresses(Memory(failWrites = true))
        assertEquals(0L, flaky.next("a"))
        assertEquals(1L, flaky.next("a"))
    }

    @Test
    fun aFailedReadNeitherWipesTheFileNorIsRemembered() = runTest {
        var failReads = true
        val store = object : SmartPressStore {
            var lines = listOf("p1|61|5")
            override suspend fun read(): List<String> = if (failReads) error("io") else lines
            override suspend fun write(lines: List<String>) { this.lines = lines }
        }
        val presses = SmartPresses(store)
        assertEquals(0L, presses.next("b"))
        assertEquals(listOf("p1|61|5"), store.lines)
        failReads = false
        assertEquals(5L, presses.next("a"))
    }
}

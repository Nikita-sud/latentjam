/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistVarietyApplierTest {

    @Test
    fun `the saved level reaches the engine at launch and the queued SMART future is kept`() = runTest {
        val setting = Setting(backgroundScope)
        setting.level(4)
        settle()
        assertEquals(listOf(ARTIST_VARIETY_PENALTIES[4]), setting.penalties)
        assertEquals(0, setting.replans)
    }

    @Test
    fun `a new level replans the queued SMART future once the slider rests on it`() = runTest {
        val setting = Setting(backgroundScope)
        setting.level(2)
        runCurrent()
        setting.level(4)
        runCurrent()
        // The engine's next plan already uses the new penalty; the queue waits for the slider to rest.
        assertEquals(ARTIST_VARIETY_PENALTIES[4], setting.penalties.last())
        advanceTimeBy(ARTIST_VARIETY_SETTLE_MS - 1)
        runCurrent()
        assertEquals(0, setting.replans)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, setting.replans)
    }

    @Test
    fun `a drag across the slider's steps replans once at the level it rests on`() = runTest {
        val setting = Setting(backgroundScope)
        setting.level(2)
        runCurrent()
        for (step in listOf(3, 4, 3, 4)) {
            setting.level(step)
            advanceTimeBy(ARTIST_VARIETY_SETTLE_MS / 4)
        }
        settle()
        assertEquals(1, setting.replans)
        assertEquals(ARTIST_VARIETY_PENALTIES[4], setting.penalties.last())
    }

    @Test
    fun `back at the level the queue was planned with nothing is replanned`() = runTest {
        val setting = Setting(backgroundScope)
        setting.level(2)
        runCurrent()
        setting.level(4)
        advanceTimeBy(ARTIST_VARIETY_SETTLE_MS / 2)
        setting.level(2)
        settle()
        assertEquals(0, setting.replans)
        assertEquals(ARTIST_VARIETY_PENALTIES[2], setting.penalties.last())
    }

    @Test
    fun `a replan cut short by the next level does not hide that level's change`() = runTest {
        val setting = Setting(backgroundScope)
        setting.level(2)
        runCurrent()
        setting.replanFinishes = false
        setting.level(4)
        settle()
        assertEquals(1, setting.replans)
        // Back to the first level while that replan still runs: part of the queue already follows level 4.
        setting.replanFinishes = true
        setting.level(2)
        settle()
        assertEquals(2, setting.replans)
    }

    private fun TestScope.settle() {
        advanceTimeBy(ARTIST_VARIETY_SETTLE_MS)
        runCurrent()
    }

    /** The setting as App applies it: a new level cancels the application of the one before, as a keyed effect does. */
    private class Setting(private val scope: CoroutineScope) {
        val penalties = mutableListOf<Float>()
        var replans = 0

        /** False keeps a replan running until the next level cancels it. */
        var replanFinishes = true

        private val applier = ArtistVarietyApplier(
            setPenalty = { penalties += it },
            invalidateSmartFuture = {
                replans++
                if (!replanFinishes) awaitCancellation()
            },
        )
        private var application: Job? = null

        fun level(level: Int) {
            application?.cancel()
            application = scope.launch { applier.apply(level) }
        }
    }
}

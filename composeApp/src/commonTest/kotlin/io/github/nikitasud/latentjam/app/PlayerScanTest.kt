/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerScanTest {
    @Test
    fun holdingAtTheEndSeeksOnceAndStopsInsteadOfRepeatedlyRestartingTheEndpoint() = runTest {
        val seeks = mutableListOf<Long>()
        var edges = 0
        scanPlayerTrack(true, 10_000L, { playing(position = 9_500L) }, seeks::add) { edges++ }
        assertEquals(listOf(10_000L), seeks)
        assertEquals(1, edges)
    }

    @Test
    fun holdingAtTheBeginningDoesNotIssueAnIdenticalSeekEveryTick() = runTest {
        val seeks = mutableListOf<Long>()
        var edges = 0
        scanPlayerTrack(false, 10_000L, { playing(position = 0L) }, seeks::add) { edges++ }
        assertTrue(seeks.isEmpty())
        assertEquals(1, edges)
    }

    @Test
    fun advancingToAnotherTrackStopsTheOriginalHold() = runTest {
        var state = playing(position = 1_000L)
        val seeks = mutableListOf<Long>()
        val scan = launch { scanPlayerTrack(true, 100_000L, { state }, seeks::add) {} }
        runCurrent()
        assertEquals(listOf(2_000L), seeks)
        state = playing(position = 0L, id = "next")
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(scan.isCompleted)
        assertEquals(listOf(2_000L), seeks)
    }

    @Test
    fun advancingToAnotherOccurrenceOfTheSameTrackAlsoStopsTheHold() = runTest {
        var state = playing(position = 1_000L)
        val seeks = mutableListOf<Long>()
        val scan = launch { scanPlayerTrack(true, 100_000L, { state }, seeks::add) {} }
        runCurrent()
        state = state.copy(queueIndex = 1, positionMs = 0L)
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(scan.isCompleted)
        assertEquals(listOf(2_000L), seeks)
    }

    @Test
    fun cancellingTheHoldPreventsFurtherSeeks() = runTest {
        val seeks = mutableListOf<Long>()
        val scan = launch { scanPlayerTrack(true, 100_000L, { playing(1_000L) }, seeks::add) {} }
        runCurrent()
        scan.cancel()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf(2_000L), seeks)
    }

    @Test
    fun unknownDurationCannotSeekTheTrackBackToZero() = runTest {
        val seeks = mutableListOf<Long>()
        scanPlayerTrack(true, 0L, { playing(4_000L) }, seeks::add) {}
        assertTrue(seeks.isEmpty())
    }

    private fun playing(position: Long, id: String = "current") = NowPlaying(
        track = TrackDescriptor(TrackId(id)),
        positionMs = position,
        queueIndex = 0,
    )
}

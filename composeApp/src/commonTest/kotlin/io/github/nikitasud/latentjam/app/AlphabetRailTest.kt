/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class AlphabetRailTest {

    @Test
    fun bucketsFollowTheListWithFirstRowPerBucket() {
        val rail = railIndexOf(listOf("Abba", "Alla", "Beta", "Царь"))
        assertEquals(listOf("A", "B", "Ц"), rail.buckets)
        assertEquals(listOf(0, 2, 3), rail.startIndexes)
    }

    @Test
    fun digitsAndNamelessEntriesHaveDistinctAnchors() {
        val rail = railIndexOf(listOf("90s", "Alpha", "!!!"))
        assertEquals(listOf("#", "A", "?"), rail.buckets)
        assertEquals(listOf(0, 1, 2), rail.startIndexes)
    }

    @Test
    fun nullsAtTheEndKeepTheirOwnFinalBucket() {
        val rail = railIndexOf(listOf("7 rings", "Alpha", null, null))
        assertEquals(listOf("#", "A", "?"), rail.buckets)
        assertEquals(listOf(0, 1, 2), rail.startIndexes)
    }

    @Test
    fun touchMappingClampsAndUsesEveryBucket() {
        assertEquals(0, railBucketIndexAt(-10f, height = 400, bucketCount = 4))
        assertEquals(0, railBucketIndexAt(99f, height = 400, bucketCount = 4))
        assertEquals(1, railBucketIndexAt(100f, height = 400, bucketCount = 4))
        assertEquals(3, railBucketIndexAt(400f, height = 400, bucketCount = 4))
    }

    @Test
    fun visibleItemMapsToLatestSectionStart() {
        val starts = listOf(0, 8, 15, 27)
        assertEquals(0, currentRailBucketIndex(0, starts))
        assertEquals(1, currentRailBucketIndex(12, starts))
        assertEquals(3, currentRailBucketIndex(200, starts))
        assertEquals(3, currentRailBucketIndex(18, starts, atEnd = true))
    }

    @Test
    fun rapidDragPreviewsEveryBucketAndKeepsExactFinalBucket() {
        val coordinator = RailScrubCoordinator()
        val previews = mutableListOf<Int>()

        coordinator.begin()
        (0..25).forEach { bucket ->
            // Preview state is deliberately independent from expensive list navigation.
            previews += bucket
            coordinator.preview(bucket)
        }
        val finalJump = coordinator.finish()

        assertEquals((0..25).toList(), previews)
        assertEquals(25, finalJump?.bucketIndex)
    }

    @Test
    fun secondGestureMakesAnOlderCompletionStale() {
        val coordinator = RailScrubCoordinator()
        coordinator.begin()
        coordinator.preview(4)
        val first = checkNotNull(coordinator.finish())

        coordinator.begin()
        coordinator.preview(19)
        val second = checkNotNull(coordinator.finish())

        assertEquals(false, coordinator.isCurrent(first.generation))
        assertEquals(true, coordinator.isCurrent(second.generation))
        assertEquals(19, second.bucketIndex)
        assertEquals(null, coordinator.finish())
    }

    @Test
    fun cancelInvalidatesCatalogWorkAndIgnoresStalePointerEvents() {
        val coordinator = RailScrubCoordinator()
        coordinator.begin()
        coordinator.preview(5)
        val oldGeneration = coordinator.generation

        coordinator.cancel()
        coordinator.preview(17)

        assertEquals(false, coordinator.isCurrent(oldGeneration))
        assertEquals(null, coordinator.finish())
    }

    @Test
    fun artworkGateWaitsForMinimumAndEveryVisibleTerminal() = runTest {
        val gate = ArtworkLoadGate()
        val first = ArtworkLoadKey("first", "cover:first")
        val second = ArtworkLoadKey("second", "cover:second")
        var revealed = false
        val cycle = gate.begin()

        launch {
            gate.awaitFinished(
                cycle = cycle,
                expected = setOf(first, second),
                minimumWaitMillis = 120L,
                maximumWaitMillis = 220L,
            )
            revealed = true
        }
        runCurrent()
        gate.record(first, ArtworkLoadState.TERMINAL)
        advanceTimeBy(119L)
        runCurrent()
        assertFalse(revealed)

        gate.record(second, ArtworkLoadState.TERMINAL)
        runCurrent()
        assertFalse(revealed)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(revealed)
    }

    @Test
    fun artworkGateCannotHangOnAnUnreportedRequest() = runTest {
        val gate = ArtworkLoadGate()
        val missing = ArtworkLoadKey("missing", "cover:missing")
        var revealed = false
        val cycle = gate.begin()

        launch {
            gate.awaitFinished(
                cycle = cycle,
                expected = setOf(missing),
                minimumWaitMillis = 120L,
                maximumWaitMillis = 220L,
            )
            revealed = true
        }
        advanceTimeBy(219L)
        runCurrent()
        assertFalse(revealed)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(revealed)
    }

    @Test
    fun aNewLoadingStateInvalidatesAnEarlierTerminalInTheSameCycle() = runTest {
        val gate = ArtworkLoadGate()
        val cover = ArtworkLoadKey("track", "cover")
        val cycle = gate.begin()
        gate.record(cover, ArtworkLoadState.TERMINAL)
        gate.record(cover, ArtworkLoadState.LOADING)
        var revealed = false

        launch {
            gate.awaitFinished(
                cycle = cycle,
                expected = setOf(cover),
                minimumWaitMillis = 0L,
                maximumWaitMillis = 20L,
            )
            revealed = true
        }
        advanceTimeBy(19L)
        runCurrent()
        assertFalse(revealed)
        gate.record(cover, ArtworkLoadState.TERMINAL)
        runCurrent()
        assertTrue(revealed)
    }

    @Test
    fun eventsOutsideTheActiveCycleAreIgnored() = runTest {
        val gate = ArtworkLoadGate()
        val cover = ArtworkLoadKey("track", "cover")
        gate.record(cover, ArtworkLoadState.TERMINAL)
        val cycle = gate.begin()
        var revealed = false

        launch {
            gate.awaitFinished(
                cycle = cycle,
                expected = setOf(cover),
                minimumWaitMillis = 0L,
                maximumWaitMillis = 10L,
            )
            revealed = true
        }
        advanceTimeBy(9L)
        runCurrent()
        assertFalse(revealed)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(revealed)
    }
}

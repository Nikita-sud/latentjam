/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun rapidDragPreviewsEveryBucketButJumpsOnlyOnceAtTheEnd() {
        val coordinator = RailScrubCoordinator()
        val previews = mutableListOf<Int>()
        val jumps = mutableListOf<Int>()

        coordinator.begin()
        (0..25).forEach { bucket ->
            // Preview state is deliberately independent from expensive list navigation.
            previews += bucket
            coordinator.preview(bucket)
        }
        coordinator.finish()?.let { jumps += it.bucketIndex }

        assertEquals((0..25).toList(), previews)
        assertEquals(listOf(25), jumps)
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
}

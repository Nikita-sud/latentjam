/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class QueueAdoptionTest {

    @Test
    fun aQueueFromOutsideBecomesTheSourceWithEveryRowNamed() {
        // The library names two of the car's rows; the third is hidden from SMART, so only the player's item names it.
        val library = mapOf("a" to "A (library)", "c" to "C (library)")
        val items = listOf("a", "hidden", "c")

        assertEquals(
            listOf("A (library)", "hidden (from the player)", "C (library)"),
            adoptedQueueRows(items, named = library::get, provisional = { row -> "${items[row]} (from the player)" }),
        )
    }

    @Test
    fun aRowNothingCanNameIsLeftOutAndTheSameTrackTwiceStaysTwoRows() {
        assertEquals(
            listOf("A", "A"),
            adoptedQueueRows(listOf("a", "", "a"), named = { id -> "A".takeIf { id == "a" } }, provisional = { null }),
        )
    }

    @Test
    fun anAnnouncedQueueIsTakenOnceWhenThePlayerHoldsExactlyIt() {
        val rows = listOf("car-a", "car-b").map { MediaItem.Builder().setMediaId(it).build() }
        AndroidPlaybackStarts.announceExternalQueue(rows, C.INDEX_UNSET, shuffled = false)

        // The announcement comes before Media3 installs the queue: the player still holds the queue before it.
        assertTrue(AndroidPlaybackStarts.awaitsExternalQueue)
        assertFalse(AndroidPlaybackStarts.takeExternalQueue(listOf("the", "app's", "own")))
        assertTrue(AndroidPlaybackStarts.takeExternalQueue(listOf("car-a", "car-b")))
        // Taken: a later look at the same rows is the controller's own queue by now.
        assertFalse(AndroidPlaybackStarts.awaitsExternalQueue)
        assertFalse(AndroidPlaybackStarts.takeExternalQueue(listOf("car-a", "car-b")))
    }

    @Test
    fun aQueueTheAppStartsItselfDropsAnAnnouncementThatNeverArrived() {
        val rows = listOf(MediaItem.Builder().setMediaId("car-a").build())
        AndroidPlaybackStarts.announceExternalQueue(rows, C.INDEX_UNSET, shuffled = false)

        AndroidPlaybackStarts.forgetExternalQueue()

        assertFalse(AndroidPlaybackStarts.takeExternalQueue(listOf("car-a")))
    }

    @Test
    fun aVisibleRowIsMovedByItsPlaceInThePlayer() {
        // The player holds a row the queue cannot show at index 1: visible rows 0, 1, 2 are the player's 0, 2, 3.
        val mediaIndices = listOf(0, 2, 3)

        assertEquals(2 to 3, visibleMoveInPlayer(mediaIndices, from = 1, to = 2))
        assertEquals(3 to 0, visibleMoveInPlayer(mediaIndices, from = 2, to = 0))
    }

    @Test
    fun aMoveToTheSameRowOrPastTheQueueMovesNothing() {
        val mediaIndices = listOf(0, 1, 2)

        assertNull(visibleMoveInPlayer(mediaIndices, from = 1, to = 1))
        assertNull(visibleMoveInPlayer(mediaIndices, from = 3, to = 0))
        assertNull(visibleMoveInPlayer(mediaIndices, from = 0, to = -1))
        assertEquals(0 to 2, visibleMoveInPlayer(mediaIndices, from = 0, to = 2))
    }

    /** Applies [ranges] to [ids] the way the controller sends them to the player, one command per run. */
    private fun removed(ids: List<String>, ranges: List<IntRange>): List<String> {
        val queue = ids.toMutableList()
        for (range in ranges) queue.subList(range.first, range.last + 1).clear()
        return queue
    }

    @Test
    fun aFolderDeletedFromALongQueueIsOneCommandNotOnePerRow() {
        // Rows 2000..2499 of a 10,000-row queue are the deleted folder.
        val ids = List(10_000) { "row-$it" }
        val gone = (2_000 until 2_500).mapTo(HashSet()) { "row-$it" }

        val ranges = queueRemovalRanges(ids) { it !in gone }

        assertEquals(listOf(2_000..2_499), ranges)
        assertEquals(ids.filter { it !in gone }, removed(ids, ranges))
    }

    @Test
    fun scatteredRowsGoInRunsFromTheLastDownSoEachRunIsStillInPlace() {
        val ids = listOf("x", "a", "x", "x", "b", "c", "x")

        val ranges = queueRemovalRanges(ids) { it != "x" }

        assertEquals(listOf(6..6, 2..3, 0..0), ranges)
        assertEquals(listOf("a", "b", "c"), removed(ids, ranges))
    }

    @Test
    fun aQueueKeptInFullHasNothingToRemoveAndOneLostInFullIsOneRun() {
        assertEquals(emptyList(), queueRemovalRanges(listOf("a", "b")) { true })
        assertEquals(emptyList(), queueRemovalRanges(emptyList()) { false })
        assertEquals(listOf(0..2), queueRemovalRanges(listOf("a", "b", "c")) { false })
    }
}

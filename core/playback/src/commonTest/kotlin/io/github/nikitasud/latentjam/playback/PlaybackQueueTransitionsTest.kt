/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class PlaybackQueueTransitionsTest {

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")
    private val manual = track("manual")

    @Test
    fun `shuffle off restores source order when current belongs to source`() {
        assertEquals(
            PlaybackQueueOrder(tracks = listOf(a, b, c), currentIndex = 1),
            sourceOrderKeepingCurrent(source = listOf(a, b, c), current = b),
        )
    }

    @Test
    fun `shuffle off keeps an out of pool current item at the playhead`() {
        assertEquals(
            PlaybackQueueOrder(tracks = listOf(manual, a, b, c), currentIndex = 0),
            sourceOrderKeepingCurrent(source = listOf(a, b, c), current = manual),
        )
    }

    @Test
    fun `smart to off requests the complete source queue at the current track`() {
        assertEquals(
            PlaybackQueueOrder(tracks = listOf(a, b, c), currentIndex = 1),
            sourceOrderForShuffleTransition(
                previousMode = ShuffleMode.SMART,
                requestedMode = ShuffleMode.OFF,
                source = listOf(a, b, c),
                current = b,
            ),
        )
    }

    @Test
    fun `smart recommendation remains current ahead of restored source queue`() {
        assertEquals(
            PlaybackQueueOrder(tracks = listOf(manual, a, b, c), currentIndex = 0),
            sourceOrderForShuffleTransition(
                previousMode = ShuffleMode.SMART,
                requestedMode = ShuffleMode.OFF,
                source = listOf(a, b, c),
                current = manual,
            ),
        )
    }

    @Test
    fun `ordinary shuffle off does not replace the platform queue`() {
        assertEquals(
            null,
            sourceOrderForShuffleTransition(
                previousMode = ShuffleMode.ON,
                requestedMode = ShuffleMode.OFF,
                source = listOf(a, b, c),
                current = b,
            ),
        )
    }

    @Test
    fun `play next becomes part of source order immediately after current`() {
        assertEquals(
            listOf(a, b, manual, c),
            sourceQueueAfterManualInsert(
                source = listOf(a, b, c),
                currentId = b.id,
                track = manual,
                insertion = SourceQueueInsertion.PLAY_NEXT,
            ),
        )
    }

    @Test
    fun `play next appends when current is external and never duplicates an existing id`() {
        assertEquals(
            listOf(a, b, manual),
            sourceQueueAfterManualInsert(
                source = listOf(a, b),
                currentId = TrackId("external"),
                track = manual,
                insertion = SourceQueueInsertion.PLAY_NEXT,
            ),
        )
        assertEquals(
            listOf(a, b),
            sourceQueueAfterManualInsert(
                source = listOf(a, b, a),
                currentId = a.id,
                track = b.copy(title = "new metadata"),
                insertion = SourceQueueInsertion.PLAY_NEXT,
            ),
        )
    }

    @Test
    fun `add to queue appends a missing identity to source order`() {
        assertEquals(
            listOf(a, b, manual),
            sourceQueueAfterManualInsert(
                source = listOf(a, b),
                currentId = a.id,
                track = manual,
                insertion = SourceQueueInsertion.APPEND,
            ),
        )
    }

    @Test
    fun `smart retains actual shuffled history through current`() {
        // Physical [a,b,c,manual], actual traversal [c,a,manual,b], current manual.
        assertEquals(
            listOf(c, a, manual),
            traversalHistoryThroughCurrent(
                rows = listOf(c, a, manual, b),
                currentRowIndex = 2,
            ),
        )
        assertEquals(
            emptyList(),
            traversalHistoryThroughCurrent(rows = listOf(a, b), currentRowIndex = -1),
        )
    }

    @Test
    fun `smart append commits only against the exact queue snapshot`() {
        val queue = listOf(a, b)
        val guard = SmartAppendGuard(queueGeneration = 7L, queueSize = 2, tailId = b.id)

        assertTrue(canCommitSmartAppend(ShuffleMode.SMART, 7L, queue, guard, c.id))
        assertFalse(canCommitSmartAppend(ShuffleMode.OFF, 7L, queue, guard, c.id))
        assertFalse(canCommitSmartAppend(ShuffleMode.SMART, 8L, queue, guard, c.id))
        assertFalse(canCommitSmartAppend(ShuffleMode.SMART, 7L, listOf(a), guard, c.id))
        assertFalse(canCommitSmartAppend(ShuffleMode.SMART, 7L, listOf(b, a), guard, c.id))
        assertFalse(canCommitSmartAppend(ShuffleMode.SMART, 7L, queue, guard, a.id))
    }

    @Test
    fun `load traversal skips forward without wrapping`() {
        assertContentEquals(
            intArrayOf(1, 2, 3),
            playbackQueueTraversal(queueSize = 4, startIndex = 1, direction = 1, wrap = false),
        )
    }

    @Test
    fun `load traversal wraps once and remains bounded`() {
        assertContentEquals(
            intArrayOf(3, 0, 1, 2),
            playbackQueueTraversal(queueSize = 4, startIndex = 3, direction = 1, wrap = true),
        )
        assertContentEquals(
            intArrayOf(3, 2, 1, 0),
            playbackQueueTraversal(queueSize = 4, startIndex = -1, direction = -1, wrap = true),
        )
    }

    @Test
    fun `load traversal rejects an invalid direction`() {
        assertFailsWith<IllegalArgumentException> {
            playbackQueueTraversal(queueSize = 4, startIndex = 0, direction = 0, wrap = false)
        }
    }

    @Test
    fun `error recovery advances once through remaining rows`() {
        assertContentEquals(
            intArrayOf(2, 3),
            playbackErrorRecoveryTraversal(queueSize = 4, failedIndex = 1, repeatAll = false),
        )
        assertContentEquals(
            intArrayOf(3, 0, 1),
            playbackErrorRecoveryTraversal(queueSize = 4, failedIndex = 2, repeatAll = true),
        )
        assertContentEquals(
            intArrayOf(0, 3, 1),
            playbackErrorRecoveryTraversal(
                queueSize = 4,
                failedIndex = 2,
                repeatAll = true,
                traversalOrder = intArrayOf(2, 0, 3, 1),
            ),
        )
        assertContentEquals(
            intArrayOf(),
            playbackErrorRecoveryTraversal(queueSize = 1, failedIndex = 0, repeatAll = true),
        )
    }

    @Test
    fun `error recovery remembers failed identities across reordered indices`() {
        assertEquals(
            3,
            nextPlaybackErrorRecoveryIndex(
                mediaIds = listOf("d", "a", "b", "c"),
                failedIndex = 1,
                repeatAll = true,
                traversalOrder = intArrayOf(1, 2, 3, 0),
                failedIds = setOf("a", "b"),
            ),
        )
        assertEquals(
            null,
            nextPlaybackErrorRecoveryIndex(
                mediaIds = listOf("a", "b"),
                failedIndex = 0,
                repeatAll = true,
                traversalOrder = intArrayOf(0, 1),
                failedIds = setOf("a", "b"),
            ),
        )
    }

    @Test
    fun `linked shuffle traversal becomes a bounded permutation`() {
        val successors = mapOf(2 to 0, 0 to 3, 3 to 1, 1 to -1)

        assertContentEquals(
            intArrayOf(2, 0, 3, 1),
            boundedQueueOrder(queueSize = 4, firstIndex = 2) { successors[it] ?: -1 },
        )
    }

    @Test
    fun `malformed linked shuffle traversal falls back to natural order`() {
        assertContentEquals(
            intArrayOf(0, 1, 2, 3),
            boundedQueueOrder(queueSize = 4, firstIndex = 2) { 2 },
        )
        assertContentEquals(
            intArrayOf(0, 1, 2, 3),
            boundedQueueOrder(queueSize = 4, firstIndex = 9) { -1 },
        )
    }

    @Test
    fun `queue snapshot maps shuffled rows back to physical media indices`() {
        val snapshot = playbackQueueSnapshot(
            physicalRows = listOf(a, null, b, c),
            traversalOrder = intArrayOf(2, 0, 3, 1),
            currentMediaItemIndex = 3,
        )

        assertEquals(listOf(b, a, c), snapshot.rows)
        assertEquals(listOf(2, 0, 3), snapshot.mediaItemIndices)
        assertEquals(2, snapshot.currentRowIndex)
    }

    @Test
    fun `play next extends shuffle order immediately after current`() {
        assertContentEquals(
            intArrayOf(2, 0, 4, 3, 1),
            shuffleOrderAppendingNext(
                existingOrder = intArrayOf(2, 0, 3, 1),
                currentMediaItemIndex = 0,
            ),
        )
    }

    @Test
    fun `play next normalizes a malformed existing shuffle order`() {
        assertContentEquals(
            intArrayOf(0, 1, 3, 2),
            shuffleOrderAppendingNext(
                existingOrder = intArrayOf(0, 0, 2),
                currentMediaItemIndex = 1,
            ),
        )
    }

    @Test
    fun `a consumed audio segment restarts but a paused segment resumes`() {
        assertFalse(shouldRestartConsumedSegment(pausedFrame = 41L, fileLength = 100L))
        assertTrue(shouldRestartConsumedSegment(pausedFrame = 100L, fileLength = 100L))
    }

    @Test
    fun `only the current playback item generation may publish completion`() {
        assertTrue(isCurrentPlaybackItemGeneration(expected = 9L, current = 9L))
        assertFalse(isCurrentPlaybackItemGeneration(expected = 8L, current = 9L))
    }

    private fun track(id: String): TrackDescriptor = TrackDescriptor(TrackId(id), title = id)
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueReconciliationTest {

    @Test
    fun partialNonemptyScanPreservesMusicRowsWhosePermissionIsUnavailable() {
        val partial = AutomaticIndexingRequest(
            tracks = listOf(track("imported-file")),
            librarySnapshotAuthoritative = false,
        )

        assertNull(queueRetentionIds(partial, hiddenTracks = listOf(track("hidden-file"))))
    }

    @Test
    fun sameEmptyRowsOnlyClearTheQueueAfterTheScanBecomesAuthoritative() {
        val partial = AutomaticIndexingRequest(emptyList(), librarySnapshotAuthoritative = false)
        val complete = partial.copy(librarySnapshotAuthoritative = true)

        assertNull(queueRetentionIds(partial, hiddenTracks = emptyList()))
        assertEquals(emptySet(), queueRetentionIds(complete, hiddenTracks = emptyList()))
    }

    @Test
    fun aSourceSwitchedOffKeepsItsTracksInThePlayingQueue() {
        // Its songs are in no list: not the visible library, and not the hidden one either.
        val visible = track("visible")
        val playing = track("in-the-folder-switched-off")
        val deleted = track("deleted")
        val complete = AutomaticIndexingRequest(
            listOf(visible), librarySnapshotAuthoritative = true, notShown = setOf(playing.id),
        )

        val retained = queueRetentionIds(complete, hiddenTracks = emptyList()).orEmpty()

        assertEquals(listOf(visible, playing), listOf(visible, playing, deleted).filter { it.id in retained })
    }

    @Test
    fun authoritativeScanRetainsVisibleAndHiddenTracksButExcludesDeletedRows() {
        val visible = track("visible")
        val hidden = track("hidden")
        val deleted = track("deleted")
        val complete = AutomaticIndexingRequest(listOf(visible), librarySnapshotAuthoritative = true)
        val retained = queueRetentionIds(complete, hiddenTracks = listOf(hidden, visible)).orEmpty()
        val existingQueue = listOf(visible, hidden, deleted, hidden)

        assertEquals(setOf(visible.id, hidden.id), retained)
        assertEquals(listOf(visible, hidden, hidden), existingQueue.filter { it.id in retained })
    }

    private fun track(id: String) = TrackDescriptor(id = TrackId(id))
}

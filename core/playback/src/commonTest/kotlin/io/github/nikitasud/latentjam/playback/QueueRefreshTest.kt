/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class QueueRefreshTest {

    private fun track(id: String, title: String) = TrackDescriptor(TrackId(id), title = title)

    @Test
    fun refreshedTracksReplaceQueuedDescriptorsInPlace() {
        val queue = listOf(track("a", "A"), track("b", "B"), track("a", "A"), track("c", "C"))
        val refreshed = refreshedTracks(queue, mapOf(TrackId("a") to track("a", "A2"), TrackId("x") to track("x", "X")))
        assertEquals(listOf("A2", "B", "A2", "C"), refreshed?.map { it.title })
    }

    @Test
    fun aRefreshThatChangesNothingSaysSo() {
        val queue = listOf(track("a", "A"))
        assertNull(refreshedTracks(queue, mapOf(TrackId("a") to track("a", "A"))))
        assertNull(refreshedTracks(queue, emptyMap()))
    }

    private val albumArt = "content://media/external/audio/albumart/12?v=1"

    @Test
    fun onlyTheSongWhoseOwnCoverWasSavedGetsANewCover() {
        // The queue holds a whole album; the save changed one song's file, and only that song is refreshed.
        val queue = listOf("a", "b", "c")
        val previous = queue.associate { TrackId(it) to albumArt }
        val updates = mapOf(TrackId("b") to TrackDescriptor(TrackId("b"), artworkUri = "file:///covers/0a1b2c3d.jpg"))
        assertEquals(listOf(1 to true), refreshedItems(queue, previous, updates))
    }

    @Test
    fun aRefreshThatKeepsTheCoverLeavesTheItemsArtworkAlone() {
        val queue = listOf("a", "b", "a")
        val previous = mapOf(TrackId("a") to albumArt, TrackId("b") to albumArt)
        val updates = mapOf(TrackId("a") to TrackDescriptor(TrackId("a"), title = "New", artworkUri = albumArt))
        assertEquals(listOf(0 to false, 2 to false), refreshedItems(queue, previous, updates))
    }

    @Test
    fun aRemovedCoverAndATrackNotHeldBeforeBothChangeTheCover() {
        val queue = listOf("a", "b")
        val previous = mapOf(TrackId("a") to albumArt)
        val updates = mapOf(
            TrackId("a") to TrackDescriptor(TrackId("a"), artworkUri = null),
            TrackId("b") to TrackDescriptor(TrackId("b"), artworkUri = albumArt),
        )
        assertEquals(listOf(0 to true, 1 to true), refreshedItems(queue, previous, updates))
    }
}

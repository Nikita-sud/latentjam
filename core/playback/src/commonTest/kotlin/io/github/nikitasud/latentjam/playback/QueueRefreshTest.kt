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
}

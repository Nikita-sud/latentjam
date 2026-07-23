/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class SmartQueueEligibilityTest {

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    @Test
    fun `explicitly empty eligible library never falls back to the unfiltered pool`() {
        assertEquals(
            emptyList(),
            smartCandidatePool(
                eligibleLibrary = emptyList(),
                fallbackPool = listOf(a, b),
                eligibleLibrarySupplied = true,
            ),
        )
        assertEquals(
            listOf(a, b),
            smartCandidatePool(
                eligibleLibrary = emptyList(),
                fallbackPool = listOf(a, b),
                eligibleLibrarySupplied = false,
            ),
        )
    }

    @Test
    fun `eligibility refresh keeps history and current track but prunes the future tail`() {
        assertEquals(
            listOf(a, b),
            retainEligibleSmartTail(
                queue = listOf(a, b, c),
                currentIndex = 1,
                eligibleIds = setOf(a.id),
            ),
        )
    }

    @Test
    fun `a cued seed survives before the platform publishes its current index`() {
        assertEquals(
            listOf(a),
            retainEligibleSmartTail(
                queue = listOf(a, b),
                currentIndex = -1,
                eligibleIds = emptySet(),
            ),
        )
    }

    private fun track(id: String) = TrackDescriptor(TrackId(id), title = id.uppercase())
}

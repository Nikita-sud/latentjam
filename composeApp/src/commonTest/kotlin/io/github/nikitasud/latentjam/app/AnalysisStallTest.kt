/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Issue #27: a frozen analysis says what it waits on instead of showing "No analysis problems". */
internal class AnalysisStallTest {

    private val track = TrackId("waiting")

    @Test
    fun `a track past the limit is named with its minutes`() {
        assertEquals(
            AnalysisStall(minutes = 7, trackId = track),
            analysisStall(running = true, trackId = track, onTrackFor = 7.minutes + 30.seconds, sinceProgress = 9.minutes),
        )
    }

    @Test
    fun `a track inside its own deadline is no stall`() {
        assertNull(analysisStall(running = true, trackId = track, onTrackFor = 90.seconds, sinceProgress = 5.minutes))
    }

    @Test
    fun `no progress without a known track is an unnamed stall`() {
        assertEquals(
            AnalysisStall(minutes = 3, trackId = null),
            analysisStall(running = true, trackId = null, onTrackFor = null, sinceProgress = 3.minutes),
        )
        assertNull(analysisStall(running = true, trackId = null, onTrackFor = null, sinceProgress = 1.minutes))
        assertNull(analysisStall(running = true, trackId = null, onTrackFor = null, sinceProgress = null))
    }

    @Test
    fun `a stopped analysis is never stalled`() {
        assertNull(analysisStall(running = false, trackId = track, onTrackFor = 60.minutes, sinceProgress = 60.minutes))
    }
}

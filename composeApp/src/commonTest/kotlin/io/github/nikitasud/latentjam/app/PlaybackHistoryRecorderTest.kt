/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PlaybackHistoryRecorderTest {

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    @Test
    fun `disabled recording never emits sessions`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = false)

        assertNull(gate.onSnapshot(now(a, 0), enabled = false, nowMs = 1))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = false, nowMs = 2))
        assertNull(gate.onSnapshot(now(b, 0), enabled = false, nowMs = 3))
        assertNull(gate.onSnapshot(now(c, 0), enabled = false, nowMs = 4))
    }

    @Test
    fun `disabling drops the current session and enabling waits for the next track`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)

        assertNull(gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = true, nowMs = 2))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = false, nowMs = 3))
        assertNull(gate.onSnapshot(now(a, 95_000), enabled = true, nowMs = 4))
        assertNull(gate.onSnapshot(now(b, 0), enabled = true, nowMs = 5))
        assertNull(gate.onSnapshot(now(b, 60_000), enabled = true, nowMs = 6))

        val event = gate.onSnapshot(now(c, 0), enabled = true, nowMs = 7)
        assertEquals(b.id, event?.trackId)
        assertEquals(60_000, event?.playedMs)
    }

    @Test
    fun `recording enabled from launch keeps the normal transition behavior`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)

        assertNull(gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = true, nowMs = 2))

        val event = gate.onSnapshot(now(b, 0), enabled = true, nowMs = 3)
        assertEquals(a.id, event?.trackId)
        assertEquals(90_000, event?.playedMs)
    }

    private fun track(id: String) = TrackDescriptor(id = TrackId(id), title = id.uppercase())

    private fun now(track: TrackDescriptor, positionMs: Long) = NowPlaying(
        track = track,
        // These fixtures model live listening; a session only opens for a PLAYING track
        // (a restored-but-parked queue must not turn into phantom skips).
        isPlaying = true,
        positionMs = positionMs,
        durationMs = 100_000,
    )
}

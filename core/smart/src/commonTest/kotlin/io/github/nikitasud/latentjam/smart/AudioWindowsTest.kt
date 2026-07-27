/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Window placement is measured, not chosen by taste. On an 809-track offline sweep over a dense
 * 24-position grid, six windows spread across the middle 20–80 % of the track scored genre
 * retrieval R@1 0.333 against 0.298 for the previous three, and reached 99 % cosine convergence
 * to a full 24-window embedding. Spreading the same six across the whole track instead (0–100 %)
 * fell to 0.311, because intros, outros and fades pollute the pooled vector.
 */
class AudioWindowsTest {

    private val windowMs = 10_000L

    @Test
    fun `a long track is covered by six windows across the middle of its span`() {
        val durationMs = 210_000L
        val span = durationMs - windowMs

        val starts = AudioWindows.startsMs(durationMs, windowMs)

        assertEquals(6, starts.size, "six windows is the measured knee")
        val expected = listOf(0.2, 0.32, 0.44, 0.56, 0.68, 0.8)
            .map { fraction -> (span * fraction).toLong() }
        assertEquals(expected, starts)
    }

    @Test
    fun `windows never reach the first or last fifth of the span`() {
        val durationMs = 300_000L
        val span = durationMs - windowMs

        val starts = AudioWindows.startsMs(durationMs, windowMs)

        assertTrue(starts.first() >= (span * 0.2).toLong(), "must not start in the intro")
        assertTrue(starts.last() <= (span * 0.8).toLong(), "must not start in the outro")
    }

    @Test
    fun `starts are strictly increasing and every window fits inside the track`() {
        val durationMs = 187_000L

        val starts = AudioWindows.startsMs(durationMs, windowMs)

        assertTrue(starts.isNotEmpty(), "a decodable track must yield at least one window")
        assertEquals(starts.sorted(), starts, "windows are emitted in playback order")
        assertEquals(starts.distinct(), starts, "no window is inferred twice")
        assertTrue(starts.all { it >= 0 && it + windowMs <= durationMs }, "window overruns track")
    }

    @Test
    fun `a track no longer than one window yields a single window at the start`() {
        assertEquals(listOf(0L), AudioWindows.startsMs(windowMs, windowMs))
        assertEquals(listOf(0L), AudioWindows.startsMs(4_000L, windowMs))
    }

    @Test
    fun `an unknown duration yields a single window at the start`() {
        assertEquals(listOf(0L), AudioWindows.startsMs(null, windowMs))
    }

    @Test
    fun `a track only slightly longer than one window still yields distinct windows`() {
        // span is 60 ms here; naive rounding collapses every fraction onto 0 and the caller
        // would pay six decodes for one distinct crop.
        val starts = AudioWindows.startsMs(windowMs + 60L, windowMs)

        assertEquals(starts.distinct(), starts, "duplicate crops waste a decode and an inference")
        assertTrue(starts.isNotEmpty())
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentCompletionTest {
    // Four minutes at 44,1 kHz, a second of tolerance.
    private val end = 10_584_000L
    private val second = 44_100L

    private fun meaning(
        tokenCurrent: Boolean = true,
        engineRunning: Boolean = true,
        renderedFrame: Long = end,
        currentItem: Boolean = true,
        playing: Boolean = true,
        pauseFading: Boolean = false,
        overlapSounding: Boolean = false,
    ) = completionMeaning(
        tokenCurrent, engineRunning, renderedFrame, segmentEndFrame = end, toleranceFrames = second,
        currentItem, playing, pauseFading, overlapSounding,
    )

    @Test
    fun `a segment replaced here means nothing whatever else holds`() {
        for (running in listOf(true, false)) for (playing in listOf(true, false)) for (rendered in listOf(0L, end)) {
            assertEquals(
                CompletionMeaning.IGNORE,
                meaning(tokenCurrent = false, engineRunning = running, playing = playing, renderedFrame = rendered),
            )
        }
    }

    @Test
    fun `a track that played out while playing moves on`() {
        assertEquals(CompletionMeaning.ADVANCE, meaning())
        // A file whose audio stops short of its stated length ends where it ends: the engine ran, so it played.
        assertEquals(CompletionMeaning.ADVANCE, meaning(renderedFrame = end - 30 * second))
    }

    @Test
    fun `the end of a row no longer at the playhead means nothing`() {
        assertEquals(CompletionMeaning.IGNORE, meaning(currentItem = false))
        assertEquals(CompletionMeaning.IGNORE, meaning(currentItem = false, engineRunning = false, playing = false))
    }

    @Test
    fun `inside a pauses fade the overlaps track takes over and a track alone is remembered as ended`() {
        assertEquals(CompletionMeaning.ADVANCE, meaning(playing = false, pauseFading = true, overlapSounding = true))
        assertEquals(CompletionMeaning.REMEMBER_ENDED, meaning(playing = false, pauseFading = true, overlapSounding = false))
    }

    @Test
    fun `an end that arrives after the pause is remembered not dropped`() {
        // The listener's pause, or an interruption's, landed a moment before the notice: the engine is paused.
        assertEquals(CompletionMeaning.REMEMBER_ENDED, meaning(engineRunning = false, playing = false))
        assertEquals(CompletionMeaning.REMEMBER_ENDED, meaning(engineRunning = false, playing = false, renderedFrame = end - second))
        assertEquals(CompletionMeaning.REMEMBER_ENDED, meaning(engineRunning = true, playing = false))
    }

    @Test
    fun `an end with the output stopped under a playing track is remembered until the output is back`() {
        // Moving on now would load the next track into an output that will not start.
        assertEquals(CompletionMeaning.REMEMBER_ENDED, meaning(engineRunning = false, playing = true))
        assertEquals(CompletionMeaning.REMEMBER_ENDED, meaning(engineRunning = false, playing = true, overlapSounding = true))
    }

    @Test
    fun `a node the system emptied in the middle of a track has not ended and is cued again`() {
        for (playing in listOf(true, false)) {
            assertEquals(
                CompletionMeaning.CUE_AGAIN,
                meaning(engineRunning = false, playing = playing, renderedFrame = 60 * second),
            )
            assertEquals(
                CompletionMeaning.CUE_AGAIN,
                meaning(engineRunning = false, playing = playing, renderedFrame = end - second - 1),
            )
        }
    }

    @Test
    fun `the two halves of the table`() {
        assertEquals(SegmentEnd.STALE, segmentEnd(false, true, end, end, second))
        assertEquals(SegmentEnd.PLAYED_OUT, segmentEnd(true, true, 0L, end, second))
        assertEquals(SegmentEnd.PLAYED_OUT, segmentEnd(true, false, end - second, end, second))
        assertEquals(SegmentEnd.FLUSHED, segmentEnd(true, false, end - second - 1, end, second))
        // The Music-library player has no engine to stop: its output always counts as running.
        assertEquals(ItemEnd.ADVANCE, itemEnd(currentItem = true, playing = true, pauseFading = false, outputRunning = true, overlapSounding = false))
        assertEquals(ItemEnd.REMEMBER, itemEnd(currentItem = true, playing = false, pauseFading = false, outputRunning = true, overlapSounding = false))
        assertEquals(ItemEnd.IGNORE, itemEnd(currentItem = false, playing = true, pauseFading = false, outputRunning = true, overlapSounding = false))
    }

    @Test
    fun `a Play on a row remembered as ended moves on when there is something to move on to`() {
        assertTrue(playsOutRememberedEnd(endRemembered = true, repeatOne = false, smart = false, rowsAhead = 1))
        assertTrue(playsOutRememberedEnd(endRemembered = true, repeatOne = true, smart = false, rowsAhead = 0))
        // SMART plans the next row when the end is reported.
        assertTrue(playsOutRememberedEnd(endRemembered = true, repeatOne = false, smart = true, rowsAhead = 0))
    }

    @Test
    fun `at the end of the queue or with no end remembered a Play is what it always was`() {
        assertFalse(playsOutRememberedEnd(endRemembered = true, repeatOne = false, smart = false, rowsAhead = 0))
        assertFalse(playsOutRememberedEnd(endRemembered = false, repeatOne = true, smart = true, rowsAhead = 5))
    }
}

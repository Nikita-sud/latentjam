/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GaplessJoinTest {
    private fun wanted(
        crossfadeMs: Long = 0L,
        repeatOne: Boolean = false,
        finishingTrack: Boolean = false,
        fileBackend: Boolean = true,
        nextIsLocalFile: Boolean = true,
        nextIsSameTrack: Boolean = false,
        continuesAlbum: Boolean = true,
    ) = gaplessJoinWanted(crossfadeMs, repeatOne, finishingTrack, fileBackend, nextIsLocalFile, nextIsSameTrack, continuesAlbum)

    @Test
    fun `the next track of an album that runs on is queued behind the current one`() {
        assertTrue(wanted())
    }

    @Test
    fun `every other case keeps the plain seam`() {
        assertFalse(wanted(crossfadeMs = 4_000L), "smooth transitions overlap instead")
        assertFalse(wanted(repeatOne = true), "repeat-one starts the same track again")
        assertFalse(wanted(finishingTrack = true), "the sleep timer holds the player to the end of this track")
        assertFalse(wanted(fileBackend = false), "the Music library's player")
        assertFalse(wanted(nextIsLocalFile = false), "a Music-library song next")
        assertFalse(wanted(nextIsSameTrack = true), "the same track queued twice")
        assertFalse(wanted(continuesAlbum = false), "two unrelated tracks")
    }

    @Test
    fun `the successor is queued in the last three seconds and not before`() {
        assertFalse(withinGaplessLead(positionMs = 100_000L, durationMs = 240_000L))
        assertFalse(withinGaplessLead(positionMs = 236_999L, durationMs = 240_000L))
        assertTrue(withinGaplessLead(positionMs = 237_000L, durationMs = 240_000L))
        assertTrue(withinGaplessLead(positionMs = 239_990L, durationMs = 240_000L))
        // A track shorter than the lead is in it from its start; a length nobody knows is never near its end.
        assertTrue(withinGaplessLead(positionMs = 0L, durationMs = 2_000L))
        assertFalse(withinGaplessLead(positionMs = 5_000L, durationMs = 0L))
    }

    @Test
    fun `the next tracks position counts from the seam`() {
        // Played from its start: 10,584,000 frames, then frame 0 of the next file.
        val start = seamSegmentStart(previousSegmentStart = 0L, previousLength = 10_584_000L)
        assertEquals(0L, start + 10_584_000L)
        assertEquals(44_100L, start + 10_628_100L)
        // After a seek to frame 9,000,000 the clock began there: the seam comes 1,584,000 frames in.
        val afterSeek = seamSegmentStart(previousSegmentStart = 9_000_000L, previousLength = 10_584_000L)
        assertEquals(0L, afterSeek + 1_584_000L)
        // And through a second seam: the clock still counts from the first track's cue.
        val third = seamSegmentStart(previousSegmentStart = start, previousLength = 8_000_000L)
        assertEquals(0L, third + 10_584_000L + 8_000_000L)
    }

    @Test
    fun `the queued row is adopted only when it is still the one that follows`() {
        assertEquals(SeamAdoption.ADOPT, seamAdoption(joinKnown = true, queuedRowStillNext = true, generationAsExpected = true))
        assertEquals(SeamAdoption.RELOAD, seamAdoption(joinKnown = true, queuedRowStillNext = false, generationAsExpected = true))
        assertEquals(SeamAdoption.RELOAD, seamAdoption(joinKnown = false, queuedRowStillNext = true, generationAsExpected = true))
        assertEquals(SeamAdoption.RELOAD, seamAdoption(joinKnown = true, queuedRowStillNext = true, generationAsExpected = false))
    }

    @Test
    fun `the join is looked at often near the seam and seldom far from it`() {
        assertEquals(GAPLESS_IDLE_TICK_MS, gaplessTickMs(positionMs = 0L, durationMs = 240_000L))
        // Half a second before the lead begins: then.
        assertEquals(500L, gaplessTickMs(positionMs = 236_500L, durationMs = 240_000L))
        assertEquals(GAPLESS_NEAR_TICK_MS, gaplessTickMs(positionMs = 236_990L, durationMs = 240_000L))
        assertEquals(GAPLESS_NEAR_TICK_MS, gaplessTickMs(positionMs = 239_000L, durationMs = 240_000L))
        assertEquals(GAPLESS_IDLE_TICK_MS, gaplessTickMs(positionMs = 5_000L, durationMs = 0L))
    }
}

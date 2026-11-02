/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FadeEntryTest {
    /** Stereo at 1 kHz, fed in odd-sized chunks; [levelDb] gives each second's level in dBFS. */
    private fun endOf(lengthS: Int, levelDb: (Double) -> Double?): FadeEnd? {
        val tracker = FadeEndTracker(sampleRate = 1000, channels = 2)
        val frames = lengthS * 1000
        var frame = 0
        while (frame < frames) {
            val count = minOf(173, frames - frame)
            val chunk = FloatArray(count * 2) { i ->
                val f = frame + i / 2
                val db = levelDb(f / 1000.0)
                val amplitude = if (db == null) 0f else 10.0.pow(db / 20.0).toFloat()
                if (f % 2 == 0) amplitude else -amplitude
            }
            tracker.add(chunk, count)
            frame += count
        }
        return tracker.finish()
    }

    @Test
    fun aFadeOutGetsBothStartsAndItsLastSound() {
        // 120 s at -10 dBFS, a 20 s dB-linear fade to -70, then 5 s of silence.
        val end = assertNotNull(endOf(145) { t -> if (t < 120) -10.0 else if (t < 140) -10.0 - 3.0 * (t - 120) else null })
        // The last sound is where the fade crosses -50 dBFS (40 dB down: 13.3 s in).
        assertTrue(end.lastSoundMs in 133_000L..134_000L, end.toString())
        val knee = assertNotNull(end.kneeLeadMs, end.toString())
        val visible = assertNotNull(end.visibleLeadMs, end.toString())
        // The knee sits at the fade's start; the visible start a dB or so of fall later.
        assertTrue(knee in 12_000L..15_000L, end.toString())
        assertTrue(visible in 1_000L..knee, end.toString())
    }

    @Test
    fun aColdEndGetsNoFadeEntry() {
        val end = assertNotNull(endOf(150) { -12.0 })
        assertEquals(150_000L, end.lastSoundMs)
        assertNull(end.kneeLeadMs)
        assertNull(end.visibleLeadMs)
    }

    @Test
    fun aTrackWithNoSoundHasNoEnding() {
        assertNull(endOf(30) { null })
    }

    @Test
    fun theEntryIsCappedAndTooShortOnesKeepTheCrossfade() {
        val fade = FadeEnd(lastSoundMs = 200_000L, kneeLeadMs = 30_000L, visibleLeadMs = 800L)
        assertEquals(FADE_ENTRY_MAX_MS, fadeEntryOverlapMs(fade, FadeEntryRule.KNEE, 200_000L, 240_000L))
        // A third of the incoming track at most.
        assertEquals(10_000L, fadeEntryOverlapMs(fade, FadeEntryRule.KNEE, 200_000L, 30_000L))
        // Under a second is no different from a crossfade.
        assertNull(fadeEntryOverlapMs(fade, FadeEntryRule.VISIBLE, 200_000L, 240_000L))
        assertNull(fadeEntryOverlapMs(FadeEnd(200_000L, null, null), FadeEntryRule.KNEE, 200_000L, 240_000L))
        assertNull(fadeEntryOverlapMs(null, FadeEntryRule.KNEE, 200_000L, 240_000L))
    }

    @Test
    fun aFadeEntryHoldsTheOutgoingTrackToItsEnd() {
        fun at(positionMs: Long, holds: Boolean) = deckEnvelope(
            positionMs = positionMs, durationMs = 200_000L, fadeMs = 6_000L, edges = FadeEdges.BOTH,
            riseMs = 0L, joinedByOverlap = false, curveMs = 15_000L, curveEndMs = 190_000L, holdsCurve = holds,
        )
        assertEquals(1f, at(185_000L, holds = true))
        assertTrue(at(185_000L, holds = false) < 0.8f)
        assertEquals(0f, at(191_000L, holds = true))
    }
}

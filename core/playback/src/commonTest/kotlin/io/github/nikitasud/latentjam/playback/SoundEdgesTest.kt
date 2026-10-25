/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundEdgesTest {
    /** Mono at 1 kHz: frames and ms are the same numbers. [level] gives each frame's amplitude. */
    private fun spanOf(lengthMs: Int, level: (Int) -> Float): SoundSpan? {
        val tracker = SoundSpanTracker(sampleRate = 1000, channels = 1)
        var frame = 0
        while (frame < lengthMs) {
            val count = minOf(37, lengthMs - frame) // chunks that do not line up with the windows
            tracker.add(FloatArray(count) { level(frame + it) * if (it % 2 == 0) 1f else -1f }, count)
            frame += count
        }
        return tracker.finish()
    }

    @Test
    fun leadingAndTrailingSilenceLieOutsideTheSpan() {
        val span = spanOf(10_000) { frame -> if (frame in 1_200 until 8_600) 0.3f else 0f }
        assertEquals(SoundSpan(1_200, 8_600), span)
    }

    @Test
    fun aQuietPassageAndAHiddenBonusTrackStayInside() {
        // Music, then a long gap, then a bonus track: the last sound is the bonus's end.
        val span = spanOf(60_000) { frame ->
            when (frame) {
                in 0 until 20_000 -> 0.3f
                in 20_000 until 22_000 -> 0.01f // -40 dBFS: quiet, but sound
                in 22_000 until 50_000 -> 0f
                in 50_000 until 58_000 -> 0.2f
                else -> 0f
            }
        }
        assertEquals(SoundSpan(0, 58_000), span)
    }

    @Test
    fun hissBelowTheThresholdIsSilenceAndAFileWithNoSoundHasNoSpan() {
        assertNull(spanOf(5_000) { 0.001f }) // -60 dBFS
        assertEquals(SoundSpan(1_000, 2_000), spanOf(5_000) { frame -> if (frame in 1_000 until 2_000) 0.5f else 0.001f })
    }

    @Test
    fun anOverlapMeetsTheTracksAtTheirSoundKeepingAGuard() {
        val anchors = seamAnchors(200_000, SoundSpan(0, 190_000), SoundSpan(1_500, 180_000))
        assertEquals(SeamAnchors(outgoingEndMs = 190_100, incomingStartMs = 1_400), anchors)
        assertEquals(9_900, anchors.trailMs(200_000))
    }

    @Test
    fun shortEdgesUnmeasuredTracksAndImplausibleSpansKeepTheFilesEdges() {
        val asBefore = SeamAnchors(outgoingEndMs = 200_000, incomingStartMs = 0)
        assertEquals(asBefore, seamAnchors(200_000, null, null))
        // Silence shorter than the minimum is how the track was mastered.
        assertEquals(asBefore, seamAnchors(200_000, SoundSpan(0, 199_800), SoundSpan(350, 180_000)))
        // An end past the file, or a silence longer than any real one, is not trusted.
        assertEquals(asBefore, seamAnchors(200_000, SoundSpan(0, 205_000), SoundSpan(45_000, 180_000)))
        assertEquals(asBefore, seamAnchors(200_000, SoundSpan(0, 120_000), null))
    }

    @Test
    fun aDeckEnvelopeRisesFromTheFirstSoundAndEndsAtTheLast() {
        // The rise over 3 s from 1 s in; before it the track is silent anyway.
        val rising = deckEnvelope(2_500, 180_000, 12_000, FadeEdges.BOTH, 3_000, true, 0, riseFromMs = 1_000)
        assertEquals(overlapInGain(0.5f), rising)
        assertEquals(1f, deckEnvelope(4_000, 180_000, 12_000, FadeEdges.BOTH, 3_000, true, 0, riseFromMs = 1_000))
        // The out-curve over 3 s ending at 170 s; the trailing silence after it stays silent.
        fun end(positionMs: Long) =
            deckEnvelope(positionMs, 180_000, 12_000, FadeEdges.BOTH, 0, false, curveMs = 3_000, curveEndMs = 170_000)
        assertEquals(1f, end(160_000))
        assertEquals(overlapOutGain(0.5f), end(168_500))
        assertEquals(0f, end(175_000))
    }
}

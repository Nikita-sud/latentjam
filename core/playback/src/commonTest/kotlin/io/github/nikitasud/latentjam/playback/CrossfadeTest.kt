/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CrossfadeTest {

    @Test
    fun fadesRiseAtTheStartAndFallAtTheEnd() {
        val duration = 180_000L
        val fade = 4_000L
        assertEquals(0f, crossfadeFactor(0, duration, fade))
        assertEquals(0.5f, crossfadeFactor(2_000, duration, fade))
        assertEquals(1f, crossfadeFactor(4_000, duration, fade))
        assertEquals(1f, crossfadeFactor(90_000, duration, fade))
        assertEquals(0.5f, crossfadeFactor(178_000, duration, fade))
        assertEquals(0f, crossfadeFactor(180_000, duration, fade))
    }

    @Test
    fun zeroFadeMeansFullVolumeEverywhere() {
        assertEquals(1f, crossfadeFactor(0, 180_000, 0))
        assertEquals(1f, crossfadeFactor(179_999, 180_000, 0))
    }

    @Test
    fun aShortTrackStillGetsBothSlopes() {
        // 6 s track with a 12 s fade: each slope shrinks to 3 s.
        assertEquals(0f, crossfadeFactor(0, 6_000, 12_000))
        assertEquals(1f, crossfadeFactor(3_000, 6_000, 12_000))
        assertEquals(0f, crossfadeFactor(6_000, 6_000, 12_000))
    }

    @Test
    fun unknownDurationFadesInButNeverMutesMidPlay() {
        assertEquals(0.5f, crossfadeFactor(2_000, 0, 4_000))
        assertEquals(1f, crossfadeFactor(3_600_000, 0, 4_000))
    }

    @Test
    fun sanitizerClampsToTheSliderRange() {
        assertEquals(0, sanitizeCrossfadeSeconds(-3))
        assertEquals(7, sanitizeCrossfadeSeconds(7))
        assertEquals(MAX_CROSSFADE_SECONDS, sanitizeCrossfadeSeconds(99))
    }

    @Test
    fun normalizationGainMovesSmoothlyInDbAndEndsExactlyAtTarget() {
        assertEquals(1f, normalizationRampVolume(1f, 0.25f, elapsedMs = 0))
        assertEquals(0.5f, normalizationRampVolume(1f, 0.25f, elapsedMs = 200))
        assertEquals(0.25f, normalizationRampVolume(1f, 0.25f, elapsedMs = 400))
        assertEquals(1f, normalizationRampVolume(0.25f, 1f, elapsedMs = 400))
    }

    private fun track(id: String, number: Int?, disc: Int? = null, album: String? = "Live at the Paradiso", artist: String? = "Band", albumArtist: String? = null) =
        TrackDescriptor(TrackId(id), album = album, artist = artist, albumArtist = albumArtist, trackNumber = number, discNumber = disc)

    @Test
    fun theNextTrackOfTheSameAlbumIsAContinuation() {
        assertTrue(continuesAlbum(track("a", 3), track("b", 4)))
        assertTrue(continuesAlbum(track("a", 12, disc = 1), track("b", 1, disc = 2)))
        assertTrue(continuesAlbum(track("a", 1, artist = "X feat. Y", albumArtist = "VA"), track("b", 2, artist = "Z", albumArtist = "va")))
        assertTrue(continuesAlbum(track("a", 1, album = " live at the paradiso"), track("b", 2)))
    }

    @Test
    fun anythingElseKeepsTheFade() {
        assertFalse(continuesAlbum(track("a", 3), track("b", 5)), "a skipped track")
        assertFalse(continuesAlbum(track("a", 4), track("b", 3)), "backwards")
        assertFalse(continuesAlbum(track("a", 3), track("b", 4, album = "Other")), "another album")
        assertFalse(continuesAlbum(track("a", 3), track("b", 4, artist = "Other Band")), "same title, another artist")
        assertFalse(continuesAlbum(track("a", null), track("b", 4)), "unnumbered")
        assertFalse(continuesAlbum(track("a", 3, album = null), track("b", 4, album = null)), "no album")
        assertFalse(continuesAlbum(track("a", 3), track("a", 3)), "repeat one")
        assertFalse(continuesAlbum(null, track("b", 1)))
        assertFalse(continuesAlbum(track("a", 5, disc = 1), track("b", 1, disc = 3)), "a skipped disc")
    }

    @Test
    fun aContinuedEdgeStaysAtFullVolume() {
        val duration = 180_000L
        val fade = 4_000L
        assertEquals(1f, crossfadeFactor(0, duration, fade, fadeIn = false))
        assertEquals(0.5f, crossfadeFactor(178_000, duration, fade, fadeIn = false))
        assertEquals(1f, crossfadeFactor(178_000, duration, fade, fadeOut = false))
        assertEquals(0f, crossfadeFactor(0, duration, fade, fadeOut = false))
        assertEquals(1f, crossfadeFactor(1_000, durationMs = 0, fadeMs = fade, fadeIn = false))
    }

    @Test
    fun anOverlapIsTheSettingButNeverMoreThanHalfOfEitherTrack() {
        assertEquals(8_000L, overlapLengthMs(8_000, 180_000, 200_000))
        assertEquals(3_000L, overlapLengthMs(8_000, 6_000, 200_000))
        assertEquals(2_500L, overlapLengthMs(8_000, 180_000, 5_000))
        assertEquals(0L, overlapLengthMs(8_000, 0, 200_000), "the outgoing end is unknown")
        assertEquals(0L, overlapLengthMs(0, 180_000, 200_000))
    }

    @Test
    fun overlapCurvesKeepTheCombinedPowerSteady() {
        assertEquals(1f, overlapOutGain(0f))
        assertEquals(0f, overlapInGain(0f))
        for (step in 0..10) {
            val progress = step / 10f
            val power = overlapOutGain(progress).let { it * it } + overlapInGain(progress).let { it * it }
            assertTrue(kotlin.math.abs(power - 1f) < 1e-5f, "power at $progress was $power")
        }
        assertEquals(0f, overlapOutGain(2f), 1e-6f)
        assertEquals(1f, overlapInGain(2f), 1e-6f)
    }

    @Test
    fun theNextOpeningIsDecodedAheadOfTheOverlap() {
        assertFalse(prerollsNow(positionMs = 100_000, durationMs = 180_000, overlapMs = 8_000))
        assertTrue(prerollsNow(positionMs = 162_000, durationMs = 180_000, overlapMs = 8_000))
        assertFalse(prerollsNow(positionMs = 179_000, durationMs = 0, overlapMs = 8_000), "unknown end")
        assertFalse(prerollsNow(positionMs = 179_000, durationMs = 180_000, overlapMs = 0))
    }

    @Test
    fun softClipLeavesOrdinaryLevelsAloneAndNeverReachesFullScaleHard() {
        assertEquals(0.5f, softClip(0.5f))
        assertEquals(-0.8f, softClip(-0.8f))
        assertTrue(softClip(1.4f) < 1f && softClip(1.4f) > 0.95f)
        assertTrue(softClip(-1.4f) > -1f)
        assertTrue(softClip(0.9f) < 0.9f && softClip(0.9f) > softClip(0.85f))
    }

    @Test
    fun albumSeamsInTheQueueKeepTheirEdgesAndOtherSeamsFade() {
        val edges = queueFadeEdges(
            listOf(track("a", 3), track("b", 4), track("x", 7, album = "Other")),
            repeatsItself = false,
        )
        assertEquals(FadeEdges(fadeIn = true, fadeOut = false), edges["a"])
        assertEquals(FadeEdges(fadeIn = false, fadeOut = true), edges["b"])
        assertEquals(FadeEdges.BOTH, edges["x"])
        assertEquals(FadeEdges.BOTH, queueFadeEdges(listOf(track("a", 3)), repeatsItself = true)["a"])
    }
}

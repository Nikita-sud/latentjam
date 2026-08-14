/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PlaybackWidgetSnapshotTest {

    @Test
    fun playingPositionProjectsWithinTheSameBootAndStopsAtDuration() {
        val snapshot = snapshot(
            positionMs = 2_000,
            durationMs = 5_000,
            capturedElapsedRealtimeMs = 10_000,
            capturedBootCount = 7,
        )

        assertEquals(3_500, snapshot.projectedPositionMs(11_500, 7))
        assertTrue(snapshot.isLivePlaying(11_500, 7))
        assertEquals(5_000, snapshot.projectedPositionMs(20_000, 7))
        assertFalse(snapshot.isLivePlaying(20_000, 7))
    }

    @Test
    fun rebootFreezesPositionAndInvalidatesLivePlayingState() {
        val snapshot = snapshot(
            positionMs = 2_000,
            durationMs = 5_000,
            capturedElapsedRealtimeMs = 10_000,
            capturedBootCount = 7,
        )

        assertEquals(2_000, snapshot.projectedPositionMs(500, 8))
        assertFalse(snapshot.isLivePlaying(500, 8))
    }

    @Test
    fun missingOrRegressedMonotonicClockNeverProjects() {
        val unknownBoot = snapshot(
            positionMs = 2_000,
            durationMs = 5_000,
            capturedElapsedRealtimeMs = 10_000,
            capturedBootCount = PlaybackWidgetSnapshot.UNKNOWN_BOOT_COUNT,
        )
        val regressedClock = unknownBoot.copy(capturedBootCount = 7)

        assertEquals(2_000, unknownBoot.projectedPositionMs(20_000, 7))
        assertFalse(unknownBoot.isLivePlaying(20_000, 7))
        assertEquals(2_000, regressedClock.projectedPositionMs(9_999, 7))
        assertFalse(regressedClock.isLivePlaying(9_999, 7))
    }

    @Test
    fun pausedSnapshotNeverProjects() {
        val snapshot = snapshot(
            positionMs = 2_000,
            durationMs = 5_000,
            capturedElapsedRealtimeMs = 10_000,
            capturedBootCount = 7,
        ).copy(isPlaying = false)

        assertEquals(2_000, snapshot.projectedPositionMs(12_000, 7))
        assertFalse(snapshot.isLivePlaying(12_000, 7))
    }

    @Test
    fun coldEmptyControllerUsesModesShownByPersistedWidget() {
        val modes = initialPlaybackModes(
            mediaItemCount = 0,
            nativeRepeatMode = RepeatMode.OFF,
            registryShuffleMode = ShuffleMode.OFF,
            nativeShuffleEnabled = false,
            widgetSnapshot = PlaybackWidgetSnapshot(
                mediaId = "track",
                repeatMode = RepeatMode.ONE,
                shuffleMode = ShuffleMode.SMART,
            ),
        )

        assertEquals(RepeatMode.ONE to ShuffleMode.SMART, modes)
    }

    @Test
    fun populatedControllerKeepsLiveModesInsteadOfStaleWidgetModes() {
        val modes = initialPlaybackModes(
            mediaItemCount = 2,
            nativeRepeatMode = RepeatMode.ALL,
            registryShuffleMode = ShuffleMode.ON,
            nativeShuffleEnabled = true,
            widgetSnapshot = PlaybackWidgetSnapshot(
                mediaId = "old-track",
                repeatMode = RepeatMode.ONE,
                shuffleMode = ShuffleMode.SMART,
            ),
        )

        assertEquals(RepeatMode.ALL to ShuffleMode.ON, modes)
    }

    @Test
    fun nativeShuffleStillRepairsAnUninitializedRegistryWithoutWidgetState() {
        val modes = initialPlaybackModes(
            mediaItemCount = 1,
            nativeRepeatMode = RepeatMode.OFF,
            registryShuffleMode = ShuffleMode.OFF,
            nativeShuffleEnabled = true,
            widgetSnapshot = PlaybackWidgetSnapshot(),
        )

        assertEquals(RepeatMode.OFF to ShuffleMode.ON, modes)
    }

    private fun snapshot(
        positionMs: Long,
        durationMs: Long,
        capturedElapsedRealtimeMs: Long,
        capturedBootCount: Int,
    ): PlaybackWidgetSnapshot = PlaybackWidgetSnapshot(
        mediaId = "track",
        isPlaying = true,
        positionMs = positionMs,
        durationMs = durationMs,
        capturedElapsedRealtimeMs = capturedElapsedRealtimeMs,
        capturedBootCount = capturedBootCount,
    )
}

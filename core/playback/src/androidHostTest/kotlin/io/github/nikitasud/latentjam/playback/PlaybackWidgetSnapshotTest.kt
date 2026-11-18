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
    fun aPhoneCallFreezesTheClockAndKeepsTheButton() {
        // Transient loss of audio focus: the transport still means to play, the sound is held back.
        val duringTheCall = snapshot(
            positionMs = 2_000,
            durationMs = 5_000,
            capturedElapsedRealtimeMs = 10_000,
            capturedBootCount = 7,
        ).copy(suppressed = true)

        // A call far longer than the rest of the track: nothing was heard of it meanwhile.
        assertEquals(2_000, duringTheCall.projectedPositionMs(600_000, 7))
        assertFalse(duringTheCall.isProgressRunning(600_000, 7))
        assertTrue(duringTheCall.isLivePlaying(600_000, 7))

        // The call ends: the service publishes a fresh anchor and the clock runs on from there.
        val afterTheCall = duringTheCall.copy(suppressed = false, capturedElapsedRealtimeMs = 600_000)
        assertEquals(3_000, afterTheCall.projectedPositionMs(601_000, 7))
        assertTrue(afterTheCall.isProgressRunning(601_000, 7))
    }

    @Test
    fun aClockRunsOnlyWhileThePositionAdvances() {
        val playing = snapshot(positionMs = 2_000, durationMs = 5_000, capturedElapsedRealtimeMs = 10_000, capturedBootCount = 7)

        assertTrue(playing.isProgressRunning(11_000, 7))
        assertFalse(playing.copy(isPlaying = false).isProgressRunning(11_000, 7))
        assertFalse(playing.isProgressRunning(20_000, 7))
        assertFalse(playing.isProgressRunning(500, 8))
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

    @Test
    fun coldEmptyPlayerKeepsTheLastTrackSnapshot() {
        // A controller setting repeat or shuffle, or a play press, on a service created cold.
        assertFalse(
            publishesWidgetSnapshot(
                widgetStateChanged = true,
                hasCurrentItem = false,
                timelineChanged = false,
            ),
        )
    }

    @Test
    fun emptiedQueueStillPublishesItsEmptyState() {
        assertTrue(
            publishesWidgetSnapshot(
                widgetStateChanged = true,
                hasCurrentItem = false,
                timelineChanged = true,
            ),
        )
    }

    @Test
    fun playerWithATrackPublishesEveryWidgetStateEvent() {
        assertTrue(
            publishesWidgetSnapshot(
                widgetStateChanged = true,
                hasCurrentItem = true,
                timelineChanged = false,
            ),
        )
        assertFalse(
            publishesWidgetSnapshot(
                widgetStateChanged = false,
                hasCurrentItem = true,
                timelineChanged = false,
            ),
        )
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

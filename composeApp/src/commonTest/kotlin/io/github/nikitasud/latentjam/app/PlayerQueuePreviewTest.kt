/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PlayerQueuePreviewTest {
    private val tracks = listOf("first", "second", "third").map { TrackDescriptor(TrackId(it)) }
    private val now = NowPlaying(track = tracks[1], queue = tracks, queueIndex = 1)

    @Test
    fun repeatOneDistinguishesAutomaticNextFromManualSkip() {
        val repeating = now.copy(repeatMode = RepeatMode.ONE)
        assertEquals(tracks[1], nextUpTrack(repeating))
        assertEquals(tracks[2], queueNeighbour(repeating, forward = true))
    }

    @Test
    fun previousPreviewRestartsOnlyAfterTheBackendThreshold() {
        assertEquals(tracks[0], queueNeighbour(now.copy(positionMs = 3_000L), forward = false))
        assertEquals(tracks[1], queueNeighbour(now.copy(positionMs = 3_001L), forward = false))
    }

    @Test
    fun repeatAllWrapsBothQueueBoundaries() {
        assertEquals(tracks[0], nextUpTrack(now.copy(queueIndex = 2, track = tracks[2], repeatMode = RepeatMode.ALL)))
        assertEquals(tracks[2], queueNeighbour(now.copy(queueIndex = 0, track = tracks[0], repeatMode = RepeatMode.ALL), false))
    }

    @Test
    fun smartTailDoesNotPromiseAnUnconfirmedRecommendation() {
        val tail = now.copy(queueIndex = 2, track = tracks[2], shuffleMode = ShuffleMode.SMART, repeatMode = RepeatMode.ALL)
        assertNull(nextUpTrack(tail))
        assertNull(queueNeighbour(tail, true))
        assertEquals(tracks[2], nextUpTrack(now.copy(shuffleMode = ShuffleMode.SMART)))
    }

    @Test
    fun hardEndsAndInvalidIndicesHaveNoNeighbour() {
        assertNull(queueNeighbour(now.copy(queueIndex = 0), false))
        assertNull(nextUpTrack(now.copy(queueIndex = 2)))
        assertNull(queueNeighbour(now.copy(queueIndex = -1), true))
        assertNull(queueNeighbour(now.copy(queueIndex = 3), false))
        assertNull(queueNeighbour(NowPlaying(), true))
    }
}

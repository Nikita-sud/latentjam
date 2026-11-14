/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PlaylistDurationTest {

    private var nextTrackId = 0

    private fun track(durationMs: Long?) = TrackDescriptor(id = TrackId("t${nextTrackId++}"), durationMs = durationMs)

    @Test
    fun aPlaylistLastsAsLongAsItsTracksTogether() {
        assertEquals(TotalDuration(425_000L, complete = true), totalDuration(listOf(track(180_000), track(245_000))))
    }

    @Test
    fun aTrackOfUnknownLengthAddsNothingAndMarksTheTotalAsALowerBound() {
        assertEquals(TotalDuration(180_000L, complete = false), totalDuration(listOf(track(180_000), track(null))))
        assertEquals(TotalDuration(180_000L, complete = false), totalDuration(listOf(track(180_000), track(0))))
    }

    @Test
    fun aNegativeLengthFromABrokenFileAddsNothing() {
        assertEquals(TotalDuration(180_000L, complete = false), totalDuration(listOf(track(180_000), track(-1))))
    }

    @Test
    fun anEmptyPlaylistLastsNothing() {
        assertEquals(TotalDuration(0L, complete = true), totalDuration(emptyList()))
    }
}

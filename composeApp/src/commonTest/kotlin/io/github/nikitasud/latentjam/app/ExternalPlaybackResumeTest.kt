/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ExternalPlaybackResumeTest {
    @Test
    fun externalColdResumeIsNotPausedOrReloadedWhenUiOpens() {
        val external = TrackDescriptor(
            id = TrackId("external-current"),
            audioUri = "content://external-current",
        )

        assertFalse(
            shouldApplySavedPlaybackAfterPlatformSync(
                NowPlaying(track = external, isPlaying = true),
            ),
        )
    }

    @Test
    fun emptyPlatformSessionStillUsesSavedPlayback() {
        assertTrue(shouldApplySavedPlaybackAfterPlatformSync(NowPlaying()))
    }
}

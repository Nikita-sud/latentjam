/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTransportStateTest {
    @Test
    fun seekBufferingKeepsThePauseActionUntilPlaybackResumes() {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_READY)) {
            assertTrue(showPauseButton(true, state, pausePending = false))
        }
    }

    @Test
    fun seekingWhilePausedNeverShowsAPauseAction() {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_READY)) {
            assertFalse(showPauseButton(false, state, pausePending = false))
        }
    }

    @Test
    fun explicitPauseTakesEffectEvenIfTheFadeIsStillFinishing() {
        assertFalse(showPauseButton(true, Player.STATE_READY, pausePending = true))
        assertFalse(showPauseButton(true, Player.STATE_BUFFERING, pausePending = true))
    }

    @Test
    fun stoppedFailedAndCompletedPlayersOfferPlayEvenWithStaleIntent() {
        assertFalse(showPauseButton(true, Player.STATE_IDLE, pausePending = false))
        assertFalse(showPauseButton(true, Player.STATE_ENDED, pausePending = false))
    }
}

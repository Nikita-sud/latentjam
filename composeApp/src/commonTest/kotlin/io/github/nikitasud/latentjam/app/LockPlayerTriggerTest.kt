/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.playback.NowPlaying
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockPlayerTriggerTest {

    @Test
    fun `opens only when the setting is on and music plays and no player is showing`() {
        for (enabled in listOf(false, true)) {
            for (playing in listOf(false, true)) {
                for (showing in listOf(false, true)) {
                    val opens = LockPlayerTrigger().onScreenOff(enabled, playing, showing)
                    assertEquals(enabled && playing && !showing, opens, "enabled=$enabled playing=$playing showing=$showing")
                }
            }
        }
    }

    @Test
    fun `a second screen-off event before the screen comes on does not open it again`() {
        val trigger = LockPlayerTrigger()
        assertTrue(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
        // The player may not have registered as showing yet when a duplicate event arrives.
        assertFalse(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
    }

    @Test
    fun `the screen coming on lets the next screen-off open it again`() {
        val trigger = LockPlayerTrigger()
        assertTrue(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
        trigger.onScreenOn()
        assertTrue(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
    }

    @Test
    fun `a refused screen-off does not use up the guard`() {
        val trigger = LockPlayerTrigger()
        assertFalse(trigger.onScreenOff(enabled = true, playing = false, playerShowing = false))
        assertTrue(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
    }

    @Test
    fun `a player already above the lock screen is never stacked even after the screen came on`() {
        val trigger = LockPlayerTrigger()
        trigger.onScreenOn()
        assertFalse(trigger.onScreenOff(enabled = true, playing = true, playerShowing = true))
    }

    @Test
    fun `the open player stays through a pause and closes when playback ends`() {
        val track = TrackDescriptor(id = TrackId("t1"), title = "Song")
        assertFalse(lockPlayerShouldClose(NowPlaying(track = track, isPlaying = true)))
        assertFalse(lockPlayerShouldClose(NowPlaying(track = track, isPlaying = false)))
        assertTrue(lockPlayerShouldClose(NowPlaying(track = null)))
    }
}

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
    fun `a swiped away player stays away through screen cycles until the next unlock`() {
        val trigger = LockPlayerTrigger()
        assertTrue(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
        trigger.onDismissed()
        trigger.onScreenOn()
        assertFalse(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
        trigger.onScreenOn()
        trigger.onUnlocked()
        assertTrue(trigger.onScreenOff(enabled = true, playing = true, playerShowing = false))
    }

    @Test
    fun `the open player stays through a pause and closes when playback ends`() {
        val track = TrackDescriptor(id = TrackId("t1"), title = "Song")
        assertFalse(lockPlayerShouldClose(NowPlaying(track = track, isPlaying = true)))
        assertFalse(lockPlayerShouldClose(NowPlaying(track = track, isPlaying = false)))
        assertTrue(lockPlayerShouldClose(NowPlaying(track = null)))
    }
}

class LockScreenSurfaceTest {

    @Test
    fun `the app player keeps every entry point`() {
        PlayerEntry.entries.forEach { assertTrue(PlayerSurface.APP.allows(it), it.name) }
    }

    @Test
    fun `the lock screen offers exactly the entry points that need no unlock`() {
        val offered = PlayerEntry.entries.filter(PlayerSurface.LOCK_SCREEN::allows).toSet()
        assertEquals(
            setOf(
                PlayerEntry.TRANSPORT,
                PlayerEntry.SEEK,
                PlayerEntry.REPEAT,
                PlayerEntry.MODE_CYCLE,
                PlayerEntry.FAVOURITE,
                PlayerEntry.QUEUE_PAGE,
                PlayerEntry.QUEUE_PLAY_REORDER_REMOVE,
                PlayerEntry.LYRICS,
            ),
            offered,
        )
    }

    @Test
    fun `nothing that opens a menu sheet picker or another screen is offered on the lock screen`() {
        listOf(
            PlayerEntry.TRACK_MENU,
            PlayerEntry.QUEUE_ROW_MENU,
            PlayerEntry.ADD_QUEUE_TO_PLAYLIST,
            PlayerEntry.COVER_DETAILS,
            PlayerEntry.ARTIST_ALBUM_LINKS,
            PlayerEntry.SOURCE_LINK,
            PlayerEntry.OUTPUT_CHOOSER,
            PlayerEntry.MODE_MENU,
            PlayerEntry.SLEEP_TIMER,
        ).forEach { assertFalse(PlayerSurface.LOCK_SCREEN.allows(it), it.name) }
    }
}

class LockPlayerSwipeTest {

    @Test
    fun `a pull past a quarter of the screen closes the lock player`() {
        assertTrue(lockPlayerSwipeCloses(pulledPx = 260f, velocityPxPerS = 0f, heightPx = 1000f, flingPxPerS = 2000f))
        assertFalse(lockPlayerSwipeCloses(pulledPx = 240f, velocityPxPerS = 0f, heightPx = 1000f, flingPxPerS = 2000f))
    }

    @Test
    fun `a fast fling down closes it after a short pull but a fling without any pull does not`() {
        assertTrue(lockPlayerSwipeCloses(pulledPx = 40f, velocityPxPerS = 2500f, heightPx = 1000f, flingPxPerS = 2000f))
        assertFalse(lockPlayerSwipeCloses(pulledPx = 0f, velocityPxPerS = 2500f, heightPx = 1000f, flingPxPerS = 2000f))
    }

    @Test
    fun `an unmeasured screen never closes it`() {
        assertFalse(lockPlayerSwipeCloses(pulledPx = 500f, velocityPxPerS = 5000f, heightPx = 0f, flingPxPerS = 2000f))
    }
}

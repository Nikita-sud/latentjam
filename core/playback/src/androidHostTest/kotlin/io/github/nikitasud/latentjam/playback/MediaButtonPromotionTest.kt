/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Intent
import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MediaButtonPromotionTest {

    private fun promotes(
        keyCode: Int?,
        keyAction: Int? = KeyEvent.ACTION_DOWN,
        action: String? = Intent.ACTION_MEDIA_BUTTON,
        dismissed: Boolean = false,
        sdkInt: Int = 37,
    ) = promotesForMediaButton(action, keyCode, keyAction, dismissed, sdkInt)

    @Test
    fun aPressThatAsksToPlayEntersTheForeground() {
        assertTrue(promotes(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(promotes(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(promotes(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    @Test
    fun stopSkipsAndTheReleaseAreLeftToMedia3() {
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_STOP))
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PLAY, keyAction = KeyEvent.ACTION_UP))
        assertFalse(promotes(null, keyAction = null))
    }

    @Test
    fun theNotificationsDismissalIsNeverPromoted() {
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, dismissed = true))
    }

    @Test
    fun otherStartsAndOldAndroidAreLeftAlone() {
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PLAY, action = null))
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PLAY, action = "androidx.media3.session.MediaSessionService"))
        // Before Android 8 there is no foreground-start contract to answer.
        assertFalse(promotes(KeyEvent.KEYCODE_MEDIA_PLAY, sdkInt = 25))
        assertTrue(promotes(KeyEvent.KEYCODE_MEDIA_PLAY, sdkInt = 26))
    }

    @Test
    fun onlyAPromotionStillWaitingForItsQueueIsEndedByTheCheck() {
        assertEquals(PromotionWatch.Done, promotionWatch(pending = false, resumptionInFlight = false))
        assertEquals(PromotionWatch.Done, promotionWatch(pending = false, resumptionInFlight = true))
        assertEquals(PromotionWatch.Wait, promotionWatch(pending = true, resumptionInFlight = true))
        assertEquals(PromotionWatch.Exit, promotionWatch(pending = true, resumptionInFlight = false))
    }

    @Test
    fun aTapWhilePlayingPauses() {
        assertEquals(TileClick.Pause, tileClickAction(true, hasQueue = true, startPending = false, canRestoreSavedQueue = false))
        assertEquals(TileClick.Pause, tileClickAction(true, hasQueue = true, startPending = true, canRestoreSavedQueue = true))
    }

    @Test
    fun aTapWithALoadedOrSavedQueuePlays() {
        assertEquals(TileClick.Play, tileClickAction(false, hasQueue = true, startPending = false, canRestoreSavedQueue = false))
        assertEquals(TileClick.Play, tileClickAction(false, hasQueue = false, startPending = false, canRestoreSavedQueue = true))
    }

    @Test
    fun aTapWithNothingToPlayOpensTheApp() {
        // False also when a queue is saved but music access was revoked: the app asks for it.
        assertEquals(TileClick.OpenApp, tileClickAction(false, hasQueue = false, startPending = false, canRestoreSavedQueue = false))
        assertEquals(TileClick.OpenApp, tileClickAction(false, hasQueue = false, startPending = false, canRestoreSavedQueue = null))
    }

    @Test
    fun aSecondTapWhileTheFirstIsStartingIsIgnored() {
        assertEquals(TileClick.Ignore, tileClickAction(false, hasQueue = false, startPending = true, canRestoreSavedQueue = true))
        assertEquals(TileClick.Ignore, tileClickAction(false, hasQueue = true, startPending = true, canRestoreSavedQueue = true))
    }
}

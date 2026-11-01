/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Intent
import android.view.KeyEvent

/**
 * Whether a start of the service must enter the foreground before Media3 handles it.
 *
 * Only a press that asks to play: a headset or the Quick Settings tile starting a cold process, or
 * the notification's Play on a paused service. Android 15+ grants audio focus only to an app on
 * screen or running a foreground service, and Media3 enters the foreground a few hops after the
 * player has already asked — so the first play from the background was refused (and a start that
 * found nothing to play was never answered, which crashed). Stop, Next, Previous and the
 * notification's dismissal never start playback, so they are left to Media3.
 */
internal fun promotesForMediaButton(
    action: String?,
    keyCode: Int?,
    keyAction: Int?,
    dismissed: Boolean,
    sdkInt: Int,
): Boolean = sdkInt >= 26 &&
    action == Intent.ACTION_MEDIA_BUTTON &&
    !dismissed &&
    keyAction == KeyEvent.ACTION_DOWN &&
    keyCode in PLAY_KEYS

private val PLAY_KEYS = setOf(
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK,
)

internal enum class PromotionWatch { Done, Wait, Exit }

/**
 * What the check after a promotion does. Once Media3 has drawn its own notification it owns the
 * foreground — including the ten minutes it keeps after a pause, which a later resume from the lock
 * screen or a car needs — so only a promotion still waiting for a queue is ours to end, and not
 * while the saved queue is still being read.
 */
internal fun promotionWatch(pending: Boolean, resumptionInFlight: Boolean): PromotionWatch = when {
    !pending -> PromotionWatch.Done
    resumptionInFlight -> PromotionWatch.Wait
    else -> PromotionWatch.Exit
}

internal enum class TileClick { Pause, Play, OpenApp, Ignore }

/**
 * What a tap on the Quick Settings tile does, decided at once from the in-process session state.
 *
 * Waiting for a controller to connect first lost the tap whenever the shade closed in between, and
 * the controller itself was the service's only client: its release destroyed the service while the
 * saved queue was still being read. [canRestoreSavedQueue] is null when the app has not said.
 */
internal fun tileClickAction(
    showsPause: Boolean,
    hasQueue: Boolean,
    startPending: Boolean,
    canRestoreSavedQueue: Boolean?,
): TileClick = when {
    showsPause -> TileClick.Pause
    startPending -> TileClick.Ignore
    hasQueue || canRestoreSavedQueue == true -> TileClick.Play
    else -> TileClick.OpenApp
}

/**
 * What the Quick Settings tile needs from the session, published by [PlaybackService] in this same
 * process. The tile reads it on a tap instead of connecting a controller, so a decision never waits
 * for a connection and a cold process is not woken just to draw the tile. Main thread only.
 */
internal object PlaybackSessionState {
    var hasQueue: Boolean = false
        private set
    var showsPause: Boolean = false
        private set

    /** Until when a tap that started playback holds off further taps; 0 when none is pending. */
    private var startPendingUntilMs: Long = 0L

    fun publish(hasQueue: Boolean, showsPause: Boolean): Boolean {
        if (showsPause) startPendingUntilMs = 0L
        if (this.hasQueue == hasQueue && this.showsPause == showsPause) return false
        this.hasQueue = hasQueue
        this.showsPause = showsPause
        return true
    }

    /** The service is gone: nothing loaded, nothing playing, nothing starting. */
    fun clear() {
        publish(hasQueue = false, showsPause = false)
        startPendingUntilMs = 0L
    }

    fun markStartPending(nowMs: Long) {
        startPendingUntilMs = nowMs + START_PENDING_MS
    }

    fun clearStartPending() {
        startPendingUntilMs = 0L
    }

    fun startPending(nowMs: Long): Boolean = nowMs < startPendingUntilMs

    private const val START_PENDING_MS = 20_000L
}

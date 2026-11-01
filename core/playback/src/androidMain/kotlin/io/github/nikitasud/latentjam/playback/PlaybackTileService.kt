/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Quick Settings tile: one tap toggles play/pause without opening the app.
 *
 * The tile shares the playback service's process and reads the session's state from
 * [PlaybackSessionState] instead of connecting to it. A play starts the service the way a headset
 * does — a Play key sent with `startForegroundService` — so the service is started, not merely
 * bound, and outlives the shade. It used to send `play()` through a short-lived controller and
 * release it at once: in a cold process that controller was the service's only client, and its
 * release destroyed the service while the saved queue was still being read, so the tap did nothing.
 */
public class PlaybackTileService : TileService() {

    override fun onStartListening() {
        val now = SystemClock.elapsedRealtime()
        updateTile(PlaybackSessionState.showsPause || PlaybackSessionState.startPending(now))
    }

    override fun onClick() {
        val now = SystemClock.elapsedRealtime()
        val state = PlaybackSessionState
        // Read only when nothing is loaded: it decodes the saved queue's preferences.
        val hasSavedQueue = if (state.showsPause || state.hasQueue) null else savedQueue()
        when (tileClickAction(state.showsPause, state.hasQueue, state.startPending(now), hasSavedQueue)) {
            TileClick.Pause -> pause()
            TileClick.Play -> play(now)
            TileClick.OpenApp -> openApp()
            TileClick.Ignore -> Unit
        }
    }

    private fun savedQueue(): Boolean? = try {
        MediaBrowseRegistry.hasSavedQueue?.invoke()
    } catch (_: RuntimeException) {
        null
    }

    /**
     * A Play key for the service, as [androidx.media3.session.MediaButtonReceiver] sends one. The
     * service enters the foreground before Media3 handles it, and Media3 restores the saved queue
     * when the player is empty, restarts an ended track, or resumes the loaded one.
     */
    private fun play(now: Long) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(this, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (refused: RuntimeException) {
            // ForegroundServiceStartNotAllowedException or a background-start refusal: the app can
            // still be opened from a tile tap.
            PlaybackSessionState.clearStartPending()
            openApp()
            return
        }
        PlaybackSessionState.markStartPending(now)
        updateTile(true)
    }

    /**
     * A pause needs no foreground or focus, so the session's own command path serves it. Bound
     * through the application, so closing the shade (which unbinds this tile) cannot cut it short.
     */
    private fun pause() {
        updateTile(false)
        val app = applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            runCatching {
                val controller = future.get()
                try {
                    controller.pause()
                } finally {
                    controller.release()
                }
            }
        }, mainThreadExecutor)
    }

    /** Nothing to play: the app is where the listener can pick something. */
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val open = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(
                            this,
                            OPEN_APP_REQUEST_CODE,
                            launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                } else {
                    // The Intent overload throws from Android 14 on; the PendingIntent one does not
                    // exist before it.
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(launch)
                }
            } catch (_: RuntimeException) {
                // A ROM without the shade's activity start; the tap simply does nothing.
            }
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }

    private fun updateTile(playing: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    // Context.getMainExecutor() was added in API 28; LatentJam supports API 24.
    private val mainThreadExecutor = java.util.concurrent.Executor { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    }

    private companion object {
        const val OPEN_APP_REQUEST_CODE = 4203
    }
}

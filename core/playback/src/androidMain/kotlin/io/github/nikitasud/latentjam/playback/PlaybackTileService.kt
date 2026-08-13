/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Quick Settings tile: one tap toggles play/pause without opening the app.
 *
 * The tile lives in the system shade, so it holds no long-lived session — each listening window
 * and each click builds a short-lived [MediaController] against the playback service and releases
 * it. The service keeps running (or starts) independently, exactly as with the notification.
 */
public class PlaybackTileService : TileService() {

    private var pending: ListenableFuture<MediaController>? = null

    override fun onStartListening() {
        withController { controller -> updateTile(controller.isPlaying) }
    }

    override fun onClick() {
        withController { controller ->
            val shouldPlay = !controller.playWhenReady
            if (shouldPlay) controller.play() else controller.pause()
            // MediaController commands are asynchronous. Reflect the requested state now; the
            // next listening callback reconciles it with the session's authoritative state.
            updateTile(shouldPlay)
        }
    }

    override fun onStopListening() {
        pending?.cancel(true)
        pending = null
    }

    override fun onDestroy() {
        pending?.cancel(true)
        pending = null
        super.onDestroy()
    }

    private fun updateTile(playing: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun withController(block: (MediaController) -> Unit) {
        pending?.cancel(true)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        pending = future
        future.addListener({
            if (future.isCancelled) return@addListener
            val result = runCatching {
                val controller = future.get()
                try {
                    block(controller)
                } finally {
                    controller.release()
                }
            }
            if (result.isFailure) updateUnavailable()
            if (pending === future) pending = null
        }, mainThreadExecutor)
    }

    private fun updateUnavailable() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    // Context.getMainExecutor() was added in API 28; LatentJam supports API 24.
    private val mainThreadExecutor = java.util.concurrent.Executor { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    }
}

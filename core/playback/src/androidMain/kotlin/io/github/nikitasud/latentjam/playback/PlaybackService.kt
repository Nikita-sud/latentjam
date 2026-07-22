/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback host: one ExoPlayer inside a Media3 [MediaSessionService].
 *
 * Media3 supplies the media notification, lock-screen/system controls, audio
 * focus, and becoming-noisy handling — this class only assembles the pieces.
 * The UI never touches this service directly; [AndroidPlaybackController]
 * connects through a `MediaController`, which keeps playback alive across
 * activity death (the "works like a real player" contract).
 *
 * Declared in the :androidApp manifest with
 * `androidx.media3.session.MediaSessionService` intent-filter and
 * `mediaPlayback` foreground-service type.
 */
public class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // The audio session id is generated HERE rather than read back from the player: the
        // equalizer effect binds to a session, and asking for one we chose removes any window
        // where the effect could attach to a session the player has already replaced.
        val audioSessionId = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { setAudioSessionId(audioSessionId) }
        // Announced rather than injected: this service is built by the system and cannot see
        // the app's scoped Koin graph. Whoever owns the equalizer picks the session up from here.
        AudioSessionRegistry.publish(audioSessionId)
        val sessionBuilder = MediaSession.Builder(this, player)
        appLaunchPendingIntent()?.let(sessionBuilder::setSessionActivity)
        mediaSession = sessionBuilder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        AudioSessionRegistry.publish(null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Explicitly owns the media-notification body tap.
     *
     * Media3 can infer this for some manifest/task combinations, but that inference is not stable
     * across OEM System UI implementations. Supplying the launch PendingIntent makes the mini
     * player return to the existing LatentJam task everywhere and avoids a second activity.
     */
    private fun appLaunchPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            MEDIA_NOTIFICATION_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val MEDIA_NOTIFICATION_REQUEST_CODE = 4202
    }
}

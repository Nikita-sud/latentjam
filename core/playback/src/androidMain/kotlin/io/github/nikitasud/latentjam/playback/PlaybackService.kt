/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

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
        mediaSession = MediaSession.Builder(this, player).build()
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
}

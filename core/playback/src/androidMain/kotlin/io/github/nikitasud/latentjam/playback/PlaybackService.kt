/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
@UnstableApi
public class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var playbackPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            if (!result.isAccepted) return result
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    result.availableSessionCommands.buildUpon()
                        .add(CycleShuffleModeCommand)
                        .build(),
                )
                .setAvailablePlayerCommands(result.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand == CycleShuffleModeCommand) {
                AndroidShuffleModeRegistry.cycle()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** Keeps stateful notification icons in step with changes from either the app or System UI. */
    private val playerListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val currentMode = AndroidShuffleModeRegistry.mode.value
            // SMART intentionally maps to ExoPlayer shuffle=false. An actual external change to
            // true still exits SMART and becomes normal random shuffle.
            if (!(currentMode == ShuffleMode.SMART && !shuffleModeEnabled)) {
                AndroidShuffleModeRegistry.set(
                    if (shuffleModeEnabled) ShuffleMode.ON else ShuffleMode.OFF,
                )
            }
            refreshMediaButtons()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = refreshMediaButtons()
    }

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
        playbackPlayer = player
        // Announced rather than injected: this service is built by the system and cannot see
        // the app's scoped Koin graph. Whoever owns the equalizer picks the session up from here.
        AudioSessionRegistry.publish(audioSessionId)
        val sessionBuilder = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setMediaButtonPreferences(
                mediaButtonPreferences(player, AndroidShuffleModeRegistry.mode.value),
            )
        appLaunchPendingIntent()?.let(sessionBuilder::setSessionActivity)
        mediaSession = sessionBuilder.build()
        player.addListener(playerListener)
        serviceScope.launch {
            AndroidShuffleModeRegistry.mode.collectLatest { refreshMediaButtons() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        AudioSessionRegistry.publish(null)
        serviceScope.cancel()
        playbackPlayer?.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        playbackPlayer = null
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Requests the same order as the in-app player: repeat on the left secondary slot and the
     * three-state shuffle on the right secondary slot. Android's legacy System UI bridge ignores
     * explicit secondary slots, so OVERFLOW also keeps both actions visible there; it preserves
     * their repeat-before-shuffle order even when an OEM chooses the precise placement.
     */
    private fun mediaButtonPreferences(
        player: Player,
        shuffleMode: ShuffleMode,
    ): List<CommandButton> = listOf(
        CommandButton.Builder(repeatIcon(player.repeatMode))
            .setDisplayName(repeatActionName(player.repeatMode))
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, nextRepeatMode(player.repeatMode))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(shuffleIcon(shuffleMode))
            .apply {
                // SMART wears the app's own mark, exactly like the in-app player. Media3 takes a
                // drawable resource here; the semantic icon stays UNDEFINED so nothing overrides it.
                if (shuffleMode == ShuffleMode.SMART) {
                    setIconResId(R.drawable.ic_shuffle_smart_mark)
                }
            }
            .setDisplayName(shuffleActionName(shuffleMode))
            .setSessionCommand(CycleShuffleModeCommand)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun refreshMediaButtons() {
        val player = playbackPlayer ?: return
        mediaSession?.setMediaButtonPreferences(
            mediaButtonPreferences(player, AndroidShuffleModeRegistry.mode.value),
        )
    }

    private fun shuffleIcon(mode: ShuffleMode): Int = when (mode) {
        ShuffleMode.OFF -> CommandButton.ICON_SHUFFLE_OFF
        ShuffleMode.ON -> CommandButton.ICON_SHUFFLE_ON
        // The drawable set in mediaButtonPreferences supplies the glyph; no built-in star.
        ShuffleMode.SMART -> CommandButton.ICON_UNDEFINED
    }

    private fun shuffleActionName(mode: ShuffleMode): String = when (mode) {
        ShuffleMode.OFF -> "Turn shuffle on"
        ShuffleMode.ON -> "Turn SMART shuffle on"
        ShuffleMode.SMART -> "Turn shuffle off"
    }

    private fun repeatIcon(repeatMode: Int): Int = when (repeatMode) {
        Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
        Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
        else -> CommandButton.ICON_REPEAT_OFF
    }

    private fun repeatActionName(repeatMode: Int): String = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> "Repeat all"
        Player.REPEAT_MODE_ALL -> "Repeat this track"
        else -> "Turn repeat off"
    }

    private fun nextRepeatMode(repeatMode: Int): Int = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CommandButton
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
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
public class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var playbackPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // The service has to be exported so Android System UI, Bluetooth controls and the
            // app's own MediaController can reach the session. Media3 1.10's default callback,
            // however, builds an accepted result with DEFAULT_PLAYER_COMMANDS before it knows
            // who is connecting. Reject callers Android has not authenticated as our app, a
            // system component, or a holder of media-control permission; otherwise any installed
            // app can replace/seek/stop this player's queue through the exported component.
            if (!controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val result = super.onConnect(session, controller)
            if (!result.isAccepted) return result
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    result.availableSessionCommands.buildUpon()
                        .add(CycleShuffleModeCommand)
                        .add(InsertShufflePlayNextCommand)
                        .add(MaterializeRestoredShuffleOrderCommand)
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
            if (controller.isTrusted) {
                when (customCommand) {
                    CycleShuffleModeCommand -> {
                        AndroidShuffleModeRegistry.cycle()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }

                    InsertShufflePlayNextCommand -> {
                        val player = playbackPlayer
                            ?: return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE),
                            )
                        val item = runCatching {
                            args.getBundle(PlayNextMediaItemBundleKey)?.let { bundle ->
                                MediaItem.fromBundle(bundle, MediaLibraryInfo.INTERFACE_VERSION)
                            }
                        }.getOrNull()
                        if (item?.localConfiguration == null || item.mediaId.isBlank()) {
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE),
                            )
                        }
                        insertPlayNext(player, item)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }

                    MaterializeRestoredShuffleOrderCommand -> {
                        val player = playbackPlayer
                            ?: return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE),
                            )
                        // The controller has already installed NowPlaying.queue physically in its
                        // persisted traversal order. Identity is therefore the exact saved Next
                        // chain. Enable native shuffle only after installing it, so both player
                        // listeners continue to report logical ON without an OFF transition.
                        player.setShuffleOrder(
                            DefaultShuffleOrder(
                                restoredOnIdentityTraversal(player.mediaItemCount),
                                System.nanoTime(),
                            ),
                        )
                        player.shuffleModeEnabled = true
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(folderItem(BROWSE_ROOT_ID, "LatentJam"), params),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browseFuture {
            LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = browseFuture {
            resolvePlayable(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        // External browsers (Android Auto) send browse-only items with no URI. In-process items
        // arrive complete and pass through untouched, so the in-app play path is unchanged.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = browseFuture {
            mediaItems.map { item ->
                if (item.localConfiguration != null) item else resolvePlayable(item.mediaId) ?: item
            }.toMutableList()
        }
    }


    /** Bridges the coroutine-shaped catalog into Media3's ListenableFuture callbacks. */
    private fun <T : Any> browseFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                future.set(block())
            } catch (failure: Throwable) {
                future.setException(failure)
            }
        }
        return future
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> {
        val catalog = MediaBrowseRegistry.catalog?.invoke() ?: return emptyList()
        return when (parentId) {
            BROWSE_ROOT_ID -> listOf(
                folderItem(BROWSE_COLLECTIONS_ID, catalog.collectionsTitle),
                folderItem(BROWSE_TRACKS_ID, catalog.tracksTitle),
            )
            BROWSE_COLLECTIONS_ID -> catalog.collections.map { folderItem(it.id, it.title) }
            BROWSE_TRACKS_ID -> catalog.tracks.take(BROWSE_TRACK_LIMIT).map(::playableItem)
            else -> catalog.collections.firstOrNull { it.id == parentId }
                ?.tracks?.map(::playableItem)
                .orEmpty()
        }
    }

    private suspend fun resolvePlayable(mediaId: String): MediaItem? {
        if (mediaId.isBlank()) return null
        val catalog = MediaBrowseRegistry.catalog?.invoke() ?: return null
        val track = catalog.tracks.firstOrNull { it.id.value == mediaId }
            ?: catalog.collections.asSequence()
                .flatMap { it.tracks.asSequence() }
                .firstOrNull { it.id.value == mediaId }
        return track?.takeIf { it.audioUri != null }?.let(::playableItem)
    }

    private fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun playableItem(track: TrackDescriptor): MediaItem = MediaItem.Builder()
        .setMediaId(track.id.value)
        .setUri(track.audioUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title ?: track.id.value)
                .setArtist(track.artist)
                .setArtworkUri(track.artworkUri?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

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
        val sessionBuilder = MediaLibrarySession.Builder(this, player, sessionCallback)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

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
                    setCustomIconResId(R.drawable.ic_shuffle_smart_mark)
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

    /**
     * Inserts without surrendering Play Next semantics to ExoPlayer's random insertion rule.
     *
     * `DefaultShuffleOrder.cloneAndInsert` intentionally chooses a random traversal slot. This
     * service is the only layer that owns the real ExoPlayer (the app sees a MediaController), so
     * it appends physically and then installs the old permutation with that new index directly
     * after the playhead. Native next/previous and system media controls continue using ExoPlayer.
     */
    private fun insertPlayNext(player: ExoPlayer, item: MediaItem) {
        if (player.mediaItemCount == 0) {
            player.addMediaItem(item)
            return
        }
        if (!player.shuffleModeEnabled) {
            val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
            player.addMediaItem(insertAt, item)
            return
        }

        val shuffleOrder = player.shuffleOrder
        val oldTraversal = boundedQueueOrder(
            queueSize = player.mediaItemCount,
            firstIndex = shuffleOrder.firstIndex,
            nextIndex = shuffleOrder::getNextIndex,
        )
        val extendedTraversal = shuffleOrderAppendingNext(
            existingOrder = oldTraversal,
            currentMediaItemIndex = player.currentMediaItemIndex,
        )
        player.addMediaItem(item)
        player.setShuffleOrder(DefaultShuffleOrder(extendedTraversal, System.nanoTime()))
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
        const val BROWSE_ROOT_ID = "browse-root"
        const val BROWSE_COLLECTIONS_ID = "browse-collections"
        const val BROWSE_TRACKS_ID = "browse-tracks"

        /** Car screens are for glancing; a whole multi-thousand-track library is not. */
        const val BROWSE_TRACK_LIMIT = 500

        const val MEDIA_NOTIFICATION_REQUEST_CODE = 4202
    }
}

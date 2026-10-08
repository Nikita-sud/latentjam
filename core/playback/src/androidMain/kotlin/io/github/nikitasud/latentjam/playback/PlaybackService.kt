/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var pendingResumptionMode: ShuffleMode? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) {
                recordExternalMediaEvent(
                    source = controller.packageName,
                    what = "connect trusted=${controller.isTrusted}",
                )
            }
            // The service has to be exported so Android System UI, Bluetooth controls and the
            // app's own MediaController can reach the session. Media3 1.10's default callback,
            // however, builds an accepted result with DEFAULT_PLAYER_COMMANDS before it knows
            // who is connecting — so who gets what is decided here.
            //
            // Untrusted callers used to be REJECTED outright, and that rejection ate the
            // headphones' pause: on this OEM the Google app dispatches Bluetooth taps, Media3
            // holds PLAY_PAUSE ~300ms for double-click detection and then executes it AS that
            // caller — which must therefore be connected. The black box caught the exact
            // pattern live (XM6, 2026-08-15): key=85 arrives, +301ms the dispatcher's connect
            // is refused, the deferred toggle dies, playback never flips. NEXT worked because
            // only PLAY_PAUSE is deferred.
            //
            // So: untrusted callers now connect with a TRANSPORT-AND-VOLUME surface — exactly
            // the reach any installed app already has through the system's media-key dispatch
            // and AudioManager — and none of what the rejection was protecting: no queue
            // mutation, no setting media items, no stop, no custom commands. Device volume is
            // in because an OEM's output panel drives the slider through the session; without
            // these commands its slider moves and the sound does not.
            if (!controller.isTrusted) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SessionCommands.EMPTY)
                    .setAvailablePlayerCommands(
                        Player.Commands.Builder()
                            .addAll(
                                Player.COMMAND_PLAY_PAUSE,
                                Player.COMMAND_SEEK_TO_NEXT,
                                Player.COMMAND_SEEK_TO_PREVIOUS,
                                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                                Player.COMMAND_SEEK_BACK,
                                Player.COMMAND_SEEK_FORWARD,
                                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                                Player.COMMAND_GET_DEVICE_VOLUME,
                                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                            )
                            .build(),
                    )
                    .build()
            }
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


        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            @Suppress("DEPRECATION")
            val keyEvent =
                intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            recordExternalMediaEvent(
                source = controllerInfo.packageName,
                what = "key=${keyEvent?.keyCode} action=${keyEvent?.action}",
            )
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (controller.packageName != packageName) {
                recordExternalMediaEvent(
                    source = controller.packageName,
                    what = "playerCommand=$playerCommand",
                )
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
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
            val catalog = MediaBrowseRegistry.catalog?.invoke()
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_INVALID_STATE,
                    params,
                )
            val children = childrenOf(catalog, parentId)
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_BAD_VALUE,
                    params,
                )
            val resultPage = browsePage(children, page, pageSize)
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_BAD_VALUE,
                    params,
                )
            LibraryResult.ofItemList(ImmutableList.copyOf(resultPage), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = browseFuture {
            val catalog = MediaBrowseRegistry.catalog?.invoke()
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_INVALID_STATE,
                )
            resolveBrowseItem(catalog, mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = browseFuture {
            if (query.isBlank()) {
                return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_BAD_VALUE,
                    params,
                )
            }
            val catalog = MediaBrowseRegistry.catalog?.invoke()
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_INVALID_STATE,
                    params,
                )
            val count = rankMediaSearch(catalog.tracks, query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browseFuture {
            val catalog = MediaBrowseRegistry.catalog?.invoke()
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_INVALID_STATE,
                    params,
                )
            val matches = rankMediaSearch(catalog.tracks, query)
            val resultPage = browsePage(matches, page, pageSize)
                ?: return@browseFuture LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_BAD_VALUE,
                    params,
                )
            LibraryResult.ofItemList(
                ImmutableList.copyOf(resultPage.map(::playableItem)),
                params,
            )
        }

        // Add to queue and replace. A replace left with nothing that can play would delete the rows
        // it names, so it fails instead, like a set that finds nothing: see [playableRequestFrom].
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = browseFuture {
            playableRequestFrom(controller, mediaItems).items.toMutableList()
        }

        // A queue set from outside the app (Android Auto's browse tree, a voice request, another
        // app's media browser) starts on the listener's pick, but nothing announces it: the app's
        // controller announces only its own commands, and leaves a queue it restores at launch
        // unexplained. So the pick is announced here once resolved. Media3 installs the queue only
        // after this future completes, so the player's transition finds the announcement waiting.
        // Resumption and Add to queue take other callbacks and stay unannounced, and so does a
        // request that leaves the music to the app, which picks no track: see [playAnything].
        //
        // The items resolve here, not through Media3's default, which pairs the start it was given
        // with whatever onAddMediaItems returns: once a row that cannot play drops out, that start
        // points at another track, or past the end.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (asksForAnything(mediaItems.map(::requestedRow))) return playAnything(controller)
            val resolved = browseFuture {
                val request = playableRequestFrom(
                    controller,
                    mediaItems,
                    startIndex.takeUnless { it == C.INDEX_UNSET },
                    startPositionMs.takeUnless { it == C.TIME_UNSET },
                )
                MediaSession.MediaItemsWithStartPosition(
                    request.items,
                    request.startIndex ?: C.INDEX_UNSET,
                    request.startPositionMs ?: C.TIME_UNSET,
                )
            }
            if (controller.packageName == packageName) return resolved
            return Futures.transform(
                resolved,
                { queue ->
                    AndroidPlaybackStarts.announceExternalQueue(
                        queue.mediaItems,
                        queue.startIndex,
                        shuffled = playbackPlayer?.shuffleModeEnabled == true,
                    )
                    queue
                },
                // The request resolves on the main thread, where the ledger and player live.
                MoreExecutors.directExecutor(),
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            playWhenReady: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = browseFuture {
            val resume = MediaBrowseRegistry.resumption?.invoke()
                ?: throw UnsupportedOperationException("No saved playback queue")
            pendingResumptionMode = resume.shuffleMode
            MediaSession.MediaItemsWithStartPosition(
                resume.tracks.map(::playableItem),
                resume.startIndex,
                resume.positionMs,
            )
        }
    }


    /** Bridges the coroutine-shaped catalog into Media3's ListenableFuture callbacks. */
    private fun <T : Any> browseFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try {
                future.set(block())
            } catch (cancelled: CancellationException) {
                future.cancel(false)
                throw cancelled
            } catch (failure: Throwable) {
                future.setException(failure)
            }
        }
        future.addListener(
            { if (future.isCancelled) job.cancel() },
            MoreExecutors.directExecutor(),
        )
        job.invokeOnCompletion { failure ->
            if (failure != null && !future.isDone) future.setException(failure)
        }
        return future
    }

    private fun childrenOf(catalog: MediaBrowseCatalog, parentId: String): List<MediaItem>? {
        return when (parentId) {
            BROWSE_ROOT_ID -> listOf(
                folderItem(BROWSE_COLLECTIONS_ID, catalog.collectionsTitle),
                folderItem(BROWSE_TRACKS_ID, catalog.tracksTitle),
            )
            BROWSE_COLLECTIONS_ID -> catalog.collections.map { folderItem(it.id, it.title) }
            BROWSE_TRACKS_ID -> catalog.tracks.map(::playableItem)
            else -> catalog.collections.firstOrNull { it.id == parentId }
                ?.tracks?.map(::playableItem)
        }
    }

    private fun resolveBrowseItem(catalog: MediaBrowseCatalog, mediaId: String): MediaItem? =
        when (mediaId) {
            BROWSE_ROOT_ID -> folderItem(BROWSE_ROOT_ID, "LatentJam")
            BROWSE_COLLECTIONS_ID -> folderItem(
                BROWSE_COLLECTIONS_ID,
                catalog.collectionsTitle,
            )
            BROWSE_TRACKS_ID -> folderItem(BROWSE_TRACKS_ID, catalog.tracksTitle)
            else -> catalog.collections.firstOrNull { it.id == mediaId }
                ?.let { folderItem(it.id, it.title) }
                ?: resolvePlayable(catalog, mediaId)
        }

    /**
     * What [controller]'s request for [mediaItems] can play; see [playableRequest]. External
     * browsers (Android Auto) send browse-only items with no URI, resolved here by id or search
     * query. In-process items arrive complete and pass through untouched, so the in-app play path
     * is unchanged.
     *
     * A request none of whose items can play throws. Media3 returns an error result to a Media3
     * controller, and the legacy playFrom, prepareFrom and addQueueItem path drops the request, so
     * the queue that was playing plays on.
     */
    private suspend fun playableRequestFrom(
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int? = null,
        startPositionMs: Long? = null,
    ): PlayableRequest<MediaItem> {
        // The catalog is what lets a row with no URI be resolved by id or search query, so only a
        // request carrying such a row needs it at all: the in-app path sends complete items and
        // stays untouched. Building it scans the whole library and its playlists, and the service
        // callbacks run on Dispatchers.Main.immediate, so it is deferred until an unresolved row is
        // actually seen and then built off the main thread — otherwise every set from the app waits
        // for that scan before Media3 installs the queue.
        val catalog = if (mediaItems.any { it.localConfiguration == null }) {
            withContext(Dispatchers.IO) { MediaBrowseRegistry.catalog?.invoke() }
        } else {
            null
        }
        val rows = mediaItems.map { item ->
            if (item.localConfiguration != null) {
                item
            } else {
                catalog?.let { available ->
                    resolvePlayable(available, item.mediaId)
                        ?: item.requestMetadata.searchQuery
                            ?.let { query -> resolveSearchPlayable(available, query) }
                }
            }
        }
        return playableRequest(rows, startIndex, startPositionMs) ?: run {
            // Nothing else marks the refusal: the black box would show the request arrive, then
            // nothing, like a command that went missing.
            recordExternalMediaEvent(
                source = controller.packageName,
                what = "refused: none of ${mediaItems.size} requested items can play",
            )
            throw UnsupportedOperationException("None of the requested items can play")
        }
    }

    private fun requestedRow(item: MediaItem): RequestedRow = RequestedRow(
        mediaId = item.mediaId,
        searchQuery = item.requestMetadata.searchQuery,
        carriesAudio = item.localConfiguration != null,
    )

    /**
     * Answers a request that leaves the music to the app, such as a voice "play music on LatentJam"
     * (see [asksForAnything]): the queue already loaded, or else the one saved for the next launch,
     * installed the way playback resumption installs it. With neither, the request is refused like
     * one that cannot play.
     *
     * The loaded queue is answered with [KeepLoadedQueue], which [LoadedQueueKeeper] leaves
     * uninstalled, so its track carries on from where it stands and its shuffle order stays. Media3
     * then prepares the player if it must, restarts a track that ended, and plays only for a
     * controller that asked to play rather than prepare. The listener asked for music, not for the
     * track either queue starts on, so neither is announced as their pick.
     */
    private fun playAnything(
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if ((playbackPlayer?.mediaItemCount ?: 0) > 0) {
            recordExternalMediaEvent(controller.packageName, "play anything: the loaded queue")
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(KeepLoadedQueue),
                    C.INDEX_UNSET,
                    C.TIME_UNSET,
                ),
            )
        }
        return browseFuture {
            val resume = MediaBrowseRegistry.resumption?.invoke() ?: run {
                recordExternalMediaEvent(
                    source = controller.packageName,
                    what = "refused: no queue loaded or saved",
                )
                throw UnsupportedOperationException("No queue loaded or saved")
            }
            recordExternalMediaEvent(controller.packageName, "play anything: the saved queue")
            pendingResumptionMode = resume.shuffleMode
            MediaSession.MediaItemsWithStartPosition(
                resume.tracks.map(::playableItem),
                resume.startIndex,
                resume.positionMs,
            )
        }
    }

    private fun resolvePlayable(catalog: MediaBrowseCatalog, mediaId: String): MediaItem? {
        if (mediaId.isBlank()) return null
        val track = catalog.tracks.firstOrNull { it.id.value == mediaId }
            ?: catalog.collections.asSequence()
                .flatMap { it.tracks.asSequence() }
                .firstOrNull { it.id.value == mediaId }
        return track?.takeIf { !it.audioUri.isNullOrBlank() }?.let(::playableItem)
    }

    private fun resolveSearchPlayable(catalog: MediaBrowseCatalog, query: String): MediaItem? =
        rankMediaSearch(catalog.tracks, query).firstOrNull()?.let(::playableItem)

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
                .setAlbumTitle(track.album)
                .setGenre(track.genre)
                .setDurationMs(track.durationMs)
                .setArtworkUri(track.artworkUri?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()


    /** Bounds how often one source may write into [MediaBlackBox]; see [recordExternalMediaEvent]. */
    private val transportEvents = TransportEventThrottle()

    /**
     * Durable black box for the transport surface no test bench can reach: headphone taps,
     * OEM Bluetooth stacks, System UI. One line per EXTERNAL command with the source package,
     * what arrived, and whether audio was actually playing at that instant — so "my headphones
     * did not pause it" becomes attributable days later instead of folklore. Bounded on disk
     * and best-effort by design — see [MediaBlackBox], which SMART's abstentions share.
     *
     * Who may write is not something this surface can choose — any app on the session's transport
     * surface may command it — so how *often* one source may write is: see
     * [TransportEventThrottle]. Without it a source stuck in a loop bought a line per command and
     * could push the bounded log past its size, dropping the history it exists to keep.
     */
    private fun recordExternalMediaEvent(source: String?, what: String) {
        val name = source ?: UNKNOWN_EVENT_SOURCE
        val admitted = transportEvents.admit(name, what, SystemClock.elapsedRealtime()) ?: return
        MediaBlackBox.record(filesDir, "$name $admitted playing=${playbackPlayer?.isPlaying}")
    }

    /** Keeps stateful notification icons in step with changes from either the app or System UI. */
    private val playerListener = object : Player.Listener {
        // The black box records commands ARRIVING; these two record what playback DID and why.
        // Together they close the loop: a pause that executed and was then undone by focus or a
        // remote becomes two attributable lines instead of "the tap did nothing".
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val cause = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "focus-loss"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "becoming-noisy"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end-of-item"
                else -> "reason-$reason"
            }
            recordExternalMediaEvent("player", "playWhenReady=$playWhenReady cause=$cause")
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            val cause = when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                    "transient-focus-loss"
                else -> "reason-$playbackSuppressionReason"
            }
            recordExternalMediaEvent("player", "suppression=$cause")
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val installingResumption = pendingResumptionMode != null
            applyPendingResumptionMode()
            if (!installingResumption) clearStaleActiveResumption()
        }

        // How each play began is read here, from the player itself, for the listening log — see
        // [AndroidPlaybackStarts] for why the app's own MediaController cannot be trusted with it.
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = AndroidPlaybackStarts.onPositionDiscontinuity(oldPosition, newPosition, reason)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playbackPlayer?.let { AndroidPlaybackStarts.onMediaItemTransition(it, mediaItem, reason) }
        }

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

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAnyWidgetStateEvent()) publishWidgetSnapshot(player)
        }
    }

    private var lastQueueEditRequest: Long? = null

    /** Runs synchronously inside the ordered metadata command, before later prepare/play calls. */
    private fun applyQueueEdit(marker: android.os.Bundle) {
        val player = playbackPlayer ?: return
        when (marker.getString(AndroidQueueCommand.OPERATION)) {
            AndroidQueueCommand.START, AndroidQueueCommand.RESTORE -> {
                if (
                    !player.shuffleModeEnabled ||
                    player.currentMediaItem?.mediaId != marker.getString(AndroidQueueCommand.MEDIA_ID) ||
                    player.currentMediaItemIndex != marker.getInt(AndroidQueueCommand.INDEX, -1) ||
                    player.mediaItemCount != marker.getInt(AndroidQueueCommand.SIZE, -1)
                ) return
                val order = if (marker.getString(AndroidQueueCommand.OPERATION) == AndroidQueueCommand.RESTORE) {
                    restoredOnIdentityTraversal(player.mediaItemCount)
                } else {
                    shuffleOrderStartingAt(nativeTraversal(player), player.currentMediaItemIndex)
                }
                player.setShuffleOrder(DefaultShuffleOrder(order, System.nanoTime()))
            }
            AndroidQueueCommand.PLAY_NEXT, AndroidQueueCommand.APPEND -> {
                val item = runCatching {
                    marker.getBundle(AndroidQueueCommand.ITEM)?.let {
                        MediaItem.fromBundle(it, MediaLibraryInfo.INTERFACE_VERSION)
                    }
                }.getOrNull() ?: return
                if (item.localConfiguration == null || item.mediaId.isBlank()) return
                insertManualQueueItem(
                    player, item,
                    playNext = marker.getString(AndroidQueueCommand.OPERATION) == AndroidQueueCommand.PLAY_NEXT,
                )
            }
            AndroidQueueCommand.MOVE -> {
                if (!player.shuffleModeEnabled ||
                    player.mediaItemCount != marker.getInt(AndroidQueueCommand.SIZE, -1)
                ) return
                val reordered = shuffleOrderMovingRow(
                    nativeTraversal(player),
                    marker.getInt(AndroidQueueCommand.FROM, -1),
                    marker.getInt(AndroidQueueCommand.TO, -1),
                )
                player.setShuffleOrder(DefaultShuffleOrder(reordered, System.nanoTime()))
            }
        }
    }

    private fun nativeTraversal(player: ExoPlayer): IntArray = player.shuffleOrder.let { order ->
        boundedQueueOrder(player.mediaItemCount, order.firstIndex, order::getNextIndex)
    }

    /** Restores the saved logical queue order after Media3 installs resumption's media items. */
    private fun applyPendingResumptionMode() {
        val mode = pendingResumptionMode ?: return
        val player = playbackPlayer?.takeIf { it.mediaItemCount > 0 } ?: return
        pendingResumptionMode = null
        AndroidShuffleModeRegistry.set(mode)
        when (mode) {
            ShuffleMode.ON -> {
                // The persisted queue is already in traversal order. Identity shuffle preserves
                // that exact Next chain while retaining native shuffle semantics and UI state.
                player.setShuffleOrder(
                    DefaultShuffleOrder(
                        restoredOnIdentityTraversal(player.mediaItemCount),
                        System.nanoTime(),
                    ),
                )
                player.shuffleModeEnabled = true
            }
            ShuffleMode.OFF, ShuffleMode.SMART -> player.shuffleModeEnabled = false
        }
        refreshMediaButtons()
    }

    /** A later, unrelated queue must not leave the prior resumption handoff globally retained. */
    private fun clearStaleActiveResumption() {
        val resume = MediaBrowseRegistry.currentActiveResumption() ?: return
        val player = playbackPlayer ?: return
        val actualIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (actualIds != resume.tracks.map { it.id.value }) {
            MediaBrowseRegistry.clearActiveResumption(resume)
        }
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
            .setMaxSeekToPreviousPositionMs(PREVIOUS_RESTART_THRESHOLD_MS)
            .build()
            .apply { setAudioSessionId(audioSessionId) }
        playbackPlayer = player
        // Announced rather than injected: this service is built by the system and cannot see
        // the app's scoped Koin graph. Whoever owns the equalizer picks the session up from here.
        AudioSessionRegistry.publish(audioSessionId)
        val sessionPlayer = object : LoadedQueueKeeper(player) {
            override fun setPlaylistMetadata(playlistMetadata: MediaMetadata) {
                val marker = playlistMetadata.extras?.getBundle(AndroidQueueCommand.KEY)
                if (marker == null) {
                    super.setPlaylistMetadata(playlistMetadata)
                    return
                }
                // Intercept the ordered command itself: MediaMetadata.equals intentionally
                // ignores bundle contents, so an onPlaylistMetadataChanged listener silently
                // loses every subsequent edit. Do not publish private commands as metadata.
                val request = marker.getLong(AndroidQueueCommand.REQUEST)
                if (lastQueueEditRequest == request) return
                lastQueueEditRequest = request
                applyQueueEdit(marker)
            }
        }
        val sessionBuilder = MediaLibrarySession.Builder(this, sessionPlayer, sessionCallback)
            .setMediaButtonPreferences(
                mediaButtonPreferences(player, AndroidShuffleModeRegistry.mode.value),
            )
            // Media3 still sizes and caches covers around this, as it does around its own loader.
            .setBitmapLoader(TrackCoverBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
        appLaunchPendingIntent()?.let(sessionBuilder::setSessionActivity)
        mediaSession = sessionBuilder.build()
        player.addListener(playerListener)
        // The listener's persisted repeat choice belongs to the transport this service is about to
        // host: the widget and the player screen show it again as soon as the app connects (see
        // [initialPlaybackModes]), and until now only the shuffle half reached a player.
        applyPersistedRepeatMode(player)
        // If Android recreated the service after an unclean process death, the persisted timing
        // anchor must no longer claim that progress is live while Media3 restores the queue.
        PlaybackWidgetStateStore.markPaused(this)
        serviceScope.launch {
            AndroidShuffleModeRegistry.mode.collectLatest {
                refreshMediaButtons()
                // SMART and OFF both map to ExoPlayer shuffle=false, so only this logical-mode
                // bridge can distinguish them for the widget. Preserve prior metadata until a
                // real timeline arrives when the service has just been recreated empty.
                playbackPlayer?.takeIf { it.currentMediaItem != null }
                    ?.let(::publishWidgetSnapshot)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Freezes the widget clock on the way out of the task, not only on the way out of the process.
     *
     * A snapshot whose anchor says "playing" outlives the process that could correct it: the launcher
     * keeps the Chronometer ticking until some later render replaces it, and after a crash or a
     * force-stop until the periodic widget update arrives. Publishing the paused snapshot here — and
     * from [onDestroy], which covers every other teardown — turns normal completion into an immediate
     * correction, leaving only the crash case to the update period.
     *
     * Playback that survives the swipe keeps the live snapshot: until the transport itself leaves the
     * playing state, `playWhenReady` still describes the truth the widget shows, and [onDestroy]
     * freezes it when the service finally stops. Overriding this does not change what the service
     * does — `MediaLibraryService.onTaskRemoved` still decides whether a swipe stops playback or
     * leaves it running, and it is still called.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (playbackPlayer?.playWhenReady != true) PlaybackWidgetStateStore.markPaused(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PlaybackWidgetStateStore.markPaused(this)
        AudioSessionRegistry.publish(null)
        MediaBrowseRegistry.clearActiveResumption()
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

    /** Captures player state only on discrete Media3 events; progress is projected by readers. */
    private fun publishWidgetSnapshot(player: Player) {
        val currentItem = player.currentMediaItem
        val metadata = currentItem?.mediaMetadata
        val nextIndex = player.nextMediaItemIndex
        val nextItem = nextIndex
            .takeIf { it != C.INDEX_UNSET && it in 0 until player.mediaItemCount }
            ?.let(player::getMediaItemAt)
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        PlaybackWidgetStateStore.publish(
            this,
            PlaybackWidgetSnapshot(
                mediaId = currentItem?.mediaId.orEmpty(),
                title = metadata?.title?.toString().orEmpty(),
                artist = metadata?.artist?.toString().orEmpty(),
                artworkUri = metadata?.artworkUri?.toString(),
                // The transport the listener asked for, not the instantaneous player flag: a seek
                // buffers for a moment, and reading isPlaying flipped the widget to Play and froze
                // its clock while the music was merely on its way. Same rule as the in-app player,
                // see [showPauseButton]; the service has no pending pause of its own.
                isPlaying = showPauseButton(
                    playWhenReady = player.playWhenReady,
                    playbackState = player.playbackState,
                    pausePending = false,
                ),
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration
                    .takeUnless { it == C.TIME_UNSET }
                    ?.coerceAtLeast(0)
                    ?: 0,
                hasPrevious = player.previousMediaItemIndex != C.INDEX_UNSET,
                hasNext = nextIndex != C.INDEX_UNSET,
                repeatMode = player.repeatMode.toWidgetRepeatMode(),
                shuffleMode = AndroidShuffleModeRegistry.mode.value,
                nextTitle = nextItem?.mediaMetadata?.title?.toString()
                    ?: nextItem?.mediaId?.takeIf(String::isNotBlank),
                capturedElapsedRealtimeMs = elapsedRealtimeMs,
                capturedBootCount = PlaybackWidgetStateStore.currentBootCount(this),
            ),
        )
    }

    /**
     * Puts the listener's persisted repeat choice onto the freshly built player.
     *
     * The widget snapshot survives process death and is what the app reads to restore both
     * transport modes before a command reaches an empty service (see [initialPlaybackModes]) — but
     * only [AndroidShuffleModeRegistry] carried its half all the way to the player, so the restored
     * repeat was shown by the player screen and the widget while the new player repeated nothing and
     * stopped at the end of the queue. A snapshot with no track is a player that never played
     * anything, so there is no choice to carry over.
     */
    private fun applyPersistedRepeatMode(player: Player) {
        val persisted = PlaybackWidgetStateStore.read(this)
        if (persisted.mediaId.isBlank()) return
        player.repeatMode = persisted.repeatMode.toNativeRepeatMode()
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
     * after the playhead or at the traversal end. Native media controls use that same order.
     */
    private fun insertManualQueueItem(player: ExoPlayer, item: MediaItem, playNext: Boolean) {
        if (!player.shuffleModeEnabled || player.mediaItemCount == 0) {
            val insertAt = if (playNext && player.mediaItemCount > 0) {
                (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
            } else player.mediaItemCount
            player.addMediaItem(insertAt, item)
            return
        }
        val oldTraversal = nativeTraversal(player)
        val extendedTraversal = if (playNext) {
            shuffleOrderAppendingNext(oldTraversal, player.currentMediaItemIndex)
        } else {
            // A missing playhead means append to the end of traversal, not a random position.
            shuffleOrderAppendingNext(oldTraversal, currentMediaItemIndex = -1)
        }
        player.addMediaItem(item)
        player.setShuffleOrder(DefaultShuffleOrder(extendedTraversal, System.nanoTime()))
    }

    private fun shuffleIcon(mode: ShuffleMode): Int = when (mode) {
        ShuffleMode.OFF -> CommandButton.ICON_SHUFFLE_OFF
        ShuffleMode.ON -> CommandButton.ICON_SHUFFLE_ON
        // The drawable set in mediaButtonPreferences supplies the glyph; no built-in star.
        ShuffleMode.SMART -> CommandButton.ICON_UNDEFINED
    }

    // Media3 takes CommandButton.displayName as plain text and does not localize it, while the
    // notification, Android Auto/head units and accessibility services all read it aloud or show
    // it, so the labels live in res/values and its translations rather than here.
    private fun shuffleActionName(mode: ShuffleMode): String = getString(
        when (mode) {
            ShuffleMode.OFF -> R.string.media_session_shuffle_on
            ShuffleMode.ON -> R.string.media_session_shuffle_smart_on
            ShuffleMode.SMART -> R.string.media_session_shuffle_off
        },
    )

    private fun repeatIcon(repeatMode: Int): Int = when (repeatMode) {
        Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
        Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
        else -> CommandButton.ICON_REPEAT_OFF
    }

    private fun repeatActionName(repeatMode: Int): String = getString(
        when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.string.media_session_repeat_all
            Player.REPEAT_MODE_ALL -> R.string.media_session_repeat_one
            else -> R.string.media_session_repeat_off
        },
    )

    private fun nextRepeatMode(repeatMode: Int): Int = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    private fun Int.toWidgetRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }

    private fun RepeatMode.toNativeRepeatMode(): Int = when (this) {
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    }

    private fun Player.Events.containsAnyWidgetStateEvent(): Boolean =
        contains(Player.EVENT_TIMELINE_CHANGED) ||
            contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
            contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
            contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            contains(Player.EVENT_IS_PLAYING_CHANGED) ||
            contains(Player.EVENT_POSITION_DISCONTINUITY) ||
            contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
            contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)

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

        const val MEDIA_NOTIFICATION_REQUEST_CODE = 4202

        /** Source name used when the caller's package is unknown (a system key event). */
        const val UNKNOWN_EVENT_SOURCE = "?"
    }
}

/**
 * Token bucket that bounds how much one source can write into the durable black box.
 *
 * The transport surface is open by design — headphone dispatchers, OEM stacks and other media apps
 * must be able to command the session — and every command used to buy a line in [MediaBlackBox],
 * whose retention keeps only the newest half once it passes its size bound. A source stuck in a loop
 * could therefore still shorten the very history a report is about.
 *
 * A real fault is a burst, not a stream: a Bluetooth tap arrives as a connect line, a key line and
 * the resulting state change within a second, so every source may spend [BURST_LINES] at once and
 * then earns [REFILL_MS] per further line. What a burst loses is not lost from the report: the
 * suppressed lines are counted and summarised by the next admitted one, so a flood still reads as a
 * flood. The bucket table is bounded too — a source that exists only to flood the log must not be
 * able to grow the map without limit. When the table is full the least recently seen source is
 * evicted, and only that one: a persistent flooder keeps whatever it has already spent, so a caller
 * cannot buy itself a fresh burst by walking enough names past the table. Evicting a source drops its
 * suppressed count with it — an evicted source is one that has not written anything for longer than
 * [MAX_SOURCES] other sources, so the line the count would have annotated is long gone.
 */
internal class TransportEventThrottle(
    private val burstLines: Double = BURST_LINES,
    private val refillMs: Long = REFILL_MS,
) {
    private val buckets = mutableMapOf<String, Bucket>()

    /** A line to write for [source], or null while that source's bucket is empty. */
    fun admit(source: String, what: String, nowMs: Long): String? {
        if (source !in buckets && buckets.size >= MAX_SOURCES) evictLeastRecentlySeen()
        val bucket = buckets.getOrPut(source) { Bucket(tokens = burstLines, lastRefillMs = nowMs) }
        val elapsedMs = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L)
        bucket.tokens = minOf(burstLines, bucket.tokens + elapsedMs.toDouble() / refillMs)
        bucket.lastRefillMs = nowMs
        bucket.lastSeenMs = nowMs
        if (bucket.tokens < 1.0) {
            bucket.suppressed++
            return null
        }
        bucket.tokens -= 1.0
        val suppressed = bucket.suppressed
        bucket.suppressed = 0
        return if (suppressed == 0) what else "$what (+$suppressed suppressed)"
    }

    /**
     * Makes room for one new source by dropping the one that has gone longest without a line.
     *
     * Preferring the least recently seen bucket over the oldest-created one is what keeps a flooder
     * honest: the source currently spending its tokens stays in the table, while idle names age out.
     */
    private fun evictLeastRecentlySeen() {
        val stalest = buckets.minByOrNull { (_, bucket) -> bucket.lastSeenMs }?.key ?: return
        buckets.remove(stalest)
    }

    private class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
        var suppressed: Int = 0,
        var lastSeenMs: Long = lastRefillMs,
    )

    private companion object {
        const val BURST_LINES = 4.0
        const val REFILL_MS = 1_000L
        const val MAX_SOURCES = 32
    }
}

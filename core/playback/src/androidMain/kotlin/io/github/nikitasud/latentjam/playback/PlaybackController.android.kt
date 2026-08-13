/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [PlaybackController]: a `MediaController` bridge onto
 * [PlaybackService]'s ExoPlayer.
 *
 * ### SMART queue strategy
 * OFF and ON map straight onto the player queue (natural order / ExoPlayer's
 * shuffle order). SMART keeps the queue linear and holds a LOOKAHEAD of
 * the configured number of tracks: whenever the queue runs shorter than that, the
 * controller tops it back up from the injected [NextTrackChooser], continuing the walk from
 * the tail each time. Skipping or finishing a track therefore always has
 * somewhere to go, there is a readable queue rather than a single next-track
 * hint, and the played queue doubles as listening history.
 *
 * All MediaController access is confined to the main thread, per Media3's threading contract.
 * Immutable queue metadata and MediaItems are prepared on a worker first, so tapping a track in a
 * large library does not make the UI thread construct hundreds of objects before playback starts.
 */
internal class AndroidPlaybackController(
    private val context: Context,
    private val chooser: NextTrackChooser,
    private val engine: SimilarityEngine,
) : PlaybackController {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = mutableState.asStateFlow()

    private val controllerMutex = Mutex()
    private var controller: MediaController? = null

    /** The newest tap wins even when an older, larger queue takes longer to prepare. */
    private val playRequestGeneration = AtomicLong()

    /** Main-thread-owned identity of the queue used to reject stale SMART inference results. */
    private var queueGeneration = 0L

    /**
     * Remembers a companion-policy invalidation that arrived before SMART had a queue to prune.
     * Playlist loading and launch restore are concurrent; dropping that early signal would restore
     * a saved future planned under the old policy and leave it untouched for the whole lookahead.
     */
    private var smartFutureInvalidationPending = false

    /**
     * Serialises SMART appends.
     *
     * [appendSmartNextIfNeeded] decides whether to append by reading the player, then SUSPENDS in
     * the chooser (the engine plans on its own dispatcher) before it writes. Two invocations that
     * overlap across that suspension both read the pre-append queue, both conclude a track is
     * needed, and both append — which is where consecutive duplicates came from. There is always a
     * second invocation available to overlap: [next] calls this directly while the resulting
     * `onMediaItemTransition` launches another on [mainScope].
     */
    private val appendMutex = Mutex()

    /** The source list for OFF/ON and the complete library for SMART are intentionally separate. */
    private var pool: List<TrackDescriptor> = emptyList()
    private var smartLibrary: List<TrackDescriptor> = emptyList()
    private var smartLibrarySupplied: Boolean = false
    private var smartLookahead: Int = DEFAULT_SMART_LOOKAHEAD
    private var poolById: Map<String, TrackDescriptor> = emptyMap()
    private var smartById: Map<String, TrackDescriptor> = emptyMap()
    private var mode: ShuffleMode = ShuffleMode.OFF

    /** Rebuilt only when the queue actually changes; shared by ticker emissions. */
    private var cachedQueue: List<TrackDescriptor> = emptyList()
    /** Maps each user-visible queue row back to Media3's physical playlist index. */
    private var cachedQueueMediaIndices: List<Int> = emptyList()
    private var tickerJob: Job? = null
    private var repeat: RepeatMode = RepeatMode.OFF

    /**
     * Identities already attempted by the current automatic fatal-error recovery chain.
     *
     * Media3 reports each unreadable item asynchronously. Keeping identities rather than physical
     * indices survives shuffle-order changes and makes the chain bounded even with repeat-all
     * enabled. Manual transport commands clear it because they are a fresh user request and may
     * follow a transient I/O failure.
     */
    private val failedRecoveryIds = mutableSetOf<String>()

    /** Small bounded cache: only coverless tracks that actually reach the playhead are rendered. */
    private val latentArtworkCachedIds = mutableSetOf<String>()
    private val fallbackArtworkCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            val shouldRemove = size > MAX_FALLBACK_ARTWORK_CACHE
            if (shouldRemove && eldest != null) latentArtworkCachedIds.remove(eldest.key)
            return shouldRemove
        }
    }
    private val fallbackArtworkInFlight = mutableSetOf<String>()

    init {
        mainScope.launch {
            AndroidShuffleModeRegistry.mode.collectLatest(::applyShuffleMode)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            rebuildQueueSnapshot()
            pushState()
            mainScope.launch {
                decorateCoverlessTrack(mediaItem)
                appendSmartNextIfNeeded()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            rebuildQueueSnapshot()
            pushState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateTicker(isPlaying)
            pushState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Reaching READY proves the recovery chain found a readable item. Forget failures from
            // that completed chain so a later repeat/session may legitimately retry those tracks.
            if (playbackState == Player.STATE_READY) failedRecoveryIds.clear()
            pushState()
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = controller ?: return
            val failedId = player.currentMediaItem?.mediaId ?: return
            val reportedIndex = player.currentMediaItemIndex
            // A fatal source failure can leave Media3's index unset even though it still exposes the
            // failing item. Resolve that stable identity back into the physical queue before choosing
            // recovery candidates; treating INDEX_UNSET as terminal would strand readable tail rows.
            val failedIndex = reportedIndex.takeIf { it in 0 until player.mediaItemCount }
                ?: (0 until player.mediaItemCount).firstOrNull { index ->
                    player.getMediaItemAt(index).mediaId == failedId
                }
                ?: run {
                    player.pause()
                    pushState()
                    return
                }
            val intendedToPlay = player.playWhenReady
            failedRecoveryIds += failedId
            val traversalOrder = playerTraversalOrder(player)
            val replacement = nextPlaybackErrorRecoveryIndex(
                mediaIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                failedIndex = failedIndex,
                repeatAll = player.repeatMode == Player.REPEAT_MODE_ALL,
                traversalOrder = traversalOrder,
                failedIds = failedRecoveryIds,
            )

            if (replacement == null) {
                // Every reachable row failed exactly once. Park on the final failed row with honest
                // paused state; a later explicit Play/Next clears the attempt set and may retry after
                // transient storage access returns.
                player.pause()
                rebuildQueueSnapshot()
                pushState()
                return
            }

            queueGeneration++
            player.seekToDefaultPosition(replacement)
            player.prepare()
            if (intendedToPlay) player.play()
            rebuildQueueSnapshot()
            pushState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // System UI and Bluetooth controllers can seek while paused, when the ticker is off.
            // Publish that discontinuity immediately so Now Playing and persisted resume position
            // do not keep the pre-seek value until playback happens to resume.
            pushState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // SMART deliberately disables ExoPlayer's random shuffle because LatentJam owns the
            // queue. Do not mistake that internal transition for the user switching SMART off.
            if (!(mode == ShuffleMode.SMART && !shuffleModeEnabled)) {
                mode = if (shuffleModeEnabled) ShuffleMode.ON else ShuffleMode.OFF
                AndroidShuffleModeRegistry.set(mode)
            }
            rebuildQueueSnapshot()
            pushState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeat = repeatMode.toLatentJamRepeatMode()
            pushState()
        }
    }

    override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>) {
        val prepared = withContext(Dispatchers.Default) {
            tracks.distinctBy { it.id }.let { distinct ->
                distinct to distinct.associateBy { it.id.value }
            }
        }
        withContext(Dispatchers.Main.immediate) {
            val player = controller
            val keepThrough = when {
                player == null || player.mediaItemCount == 0 -> -1
                player.currentMediaItemIndex >= 0 -> player.currentMediaItemIndex
                else -> 0
            }
            // Generated tracks are normally resolved through smartById rather than the source
            // queue's poolById. Preserve descriptors through the current item before replacing the
            // eligible map, or excluding the playing artist would make NowPlaying disappear.
            val retainedHistory = if (mode == ShuffleMode.SMART && player != null) {
                (0..keepThrough)
                    .mapNotNull { index ->
                        val id = player.getMediaItemAt(index).mediaId
                        trackById(id)?.let { id to it }
                    }
                    .toMap()
            } else {
                emptyMap()
            }
            smartLibrary = prepared.first
            smartById = prepared.second + retainedHistory
            smartLibrarySupplied = true
            queueGeneration++
            if (mode == ShuffleMode.SMART && player != null) {
                // An eligibility change is stronger than a queue planned before it. Preserve what
                // already played and the current seed, then remove newly ineligible future rows.
                for (index in player.mediaItemCount - 1 downTo keepThrough + 1) {
                    if (player.getMediaItemAt(index).mediaId !in prepared.second) {
                        player.removeMediaItem(index)
                    }
                }
                rebuildQueueSnapshot()
                pushState()
                appendSmartNextIfNeeded()
            }
        }
    }

    override suspend fun setSmartQueueLength(length: Int) {
        withContext(Dispatchers.Main.immediate) {
            smartLookahead = length.coerceIn(1, MAX_SMART_LOOKAHEAD)
            appendSmartNextIfNeeded()
        }
    }

    override suspend fun invalidateSmartFuture() {
        withContext(Dispatchers.Main.immediate) {
            // Invalidate before touching the queue so an in-flight chooser cannot commit a row
            // selected under the old companion policy after the replacement tail is installed.
            queueGeneration++
            smartFutureInvalidationPending = true
            val player = controller ?: return@withContext
            if (mode != ShuffleMode.SMART || player.mediaItemCount == 0) return@withContext

            removeUnplayedSmartFuture(player)
            smartFutureInvalidationPending = false
            rebuildQueueSnapshot()
            pushState()
            appendSmartNextIfNeeded()
        }
    }

    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val requestGeneration = playRequestGeneration.incrementAndGet()
        val modeAtRequest = withContext(Dispatchers.Main.immediate) { mode }
        var prepared = withContext(Dispatchers.Default) {
            preparePlayback(
                tracks = tracks,
                startIndex = startIndex,
                includeFullQueue = modeAtRequest != ShuffleMode.SMART,
            )
        }

        while (playRequestGeneration.get() == requestGeneration) {
            var needsFullQueue = false
            val committed = withContext(Dispatchers.Main.immediate) {
                if (playRequestGeneration.get() != requestGeneration) return@withContext false
                val player = controller()
                // Building the controller can suspend. A newer tap may have arrived meanwhile.
                if (playRequestGeneration.get() != requestGeneration) return@withContext false

                val currentMode = mode
                val fullQueue = prepared.fullQueue
                if (currentMode != ShuffleMode.SMART && fullQueue == null) {
                    // Shuffle mode changed while the selected SMART item was being prepared. Finish
                    // constructing the natural queue on the worker, then revalidate once more.
                    needsFullQueue = true
                    return@withContext false
                }

                pool = prepared.tracks
                poolById = prepared.byId
                beginFreshRecoveryAttempt()
                queueGeneration++
                val committedQueueGeneration = queueGeneration

                if (currentMode == ShuffleMode.SMART) {
                    // SMART owns its queue: start from the tapped track alone and let the chooser
                    // build the path forward. Do not construct hundreds of unused MediaItems.
                    player.setMediaItems(listOf(prepared.selectedItem))
                    // A fresh one-row queue contains no recommendation from the previous policy.
                    smartFutureInvalidationPending = false
                } else {
                    player.setMediaItems(fullQueue!!, prepared.startIndex, 0L)
                    player.shuffleModeEnabled = currentMode == ShuffleMode.ON
                }
                player.prepare()
                player.play()
                rebuildQueueSnapshot()
                pushState()

                // Artwork and SMART lookahead may suspend behind I/O/inference. Playback is already
                // running, so keep them out of this method's critical path and reject their work if
                // another request replaces the queue first.
                val currentItem = player.currentMediaItem
                mainScope.launch {
                    decorateCoverlessTrack(currentItem)
                    if (
                        playRequestGeneration.get() == requestGeneration &&
                        queueGeneration == committedQueueGeneration
                    ) {
                        appendSmartNextIfNeeded()
                    }
                }
                true
            }
            if (committed || !needsFullQueue) return
            prepared = withContext(Dispatchers.Default) {
                prepared.copy(fullQueue = prepared.tracks.map { it.toMediaItem() })
            }
        }
    }

    override suspend fun togglePlayPause(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        if (player.isPlaying) {
            player.pause()
        } else {
            beginFreshRecoveryAttempt()
            // `play()` alone is a no-op after the final item reaches STATE_ENDED. Media controls
            // conventionally restart that item, and the in-app button must match them.
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        pushState()
    }

    override suspend fun pause(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        if (player.playWhenReady) player.pause()
        pushState()
    }

    override suspend fun next(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        beginFreshRecoveryAttempt()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            pushState()
            // Prediction must not delay a skip when a playable item is already queued.
            mainScope.launch { appendSmartNextIfNeeded() }
        } else {
            appendSmartNextIfNeeded()
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            }
            pushState()
        }
    }

    override suspend fun previous(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        beginFreshRecoveryAttempt()
        player.seekToPrevious()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        pushState()
    }

    override suspend fun seekTo(positionMs: Long): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        player.seekTo(positionMs)
        pushState()
    }

    override suspend fun playAt(queueIndex: Int): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        val mediaItemIndex = cachedQueueMediaIndices.getOrNull(queueIndex) ?: return@withContext
        if (mediaItemIndex !in 0 until player.mediaItemCount) return@withContext
        beginFreshRecoveryAttempt()
        player.seekTo(mediaItemIndex, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        pushState()
    }

    override suspend fun cycleRepeatMode(): RepeatMode = withContext(Dispatchers.Main) {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        controller?.repeatMode = when (repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        pushState()
        repeat
    }

    override suspend fun retainQueue(trackIds: Set<TrackId>): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        val keep = trackIds.mapTo(HashSet()) { it.value }
        // Backwards, so surviving indices stay valid while earlier ones are removed. Media3
        // treats removing the current item as that item ending: playback advances on its own.
        var removed = false
        for (index in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(index).mediaId !in keep) {
                player.removeMediaItem(index)
                removed = true
            }
        }
        if (!removed) return@withContext
        queueGeneration++
        pool = pool.filter { it.id.value in keep }
        poolById = poolById.filterKeys { it in keep }
        rebuildQueueSnapshot()
        pushState()
    }

    override suspend fun playNext(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        val player = controller()
        beginFreshRecoveryAttempt()
        queueGeneration++
        pool = sourceQueueAfterManualInsert(
            source = pool,
            currentId = player.currentMediaItem?.mediaId?.let(::TrackId),
            track = track,
            insertion = SourceQueueInsertion.PLAY_NEXT,
        )
        poolById = poolById + (track.id.value to track)
        val item = track.toMediaItem()
        if (player.mediaItemCount == 0) {
            // Queue actions are allowed before the first play. Cue the first item paused, matching
            // iOS, rather than silently dropping the command because no controller existed yet.
            player.pause()
            player.setMediaItem(item)
            player.prepare()
        } else {
            val insertedByService = mode == ShuffleMode.ON &&
                player.shuffleModeEnabled &&
                sendShufflePlayNext(player, item)
            if (!insertedByService) {
                val insertAt = if (mode == ShuffleMode.ON && player.shuffleModeEnabled) {
                    player.nextMediaItemIndex.takeIf { it >= 0 } ?: player.mediaItemCount
                } else {
                    (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
                }
                player.addMediaItem(insertAt, item)
            }
        }
        rebuildQueueSnapshot()
        pushState()
    }

    override suspend fun addToQueue(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        val player = controller()
        beginFreshRecoveryAttempt()
        queueGeneration++
        pool = sourceQueueAfterManualInsert(
            source = pool,
            currentId = player.currentMediaItem?.mediaId?.let(::TrackId),
            track = track,
            insertion = SourceQueueInsertion.APPEND,
        )
        poolById = poolById + (track.id.value to track)
        if (player.mediaItemCount == 0) {
            player.pause()
            player.setMediaItem(track.toMediaItem())
            player.prepare()
        } else {
            player.addMediaItem(track.toMediaItem())
        }
        rebuildQueueSnapshot()
        pushState()
    }

    override suspend fun moveQueueItem(from: Int, to: Int): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        // Under random shuffle the sheet shows traversal order while Media3 moves operate on
        // physical order; the two disagree, so the call is inert and the UI hides the affordance.
        if (player.shuffleModeEnabled) return@withContext
        val count = player.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return@withContext
        queueGeneration++
        player.moveMediaItem(from, to)
        rebuildQueueSnapshot()
        pool = sourceQueueAfterVisibleReorder(pool, cachedQueue, mode)
        pushState()
    }

    override suspend fun removeQueueItem(index: Int): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        val mediaItemIndex = cachedQueueMediaIndices.getOrNull(index) ?: return@withContext
        if (mediaItemIndex !in 0 until player.mediaItemCount) return@withContext
        val removedId = player.getMediaItemAt(mediaItemIndex).mediaId
        queueGeneration++
        // Media3 treats removing the current item as that item ending: playback advances alone.
        player.removeMediaItem(mediaItemIndex)
        val stillQueued = (0 until player.mediaItemCount).any {
            player.getMediaItemAt(it).mediaId == removedId
        }
        // A queue may hold the same track twice; the source pool row leaves only with its last
        // queue occurrence, mirroring retainQueue.
        if (!stillQueued) {
            pool = pool.filter { it.id.value != removedId }
            poolById = poolById - removedId
        }
        rebuildQueueSnapshot()
        pushState()
    }

    override suspend fun cycleShuffleMode(): ShuffleMode = withContext(Dispatchers.Main) {
        val nextMode = AndroidShuffleModeRegistry.cycle()
        applyShuffleMode(nextMode)
        nextMode
    }

    override suspend fun setShuffleMode(mode: ShuffleMode): Unit = withContext(Dispatchers.Main) {
        // Through the registry so the notification's cycle button continues from the restored
        // mode instead of from whatever the registry last saw.
        AndroidShuffleModeRegistry.set(mode)
        applyShuffleMode(mode)
    }

    override suspend fun restoreQueue(
        tracks: List<TrackDescriptor>,
        startIndex: Int,
        positionMs: Long,
        sourceTracks: List<TrackDescriptor>?,
    ) {
        if (tracks.isEmpty()) return
        // Same generation contract as play(): a user tap that lands during a slow restore must
        // win, and the restore must then abandon its stale queue rather than clobber the tap's.
        val requestGeneration = playRequestGeneration.incrementAndGet()
        val prepared = withContext(Dispatchers.Default) {
            val restorePlan = playbackResumePlan(tracks, startIndex, sourceTracks)
            val live = preparePlayback(
                tracks = restorePlan.liveQueue,
                startIndex = restorePlan.currentIndex,
                // Resume always restores the exact saved live queue, including SMART's generated
                // future. The separately persisted source remains available for SMART -> OFF.
                includeFullQueue = true,
            )
            val source = restorePlan.sourceQueue
            Triple(live, source, source.associateBy { it.id.value } + live.byId)
        }
        withContext(Dispatchers.Main.immediate) {
            if (playRequestGeneration.get() != requestGeneration) return@withContext
            val player = controller()
            if (playRequestGeneration.get() != requestGeneration) return@withContext
            val live = prepared.first
            pool = prepared.second
            // The live SMART queue can contain generated rows absent from the canonical source;
            // retain descriptors for both so queue/state reconstruction never loses those rows.
            poolById = prepared.third
            queueGeneration++
            beginFreshRecoveryAttempt()
            val startPositionMs = positionMs.coerceAtLeast(0L)
            player.setMediaItems(live.fullQueue!!, live.startIndex, startPositionMs)
            // [tracks] is NowPlaying.queue: already the exact saved traversal order for ON. Keep
            // it as an identity native shuffle traversal instead of randomly permuting it again.
            // The trusted service owns ExoPlayer's ShuffleOrder, which MediaController cannot set.
            if (mode == ShuffleMode.ON) {
                val identityOrderInstalled = runCatching {
                    materializeRestoredShuffleOrder(player)
                }.getOrDefault(false)
                val restoredPolicy = restoredOnShufflePolicy(identityOrderInstalled)
                when (restoredPolicy.order) {
                    RestoredOnShuffleOrder.SAVED_IDENTITY -> Unit
                    RestoredOnShuffleOrder.NATIVE_RANDOM_FALLBACK -> {
                        // Service/controller version skew or a transient command failure must not
                        // abort launch after queue state was already committed. Native random
                        // shuffle loses exact saved Next, but keeps transport/logical mode ON.
                        println(
                            "Playback: restored ON identity order unavailable; using native shuffle",
                        )
                    }
                }
                player.shuffleModeEnabled = restoredPolicy.nativeShuffleEnabled
            } else {
                player.shuffleModeEnabled = false
            }
            // Paused is the whole point: the session reappears, nothing sounds. pause() before
            // prepare() pins playWhenReady false no matter what state the controller came back in.
            player.pause()
            player.prepare()
            val refillAfterPendingInvalidation = mode == ShuffleMode.SMART &&
                smartFutureInvalidationPending
            if (refillAfterPendingInvalidation) {
                removeUnplayedSmartFuture(player)
                smartFutureInvalidationPending = false
            }
            rebuildQueueSnapshot()
            pushState()
            // Preserve every saved row, but repair a legitimately short SMART queue regardless of
            // whether setSmartLibrary happened just before or just after this restore coroutine.
            // A full saved lookahead exits without invoking the chooser/model.
            if (mode == ShuffleMode.SMART) mainScope.launch { appendSmartNextIfNeeded() }
        }
    }

    /** Applies a mode chosen by either the app UI or the notification. Main-thread only. */
    private suspend fun applyShuffleMode(nextMode: ShuffleMode) {
        val currentPlayer = controller
        if (
            mode == nextMode &&
            (
                currentPlayer == null ||
                    nextMode == ShuffleMode.SMART ||
                    currentPlayer.shuffleModeEnabled == (nextMode == ShuffleMode.ON)
                )
        ) {
            pushState()
            return
        }
        val previousMode = mode
        beginFreshRecoveryAttempt()
        queueGeneration++
        mode = nextMode
        var topUpSmartQueue = false
        controller?.let { player ->
            val sourceOrder = sourceOrderForShuffleTransition(
                previousMode = previousMode,
                requestedMode = mode,
                source = pool,
                current = player.currentMediaItem?.mediaId?.let(::trackById),
            )
            when (mode) {
                ShuffleMode.OFF -> if (sourceOrder == null) {
                    player.shuffleModeEnabled = false
                } else {
                    // SMART removed the source tail and replaced it with recommendations. Disabling
                    // ExoPlayer shuffle alone would leave that SMART queue playing under an OFF
                    // indicator. Keep the native current item and splice source rows around it;
                    // replacing the whole playlist + prepare() reloads audio and caused a gap.
                    val current = sourceOrder.tracks[sourceOrder.currentIndex]
                    poolById = poolById + (current.id.value to current)
                    player.shuffleModeEnabled = false
                    if (!replaceQueueAroundCurrent(player, sourceOrder)) {
                        // Defensive mismatch fallback (for an externally replaced media session).
                        // Normal in-app transitions always hit the splice path above and remain
                        // gapless; correctness wins if a foreign controller changed current first.
                        val positionMs = player.currentPosition.coerceAtLeast(0L)
                        val playWhenReady = player.playWhenReady
                        player.setMediaItems(
                            sourceOrder.tracks.map { it.toMediaItem() },
                            sourceOrder.currentIndex,
                            positionMs,
                        )
                        player.prepare()
                        if (playWhenReady) player.play()
                    }
                }
                ShuffleMode.ON -> player.shuffleModeEnabled = true
                ShuffleMode.SMART -> {
                    // Snapshot BEFORE disabling shuffle: once false, Media3 exposes physical source
                    // order and the actual played history can no longer be recovered.
                    rebuildQueueSnapshot()
                    val retainedHistory = traversalHistoryThroughCurrent(
                        rows = cachedQueue,
                        currentRowIndex = cachedQueueMediaIndices.indexOf(
                            player.currentMediaItemIndex,
                        ),
                    ).ifEmpty {
                        player.currentMediaItem?.mediaId?.let(::trackById)?.let(::listOf).orEmpty()
                    }
                    player.shuffleModeEnabled = false
                    // Reinstall actual traversal history as a linear SMART queue without removing
                    // the already-decoding current item. Physical truncation is wrong under
                    // shuffle because it preserves source predecessors, not played traversal.
                    if (retainedHistory.isNotEmpty()) {
                        val smartOrder = PlaybackQueueOrder(
                            tracks = retainedHistory,
                            currentIndex = retainedHistory.lastIndex,
                        )
                        if (!replaceQueueAroundCurrent(
                            player = player,
                            order = smartOrder,
                        )) {
                            val positionMs = player.currentPosition.coerceAtLeast(0L)
                            val playWhenReady = player.playWhenReady
                            player.setMediaItems(
                                retainedHistory.map { it.toMediaItem() },
                                retainedHistory.lastIndex,
                                positionMs,
                            )
                            player.prepare()
                            if (playWhenReady) player.play()
                        }
                    }
                    // This queue was rebuilt from history only; every new row uses current policy.
                    smartFutureInvalidationPending = false
                    topUpSmartQueue = true
                }
            }
            rebuildQueueSnapshot()
        }
        pushState()
        // Queue planning can load local models. It is not part of the transport-mode transaction:
        // publish the mode/current item immediately and fill the future asynchronously.
        if (topUpSmartQueue) mainScope.launch { appendSmartNextIfNeeded() }
    }

    /**
     * Main-thread only. Tops SMART back up to [smartLookahead] tracks ahead of the playhead.
     *
     * Serialised: the decision below and the append that follows it must be one atomic step, or
     * concurrent callers duplicate each other's work. See [appendMutex].
     */
    private suspend fun appendSmartNextIfNeeded() = appendMutex.withLock { appendSmartNext() }

    /** Main-thread only. Keeps physical SMART history/current and removes every later row. */
    private fun removeUnplayedSmartFuture(player: Player) {
        if (player.mediaItemCount == 0) return
        val keepThrough = player.currentMediaItemIndex
            .takeIf { it in 0 until player.mediaItemCount }
            ?: 0
        for (index in player.mediaItemCount - 1 downTo keepThrough + 1) {
            player.removeMediaItem(index)
        }
    }

    /**
     * Replaces every non-current Media3 row while retaining the native current MediaItem itself.
     * Media3 documents playlist edits around the current item as continuous; no seek or prepare is
     * issued here, so decoder state, position, playWhenReady and repeat survive the mode switch.
     */
    private fun replaceQueueAroundCurrent(player: Player, order: PlaybackQueueOrder): Boolean {
        val splice = playbackQueueSplice(order) ?: return false
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return false
        if (player.getMediaItemAt(currentIndex).mediaId != splice.current.id.value) return false

        // Replace ranges on either side of current, never the active row itself. Media3 applies
        // each range atomically and keeps its active Period/decoder. Suffix first leaves the
        // current physical index stable while the prefix replacement is calculated/applied.
        player.replaceMediaItems(
            currentIndex + 1,
            player.mediaItemCount,
            splice.afterCurrent.map { it.toMediaItem() },
        )
        player.replaceMediaItems(
            0,
            currentIndex,
            splice.beforeCurrent.map { it.toMediaItem() },
        )
        return true
    }

    private suspend fun appendSmartNext() {
        val player = controller ?: return
        if (mode != ShuffleMode.SMART) return
        if (player.mediaItemCount == 0) return

        var appended = false
        // Each pass appends exactly one item, so the shortfall shrinks by one and the loop needs at
        // most [smartLookahead] passes. The counter also caps it if a player ever fails to reflect an
        // append immediately, which would otherwise spin.
        var guard = smartLookahead
        while (
            guard-- > 0 &&
            smartQueueNeedsTopUp(
                queueSize = player.mediaItemCount,
                currentIndex = player.currentMediaItemIndex,
                lookahead = smartLookahead,
            )
        ) {
            // The walk continues from the END of the queue, not from what is playing. The tail is
            // the last thing the chain decided, so seeding with it is what lets the chooser
            // recognise its own plan and serve the next hop from it. Passing the playing track for
            // every append would instead look like a playhead that had jumped somewhere
            // unpredicted, and the chooser would throw the plan away and replan from the same seed
            // on each pass — the walk's spacing and drift rules reset every hop, and the engine
            // would be asked to plan N chains to fill N slots.
            val tailIndex = player.mediaItemCount - 1
            val tailId = player.getMediaItemAt(tailIndex).mediaId
            val seed = trackById(tailId) ?: break
            val expectedGeneration = queueGeneration
            val expectedItemCount = player.mediaItemCount
            val recentIds = (maxOf(0, tailIndex - RECENT_WINDOW) until tailIndex)
                .map { index -> TrackId(player.getMediaItemAt(index).mediaId) }
            // Everything ALREADY in the queue is off the table, not just the recent window: a track
            // appended twice would play twice in one sitting, and the queue list would hold two rows
            // claiming the same identity.
            val queued = (0 until player.mediaItemCount)
                .mapTo(HashSet()) { index -> TrackId(player.getMediaItemAt(index).mediaId) }
            val candidates = smartCandidatePool(
                eligibleLibrary = smartLibrary,
                fallbackPool = pool,
                eligibleLibrarySupplied = smartLibrarySupplied,
            ).filter { it.id !in queued }
            if (candidates.isEmpty()) break

            val chosen = runCatching { chooser.choose(seed, recentIds, candidates) }
                .getOrNull()
                // SMART must never present a random row as a recommendation. If neither the
                // acoustic nor metadata path can answer yet, keep the honest short queue and let
                // the next index update / transition retry.
                ?: break

            // The chooser suspends for local inference. A new play request, mode change, manual
            // queue edit, or library replacement may have happened while it was working. Never add
            // a recommendation planned for that obsolete queue to the one now on screen.
            if (
                controller !== player ||
                mode != ShuffleMode.SMART ||
                queueGeneration != expectedGeneration ||
                player.mediaItemCount != expectedItemCount ||
                player.getMediaItemAt(player.mediaItemCount - 1).mediaId != tailId
            ) {
                break
            }
            if ((0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == chosen.id.value }) {
                break
            }
            player.addMediaItem(chosen.toMediaItem())
            queueGeneration++
            appended = true
        }
        if (appended) {
            rebuildQueueSnapshot()
            pushState()
        }
    }

    /** Main-thread only. */
    private fun rebuildQueueSnapshot() {
        val player = controller ?: return
        val physicalRows = (0 until player.mediaItemCount).map { itemIndex ->
            trackById(player.getMediaItemAt(itemIndex).mediaId)
        }
        val traversalOrder = playerTraversalOrder(player)
        val snapshot = playbackQueueSnapshot(
            physicalRows = physicalRows,
            traversalOrder = traversalOrder,
            currentMediaItemIndex = player.currentMediaItemIndex,
        )
        cachedQueue = snapshot.rows
        cachedQueueMediaIndices = snapshot.mediaItemIndices
    }

    /** Main-thread-only physical indices in the order the current transport traverses them. */
    private fun playerTraversalOrder(player: Player): IntArray {
        val timeline = player.currentTimeline
        return if (
            player.shuffleModeEnabled &&
            timeline.windowCount == player.mediaItemCount
        ) {
            boundedQueueOrder(
                queueSize = player.mediaItemCount,
                firstIndex = timeline.getFirstWindowIndex(/* shuffleModeEnabled = */ true),
            ) { index ->
                timeline.getNextWindowIndex(
                    index,
                    Player.REPEAT_MODE_OFF,
                    /* shuffleModeEnabled = */ true,
                )
            }
        } else {
            IntArray(player.mediaItemCount) { it }
        }
    }

    private fun beginFreshRecoveryAttempt() {
        failedRecoveryIds.clear()
    }

    private fun trackById(id: String): TrackDescriptor? = poolById[id] ?: smartById[id]

    /** Coarse position refresh while playing; idle otherwise. */
    private fun updateTicker(isPlaying: Boolean) {
        if (isPlaying) {
            if (tickerJob == null) {
                tickerJob = mainScope.launch {
                    while (isActive) {
                        pushState()
                        delay(TICKER_INTERVAL_MS)
                    }
                }
            }
        } else {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    private fun pushState() {
        val player = controller
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        val track = player?.currentMediaItem?.mediaId?.let(::trackById)
        mutableState.value = NowPlaying(
            track = track,
            isPlaying = player?.isPlaying == true,
            shuffleMode = mode,
            repeatMode = repeat,
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = duration ?: track?.durationMs ?: 0,
            queue = cachedQueue,
            queueIndex = player?.currentMediaItemIndex
                ?.let(cachedQueueMediaIndices::indexOf)
                ?.takeIf { it >= 0 }
                ?: -1,
            sourceQueue = pool,
        )
    }

    /**
     * Android 13+ derives a media player's System UI palette from its session artwork. Real cover
     * art already flows through [MediaMetadata.artworkUri]. For a coverless track we therefore
     * publish a tiny generated cover whose dominant colour is the exact latent/identity colour
     * used inside LatentJam. This stays local and avoids custom-notification behaviour that newer
     * Android versions intentionally ignore.
     */
    private suspend fun decorateCoverlessTrack(candidate: MediaItem?) {
        val mediaItem = candidate ?: return
        val id = mediaItem.mediaId
        val track = trackById(id) ?: return
        if (
            mediaItem.mediaMetadata.artworkData != null ||
            !fallbackArtworkInFlight.add(id)
        ) {
            return
        }

        try {
            // MediaStore commonly supplies a content URI even for albums with no embedded art.
            // Treat it as a cover only if it can actually be opened; Media3 otherwise publishes a
            // null large icon and System UI falls back to a generic grey card.
            val claimedArtworkUri = mediaItem.mediaMetadata.artworkUri?.toString()
                ?: track.artworkUri
            if (claimedArtworkUri != null && hasReadableArtwork(claimedArtworkUri)) return

            // Publish a deterministic identity colour immediately. The engine serialises reads
            // behind an indexing batch, so waiting for an embedding here could leave a first-time
            // user's notification grey for minutes. This mirrors the app UI: identity now, latent
            // colour as soon as the vector becomes available.
            val immediateArtwork = fallbackArtworkCache[id]
                ?: withContext(Dispatchers.Default) {
                    renderFallbackArtwork(identityTrackColorSeed(id).toArgb())
                }.also { fallbackArtworkCache[id] = it }
            if (!publishFallbackArtwork(id, immediateArtwork)) return
            if (id in latentArtworkCachedIds) return

            val embedding = runCatching { engine.embedding(track.id) }.getOrNull() ?: return
            val latentArtwork = withContext(Dispatchers.Default) {
                renderFallbackArtwork(latentTrackColorSeed(embedding).toArgb())
            }
            fallbackArtworkCache[id] = latentArtwork
            latentArtworkCachedIds += id
            publishFallbackArtwork(id, latentArtwork)
        } finally {
            fallbackArtworkInFlight.remove(id)
        }
    }

    /** Re-checks the playhead after every suspension before replacing metadata in place. */
    private fun publishFallbackArtwork(id: String, artwork: ByteArray): Boolean {
        val player = controller ?: return false
        val current = player.currentMediaItem ?: return false
        if (current.mediaId != id) return false
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return false
        val metadata = current.mediaMetadata.buildUpon()
            .setArtworkUri(null)
            .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(metadata).build())
        return true
    }

    private suspend fun hasReadableArtwork(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                input != null && input.read() != -1
            }
        }.getOrDefault(false)
    }

    /** Connects to [PlaybackService] on first use; cached afterwards. */
    private suspend fun controller(): MediaController = controllerMutex.withLock {
        controller ?: buildController().also { built ->
            controller = built
            repeat = built.repeatMode.toLatentJamRepeatMode()
            if (
                AndroidShuffleModeRegistry.mode.value == ShuffleMode.OFF &&
                built.shuffleModeEnabled
            ) {
                AndroidShuffleModeRegistry.set(ShuffleMode.ON)
            }
            mode = AndroidShuffleModeRegistry.mode.value
            built.addListener(playerListener)
            built.shuffleModeEnabled = mode == ShuffleMode.ON
        }
    }

    private suspend fun buildController(): MediaController = suspendCancellableCoroutine { continuation ->
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val mainExecutor = java.util.concurrent.Executor { runnable ->
            Handler(Looper.getMainLooper()).post(runnable)
        }
        future.addListener({
            try {
                continuation.resume(future.get())
            } catch (t: Throwable) {
                continuation.resumeWithException(t)
            }
        }, mainExecutor)
        continuation.invokeOnCancellation { future.cancel(true) }
    }

    /** Delegates the one shuffle edit a MediaController cannot express to the owning service. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private suspend fun sendShufflePlayNext(
        player: MediaController,
        item: MediaItem,
    ): Boolean {
        if (!player.isSessionCommandAvailable(InsertShufflePlayNextCommand)) return false
        val args = Bundle().apply {
            putBundle(
                PlayNextMediaItemBundleKey,
                item.toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION),
            )
        }
        return awaitSessionCommandResult(player.sendCustomCommand(InsertShufflePlayNextCommand, args))
    }

    /** Installs identity ShuffleOrder for a physically materialized saved ON traversal. */
    private suspend fun materializeRestoredShuffleOrder(player: MediaController): Boolean {
        if (!player.isSessionCommandAvailable(MaterializeRestoredShuffleOrderCommand)) return false
        return awaitSessionCommandResult(
            player.sendCustomCommand(MaterializeRestoredShuffleOrderCommand, Bundle.EMPTY),
        )
    }

    private suspend fun awaitSessionCommandResult(
        future: com.google.common.util.concurrent.ListenableFuture<SessionResult>,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val mainExecutor = java.util.concurrent.Executor { runnable ->
            Handler(Looper.getMainLooper()).post(runnable)
        }
        future.addListener({
            if (!continuation.isActive) return@addListener
            val succeeded = runCatching {
                future.get().resultCode == SessionResult.RESULT_SUCCESS
            }.getOrDefault(false)
            continuation.resume(succeeded)
        }, mainExecutor)
        continuation.invokeOnCancellation { future.cancel(true) }
    }

    private fun TrackDescriptor.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.value)
        .setUri(audioUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .build(),
        )
        .build()

    private fun preparePlayback(
        tracks: List<TrackDescriptor>,
        startIndex: Int,
        includeFullQueue: Boolean,
    ): PreparedPlayback {
        val stableTracks = tracks.toList()
        val safeStartIndex = startIndex.coerceIn(stableTracks.indices)
        val fullQueue = if (includeFullQueue) stableTracks.map { it.toMediaItem() } else null
        return PreparedPlayback(
            tracks = stableTracks,
            byId = stableTracks.associateBy { it.id.value },
            startIndex = safeStartIndex,
            selectedItem = fullQueue?.get(safeStartIndex) ?: stableTracks[safeStartIndex].toMediaItem(),
            fullQueue = fullQueue,
        )
    }

    private data class PreparedPlayback(
        val tracks: List<TrackDescriptor>,
        val byId: Map<String, TrackDescriptor>,
        val startIndex: Int,
        val selectedItem: MediaItem,
        val fullQueue: List<MediaItem>?,
    )

    private fun Int.toLatentJamRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }

    /** Renders a local cover with a dominant seed colour and a quiet waveform mark. */
    private fun renderFallbackArtwork(seedArgb: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(FALLBACK_ARTWORK_SIZE, FALLBACK_ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    FALLBACK_ARTWORK_SIZE.toFloat(),
                    FALLBACK_ARTWORK_SIZE.toFloat(),
                    blendArgb(seedArgb, Color.WHITE, 0.16f),
                    blendArgb(seedArgb, Color.BLACK, 0.34f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), background)

            val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (relativeLuminance(seedArgb) > 0.48f) {
                    Color.argb(190, 0, 0, 0)
                } else {
                    Color.argb(210, 255, 255, 255)
                }
                strokeCap = Paint.Cap.ROUND
                strokeWidth = FALLBACK_ARTWORK_SIZE * 0.055f
            }
            val center = FALLBACK_ARTWORK_SIZE / 2f
            val spacing = FALLBACK_ARTWORK_SIZE * 0.13f
            val heights = floatArrayOf(0.20f, 0.40f, 0.62f, 0.40f, 0.20f)
            heights.forEachIndexed { index, height ->
                val x = center + (index - 2) * spacing
                val half = FALLBACK_ARTWORK_SIZE * height / 2f
                canvas.drawLine(x, center - half, x, center + half, mark)
            }

            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun blendArgb(from: Int, to: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        fun channel(start: Int, end: Int): Int = (start + (end - start) * amount).toInt()
        return Color.argb(
            channel(Color.alpha(from), Color.alpha(to)),
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)),
        )
    }

    private fun relativeLuminance(argb: Int): Float {
        fun linear(channel: Int): Float {
            val value = channel / 255f
            return if (value <= 0.04045f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * linear(Color.red(argb)) +
            0.7152f * linear(Color.green(argb)) +
            0.0722f * linear(Color.blue(argb))
    }

    private companion object {
        const val DEFAULT_SMART_LOOKAHEAD = 20
        const val MAX_SMART_LOOKAHEAD = 100

        /** How many queue entries before the seed are passed to the chooser as context. */
        const val RECENT_WINDOW = 10

        /** Seek-bar refresh cadence while playing. */
        const val TICKER_INTERVAL_MS = 500L

        const val FALLBACK_ARTWORK_SIZE = 256
        const val MAX_FALLBACK_ARTWORK_CACHE = 64
    }
}

public actual fun playbackModule(): Module = module {
    single<PlaybackController> {
        AndroidPlaybackController(context = get(), chooser = get(), engine = get())
    }
}

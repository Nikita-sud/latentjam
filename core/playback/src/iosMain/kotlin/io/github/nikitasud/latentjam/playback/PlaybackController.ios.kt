/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextMoveToPoint
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGContextSetLineCap
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetStrokeColorWithColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPChangeRepeatModeCommandEvent
import platform.MediaPlayer.MPChangeShuffleModeCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPMediaItemCollection
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaQuery
import platform.MediaPlayer.MPMusicPlaybackState
import platform.MediaPlayer.MPMusicPlayerController
import platform.MediaPlayer.MPMusicPlayerControllerPlaybackStateDidChangeNotification
import platform.MediaPlayer.MPMusicRepeatMode
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.MediaPlayer.MPRepeatType
import platform.MediaPlayer.MPShuffleType
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import kotlin.math.pow

/**
 * iOS [PlaybackController] over an app-owned [IosAudioEngine] for imported files and Apple's
 * [MPMusicPlayerController] for protected Music-library items.
 *
 * The queue is owned here rather than by an AVQueuePlayer: [NowPlaying.queue] is the whole queue,
 * including played entries, and [playAt] may jump anywhere in it. The player node is rescheduled
 * for the selected file while this controller preserves queue/history semantics.
 *
 * ### SMART queue strategy
 * Deliberately identical to the Android controller: hold the configured number of
 * tracks beyond the playhead, and top up by seeding the chooser from the
 * QUEUE TAIL rather than the playing track. Seeding from the playing track
 * would look to the chooser like a playhead that had jumped somewhere it had
 * not planned, so it would discard its plan and replan on every single append.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPlaybackController(
    private val chooser: NextTrackChooser,
    private val audioEngine: IosAudioEngine,
    private val engine: SimilarityEngine,
) : PlaybackController {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = mutableState.asStateFlow()

    /** Plays Music.app library items, including protected on-device downloads. */
    private val mediaPlayer = MPMusicPlayerController.applicationMusicPlayer

    private var activeBackend: PlaybackBackend = PlaybackBackend.FILE

    /** True only after this controller asked the MediaPlayer backend to start. */
    private var mediaItemStarted: Boolean = false

    /**
     * Identifies the native item currently owned by the controller.
     *
     * Both native backends can report completion after a replacement has already been queued.
     * Advancing that replacement would skip a real track, so every completion captures this
     * generation and revalidates it immediately before changing the queue.
     */
    private var playbackItemGeneration: Long = 0L

    private val mediaItemsById = mutableMapOf<String, platform.MediaPlayer.MPMediaItem>()
    private var mediaLibraryModifiedAt: Double? = null

    /** Immutable snapshots: the UI holds these across ticker emissions. */
    private var queue: List<TrackDescriptor> = emptyList()
    private var queueIndex: Int = -1

    /** The source list for OFF/ON and the complete library for SMART are intentionally separate. */
    private var pool: List<TrackDescriptor> = emptyList()
    private var smartLibrary: List<TrackDescriptor> = emptyList()
    private var smartLibrarySupplied: Boolean = false
    private var smartLookahead: Int = DEFAULT_SMART_LOOKAHEAD

    private var mode: ShuffleMode = ShuffleMode.OFF
    private var repeat: RepeatMode = RepeatMode.OFF
    private var playing: Boolean = false

    /**
     * True while the system silenced us mid-play (a call, Siri, another app).
     * Gates the auto-resume so we only pick playback back up if we did not stop
     * on purpose — matching Media3's `handleAudioFocus = true` on Android.
     */
    private var pausedByInterruption: Boolean = false

    /** The playback category and activation are both lazy: browsing must not interrupt other audio. */
    private var audioSessionConfigured: Boolean = false
    private var audioSessionActive: Boolean = false

    /**
     * Uptime of the last play/pause the *system* performed for us.
     *
     * One press of an AirPod arrives twice: as an audio-session interruption and
     * as a remote toggle. Absolute commands (play, pause) are idempotent and
     * survive that, but a bare toggle would undo whatever the interruption just
     * did — pressing pause would keep playing, pressing play would stay paused.
     * The toggle therefore ignores anything that lands in the same instant.
     */
    private var lastSystemTransportChange: Double = 0.0

    /** See the Android controller: read-then-append must be one atomic step. */
    private val appendMutex = Mutex()

    /** Invalidates a SMART result whenever the queue or its candidate universe is replaced. */
    private var queueGeneration: Long = 0L

    /** See Android: policy may change while launch restore has not installed its queue yet. */
    private var smartFutureInvalidationPending: Boolean = false

    private var tickerJob: Job? = null

    /** Last values pushed to the lock screen, to keep the ticker off that path. */
    private var lastInfoTrackId: String? = null
    private var lastInfoPlaying: Boolean? = null

    /** Bounded to tracks that have actually reached the playhead. */
    private val realArtworkIds = mutableSetOf<String>()
    private val latentArtworkIds = mutableSetOf<String>()
    private val fallbackArtworkInFlight = mutableSetOf<String>()
    private val artworkCache = mutableMapOf<String, UIImage>()
    private val artworkOrder = ArrayDeque<String>()

    init {
        // LatentJam implements repeat itself so it can apply the same queue semantics to imported
        // files and protected Music-library items. `Default` would inherit the user's Music.app
        // preference and can loop a one-item native queue without ever notifying us it ended.
        mediaPlayer.repeatMode = MPMusicRepeatMode.MPMusicRepeatModeNone
        wireAudioSessionObservers()
        wireMediaPlayer()
        wireRemoteCommands()
    }

    override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>): Unit =
        withContext(Dispatchers.Main) {
            // Eligibility is part of a pending chooser's input even when pruning leaves the visible
            // queue unchanged, so invalidate before publishing the replacement universe.
            queueGeneration++
            smartLibrary = tracks.distinctBy { it.id }
            smartLibrarySupplied = true
            if (mode == ShuffleMode.SMART && queue.isNotEmpty()) {
                queue = retainEligibleSmartTail(
                    queue = queue,
                    currentIndex = queueIndex,
                    eligibleIds = smartLibrary.mapTo(HashSet()) { it.id },
                )
                pushState()
                appendSmartNextIfNeeded()
            }
        }

    override suspend fun setSmartQueueLength(length: Int): Unit = withContext(Dispatchers.Main) {
        val sanitized = length.coerceIn(1, MAX_SMART_LOOKAHEAD)
        if (smartLookahead != sanitized) {
            smartLookahead = sanitized
            queueGeneration++
        }
        appendSmartNextIfNeeded()
    }

    override suspend fun invalidateSmartFuture(): Unit = withContext(Dispatchers.Main) {
        // Reject a suspended chooser result before pruning the old policy's unplayed plan. The
        // current backend remains loaded because history and the current row are left untouched.
        queueGeneration++
        smartFutureInvalidationPending = true
        if (mode != ShuffleMode.SMART || queue.isEmpty()) return@withContext
        queue = smartHistoryThroughCurrent(queue, queueIndex)
        if (queueIndex !in queue.indices) queueIndex = 0
        smartFutureInvalidationPending = false
        pushState()
        appendSmartNextIfNeeded()
    }

    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int): Unit =
        withContext(Dispatchers.Main) {
            if (tracks.isEmpty()) return@withContext
            val previousQueue = queue
            val previousPool = pool
            val previousIndex = queueIndex
            val previousPositionMs = positionMs()
            val wasPlaying = playing
            val start = startIndex.coerceIn(0, tracks.lastIndex)
            pool = tracks

            when (mode) {
                // SMART owns its queue: begin with the tapped track alone and let
                // the chooser build the path forward from it.
                ShuffleMode.SMART -> {
                    queue = listOf(tracks[start])
                    queueIndex = 0
                    // A newly seeded queue has no future planned under an earlier policy.
                    smartFutureInvalidationPending = false
                }
                ShuffleMode.ON -> {
                    val started = tracks[start]
                    queue = listOf(started) + tracks.filter { it.id != started.id }.shuffled()
                    queueIndex = 0
                }
                ShuffleMode.OFF -> {
                    queue = tracks
                    queueIndex = start
                }
            }
            queueGeneration++
            val requestedIndex = queueIndex
            var loaded = loadPlayableFrom(
                startIndex = requestedIndex,
                direction = 1,
                autoPlay = true,
                wrap = false,
            )
            // A failed replacement must not leave the old backend sounding under the new queue.
            // If there was a valid prior selection, reload it and its position instead of claiming
            // an unreadable row from the requested collection.
            if (!loaded && previousIndex in previousQueue.indices) {
                queue = previousQueue
                pool = previousPool
                queueGeneration++
                loaded = restorePreviousPlayback(
                    previousIndex = previousIndex,
                    previousPositionMs = previousPositionMs,
                    wasPlaying = wasPlaying,
                )
            }
            pushState()
            if (loaded) appendSmartNextIfNeeded()
        }

    override suspend fun togglePlayPause(): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty()) return@withContext
        if (playing) {
            pauseActiveBackend()
            playing = false
            deactivateAudioSession()
        } else {
            playing = playActiveBackend()
            if (!playing) deactivateAudioSession()
        }
        updateTicker()
        pushState()
    }

    override suspend fun pause(): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty() || !playing) return@withContext
        pauseActiveBackend()
        playing = false
        deactivateAudioSession()
        updateTicker()
        pushState()
    }

    override suspend fun next(): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty()) return@withContext
        appendSmartNextIfNeeded()
        advance()
    }

    override suspend fun previous(): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty()) return@withContext
        val previousIndex = queueIndex
        val previousPositionMs = positionMs()
        val wasPlaying = playing
        // Player-standard: restart the track unless you are already at its start.
        if (previousPositionMs > RESTART_THRESHOLD_MS || queueIndex <= 0) {
            seekActiveBackend(0L)
            invalidateNowPlayingInfo()
        } else {
            val loaded = loadPlayableFrom(
                startIndex = previousIndex - 1,
                direction = -1,
                autoPlay = wasPlaying,
                wrap = false,
            )
            if (!loaded) {
                restorePreviousPlayback(previousIndex, previousPositionMs, wasPlaying)
            }
        }
        pushState()
    }

    override suspend fun seekTo(positionMs: Long): Unit = withContext(Dispatchers.Main) {
        seekActiveBackend(positionMs)
        // iOS extrapolates the lock-screen position from the playback rate, so a
        // seek it was not told about leaves the lock screen counting from the old
        // position for the rest of the track.
        invalidateNowPlayingInfo()
        pushState()
    }

    override suspend fun playAt(queueIndex: Int): Unit = withContext(Dispatchers.Main) {
        if (queueIndex !in queue.indices) return@withContext
        val previousIndex = this@IosPlaybackController.queueIndex
        val previousPositionMs = positionMs()
        val wasPlaying = playing
        val loaded = loadPlayableFrom(
            startIndex = queueIndex,
            direction = 1,
            autoPlay = true,
            wrap = false,
        )
        if (!loaded && previousIndex in queue.indices) {
            restorePreviousPlayback(previousIndex, previousPositionMs, wasPlaying)
        }
        pushState()
        if (this@IosPlaybackController.queueIndex >= 0) appendSmartNextIfNeeded()
    }

    override suspend fun cycleRepeatMode(): RepeatMode = withContext(Dispatchers.Main) {
        applyRepeatMode(
            when (repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    private fun applyRepeatMode(requested: RepeatMode): RepeatMode {
        if (repeat == requested) {
            syncRemotePlaybackModes()
            return repeat
        }
        repeat = requested
        syncRemotePlaybackModes()
        pushState()
        return repeat
    }

    override suspend fun retainQueue(trackIds: Set<TrackId>): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty()) return@withContext
        val current = queue.getOrNull(queueIndex)
        val previousIndex = queueIndex
        val wasPlaying = playing
        val kept = queue.filter { it.id in trackIds }
        if (kept.size == queue.size) return@withContext
        pool = pool.filter { it.id in trackIds }
        queue = kept
        queueGeneration++
        if (kept.isEmpty()) {
            stopBackendsAndClearCurrent()
            pushState()
            return@withContext
        }
        if (current != null && current.id in trackIds) {
            // The playing track survived; only its position in the queue may have shifted.
            queueIndex = kept.indexOfFirst { it.id == current.id }
        } else {
            // The current entry was deleted: behave like it ended and move to the track that
            // now occupies its slot, keeping whether we were playing.
            val replacementIndex = previousIndex.coerceIn(0, kept.lastIndex)
            val loaded = loadPlayableFrom(
                startIndex = replacementIndex,
                direction = 1,
                autoPlay = wasPlaying,
                wrap = false,
            )
            if (!loaded && replacementIndex > 0) {
                loadPlayableFrom(
                    startIndex = replacementIndex - 1,
                    direction = -1,
                    autoPlay = wasPlaying,
                    wrap = false,
                )
            }
        }
        pushState()
    }

    override suspend fun playNext(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        val insertAt = (queueIndex + 1).coerceIn(0, queue.size)
        queue = queue.toMutableList().apply { add(insertAt, track) }
        pool = sourceQueueAfterManualInsert(
            source = pool,
            currentId = queue.getOrNull(queueIndex)?.id,
            track = track,
            insertion = SourceQueueInsertion.PLAY_NEXT,
        )
        queueGeneration++
        cueIfNothingCurrent()
        pushState()
    }

    override suspend fun addToQueue(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        queue = queue + track
        pool = sourceQueueAfterManualInsert(
            source = pool,
            currentId = queue.getOrNull(queueIndex)?.id,
            track = track,
            insertion = SourceQueueInsertion.APPEND,
        )
        queueGeneration++
        cueIfNothingCurrent()
        pushState()
    }

    override suspend fun moveQueueItem(from: Int, to: Int): Unit = withContext(Dispatchers.Main) {
        if (from !in queue.indices || to !in queue.indices || from == to) return@withContext
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        queueGeneration++
        // The current entry is tracked by index; arithmetic (not id search) keeps the right one
        // anchored even when the same track sits in the queue twice.
        queueIndex = when {
            queueIndex == from -> to
            from < queueIndex && to >= queueIndex -> queueIndex - 1
            from > queueIndex && to <= queueIndex -> queueIndex + 1
            else -> queueIndex
        }
        pool = sourceQueueAfterVisibleReorder(pool, queue, mode)
        pushState()
    }

    override suspend fun removeQueueItem(index: Int): Unit = withContext(Dispatchers.Main) {
        if (index !in queue.indices) return@withContext
        val removed = queue[index]
        val wasCurrent = index == queueIndex
        val wasPlaying = playing
        queue = queue.toMutableList().apply { removeAt(index) }
        queueGeneration++
        if (queue.none { it.id == removed.id }) {
            pool = pool.filter { it.id != removed.id }
        }
        when {
            queue.isEmpty() -> {
                stopBackendsAndClearCurrent()
            }
            wasCurrent -> {
                // Behave like the track ended: the next track now occupies this slot, keeping
                // whether we were playing — the same contract as retainQueue's deletion path.
                val startIndex = index.coerceIn(0, queue.lastIndex)
                val loaded = loadPlayableFrom(
                    startIndex = startIndex,
                    direction = 1,
                    autoPlay = wasPlaying,
                    wrap = false,
                )
                if (!loaded && startIndex > 0) {
                    loadPlayableFrom(
                        startIndex = startIndex - 1,
                        direction = -1,
                        autoPlay = wasPlaying,
                        wrap = false,
                    )
                }
            }
            index < queueIndex -> queueIndex--
        }
        pushState()
    }

    /**
     * Makes the first queued track current when the queue was empty.
     *
     * Without this, queueing onto an empty queue leaves [queueIndex] at -1 while
     * the queue holds a track — a state [NowPlaying] has no way to describe, and
     * one the transport would render as "nothing playing" over a full queue. The
     * track is cued, not started: queueing is not a request to play.
     */
    private fun cueIfNothingCurrent() {
        if (queueIndex < 0 && queue.isNotEmpty()) {
            loadPlayableFrom(startIndex = 0, direction = 1, autoPlay = false, wrap = false)
        }
    }

    override suspend fun cycleShuffleMode(): ShuffleMode = withContext(Dispatchers.Main) {
        applyShuffleMode(
            when (mode) {
                ShuffleMode.OFF -> ShuffleMode.ON
                ShuffleMode.ON -> ShuffleMode.SMART
                ShuffleMode.SMART -> ShuffleMode.OFF
            },
        )
    }

    override suspend fun setShuffleMode(mode: ShuffleMode): Unit = withContext(Dispatchers.Main) {
        applyShuffleMode(mode)
    }

    override suspend fun restoreQueue(
        tracks: List<TrackDescriptor>,
        startIndex: Int,
        positionMs: Long,
        sourceTracks: List<TrackDescriptor>?,
    ): Unit = withContext(Dispatchers.Main) {
        if (tracks.isEmpty()) return@withContext
        val restorePlan = playbackResumePlan(tracks, startIndex, sourceTracks)
        // The live queue and canonical source are independent on resume: SMART's saved future is
        // restored exactly, while leaving SMART can still reconstruct the originating playlist.
        pool = restorePlan.sourceQueue
        queue = restorePlan.liveQueue
        queueIndex = restorePlan.currentIndex
        queueGeneration++
        val refillAfterPendingInvalidation = mode == ShuffleMode.SMART &&
            smartFutureInvalidationPending
        if (refillAfterPendingInvalidation) {
            queue = smartHistoryThroughCurrent(queue, queueIndex)
            smartFutureInvalidationPending = false
            // Rebuild before opening audio so an unreadable saved current row may recover into the
            // newly planned tail just like an ordinary resume recovers into its saved future.
            appendSmartNextIfNeeded()
        }
        // Paused is the whole point: the session reappears, nothing sounds until asked.
        val requestedIndex = queueIndex
        val loaded = loadPlayableFrom(
            startIndex = requestedIndex,
            direction = 1,
            autoPlay = false,
            wrap = false,
        )
        // A resume position belongs only to the saved row, never to a later row selected because
        // the saved file disappeared between launches.
        if (loaded && queueIndex == requestedIndex && positionMs > 0) {
            seekActiveBackend(positionMs)
            invalidateNowPlayingInfo()
        }
        pushState()
        if (mode == ShuffleMode.SMART && !refillAfterPendingInvalidation) {
            // Close the same setSmartLibrary/restore ordering race as Android. Full saved queues
            // are untouched; only a short tail asks the current chooser to top up.
            mainScope.launch { appendSmartNextIfNeeded() }
        }
    }

    private suspend fun applyShuffleMode(requested: ShuffleMode): ShuffleMode {
        if (mode == requested) {
            syncRemotePlaybackModes()
            return mode
        }
        // Invalidate an inference started under the previous mode before it can publish.
        queueGeneration++
        mode = requested
        val current = queue.getOrNull(queueIndex)
        var topUpSmartQueue = false
        when (mode) {
            ShuffleMode.OFF -> if (current != null) {
                // Back to source order without changing the logical current. A manual/SMART item
                // may not exist in the source; keep it at the playhead instead of coercing the
                // missing lookup to source index zero while its audio continues.
                val ordered = sourceOrderKeepingCurrent(source = pool, current = current)
                queue = ordered.tracks
                queueIndex = ordered.currentIndex
            }
            ShuffleMode.ON -> if (current != null && pool.isNotEmpty()) {
                queue = listOf(current) + (pool.filter { it.id != current.id }).shuffled()
                queueIndex = 0
            }
            ShuffleMode.SMART -> {
                // Drop the pre-planned tail; the chooser decides the path now.
                if (queueIndex >= 0) queue = queue.take(queueIndex + 1)
                smartFutureInvalidationPending = false
                topUpSmartQueue = true
            }
        }
        syncRemotePlaybackModes()
        pushState()
        // iOS already retains its loaded native item for every queue transformation. Keep model
        // planning out of the mode-button path as well so the UI/remote state changes immediately.
        if (topUpSmartQueue) mainScope.launch { appendSmartNextIfNeeded() }
        return mode
    }

    /** Main-thread only. Serialised — see [appendMutex]. */
    private suspend fun appendSmartNextIfNeeded() = appendMutex.withLock { appendSmartNext() }

    private suspend fun appendSmartNext() {
        if (mode != ShuffleMode.SMART) return
        if (queue.isEmpty()) return

        var appended = false
        // Each pass appends exactly one item, so the shortfall shrinks by one and at
        // most [smartLookahead] passes are ever needed; the counter caps it regardless.
        var guard = smartLookahead
        while (
            guard-- > 0 &&
            smartQueueNeedsTopUp(queue.size, queueIndex, smartLookahead)
        ) {
            val tailIndex = queue.lastIndex
            val seed = queue[tailIndex]
            val commitGuard = SmartAppendGuard(
                queueGeneration = queueGeneration,
                queueSize = queue.size,
                tailId = seed.id,
            )
            val recentIds = (maxOf(0, tailIndex - RECENT_WINDOW) until tailIndex)
                .map { queue[it].id }
            // Everything already queued is off the table, not just the recent window:
            // a track appended twice would play twice in one sitting, and the queue
            // would show two rows claiming the same identity.
            val queued = queue.mapTo(HashSet()) { it.id }
            val candidates = smartCandidatePool(
                eligibleLibrary = smartLibrary,
                fallbackPool = pool,
                eligibleLibrarySupplied = smartLibrarySupplied,
            ).filter { it.id !in queued }
            if (candidates.isEmpty()) break

            val chosen = runCatching { chooser.choose(seed, recentIds, candidates) }
                .getOrNull()
                // SMART must never present a random row as a recommendation. If neither local
                // model path can answer yet, leave the queue short and retry later.
                ?: break
            // Inference suspends. Another coroutine may have replaced the queue, switched modes,
            // manually edited its tail, or refreshed eligibility while the chooser was working.
            if (
                !canCommitSmartAppend(
                    mode = mode,
                    queueGeneration = queueGeneration,
                    queue = queue,
                    guard = commitGuard,
                    chosenId = chosen.id,
                )
            ) {
                break
            }
            queue = queue + chosen
            queueGeneration++
            appended = true
        }
        if (appended) pushState()
    }

    /**
     * Moves to the next queue entry, honouring [repeat] at the end of the queue.
     *
     * Entries that will not open are skipped rather than stalled on: a file the
     * user deleted between the scan and now would otherwise leave the transport
     * showing a track the player never loaded, with the audio stopped.
     */
    private fun advance() {
        val previousIndex = queueIndex
        val previousPositionMs = positionMs()
        val startIndex = queueIndex + 1
        val wrap = repeat == RepeatMode.ALL
        if (playbackQueueTraversal(queue.size, startIndex, direction = 1, wrap = wrap).isEmpty()) {
            pauseActiveBackend()
            playing = false
            deactivateAudioSession()
            updateTicker()
            pushState()
            return
        }
        val loaded = loadPlayableFrom(
            startIndex = startIndex,
            direction = 1,
            autoPlay = true,
            wrap = wrap,
        )
        if (!loaded && previousIndex in queue.indices) {
            // Every candidate was unreadable. Keep the last real row parked rather than publishing
            // whichever dead candidate happened to be tried last.
            restorePreviousPlayback(previousIndex, previousPositionMs, wasPlaying = false)
        }
        pushState()
    }

    /**
     * Points the player at [queueIndex], re-scoping the end-of-item observer to
     * the new item as it goes. Returns false when the entry cannot be opened.
     */
    private fun loadCurrentItem(autoPlay: Boolean): Boolean {
        val track = queue.getOrNull(queueIndex) ?: run {
            stopBackendsAfterLoadFailure()
            return false
        }
        val isMediaLibraryItem = track.id.value.startsWith(MEDIA_ID_PREFIX)
        val loaded = if (isMediaLibraryItem && track.audioUri == null) {
            loadMediaLibraryItem(track, autoPlay)
        } else {
            val url = track.audioUri?.let { NSURL.URLWithString(it) }
            val fileLoaded = url != null && loadFileItem(url, autoPlay)
            fileLoaded || (isMediaLibraryItem && loadMediaLibraryItem(track, autoPlay))
        }
        if (!loaded) stopBackendsAfterLoadFailure()
        return loaded
    }

    /** Loads a file after silencing MediaPlayer; [IosAudioEngine] clears itself on failure. */
    private fun loadFileItem(url: NSURL, autoPlay: Boolean): Boolean {
        val itemGeneration = ++playbackItemGeneration
        mediaItemStarted = false
        // Ignore the stop notification from the backend we are replacing.
        activeBackend = PlaybackBackend.FILE
        mediaPlayer.stop()
        if (autoPlay && !activateAudioSession()) return false
        val loaded = audioEngine.load(url, autoPlay) {
            mainScope.launch { onItemEnded(itemGeneration) }
        }
        if (!loaded) {
            return false
        }
        audioEngine.setOutputSupportsEqualizer(true)
        playing = autoPlay
        if (!autoPlay) deactivateAudioSession()
        updateTicker()
        invalidateNowPlayingInfo()
        return true
    }

    /** Resolves a persistent Music-library ID and cues it in Apple's local player. */
    private fun loadMediaLibraryItem(track: TrackDescriptor, autoPlay: Boolean): Boolean {
        val persistentId = track.id.value.removePrefix(MEDIA_ID_PREFIX)
        val item = resolveMediaItem(persistentId) ?: return false
        if (autoPlay && !activateAudioSession()) return false

        ++playbackItemGeneration
        audioEngine.stop()
        audioEngine.setOutputSupportsEqualizer(false)
        mediaItemStarted = false
        mediaPlayer.stop()
        // A one-item native queue is an implementation detail. Never let Music.app's remembered
        // repeat preference consume its end event before the shared queue controller sees it.
        mediaPlayer.repeatMode = MPMusicRepeatMode.MPMusicRepeatModeNone
        mediaPlayer.setQueueWithItemCollection(MPMediaItemCollection(items = listOf(item)))
        activeBackend = PlaybackBackend.MEDIA_LIBRARY
        playing = false
        if (autoPlay) {
            // The Playing notification, not this request, proves the new native item started.
            // Keeping this false closes the window in which a delayed Stopped notification from
            // the queue we just replaced could be mistaken for completion of the new item.
            mediaPlayer.play()
            playing = true
        } else {
            deactivateAudioSession()
        }
        updateTicker()
        invalidateNowPlayingInfo()
        return true
    }

    /** Tries each candidate once; unreadable rows are skipped without ever being published. */
    private fun loadPlayableFrom(
        startIndex: Int,
        direction: Int,
        autoPlay: Boolean,
        wrap: Boolean,
    ): Boolean {
        for (candidate in playbackQueueTraversal(queue.size, startIndex, direction, wrap)) {
            queueIndex = candidate
            if (loadCurrentItem(autoPlay)) return true
        }
        stopBackendsAndClearCurrent()
        return false
    }

    /** Re-cues the prior real row after a requested replacement proved unreadable. */
    private fun restorePreviousPlayback(
        previousIndex: Int,
        previousPositionMs: Long,
        wasPlaying: Boolean,
    ): Boolean {
        val restored = loadPlayableFrom(
            startIndex = previousIndex,
            direction = 1,
            autoPlay = false,
            wrap = false,
        )
        if (!restored) return false
        if (queueIndex == previousIndex && previousPositionMs > 0L) {
            seekActiveBackend(previousPositionMs)
        }
        playing = wasPlaying && playActiveBackend()
        if (!playing) deactivateAudioSession()
        updateTicker()
        invalidateNowPlayingInfo()
        return true
    }

    /** Stops both native players while a bounded scan considers another candidate. */
    private fun stopBackendsAfterLoadFailure() {
        ++playbackItemGeneration
        mediaItemStarted = false
        // Set first so the MediaPlayer stop notification cannot advance the queue being repaired.
        activeBackend = PlaybackBackend.FILE
        audioEngine.stop()
        mediaPlayer.stop()
        audioEngine.setOutputSupportsEqualizer(true)
        playing = false
        deactivateAudioSession()
        updateTicker()
        invalidateNowPlayingInfo()
    }

    private fun stopBackendsAndClearCurrent() {
        stopBackendsAfterLoadFailure()
        queueIndex = -1
    }

    /** Refreshes on a miss or Music-library change, so a removed cached item is never claimed. */
    private fun resolveMediaItem(persistentId: String): platform.MediaPlayer.MPMediaItem? {
        val modifiedAt = MPMediaLibrary.defaultMediaLibrary().lastModifiedDate.timeIntervalSince1970
        if (mediaLibraryModifiedAt != modifiedAt || persistentId !in mediaItemsById) {
            mediaItemsById.clear()
            MPMediaQuery.songsQuery().items.orEmpty()
                .mapNotNull { it as? platform.MediaPlayer.MPMediaItem }
                .forEach { mediaItemsById[it.persistentID.toString()] = it }
            mediaLibraryModifiedAt = modifiedAt
        }
        return mediaItemsById[persistentId]
    }

    private fun onItemEnded(itemGeneration: Long) {
        if (!isCurrentPlaybackItemGeneration(itemGeneration, playbackItemGeneration)) return
        if (repeat == RepeatMode.ONE) {
            seekActiveBackend(0L)
            playing = playActiveBackend()
            if (!playing) deactivateAudioSession()
            pushState()
            return
        }
        mainScope.launch {
            if (!isCurrentPlaybackItemGeneration(itemGeneration, playbackItemGeneration)) {
                return@launch
            }
            // Top up before advancing so SMART always has somewhere to go.
            appendSmartNextIfNeeded()
            if (!isCurrentPlaybackItemGeneration(itemGeneration, playbackItemGeneration)) {
                return@launch
            }
            advance()
        }
    }

    /** Configures and activates only when samples are about to be requested. */
    private fun activateAudioSession(): Boolean {
        if (audioSessionActive) return true
        val session = AVAudioSession.sharedInstance()
        if (!audioSessionConfigured) {
            // .playback is what makes audio survive the lock screen and ignore the ring/silent
            // switch. Delaying this category change also means opening the app to browse never
            // takes audio focus from another player.
            audioSessionConfigured = session.setCategory(AVAudioSessionCategoryPlayback, null)
            if (!audioSessionConfigured) return false
        }
        audioSessionActive = session.setActive(true, null)
        return audioSessionActive
    }

    /** Returns focus after an explicit pause, terminal end, or failed load. */
    private fun deactivateAudioSession() {
        if (!audioSessionActive) return
        val deactivated = AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
        if (deactivated) audioSessionActive = false
    }

    /**
     * Mirrors what Media3 gives Android for free (`handleAudioFocus = true` +
     * `setHandleAudioBecomingNoisy(true)`). The AVAudioEngine graph is otherwise
     * deaf to everything the system does behind our back, so a headphone tap or
     * an unplug would silence the audio while the lock screen kept showing a
     * pause button over dead sound — and no way to resume from there.
     *
     * Both handlers force a lock-screen refresh via [invalidateNowPlayingInfo]
     * so the transport always learns the new rate, even when the play flag was
     * already what the change-guard expected.
     *
     * MediaPlayer-backed items are left alone: MPMusicPlayerController raises its
     * own interruption state, already handled in [wireMediaPlayer].
     */
    private fun wireAudioSessionObservers() {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            AVAudioSessionInterruptionNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { note -> onAudioInterruption(note) }
        center.addObserverForName(
            AVAudioSessionRouteChangeNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { note -> onAudioRouteChange(note) }
    }

    private fun onAudioInterruption(note: NSNotification?) {
        val info = note?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.longValue ?: return
        val began = type == AVAudioSessionInterruptionTypeBegan.toLong()
        val option = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.longValue ?: 0L
        val shouldResume =
            option and AVAudioSessionInterruptionOptionShouldResume.toLong() != 0L
        mainScope.launch {
            if (activeBackend != PlaybackBackend.FILE) return@launch
            if (began) {
                if (playing) {
                    // iOS has already deactivated the session for the interruption.
                    audioSessionActive = false
                    pausedByInterruption = true
                    pauseFromSystem(deactivateSession = false)
                }
            } else {
                if (pausedByInterruption && shouldResume && queue.isNotEmpty()) {
                    playing = playActiveBackend()
                    if (!playing) deactivateAudioSession()
                    lastSystemTransportChange = uptimeSeconds()
                    updateTicker()
                    invalidateNowPlayingInfo()
                    pushState()
                }
                pausedByInterruption = false
            }
        }
    }

    private fun onAudioRouteChange(note: NSNotification?) {
        val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.longValue
            ?: return
        // Only "the thing I was playing to just vanished" — unplugging headphones
        // or pulling an AirPod. Everything else (new device, category change) is
        // none of our business and would pause on innocuous route reshuffles.
        val oldDeviceGone = reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable.toLong()
        if (!oldDeviceGone) return
        mainScope.launch {
            if (activeBackend == PlaybackBackend.FILE && playing) pauseFromSystem()
        }
    }

    /** Reflect a system-initiated stop and make sure the lock screen agrees. */
    private fun pauseFromSystem(deactivateSession: Boolean = true) {
        pauseActiveBackend()
        playing = false
        if (deactivateSession) deactivateAudioSession()
        lastSystemTransportChange = uptimeSeconds()
        updateTicker()
        invalidateNowPlayingInfo()
        pushState()
    }

    /** Monotonic seconds; unaffected by the wall clock moving. */
    private fun uptimeSeconds(): Double = NSProcessInfo.processInfo.systemUptime

    /**
     * True when the system just moved the transport for us, so a remote toggle
     * arriving now is the second delivery of one physical press, not a new one.
     */
    private fun systemJustChangedTransport(): Boolean =
        uptimeSeconds() - lastSystemTransportChange < DUPLICATE_TRANSPORT_WINDOW_S

    /** Bridges natural completion from the MediaPlayer backend into the shared queue. */
    private fun wireMediaPlayer() {
        mediaPlayer.beginGeneratingPlaybackNotifications()
        NSNotificationCenter.defaultCenter.addObserverForName(
            MPMusicPlayerControllerPlaybackStateDidChangeNotification,
            mediaPlayer,
            NSOperationQueue.mainQueue,
        ) { _ ->
            // The observer is already delivered on the main queue. Handling it synchronously is
            // important: deferring into another coroutine lets a user play request replace the
            // queue before an old Stopped event reads these mutable fields.
            if (activeBackend == PlaybackBackend.MEDIA_LIBRARY) {
                when (mediaPlayer.playbackState) {
                    MPMusicPlaybackState.MPMusicPlaybackStatePlaying -> {
                        mediaItemStarted = true
                        playing = true
                        updateTicker()
                        pushState()
                    }
                    MPMusicPlaybackState.MPMusicPlaybackStatePaused,
                    -> {
                        playing = false
                        deactivateAudioSession()
                        updateTicker()
                        pushState()
                    }
                    MPMusicPlaybackState.MPMusicPlaybackStateInterrupted -> {
                        playing = false
                        audioSessionActive = false
                        updateTicker()
                        pushState()
                    }
                    MPMusicPlaybackState.MPMusicPlaybackStateStopped -> if (mediaItemStarted) {
                        val itemGeneration = playbackItemGeneration
                        mediaItemStarted = false
                        onItemEnded(itemGeneration)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun pauseActiveBackend() {
        when (activeBackend) {
            PlaybackBackend.FILE -> audioEngine.pause()
            PlaybackBackend.MEDIA_LIBRARY -> mediaPlayer.pause()
        }
    }

    private fun playActiveBackend(): Boolean {
        if (!activateAudioSession()) return false
        return when (activeBackend) {
            PlaybackBackend.FILE -> audioEngine.play()
            PlaybackBackend.MEDIA_LIBRARY -> {
                // Confirmed by wireMediaPlayer's Playing state. Setting it before `play()` would
                // let a queued stop from the previous item advance this replacement prematurely.
                mediaPlayer.play()
                true
            }
        }
    }

    private fun seekActiveBackend(positionMs: Long) {
        when (activeBackend) {
            PlaybackBackend.FILE -> audioEngine.seekTo(positionMs)
            PlaybackBackend.MEDIA_LIBRARY -> {
                mediaPlayer.currentPlaybackTime = positionMs / 1000.0
            }
        }
    }

    private fun wireRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler { _ ->
            mainScope.launch { if (!playing) togglePlayPause() }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.pauseCommand.addTargetWithHandler { _ ->
            mainScope.launch { if (playing) togglePlayPause() }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.togglePlayPauseCommand.addTargetWithHandler { _ ->
            // A headphone press reaches us twice — once as an interruption, once
            // here. Undoing the interruption's work would make the button dead.
            mainScope.launch { if (!systemJustChangedTransport()) togglePlayPause() }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.nextTrackCommand.addTargetWithHandler { _ ->
            mainScope.launch { next() }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.previousTrackCommand.addTargetWithHandler { _ ->
            mainScope.launch { previous() }
            MPRemoteCommandHandlerStatusSuccess
        }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val scrub = event as? MPChangePlaybackPositionCommandEvent
            if (scrub == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                mainScope.launch { seekTo((scrub.positionTime * 1000).toLong()) }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        center.changeRepeatModeCommand.addTargetWithHandler { event ->
            val change = event as? MPChangeRepeatModeCommandEvent
            val requested = change?.repeatType?.toLatentJamRepeatMode()
            if (requested == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                mainScope.launch { applyRepeatMode(requested) }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        center.changeShuffleModeCommand.addTargetWithHandler { event ->
            val change = event as? MPChangeShuffleModeCommandEvent
            val requested = change?.shuffleType?.toLatentJamShuffleMode()
            if (requested == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                mainScope.launch { applyShuffleMode(requested) }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        center.changeRepeatModeCommand.enabled = true
        center.changeShuffleModeCommand.enabled = true
        syncRemotePlaybackModes()
    }

    /**
     * MPRemoteCommandCenter has only off/items shuffle states. SMART therefore advertises as
     * shuffled, remains SMART while the system leaves shuffle enabled, and turns off when the
     * system asks for off. Selecting shuffle from off starts ordinary random shuffle; SMART still
     * remains an explicit LatentJam choice because iOS has no third standard shuffle value.
     */
    private fun syncRemotePlaybackModes() {
        // MPRemoteCommandCenter describes LatentJam's repeat state, while the one-item native
        // MediaPlayer queue must always remain non-repeating so completion reaches this controller.
        mediaPlayer.repeatMode = MPMusicRepeatMode.MPMusicRepeatModeNone
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.changeRepeatModeCommand.currentRepeatType = repeat.toPlatformRepeatType()
        center.changeShuffleModeCommand.currentShuffleType = mode.toPlatformShuffleType()
    }

    private fun MPRepeatType.toLatentJamRepeatMode(): RepeatMode = when (this) {
        MPRepeatType.MPRepeatTypeOff -> RepeatMode.OFF
        MPRepeatType.MPRepeatTypeAll -> RepeatMode.ALL
        MPRepeatType.MPRepeatTypeOne -> RepeatMode.ONE
    }

    private fun RepeatMode.toPlatformRepeatType(): MPRepeatType = when (this) {
        RepeatMode.OFF -> MPRepeatType.MPRepeatTypeOff
        RepeatMode.ALL -> MPRepeatType.MPRepeatTypeAll
        RepeatMode.ONE -> MPRepeatType.MPRepeatTypeOne
    }

    private fun MPShuffleType.toLatentJamShuffleMode(): ShuffleMode = when (this) {
        MPShuffleType.MPShuffleTypeOff -> ShuffleMode.OFF
        MPShuffleType.MPShuffleTypeItems,
        MPShuffleType.MPShuffleTypeCollections,
        -> if (mode == ShuffleMode.SMART) ShuffleMode.SMART else ShuffleMode.ON
    }

    private fun ShuffleMode.toPlatformShuffleType(): MPShuffleType = when (this) {
        ShuffleMode.OFF -> MPShuffleType.MPShuffleTypeOff
        ShuffleMode.ON,
        ShuffleMode.SMART,
        -> MPShuffleType.MPShuffleTypeItems
    }

    /** Coarse position refresh while playing; idle otherwise. */
    private fun updateTicker() {
        if (playing) {
            if (tickerJob == null) {
                tickerJob = mainScope.launch {
                    while (isActive) {
                        reconcilePlayingState()
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

    /**
     * Believes the player over our own flag.
     *
     * The system pauses playback for things we never asked about — headphones
     * unplugged, a phone call, another app taking the audio session. Without
     * this the transport keeps showing a pause button over silence.
     *
     * Only `Paused` counts: `WaitingToPlayAtSpecifiedRate` is a normal step on
     * the way into playback, and treating it as stopped would flicker the
     * button on every track change.
     */
    private fun reconcilePlayingState() {
        val backendPaused = when (activeBackend) {
            PlaybackBackend.FILE -> !audioEngine.playing
            PlaybackBackend.MEDIA_LIBRARY ->
                mediaPlayer.playbackState == MPMusicPlaybackState.MPMusicPlaybackStatePaused ||
                    mediaPlayer.playbackState == MPMusicPlaybackState.MPMusicPlaybackStateInterrupted
        }
        if (playing && backendPaused) {
            playing = false
            deactivateAudioSession()
            // Same press, same rule as the observers: this is the system moving
            // the transport, so a remote toggle chasing it must not undo it.
            lastSystemTransportChange = uptimeSeconds()
            updateTicker()
            // The ticker stops here, so this is the last chance to tell the lock
            // screen the rate is now zero.
            invalidateNowPlayingInfo()
        }
    }

    /** Forces the next [pushState] to re-publish lock-screen metadata. */
    private fun invalidateNowPlayingInfo() {
        lastInfoTrackId = null
        lastInfoPlaying = null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun positionMs(): Long {
        if (activeBackend == PlaybackBackend.MEDIA_LIBRARY) {
            return (mediaPlayer.currentPlaybackTime * 1000.0).toLong().coerceAtLeast(0L)
        }
        return audioEngine.positionMs()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun durationMs(): Long? {
        if (activeBackend == PlaybackBackend.MEDIA_LIBRARY) {
            return mediaPlayer.nowPlayingItem?.playbackDuration
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.times(1000.0)
                ?.toLong()
        }
        return audioEngine.durationMs()
    }

    private fun pushState() {
        val track = queue.getOrNull(queueIndex)
        mutableState.value = NowPlaying(
            track = track,
            isPlaying = playing,
            shuffleMode = mode,
            repeatMode = repeat,
            positionMs = positionMs(),
            durationMs = durationMs() ?: track?.durationMs ?: 0,
            queue = queue,
            queueIndex = if (queue.isEmpty()) -1 else queueIndex,
            sourceQueue = pool,
        )
        publishNowPlayingInfo(track)
    }

    /**
     * Pushes lock-screen metadata only when the track or play state changes.
     *
     * iOS extrapolates elapsed time from the playback rate, so re-publishing on
     * every ticker emission would be twice-a-second work for no visible gain.
     */
    private fun publishNowPlayingInfo(track: TrackDescriptor?) {
        // MediaPlayer owns its lock-screen payload. Publishing AVPlayer-style metadata on top of
        // it can briefly replace Apple's artwork and duration with stale values during a change.
        if (activeBackend == PlaybackBackend.MEDIA_LIBRARY) return
        val trackId = track?.id?.value
        if (trackId == lastInfoTrackId && playing == lastInfoPlaying) return
        lastInfoTrackId = trackId
        lastInfoPlaying = playing

        if (track == null) {
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            return
        }
        val info = mutableMapOf<Any?, Any?>()
        track.title?.let { info[MPMediaItemPropertyTitle] = it }
        track.artist?.let { info[MPMediaItemPropertyArtist] = it }
        track.album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        nowPlayingArtwork(track)?.let { info[MPMediaItemPropertyArtwork] = it }
        (durationMs() ?: track.durationMs)?.let {
            info[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = it / 1000.0)
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
            NSNumber(double = positionMs() / 1000.0)
        info[MPNowPlayingInfoPropertyPlaybackRate] =
            NSNumber(double = if (playing) 1.0 else 0.0)
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    /** Real embedded art first; otherwise publish the same identity/latent colour idea as Android. */
    private fun nowPlayingArtwork(track: TrackDescriptor): MPMediaItemArtwork? {
        val id = track.id.value
        val image = artworkCache[id]
            ?: loadArtwork(track.artworkUri)?.also { loaded ->
                cacheArtwork(id, loaded, real = true)
            }
            ?: renderFallbackArtwork(identityTrackColorSeed(id).toArgb())?.also { fallback ->
                cacheArtwork(id, fallback)
            }
            ?: return null

        if (id !in realArtworkIds && id !in latentArtworkIds) requestLatentArtwork(track)
        return MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
    }

    /** iOS library artwork cache URIs are local file URLs; never perform network I/O here. */
    private fun loadArtwork(uri: String?): UIImage? {
        val url = uri?.let(NSURL::URLWithString) ?: return null
        if (!url.isFileURL()) return null
        val path = url.path ?: return null
        return UIImage.imageWithContentsOfFile(path)
    }

    /** Upgrades an identity cover when its local fingerprint becomes available. */
    private fun requestLatentArtwork(track: TrackDescriptor) {
        val id = track.id.value
        if (!fallbackArtworkInFlight.add(id)) return
        mainScope.launch {
            try {
                val embedding = runCatching { engine.embedding(track.id) }.getOrNull() ?: return@launch
                val image = renderFallbackArtwork(latentTrackColorSeed(embedding).toArgb())
                    ?: return@launch
                cacheArtwork(id, image, latent = true)
                if (queue.getOrNull(queueIndex)?.id == track.id) {
                    invalidateNowPlayingInfo()
                    pushState()
                }
            } finally {
                fallbackArtworkInFlight.remove(id)
            }
        }
    }

    private fun cacheArtwork(
        id: String,
        image: UIImage,
        real: Boolean = false,
        latent: Boolean = false,
    ) {
        if (id !in artworkCache) {
            if (artworkOrder.size >= MAX_ARTWORK_CACHE) {
                val evicted = artworkOrder.removeFirst()
                artworkCache.remove(evicted)
                realArtworkIds.remove(evicted)
                latentArtworkIds.remove(evicted)
            }
            artworkOrder.addLast(id)
        }
        artworkCache[id] = image
        if (real) realArtworkIds += id
        if (latent) latentArtworkIds += id
    }

    /** A small local cover is enough for System UI to derive a stable player colour. */
    private fun renderFallbackArtwork(argb: Int): UIImage? {
        val size = FALLBACK_ARTWORK_SIZE
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(size, size), true, 0.0)
        try {
            val context = UIGraphicsGetCurrentContext() ?: return null
            val red = ((argb ushr 16) and 0xFF) / 255.0
            val green = ((argb ushr 8) and 0xFF) / 255.0
            val blue = (argb and 0xFF) / 255.0
            val background = UIColor(red = red, green = green, blue = blue, alpha = 1.0)
            CGContextSetFillColorWithColor(context, background.CGColor)
            CGContextFillRect(context, CGRectMake(0.0, 0.0, size, size))

            val mark = if (relativeLuminance(red, green, blue) > 0.48) {
                UIColor.blackColor.colorWithAlphaComponent(0.74)
            } else {
                UIColor.whiteColor.colorWithAlphaComponent(0.82)
            }
            CGContextSetStrokeColorWithColor(context, mark.CGColor)
            CGContextSetLineWidth(context, size * 0.055)
            CGContextSetLineCap(context, CGLineCap.kCGLineCapRound)
            val center = size / 2.0
            val spacing = size * 0.13
            val heights = doubleArrayOf(0.20, 0.40, 0.62, 0.40, 0.20)
            heights.forEachIndexed { index, height ->
                val x = center + (index - 2) * spacing
                val half = size * height / 2.0
                CGContextBeginPath(context)
                CGContextMoveToPoint(context, x, center - half)
                CGContextAddLineToPoint(context, x, center + half)
                CGContextStrokePath(context)
            }
            return UIGraphicsGetImageFromCurrentImageContext()
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun relativeLuminance(red: Double, green: Double, blue: Double): Double {
        fun channel(value: Double): Double =
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private companion object {
        const val MEDIA_ID_PREFIX = "ios-media:"
        const val DEFAULT_SMART_LOOKAHEAD = 20
        const val MAX_SMART_LOOKAHEAD = 100
        const val MAX_ARTWORK_CACHE = 32
        const val FALLBACK_ARTWORK_SIZE = 96.0

        /** How many queue entries before the seed are passed to the chooser. */
        const val RECENT_WINDOW = 10

        /** Seek-bar refresh cadence while playing. */
        const val TICKER_INTERVAL_MS = 500L

        /** Past this point, "previous" restarts the track instead of stepping back. */
        const val RESTART_THRESHOLD_MS = 3_000L

        /**
         * How close two transport events have to be to count as one press.
         * Long enough to cover the gap between an interruption and its remote
         * toggle, far shorter than any real double-press of a headphone button.
         */
        const val DUPLICATE_TRANSPORT_WINDOW_S = 0.6

    }

    private enum class PlaybackBackend { FILE, MEDIA_LIBRARY }
}

public actual fun playbackModule(): Module = module {
    single<PlaybackController> {
        IosPlaybackController(chooser = get(), audioEngine = get(), engine = get())
    }
}

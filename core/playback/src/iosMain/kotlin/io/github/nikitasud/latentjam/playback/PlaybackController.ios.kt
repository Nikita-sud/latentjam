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
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
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
import platform.MediaPlayer.MPMediaQuery
import platform.MediaPlayer.MPMusicPlaybackState
import platform.MediaPlayer.MPMusicPlayerController
import platform.MediaPlayer.MPMusicPlayerControllerPlaybackStateDidChangeNotification
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

    private val mediaItemsById = mutableMapOf<String, platform.MediaPlayer.MPMediaItem>()

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

    private var tickerJob: Job? = null

    /**
     * Scoped to the current item, so a notification can only ever refer to the
     * track that actually finished — no stale end-of-item can double-advance.
     */
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
        configureAudioSession()
        wireAudioSessionObservers()
        wireMediaPlayer()
        wireRemoteCommands()
    }

    override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>): Unit =
        withContext(Dispatchers.Main) {
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
        smartLookahead = length.coerceIn(1, MAX_SMART_LOOKAHEAD)
        appendSmartNextIfNeeded()
    }

    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int): Unit =
        withContext(Dispatchers.Main) {
            if (tracks.isEmpty()) return@withContext
            val start = startIndex.coerceIn(0, tracks.lastIndex)
            pool = tracks

            when (mode) {
                // SMART owns its queue: begin with the tapped track alone and let
                // the chooser build the path forward from it.
                ShuffleMode.SMART -> {
                    queue = listOf(tracks[start])
                    queueIndex = 0
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
            loadCurrentItem(autoPlay = true)
            pushState()
            appendSmartNextIfNeeded()
        }

    override suspend fun togglePlayPause(): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty()) return@withContext
        if (playing) {
            pauseActiveBackend()
            playing = false
        } else {
            playing = playActiveBackend()
        }
        updateTicker()
        pushState()
    }

    override suspend fun pause(): Unit = withContext(Dispatchers.Main) {
        if (queue.isEmpty() || !playing) return@withContext
        pauseActiveBackend()
        playing = false
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
        // Player-standard: restart the track unless you are already at its start.
        if (positionMs() > RESTART_THRESHOLD_MS || queueIndex <= 0) {
            seekActiveBackend(0L)
            invalidateNowPlayingInfo()
        } else {
            queueIndex -= 1
            loadCurrentItem(autoPlay = true)
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
        this@IosPlaybackController.queueIndex = queueIndex
        loadCurrentItem(autoPlay = true)
        pushState()
        appendSmartNextIfNeeded()
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
        val kept = queue.filter { it.id in trackIds }
        if (kept.size == queue.size) return@withContext
        pool = pool.filter { it.id in trackIds }
        queue = kept
        if (kept.isEmpty()) {
            queueIndex = -1
            pauseActiveBackend()
            playing = false
            updateTicker()
            pushState()
            return@withContext
        }
        if (current != null && current.id in trackIds) {
            // The playing track survived; only its position in the queue may have shifted.
            queueIndex = kept.indexOfFirst { it.id == current.id }
        } else {
            // The current entry was deleted: behave like it ended and move to the track that
            // now occupies its slot, keeping whether we were playing.
            queueIndex = queueIndex.coerceIn(0, kept.lastIndex)
            loadCurrentItem(autoPlay = playing)
        }
        pushState()
    }

    override suspend fun playNext(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        val insertAt = (queueIndex + 1).coerceIn(0, queue.size)
        queue = queue.toMutableList().apply { add(insertAt, track) }
        cueIfNothingCurrent()
        pushState()
    }

    override suspend fun addToQueue(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        queue = queue + track
        cueIfNothingCurrent()
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
            queueIndex = 0
            loadCurrentItem(autoPlay = false)
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
    ): Unit = withContext(Dispatchers.Main) {
        if (tracks.isEmpty()) return@withContext
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        pool = tracks
        when (mode) {
            // SMART owns its queue: restore the parked track alone; the chooser plans the path
            // forward once listening actually resumes.
            ShuffleMode.SMART -> {
                queue = listOf(tracks[start])
                queueIndex = 0
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
        // Paused is the whole point: the session reappears, nothing sounds until asked.
        loadCurrentItem(autoPlay = false)
        if (positionMs > 0) seekTo(positionMs)
        pushState()
    }

    private suspend fun applyShuffleMode(requested: ShuffleMode): ShuffleMode {
        if (mode == requested) {
            syncRemotePlaybackModes()
            return mode
        }
        mode = requested
        val current = queue.getOrNull(queueIndex)
        when (mode) {
            ShuffleMode.OFF -> if (current != null && pool.isNotEmpty()) {
                // Back to the collection's own order, keeping your place in it.
                queue = pool
                queueIndex = pool.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
            }
            ShuffleMode.ON -> if (current != null && pool.isNotEmpty()) {
                queue = listOf(current) + (pool.filter { it.id != current.id }).shuffled()
                queueIndex = 0
            }
            ShuffleMode.SMART -> {
                // Drop the pre-planned tail; the chooser decides the path now.
                if (queueIndex >= 0) queue = queue.take(queueIndex + 1)
                appendSmartNextIfNeeded()
            }
        }
        syncRemotePlaybackModes()
        pushState()
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
        while (guard-- > 0 && queue.size - 1 - queueIndex < smartLookahead) {
            val tailIndex = queue.lastIndex
            val seed = queue[tailIndex]
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
            queue = queue + chosen
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
        var candidate = queueIndex + 1
        // Bounded by the queue length, so a queue of entirely dead files ends in
        // a pause rather than a spin.
        var attempts = queue.size
        while (attempts-- > 0) {
            if (candidate >= queue.size) {
                if (repeat != RepeatMode.ALL || queue.isEmpty()) break
                candidate = 0
            }
            queueIndex = candidate
            if (loadCurrentItem(autoPlay = true)) {
                pushState()
                return
            }
            candidate += 1
        }
        pauseActiveBackend()
        playing = false
        updateTicker()
        pushState()
    }

    /**
     * Points the player at [queueIndex], re-scoping the end-of-item observer to
     * the new item as it goes. Returns false when the entry cannot be opened.
     */
    private fun loadCurrentItem(autoPlay: Boolean): Boolean {
        val track = queue.getOrNull(queueIndex) ?: return false
        val isMediaLibraryItem = track.id.value.startsWith(MEDIA_ID_PREFIX)
        if (isMediaLibraryItem && track.audioUri == null) {
            return loadMediaLibraryItem(track, autoPlay)
        }
        val url = track.audioUri?.let { NSURL.URLWithString(it) }
            ?: return if (isMediaLibraryItem) loadMediaLibraryItem(track, autoPlay) else false

        mediaItemStarted = false
        mediaPlayer.stop()
        activeBackend = PlaybackBackend.FILE
        if (!audioEngine.load(url, autoPlay) { mainScope.launch { onItemEnded() } }) {
            return if (isMediaLibraryItem) loadMediaLibraryItem(track, autoPlay) else false
        }
        audioEngine.setOutputSupportsEqualizer(true)
        playing = autoPlay
        updateTicker()
        return true
    }

    /** Resolves a persistent Music-library ID and cues it in Apple's local player. */
    private fun loadMediaLibraryItem(track: TrackDescriptor, autoPlay: Boolean): Boolean {
        val persistentId = track.id.value.removePrefix(MEDIA_ID_PREFIX)
        val item = resolveMediaItem(persistentId) ?: return false

        audioEngine.stop()
        audioEngine.setOutputSupportsEqualizer(false)
        mediaItemStarted = false
        mediaPlayer.stop()
        mediaPlayer.setQueueWithItemCollection(MPMediaItemCollection(items = listOf(item)))
        activeBackend = PlaybackBackend.MEDIA_LIBRARY
        if (autoPlay) {
            mediaItemStarted = true
            mediaPlayer.play()
            playing = true
        }
        updateTicker()
        return true
    }

    /** Refreshes on a miss so additions to Music.app work without restarting LatentJam. */
    private fun resolveMediaItem(persistentId: String): platform.MediaPlayer.MPMediaItem? {
        mediaItemsById[persistentId]?.let { return it }
        MPMediaQuery.songsQuery().items.orEmpty()
            .mapNotNull { it as? platform.MediaPlayer.MPMediaItem }
            .forEach { mediaItemsById[it.persistentID.toString()] = it }
        return mediaItemsById[persistentId]
    }

    private fun onItemEnded() {
        if (repeat == RepeatMode.ONE) {
            seekActiveBackend(0L)
            playing = playActiveBackend()
            pushState()
            return
        }
        mainScope.launch {
            // Top up before advancing so SMART always has somewhere to go.
            appendSmartNextIfNeeded()
            advance()
        }
    }

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        // .playback is what makes audio survive the lock screen and ignore the
        // ring/silent switch — without it the app is silent in the user's pocket.
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
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
                    pausedByInterruption = true
                    pauseFromSystem()
                }
            } else {
                if (pausedByInterruption && shouldResume && queue.isNotEmpty()) {
                    AVAudioSession.sharedInstance().setActive(true, null)
                    playing = playActiveBackend()
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
    private fun pauseFromSystem() {
        pauseActiveBackend()
        playing = false
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
            mainScope.launch {
                if (activeBackend != PlaybackBackend.MEDIA_LIBRARY) return@launch
                when (mediaPlayer.playbackState) {
                    MPMusicPlaybackState.MPMusicPlaybackStatePlaying -> {
                        mediaItemStarted = true
                        playing = true
                        updateTicker()
                        pushState()
                    }
                    MPMusicPlaybackState.MPMusicPlaybackStatePaused,
                    MPMusicPlaybackState.MPMusicPlaybackStateInterrupted,
                    -> {
                        playing = false
                        updateTicker()
                        pushState()
                    }
                    MPMusicPlaybackState.MPMusicPlaybackStateStopped -> if (mediaItemStarted) {
                        mediaItemStarted = false
                        onItemEnded()
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

    private fun playActiveBackend(): Boolean =
        when (activeBackend) {
            PlaybackBackend.FILE -> audioEngine.play()
            PlaybackBackend.MEDIA_LIBRARY -> {
                mediaItemStarted = true
                mediaPlayer.play()
                true
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

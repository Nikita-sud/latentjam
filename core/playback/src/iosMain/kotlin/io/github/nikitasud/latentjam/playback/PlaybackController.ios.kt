/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
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
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
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

/**
 * iOS [PlaybackController] over an app-owned [IosAudioEngine] for imported files and Apple's
 * [MPMusicPlayerController] for protected Music-library items.
 *
 * The queue is owned here rather than by an AVQueuePlayer: [NowPlaying.queue] is the whole queue,
 * including played entries, and [playAt] may jump anywhere in it. The player node is rescheduled
 * for the selected file while this controller preserves queue/history semantics.
 *
 * ### SMART queue strategy
 * Deliberately identical to the Android controller: hold [SMART_LOOKAHEAD]
 * tracks beyond the playhead, and top up by seeding the chooser from the
 * QUEUE TAIL rather than the playing track. Seeding from the playing track
 * would look to the chooser like a playhead that had jumped somewhere it had
 * not planned, so it would discard its plan and replan on every single append.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPlaybackController(
    private val chooser: NextTrackChooser,
    private val audioEngine: IosAudioEngine,
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

    private var mode: ShuffleMode = ShuffleMode.OFF
    private var repeat: RepeatMode = RepeatMode.OFF
    private var playing: Boolean = false

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

    init {
        configureAudioSession()
        wireMediaPlayer()
        wireRemoteCommands()
    }

    override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>): Unit =
        withContext(Dispatchers.Main) {
            smartLibrary = tracks.distinctBy { it.id }
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
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        pushState()
        repeat
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
        mode = when (mode) {
            ShuffleMode.OFF -> ShuffleMode.ON
            ShuffleMode.ON -> ShuffleMode.SMART
            ShuffleMode.SMART -> ShuffleMode.OFF
        }
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
        pushState()
        mode
    }

    /** Main-thread only. Serialised — see [appendMutex]. */
    private suspend fun appendSmartNextIfNeeded() = appendMutex.withLock { appendSmartNext() }

    private suspend fun appendSmartNext() {
        if (mode != ShuffleMode.SMART) return
        if (queue.isEmpty()) return

        var appended = false
        // Each pass appends exactly one item, so the shortfall shrinks by one and at
        // most SMART_LOOKAHEAD passes are ever needed; the counter caps it regardless.
        var guard = SMART_LOOKAHEAD
        while (guard-- > 0 && queue.size - 1 - queueIndex < SMART_LOOKAHEAD) {
            val tailIndex = queue.lastIndex
            val seed = queue[tailIndex]
            val recentIds = (maxOf(0, tailIndex - RECENT_WINDOW) until tailIndex)
                .map { queue[it].id }
            // Everything already queued is off the table, not just the recent window:
            // a track appended twice would play twice in one sitting, and the queue
            // would show two rows claiming the same identity.
            val queued = queue.mapTo(HashSet()) { it.id }
            val candidates = (smartLibrary.ifEmpty { pool }).filter { it.id !in queued }
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
        if (track.id.value.startsWith(MEDIA_ID_PREFIX)) {
            return loadMediaLibraryItem(track, autoPlay)
        }
        val url = track.audioUri?.let { NSURL.URLWithString(it) } ?: return false

        mediaItemStarted = false
        mediaPlayer.stop()
        activeBackend = PlaybackBackend.FILE
        if (!audioEngine.load(url, autoPlay) { mainScope.launch { onItemEnded() } }) return false
        playing = autoPlay
        updateTicker()
        return true
    }

    /** Resolves a persistent Music-library ID and cues it in Apple's local player. */
    private fun loadMediaLibraryItem(track: TrackDescriptor, autoPlay: Boolean): Boolean {
        val persistentId = track.id.value.removePrefix(MEDIA_ID_PREFIX)
        val item = resolveMediaItem(persistentId) ?: return false

        audioEngine.stop()
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
            mainScope.launch { togglePlayPause() }
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
            updateTicker()
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
        (durationMs() ?: track.durationMs)?.let {
            info[MPMediaItemPropertyPlaybackDuration] = NSNumber(double = it / 1000.0)
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
            NSNumber(double = positionMs() / 1000.0)
        info[MPNowPlayingInfoPropertyPlaybackRate] =
            NSNumber(double = if (playing) 1.0 else 0.0)
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    private companion object {
        const val MEDIA_ID_PREFIX = "ios-media:"
        /**
         * How many tracks SMART keeps queued beyond the playhead. Matches the
         * Android controller so both platforms show a queue you can read rather
         * than a single next-track hint.
         */
        const val SMART_LOOKAHEAD = 10

        /** How many queue entries before the seed are passed to the chooser. */
        const val RECENT_WINDOW = 10

        /** Seek-bar refresh cadence while playing. */
        const val TICKER_INTERVAL_MS = 500L

        /** Past this point, "previous" restarts the track instead of stepping back. */
        const val RESTART_THRESHOLD_MS = 3_000L

    }

    private enum class PlaybackBackend { FILE, MEDIA_LIBRARY }
}

public actual fun playbackModule(): Module = module {
    single<PlaybackController> { IosPlaybackController(chooser = get(), audioEngine = get()) }
}

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
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.ByteArrayOutputStream
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
 * [SMART_LOOKAHEAD] tracks: whenever the queue runs shorter than that, the
 * controller tops it back up from the injected [NextTrackChooser], continuing the walk from
 * the tail each time. Skipping or finishing a track therefore always has
 * somewhere to go, there is a readable queue rather than a single next-track
 * hint, and the played queue doubles as listening history.
 *
 * All MediaController access is confined to the main thread, per Media3's
 * threading contract — every port method hops there via [Dispatchers.Main].
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
    private val poolById = mutableMapOf<String, TrackDescriptor>()
    private var mode: ShuffleMode = ShuffleMode.OFF

    /** Rebuilt only when the queue actually changes; shared by ticker emissions. */
    private var cachedQueue: List<TrackDescriptor> = emptyList()
    private var tickerJob: Job? = null
    private var repeat: RepeatMode = RepeatMode.OFF

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

        override fun onPlaybackStateChanged(playbackState: Int) = pushState()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // SMART deliberately disables ExoPlayer's random shuffle because LatentJam owns the
            // queue. Do not mistake that internal transition for the user switching SMART off.
            if (!(mode == ShuffleMode.SMART && !shuffleModeEnabled)) {
                mode = if (shuffleModeEnabled) ShuffleMode.ON else ShuffleMode.OFF
                AndroidShuffleModeRegistry.set(mode)
            }
            pushState()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeat = repeatMode.toLatentJamRepeatMode()
            pushState()
        }
    }

    override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>): Unit =
        withContext(Dispatchers.Main) {
            smartLibrary = tracks.distinctBy { it.id }
            tracks.forEach { poolById[it.id.value] = it }
        }

    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int): Unit =
        withContext(Dispatchers.Main) {
            if (tracks.isEmpty()) return@withContext
            pool = tracks
            poolById.clear()
            smartLibrary.forEach { poolById[it.id.value] = it }
            tracks.forEach { poolById[it.id.value] = it }

            val player = controller()
            if (mode == ShuffleMode.SMART) {
                // SMART owns its queue: start from the tapped track alone and
                // let the chooser build the path forward.
                player.setMediaItems(listOf(tracks[startIndex].toMediaItem()))
            } else {
                player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
                player.shuffleModeEnabled = mode == ShuffleMode.ON
            }
            player.prepare()
            player.play()
            rebuildQueueSnapshot()
            pushState()
            decorateCoverlessTrack(player.currentMediaItem)
            appendSmartNextIfNeeded()
        }

    override suspend fun togglePlayPause(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        if (player.isPlaying) player.pause() else player.play()
        pushState()
    }

    override suspend fun next(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        appendSmartNextIfNeeded()
        player.seekToNextMediaItem()
        pushState()
    }

    override suspend fun previous(): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        player.seekToPrevious()
        pushState()
    }

    override suspend fun seekTo(positionMs: Long): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        player.seekTo(positionMs)
        pushState()
    }

    override suspend fun playAt(queueIndex: Int): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        if (queueIndex !in 0 until player.mediaItemCount) return@withContext
        player.seekTo(queueIndex, 0L)
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

    override suspend fun playNext(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        poolById[track.id.value] = track
        val insertAt = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, track.toMediaItem())
        rebuildQueueSnapshot()
        pushState()
    }

    override suspend fun addToQueue(track: TrackDescriptor): Unit = withContext(Dispatchers.Main) {
        val player = controller ?: return@withContext
        poolById[track.id.value] = track
        player.addMediaItem(track.toMediaItem())
        rebuildQueueSnapshot()
        pushState()
    }

    override suspend fun cycleShuffleMode(): ShuffleMode = withContext(Dispatchers.Main) {
        val nextMode = AndroidShuffleModeRegistry.cycle()
        applyShuffleMode(nextMode)
        nextMode
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
        mode = nextMode
        controller?.let { player ->
            when (mode) {
                ShuffleMode.OFF -> player.shuffleModeEnabled = false
                ShuffleMode.ON -> player.shuffleModeEnabled = true
                ShuffleMode.SMART -> {
                    player.shuffleModeEnabled = false
                    // Drop the pre-planned tail; the chooser now decides the path.
                    if (player.mediaItemCount > player.currentMediaItemIndex + 1) {
                        player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
                    }
                    appendSmartNextIfNeeded()
                }
            }
            rebuildQueueSnapshot()
        }
        pushState()
    }

    /**
     * Main-thread only. Tops SMART back up to [SMART_LOOKAHEAD] tracks ahead of the playhead.
     *
     * Serialised: the decision below and the append that follows it must be one atomic step, or
     * concurrent callers duplicate each other's work. See [appendMutex].
     */
    private suspend fun appendSmartNextIfNeeded() = appendMutex.withLock { appendSmartNext() }

    private suspend fun appendSmartNext() {
        val player = controller ?: return
        if (mode != ShuffleMode.SMART) return
        if (player.mediaItemCount == 0) return

        var appended = false
        // Each pass appends exactly one item, so the shortfall shrinks by one and the loop needs at
        // most SMART_LOOKAHEAD passes. The counter also caps it if a player ever fails to reflect an
        // append immediately, which would otherwise spin.
        var guard = SMART_LOOKAHEAD
        while (
            guard-- > 0 &&
            player.mediaItemCount - 1 - player.currentMediaItemIndex < SMART_LOOKAHEAD
        ) {
            // The walk continues from the END of the queue, not from what is playing. The tail is
            // the last thing the chain decided, so seeding with it is what lets the chooser
            // recognise its own plan and serve the next hop from it. Passing the playing track for
            // every append would instead look like a playhead that had jumped somewhere
            // unpredicted, and the chooser would throw the plan away and replan from the same seed
            // on each pass — the walk's spacing and drift rules reset every hop, and the engine
            // would be asked to plan N chains to fill N slots.
            val tailIndex = player.mediaItemCount - 1
            val seed = poolById[player.getMediaItemAt(tailIndex).mediaId] ?: break
            val recentIds = (maxOf(0, tailIndex - RECENT_WINDOW) until tailIndex)
                .map { index -> TrackId(player.getMediaItemAt(index).mediaId) }
            // Everything ALREADY in the queue is off the table, not just the recent window: a track
            // appended twice would play twice in one sitting, and the queue list would hold two rows
            // claiming the same identity.
            val queued = (0 until player.mediaItemCount)
                .mapTo(HashSet()) { index -> TrackId(player.getMediaItemAt(index).mediaId) }
            val candidates = (smartLibrary.ifEmpty { pool }).filter { it.id !in queued }
            if (candidates.isEmpty()) break

            val chosen = runCatching { chooser.choose(seed, recentIds, candidates) }
                .getOrNull()
                // SMART must never present a random row as a recommendation. If neither the
                // acoustic nor metadata path can answer yet, keep the honest short queue and let
                // the next index update / transition retry.
                ?: break
            player.addMediaItem(chosen.toMediaItem())
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
        cachedQueue = (0 until player.mediaItemCount).mapNotNull { itemIndex ->
            poolById[player.getMediaItemAt(itemIndex).mediaId]
        }
    }

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
        val track = player?.currentMediaItem?.mediaId?.let(poolById::get)
        mutableState.value = NowPlaying(
            track = track,
            isPlaying = player?.isPlaying == true,
            shuffleMode = mode,
            repeatMode = repeat,
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = duration ?: track?.durationMs ?: 0,
            queue = cachedQueue,
            queueIndex = player?.currentMediaItemIndex?.takeIf { cachedQueue.isNotEmpty() } ?: -1,
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
        val track = poolById[id] ?: return
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
        /**
         * How many tracks SMART keeps queued beyond the playhead.
         *
         * A queue you can read is the point: one track ahead is a next-track indicator, not a
         * queue. Kept under the chooser's chain length (12 in the app graph) so a full top-up is
         * normally served from a single plan rather than forcing an immediate replan.
         */
        const val SMART_LOOKAHEAD = 10

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

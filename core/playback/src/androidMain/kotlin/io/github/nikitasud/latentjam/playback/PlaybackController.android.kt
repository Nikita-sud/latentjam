/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * shuffle order). SMART keeps the queue linear but ALWAYS ONE TRACK AHEAD:
 * whenever the current item is the last one, the controller asks the injected
 * [NextTrackChooser] for a successor (falling back to a random unplayed
 * candidate when it abstains) and appends it. Skipping or finishing a track
 * therefore always has somewhere to go, and the played queue doubles as
 * listening history.
 *
 * All MediaController access is confined to the main thread, per Media3's
 * threading contract — every port method hops there via [Dispatchers.Main].
 */
internal class AndroidPlaybackController(
    private val context: Context,
    private val chooser: NextTrackChooser,
) : PlaybackController {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = mutableState.asStateFlow()

    private val controllerMutex = Mutex()
    private var controller: MediaController? = null

    /** Full candidate pool for SMART selection, keyed for O(1) descriptor lookup. */
    private var pool: List<TrackDescriptor> = emptyList()
    private val poolById = mutableMapOf<String, TrackDescriptor>()
    private var mode: ShuffleMode = ShuffleMode.OFF

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pushState()
            mainScope.launch { appendSmartNextIfNeeded() }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()

        override fun onPlaybackStateChanged(playbackState: Int) = pushState()
    }

    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int): Unit =
        withContext(Dispatchers.Main) {
            if (tracks.isEmpty()) return@withContext
            pool = tracks
            poolById.clear()
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
            pushState()
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

    override suspend fun cycleShuffleMode(): ShuffleMode = withContext(Dispatchers.Main) {
        mode = when (mode) {
            ShuffleMode.OFF -> ShuffleMode.ON
            ShuffleMode.ON -> ShuffleMode.SMART
            ShuffleMode.SMART -> ShuffleMode.OFF
        }
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
        }
        pushState()
        mode
    }

    /** Main-thread only. Keeps SMART one track ahead of the playhead. */
    private suspend fun appendSmartNextIfNeeded() {
        val player = controller ?: return
        if (mode != ShuffleMode.SMART) return
        if (player.mediaItemCount == 0) return
        if (player.currentMediaItemIndex < player.mediaItemCount - 1) return

        val current = player.currentMediaItem?.mediaId?.let(poolById::get) ?: return
        val currentIndex = player.currentMediaItemIndex
        val recentIds = (maxOf(0, currentIndex - RECENT_WINDOW) until currentIndex)
            .map { index -> TrackId(player.getMediaItemAt(index).mediaId) }
        val excluded = buildSet {
            addAll(recentIds)
            add(current.id)
        }
        val candidates = pool.filter { it.id !in excluded }
        if (candidates.isEmpty()) return

        val chosen = runCatching { chooser.choose(current, recentIds, candidates) }
            .getOrNull()
            ?: candidates.random()
        player.addMediaItem(chosen.toMediaItem())
    }

    private fun pushState() {
        val player = controller
        mutableState.value = NowPlaying(
            track = player?.currentMediaItem?.mediaId?.let(poolById::get),
            isPlaying = player?.isPlaying == true,
            shuffleMode = mode,
        )
    }

    /** Connects to [PlaybackService] on first use; cached afterwards. */
    private suspend fun controller(): MediaController = controllerMutex.withLock {
        controller ?: buildController().also { built ->
            controller = built
            built.addListener(playerListener)
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

    private companion object {
        /** How many previously played tracks SMART excludes from re-selection. */
        const val RECENT_WINDOW = 10
    }
}

public actual fun playbackModule(): Module = module {
    single<PlaybackController> {
        AndroidPlaybackController(context = get(), chooser = get())
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module

/**
 * The three shuffle positions — LatentJam's signature control.
 *
 * [SMART] delegates next-track choice to a [NextTrackChooser] backed by the
 * local similarity engine. If the chooser cannot answer yet, SMART abstains
 * rather than presenting a random track as a recommendation.
 */
public enum class ShuffleMode { OFF, ON, SMART }

/** Repeat positions: no repeat, repeat the queue, repeat one track. */
public enum class RepeatMode { OFF, ALL, ONE }

/**
 * Snapshot of what is playing right now — the UI's single source of truth.
 *
 * While playing, implementations refresh [positionMs] on a coarse ticker
 * (~2×/s) — plenty for a seek bar, cheap enough to ignore.
 *
 * @property positionMs Playhead position within the current track.
 * @property durationMs Current track duration (0 when unknown).
 * @property queue The play queue in order (immutable snapshot; rebuilt only
 *   when the queue actually changes, so ticker emissions share the same list).
 * @property queueIndex Index of the current track in [queue], -1 when empty.
 * @property sourceQueue Canonical natural-order queue from which playback was started. SMART may
 *   replace [queue]'s future with recommendations, but this source remains intact so leaving SMART
 *   and restoring a saved session can return to the original playlist or collection.
 */
public data class NowPlaying(
    public val track: TrackDescriptor? = null,
    public val isPlaying: Boolean = false,
    public val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    public val repeatMode: RepeatMode = RepeatMode.OFF,
    public val positionMs: Long = 0,
    public val durationMs: Long = 0,
    public val queue: List<TrackDescriptor> = emptyList(),
    public val queueIndex: Int = -1,
    public val sourceQueue: List<TrackDescriptor> = emptyList(),
)

/**
 * Strategy for picking the next track in [ShuffleMode.SMART].
 *
 * Kept as a port so :core:playback never depends on the similarity engine —
 * the app graph adapts [io.github.nikitasud.latentjam.smart.SimilarityEngine]
 * into this shape. Return `null` to abstain; the controller keeps the queue
 * short and retries when more local index data is available.
 */
public fun interface NextTrackChooser {
    public suspend fun choose(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): TrackDescriptor?
}

/**
 * Playback port for the shared UI.
 *
 * Platform implementations own the real player (Media3/ExoPlayer behind a
 * media-session service on Android; AVQueuePlayer on iOS, later). All
 * functions are main-safe suspends; [state] is safe to collect anywhere.
 * Like every port in this codebase, no platform or player types appear here.
 */
public interface PlaybackController {

    /** Live now-playing snapshot. */
    public val state: StateFlow<NowPlaying>

    /**
     * Synchronizes [state] with an already-running platform session before launch restore decides
     * whether the saved queue is still needed. Platforms whose player state is already local can
     * keep the default no-op.
     */
    public suspend fun synchronizeWithPlatformSession(): Unit = Unit

    /**
     * Supplies the complete on-device library SMART may recommend from.
     *
     * This is deliberately separate from [play]: a tap in Search can start from a one-result
     * filtered list, while SMART must still see the whole library. Implementations retain only
     * descriptors; audio and model inference remain inside the local similarity engine. An empty
     * list is an explicit empty candidate universe, not permission to fall back to [play]'s queue.
     * Replacing this while SMART is active removes newly ineligible tracks from the future tail but
     * preserves playback history and the current track.
     */
    public suspend fun setSmartLibrary(tracks: List<TrackDescriptor>)

    /**
     * Sets how many SMART-selected tracks are kept ahead of the playhead.
     * Implementations clamp invalid values and top up an active SMART queue when this grows.
     */
    public suspend fun setSmartQueueLength(length: Int)

    /**
     * Invalidates recommendations that have not played yet after SMART's planning policy changes.
     *
     * Implementations preserve queue history and the current row, discard only the unplayed SMART
     * future, reject any chooser result computed under the previous policy, and refill the queue
     * using the current policy. A no-op outside [ShuffleMode.SMART].
     */
    public suspend fun invalidateSmartFuture()

    /**
     * Per-track playback volume multipliers in `(0, 1]`, keyed by track id; a missing id plays at
     * full volume. Volume-normalization support: the app computes attenuation from measured
     * loudness and pushes the complete map here. Platforms without an app-level gain stage keep
     * the default no-op and simply play unnormalized.
     */
    public suspend fun setTrackVolumes(volumes: Map<String, Float>) {}

    /**
     * Softens track boundaries by fading amplitude over the first/last [seconds] of each track;
     * 0 restores hard cuts. Composes multiplicatively with [setTrackVolumes]. Platforms without
     * an app-level gain stage keep the default no-op.
     */
    public suspend fun setCrossfadeSeconds(seconds: Int) {}

    /**
     * Replaces the queue with [tracks] and starts playing the one at
     * [startIndex]. The list remains the natural OFF/ON playback source; SMART draws from the
     * complete library supplied by [setSmartLibrary].
     */
    public suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int)

    /** Pauses if playing, resumes if paused. No-op with an empty queue. */
    public suspend fun togglePlayPause()

    /** Pauses playback without ever starting it. No-op when already paused or the queue is empty. */
    public suspend fun pause()

    /** Skips to the next track (choosing one first in [ShuffleMode.SMART] if needed). */
    public suspend fun next()

    /** Returns to the previous track (or restarts the current one, player-standard). */
    public suspend fun previous()

    /** Moves the playhead within the current track. */
    public suspend fun seekTo(positionMs: Long)

    /** Jumps to the queue entry at [queueIndex] (no-op when out of range). */
    public suspend fun playAt(queueIndex: Int)

    /** Advances OFF → ON → SMART → OFF and returns the new mode. */
    public suspend fun cycleShuffleMode(): ShuffleMode

    /** Applies [mode] directly — restoring a persisted session, not a user tap on the cycle. */
    public suspend fun setShuffleMode(mode: ShuffleMode)

    /**
     * Loads [tracks] as the live queue with [startIndex] current at [positionMs] — PAUSED.
     *
     * The launch-restore path: the last session's track reappears in the player ready to
     * continue, but nothing sounds until the user asks. Because playback has not started, the
     * history recorder opens no session for the restored track unless it actually plays.
     * [sourceTracks] is the canonical natural-order queue retained across SMART planning. It is
     * separate because [tracks] may be a saved SMART queue containing generated recommendations.
     * `null` keeps sessions written before source queues were persisted backward compatible by
     * using [tracks]. An explicitly empty list is meaningful: the saved source existed but every
     * row was deleted while a generated SMART current row survived.
     */
    public suspend fun restoreQueue(
        tracks: List<TrackDescriptor>,
        startIndex: Int,
        positionMs: Long,
        sourceTracks: List<TrackDescriptor>? = null,
    )

    /** Advances OFF → ALL → ONE → OFF and returns the new mode. */
    public suspend fun cycleRepeatMode(): RepeatMode

    /**
     * Drops every queue entry whose id is NOT in [trackIds] — the reconciliation call for
     * tracks deleted from the device while queued. Removing the current entry behaves like
     * that track ending: playback moves on rather than clinging to a file that no longer
     * exists. A no-op when everything queued is still present.
     */
    public suspend fun retainQueue(trackIds: Set<TrackId>)

    /** Inserts [track] directly after the current one. */
    public suspend fun playNext(track: TrackDescriptor)

    /** Appends [track] to the end of the queue. */
    public suspend fun addToQueue(track: TrackDescriptor)

    /**
     * Moves the queue entry at [from] to position [to], both in [NowPlaying.queue] order.
     * Out-of-range indices are ignored. Under the random shuffle mode the visible order is a
     * traversal over a differently-ordered player, so implementations may ignore the request —
     * callers hide the reorder affordance there.
     */
    public suspend fun moveQueueItem(from: Int, to: Int)

    /**
     * Removes the queue entry at [index] ([NowPlaying.queue] order). Removing the current entry
     * behaves like that track ending: playback advances on its own.
     */
    public suspend fun removeQueueItem(index: Int)
}

/**
 * Koin bindings for [PlaybackController] on this platform. The Android actual
 * resolves an `android.content.Context` and a [NextTrackChooser] from the
 * graph; the app's modules must bind both.
 */
public expect fun playbackModule(): Module

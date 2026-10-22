/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Prepares each overlap for [CrossfadeAudioProcessor]: shortly before the playing track ends, it
 * decodes the next track's opening ([CrossfadeIntroCapture]) and publishes it as the
 * [CrossfadeSettings.plan] for that seam. A seam that changes — a queue edit, a new next track, a
 * skip — withdraws the plan; the processor then lets an overlap in progress go and the seam falls
 * back to the plain fades. Album seams played in order never get a plan (see [FadeEdges]).
 *
 * Lives in the playback service next to its ExoPlayer; main thread only.
 */
internal class CrossfadeCoordinator(
    private val context: Context,
    private val player: Player,
    private val settings: CrossfadeSettings = AndroidCrossfade.settings,
) {
    private val handler = Handler(player.applicationLooper)
    private var seam: Seam? = null
    private var capture: CrossfadeIntroCapture? = null

    /** Seams whose opening could not be decoded; not retried until the queue moves on. */
    private val failed = mutableSetOf<Seam>()

    private val tick = Runnable { update() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) update()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            failed.clear()
            // The join is heard now; a Next from here on leaves the incoming track.
            settings.progress = null
        }
    }

    fun start() {
        player.addListener(listener)
        update()
    }

    fun release() {
        player.removeListener(listener)
        handler.removeCallbacks(tick)
        withdraw()
    }

    /** Re-evaluates the seam ahead; also the periodic check while playing. */
    fun update() {
        handler.removeCallbacks(tick)
        // The setting and the album edges arrive from the controller without an event of their own.
        if (player.isPlaying) handler.postDelayed(tick, TICK_MS)
        val fadeMs = settings.fadeMs
        val current = player.currentMediaItem
        val next = nextItem()
        val wanted = if (fadeMs > 0L && current != null && next?.localConfiguration != null &&
            settings.edgesOf(current.mediaId).fadeOut
        ) Seam(current.mediaId, next.mediaId) else null
        if (seam != wanted) withdraw()
        if (wanted == null || seam != null || wanted in failed) return
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (prerollsNow(player.currentPosition, durationMs, fadeMs)) {
            begin(wanted, next!!, outgoingMs = durationMs, fadeMs = fadeMs)
        }
    }

    private fun nextItem(): MediaItem? {
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return player.currentMediaItem
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val index = timeline.getNextWindowIndex(
            player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled,
        )
        if (index == C.INDEX_UNSET) return null
        return timeline.getWindow(index, Timeline.Window()).mediaItem
    }

    private fun begin(target: Seam, next: MediaItem, outgoingMs: Long, fadeMs: Long) {
        seam = target
        val pending = CrossfadeIntroCapture(context, next, fadeMs + CROSSFADE_INTRO_MARGIN_MS) { intro ->
            capture = null
            if (seam != target) return@CrossfadeIntroCapture
            val overlapMs = intro?.let { overlapLengthMs(fadeMs, outgoingMs, it.durationMs) } ?: 0L
            if (intro == null || overlapMs <= 0L) {
                failed += target
                return@CrossfadeIntroCapture
            }
            settings.plan = OverlapPlan(
                outgoingId = target.outgoingId,
                incomingId = target.incomingId,
                overlapUs = overlapMs * 1000L,
                sampleRate = intro.sampleRate,
                channelCount = intro.channelCount,
                pcm = intro.pcm,
                frames = intro.frames,
            )
        }
        capture = pending
        pending.start()
    }

    private fun withdraw() {
        capture?.cancel()
        capture = null
        seam = null
        settings.plan = null
    }

    private data class Seam(val outgoingId: String, val incomingId: String)

    private companion object {
        const val TICK_MS = 500L
    }
}

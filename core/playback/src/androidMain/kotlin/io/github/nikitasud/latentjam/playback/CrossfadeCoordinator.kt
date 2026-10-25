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
    private var released = false

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
            settings.mixing = null
        }
    }

    fun start() {
        player.addListener(listener)
        // The sleep timer's hold arrives from the controller: withdraw at once, not on the next tick.
        settings.onChange = { handler.post { update() } }
        update()
    }

    fun release() {
        released = true
        settings.onChange = null
        player.removeListener(listener)
        // Every posted look, the periodic one and a refresh the sleep timer's hold asked for.
        handler.removeCallbacksAndMessages(null)
        withdraw()
    }

    /**
     * The slider setting a seam is planned at. Moving the slider re-plans a seam whose overlap has
     * not begun; one already sounding keeps the plan it is playing — the slider writes on every step
     * of a drag, and withdrawing then would cut the next track off and replay its opening at the
     * join. iOS carries a sounding overlap on the same way.
     */
    private fun plannedFadeMs(fadeMs: Long): Long {
        val sounding = settings.mixing ?: return fadeMs
        val planned = seam ?: return fadeMs
        return if (sounding === settings.plan && fadeMs > 0L) planned.fadeMs else fadeMs
    }

    /** Re-evaluates the seam ahead; also the periodic check while playing. */
    fun update() {
        if (released) return
        handler.removeCallbacks(tick)
        // The album edges arrive from the controller without an event of their own, so a seam is
        // looked at periodically while playing — but not with the slider at 0, the default: a
        // setting change wakes this up through [CrossfadeSettings.onChange].
        if (player.isPlaying && settings.fadeMs > 0L) handler.postDelayed(tick, TICK_MS)
        val fadeMs = settings.fadeMs
        val current = player.currentMediaItem
        val next = nextItem()
        // The slider, or the length SMART fitted to this pair; asked from the start of the track,
        // so the fit is ready long before the seam is cued.
        val seamMs = if (current != null && next != null && fadeMs > 0L) {
            settings.seamFadeMs?.invoke(current.mediaId, next.mediaId, fadeMs) ?: fadeMs
        } else {
            fadeMs
        }
        val wanted = if (fadeMs > 0L && !settings.finishingTrack && current != null &&
            next?.localConfiguration != null && settings.edgesAt(player.currentMediaItemIndex).fadeOut
        ) Seam(current.mediaId, next.mediaId, plannedFadeMs(seamMs)) else null
        if (seam != wanted) withdraw()
        if (wanted == null || seam != null || wanted in failed) return
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val spans = settings.soundSpans
        val anchors = seamAnchors(durationMs, spans[wanted.outgoingId], spans[wanted.incomingId])
        if (prerollsNow(player.currentPosition, anchors.outgoingEndMs, fadeMs)) {
            begin(wanted, next!!, outgoingMs = durationMs, fadeMs = wanted.fadeMs, anchors = anchors)
        }
    }

    /**
     * Where Next should land during an overlap: the next row, at the point of it already heard, so
     * its opening does not play twice. Null outside an overlap. Every Next the session receives —
     * the app, the notification, a headset, a car — comes through here.
     */
    fun nextFromOverlap(): Landing? {
        val plan = settings.mixing ?: return null
        if (player.currentMediaItem?.mediaId != plan.outgoingId) return null
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return null
        val index = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return null
        if (player.getMediaItemAt(index).mediaId != plan.incomingId) return null
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: return null
        val endMs = plan.outgoingEndUs.takeIf { it != C.TIME_UNSET }?.div(1000L)?.coerceAtMost(durationMs) ?: durationMs
        // The incoming opening began under the overlapUs before the outgoing track's last sound,
        // at its own first sound; the player's position is what has reached the speaker, so this
        // is the heard point, not one a buffer ahead of it.
        val heardMs = player.currentPosition - (endMs - plan.overlapUs / 1000L)
        val fromMs = plan.incomingStartFrame * 1000L / plan.sampleRate
        return if (heardMs >= 0L) {
            Landing(index, fromMs + heardMs, plan.incomingId, plan.overlapUs, riseFromUs = fromMs * 1000L)
        } else {
            null
        }
    }

    /**
     * Seeks to [landing] for a Next during an overlap. The incoming track's first PCM after the seek
     * then continues the overlap's rise ([CrossfadeSettings.pendingRise]) — recorded before the seek,
     * because the seek disables the renderer and flushes the chain more than once before it plays.
     */
    fun seekTo(landing: Landing) {
        settings.pendingRise = PendingRise(landing.mediaId, landing.positionMs * 1000L, landing.riseUs, landing.riseFromUs)
        player.seekTo(landing.index, landing.positionMs)
    }

    /** Where a Next during an overlap goes: [index] at [positionMs], rising over [riseUs] from [riseFromUs]. */
    data class Landing(
        val index: Int,
        val positionMs: Long,
        val mediaId: String,
        val riseUs: Long,
        val riseFromUs: Long = 0L,
    )

    /**
     * The row playback moves on to by itself, when it may overlap. Never the track itself: a track
     * overlapping its own opening would start its next play seconds in — past the sleep timer's
     * and the listening log's idea of a restart. Repeat-one and any next row of the same track keep
     * the plain fades.
     */
    private fun nextItem(): MediaItem? {
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val index = timeline.getNextWindowIndex(
            player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled,
        )
        if (index == C.INDEX_UNSET) return null
        // The same track next — a one-row queue on repeat-all, Play next on the playing track —
        // is a repeat too, and keeps the plain fades for the same reason.
        return timeline.getWindow(index, Timeline.Window()).mediaItem
            .takeIf { it.mediaId != player.currentMediaItem?.mediaId }
    }

    /**
     * Decodes [next]'s opening for the seam. Met at the tracks' sound edges ([anchors]), the opening
     * must also cover the incoming track's leading silence and the outgoing one's trailing silence,
     * which it plays on over; one that cannot — over the capture's size cap, or a track shorter
     * than that — is decoded again for the seam at the files' own edges, the seam as it was before.
     */
    private fun begin(target: Seam, next: MediaItem, outgoingMs: Long, fadeMs: Long, anchors: SeamAnchors) {
        seam = target
        val anchored = anchors != SeamAnchors(outgoingMs, 0L)
        val trailMs = anchors.trailMs(outgoingMs)
        val leadMs = anchors.incomingStartMs
        val lengthMs = fadeMs + leadMs + trailMs + CROSSFADE_INTRO_MARGIN_MS
        val scratch = IntroScratch.get(context)
        val slot = scratch.freeSlot(sounding = settings.soundingSlot)
        val pending = CrossfadeIntroCapture(context, next, lengthMs, scratch, slot) { intro ->
            capture = null
            if (seam != target) return@CrossfadeIntroCapture
            val overlapMs = intro?.let {
                overlapLengthMs(fadeMs, anchors.outgoingEndMs, it.durationMs - leadMs)
            } ?: 0L
            val coveredMs = intro?.let { it.frames * 1000L / it.sampleRate } ?: 0L
            if (anchored && (intro == null || overlapMs <= 0L || coveredMs < leadMs + overlapMs + trailMs)) {
                begin(target, next, outgoingMs, fadeMs, SeamAnchors(outgoingMs, 0L))
                return@CrossfadeIntroCapture
            }
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
                slot = intro.slot,
                outgoingEndUs = if (trailMs > 0L) anchors.outgoingEndMs * 1000L else C.TIME_UNSET,
                incomingStartFrame = (leadMs * intro.sampleRate / 1000L).toInt(),
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

    /** A seam at a given slider setting: moving the slider withdraws the plan and decodes anew. */
    private data class Seam(val outgoingId: String, val incomingId: String, val fadeMs: Long)

    private companion object {
        const val TICK_MS = 500L
    }
}

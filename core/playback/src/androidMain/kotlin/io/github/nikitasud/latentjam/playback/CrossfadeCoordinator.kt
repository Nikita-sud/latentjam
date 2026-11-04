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

    /**
     * Puts a track the player restarted at 0:00 after a sink error at its join back where its overlap had played it
     * to ([CrossfadeSettings.interruptedJoin]), rising as after a Next there. Only the same track, playing, soon after.
     */
    private fun resumeInterruptedJoin() {
        val join = settings.interruptedJoin ?: return
        if (System.nanoTime() - join.atNanos > INTERRUPTED_JOIN_TTL_NS) {
            settings.interruptedJoin = null
            return
        }
        val current = player.currentMediaItem ?: return
        if (current.mediaId != join.mediaId || !player.playWhenReady) return
        settings.interruptedJoin = null
        val heardMs = join.positionUs / 1000L
        if (player.currentPosition >= heardMs - RESUME_SLACK_MS) return
        settings.seamLog("seam -> ${join.mediaId}: restarted after its join; on at $heardMs ms")
        settings.pendingRise = PendingRise(join.mediaId, join.positionUs, CROSSFADE_CANCEL_MS * 1000L, fromUs = join.positionUs)
        player.seekTo(player.currentMediaItemIndex, heardMs)
    }

    /** Re-evaluates the seam ahead; also the periodic check while playing. */
    fun update() {
        if (released) return
        resumeInterruptedJoin()
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
        val rule = settings.fadeEntryRule
        val fade = current?.let { item -> rule?.let { settings.fadeEnds[item.mediaId] } }
        val wanted = if (fadeMs > 0L && !settings.finishingTrack && current != null &&
            next?.localConfiguration != null && settings.edgesAt(player.currentMediaItemIndex).fadeOut
        ) Seam(current.mediaId, next.mediaId, plannedFadeMs(seamMs), rule.takeIf { fade != null }) else null
        if (seam != wanted) {
            if (settings.mixing != null && settings.mixing === settings.plan) {
                settings.seamLog("seam ${seam?.outgoingId} -> ${seam?.incomingId}: withdrawn while sounding, for $wanted")
            }
            withdraw()
        }
        if (wanted == null || seam != null || wanted in failed) return
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val spans = settings.soundSpans
        val anchors = seamAnchors(durationMs, spans[wanted.outgoingId], spans[wanted.incomingId])
        // A fade entry reaches further back than the slider: its opening is decoded that much sooner.
        // The incoming track's length is not known yet, so this is the longest the entry can be.
        val entryMs = wanted.entryRule?.let { fadeEntryOverlapMs(fade, it, anchors.outgoingEndMs, Long.MAX_VALUE) } ?: 0L
        if (prerollsNow(player.currentPosition, anchors.outgoingEndMs, maxOf(fadeMs, entryMs))) {
            begin(wanted, next!!, outgoingMs = durationMs, fadeMs = maxOf(wanted.fadeMs, entryMs), anchors = anchors, fade = fade)
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
        return overlapLanding(plan, index, player.currentPosition, player.duration)
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
    private fun begin(
        target: Seam,
        next: MediaItem,
        outgoingMs: Long,
        fadeMs: Long,
        anchors: SeamAnchors,
        fade: FadeEnd? = null,
    ) {
        seam = target
        settings.preparingFor = target.outgoingId
        val anchored = anchors != SeamAnchors(outgoingMs, 0L)
        val trailMs = anchors.trailMs(outgoingMs)
        val leadMs = anchors.incomingStartMs
        val lengthMs = introCaptureMs(fadeMs + leadMs + trailMs)
        val scratch = IntroScratch.get(context)
        val slot = scratch.freeSlot(sounding = settings.soundingSlot)
        val pending = CrossfadeIntroCapture(context, next, lengthMs, scratch, slot, settings.outputRate) { intro ->
            capture = null
            if (seam != target) return@CrossfadeIntroCapture
            settings.preparingFor = null
            if (intro == null && fade != null && fadeMs > target.fadeMs) {
                // The longer opening a fade entry asks for did not decode (over the capture's cap at a
                // high sample rate): the seam is the crossfade it would have been.
                begin(target, next, outgoingMs, target.fadeMs, anchors)
                return@CrossfadeIntroCapture
            }
            // A fade entry where the outgoing track fades on its own; else the crossfade.
            val entryMs = intro?.let { opening ->
                target.entryRule?.let { fadeEntryOverlapMs(fade, it, anchors.outgoingEndMs, opening.durationMs - leadMs) }
            }
            val overlapMs = entryMs ?: intro?.let {
                overlapLengthMs(target.fadeMs, anchors.outgoingEndMs, it.durationMs - leadMs)
            } ?: 0L
            val coveredMs = intro?.let { it.frames * 1000L / it.sampleRate } ?: 0L
            if (anchored && (intro == null || overlapMs <= 0L || coveredMs < leadMs + overlapMs + trailMs)) {
                begin(target, next, outgoingMs, fadeMs, SeamAnchors(outgoingMs, 0L), fade)
                return@CrossfadeIntroCapture
            }
            if (intro == null || overlapMs <= 0L) {
                failed += target
                settings.seamLog("seam ${target.outgoingId} -> ${target.incomingId}: no opening (${if (intro == null) "capture failed" else "too short to overlap"})")
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
                holdsOutgoing = entryMs != null,
            )
        }
        capture = pending
        pending.start()
    }

    private fun withdraw() {
        capture?.cancel()
        capture = null
        seam = null
        settings.preparingFor = null
        settings.plan = null
    }

    /**
     * A seam at a given slider setting: moving the slider withdraws the plan and decodes anew. With
     * [entryRule], its outgoing track's end was measured and fade entries are on.
     */
    private data class Seam(val outgoingId: String, val incomingId: String, val fadeMs: Long, val entryRule: FadeEntryRule? = null)

    private companion object {
        const val TICK_MS = 500L

        /** How long a sink error's restart may take to show before an [CrossfadeSettings.interruptedJoin] lapses. */
        const val INTERRUPTED_JOIN_TTL_NS = 5_000_000_000L

        /** A restart that already landed within this of the heard point is left as it is. */
        const val RESUME_SLACK_MS = 500L
    }
}

/**
 * Where the incoming row [index] of [plan]'s overlap continues from, its outgoing track at [positionMs] of [durationMs]:
 * the point of it already heard, so its opening does not play twice. Null before the overlap's sound began, or while
 * the duration is unknown. Next during an overlap lands here, and so does the recovery from a playback error under one.
 */
internal fun overlapLanding(plan: OverlapPlan, index: Int, positionMs: Long, durationMs: Long): CrossfadeCoordinator.Landing? {
    if (durationMs == C.TIME_UNSET || durationMs <= 0L) return null
    val endMs = plan.outgoingEndUs.takeIf { it != C.TIME_UNSET }?.div(1000L)?.coerceAtMost(durationMs) ?: durationMs
    // The incoming opening began under the overlapUs before the outgoing track's last sound,
    // at its own first sound; the player's position is what has reached the speaker, so this
    // is the heard point, not one a buffer ahead of it.
    val heardMs = positionMs - (endMs - plan.overlapUs / 1000L)
    val fromMs = plan.incomingStartFrame * 1000L / plan.sampleRate
    return if (heardMs >= 0L) {
        CrossfadeCoordinator.Landing(index, fromMs + heardMs, plan.incomingId, plan.overlapUs, riseFromUs = fromMs * 1000L)
    } else {
        null
    }
}

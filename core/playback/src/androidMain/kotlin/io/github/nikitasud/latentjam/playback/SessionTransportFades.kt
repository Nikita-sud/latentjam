/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The service's pending pause, for the app's controller, the widget and the tile, kept like
 * [AndroidShuffleModeRegistry]. A pause fading out raises no player event and the session still says
 * playing until it lands; readers show it as paused at once. Main-thread only.
 */
internal object AndroidTransportFade {
    private val mutablePausePending = MutableStateFlow(false)

    val pausePending: StateFlow<Boolean> = mutablePausePending.asStateFlow()

    fun publish(pending: Boolean) {
        mutablePausePending.value = pending
    }
}

/**
 * Connection hint of the app's own MediaController. Media3's notification controller and the tile
 * share the app's package, so the package name cannot tell them apart.
 */
internal const val APP_CONTROLLER_HINT: String = "io.github.nikitasud.latentjam.APP_CONTROLLER"

/** A play this close to the start of its row is a start, at full level; further in, a resume. */
internal const val START_OF_ROW_MS: Long = 500L

/** How long an app Next at the tail waits for SMART's next row; a cold ranker plans within seconds. */
private const val NEXT_AT_TAIL_WAIT_MS: Long = 15_000L

/**
 * The pause, resume and manual-change fades for every source, owned by the service. See
 * `docs/service-side-fades-plan.md`.
 *
 * [player] is the ExoPlayer itself: actions reach it directly, never the session player's
 * overrides. A command that may wait for its fade is *deferrable*; one from the app's own
 * controller, or from nobody, is not, because a masking controller would flicker back for the
 * length of the fade (the app asks for a deferred change through an ordered op instead). Every
 * command that runs at once runs before its call returns: Media3 calls the session player in
 * sequences that expect each call to have taken effect.
 *
 * Main thread only. [scope] must run on it ([kotlinx.coroutines.Dispatchers.Main.immediate]).
 */
internal class SessionTransportFades(
    private val player: Player,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    /** Next during an overlap: land on the point of the next track already heard, if there is one. */
    private val landNext: () -> Boolean = { false },
    private val announce: (StartCause, Int) -> Unit = { cause, index ->
        AndroidPlaybackStarts.announce(cause, TrackId(player.getMediaItemAt(index).mediaId))
    },
    private val publishPausePending: (Boolean) -> Unit = AndroidTransportFade::publish,
) {
    private val fade = TransportFadeState()
    private val changes = ManualChangeFades(
        fade = fade,
        scope = scope,
        nowMs = nowMs,
        playing = ::sounding,
        sounding = { player.isPlaying },
        onGain = ::updateGainLoop,
    )
    private var pendingPause: Job? = null
    private var gainJob: Job? = null

    /** An automatic advance landed while the changes waited for their silence. */
    private var autoAdvanced = false

    /**
     * The app's Next found no row after the playhead: the change before it (a pick of the last row,
     * an earlier Next) got there after the app looked. As the app's own Next at the tail does, it
     * waits for the row planned next — the app plans when it sees the new row — and goes there when
     * it arrives. Any other command, a pause or a transition drops it, and so does a row that comes
     * only after [NEXT_AT_TAIL_WAIT_MS] (SMART had nothing; a song queued by hand later must not be
     * skipped to).
     */
    private var nextAtTail: (() -> Unit)? = null
    private var nextAtTailUntilMs = 0L

    /** What the controllers set; the ExoPlayer plays at this times the fade's gain. */
    var baseVolume: Float = 1f
        private set
    private var unmutedVolume = 1f

    val pausePending: Boolean get() = fade.pausePending

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) fade.onPlaybackStarted(nowMs())
            updateGainLoop()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Whoever paused — this envelope, focus loss, becoming noisy — the transport settled.
            if (!playWhenReady) reset()
            updateGainLoop()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            nextAtTail = null
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && changes.pending) autoAdvanced = true
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val waiting = nextAtTail ?: return
            if (nowMs() > nextAtTailUntilMs) {
                nextAtTail = null
                return
            }
            if (!player.hasNextMediaItem()) return
            nextAtTail = null
            next(deferrable = true, appOp = true, plain = waiting)
        }
    }

    init {
        player.addListener(listener)
    }

    fun release() {
        player.removeListener(listener)
        pendingPause?.cancel()
        pendingPause = null
        gainJob?.cancel()
        gainJob = null
        publishPausePending(false)
    }

    /** The music is sounding and no pause is on its way. */
    private fun sounding(): Boolean = player.isPlaying && !fade.pausePending

    fun pause(deferrable: Boolean) {
        nextAtTail = null
        if (fade.pausePending) {
            // A second pause from a car or a notification must not cut the fade short.
            if (deferrable) return
            pendingPause?.cancel()
            pendingPause = null
        }
        runNow { changes.flush() }
        if (!deferrable || !player.isPlaying) {
            player.pause()
            reset()
            updateGainLoop()
            return
        }
        fade.beginPause(nowMs())
        publishPausePending(true)
        updateGainLoop()
        pendingPause = scope.launch {
            delay(TRANSPORT_FADE_OUT_MS)
            // Install silence before stopping; the last gain tick may have been delayed.
            applyVolume()
            pendingPause = null
            player.pause()
            reset()
            updateGainLoop()
        }
    }

    fun play() {
        // A redundant PLAY from a head unit or a voice request must not dip the music.
        if (player.playWhenReady && !fade.pausePending) {
            player.play()
            return
        }
        runNow { changes.flush() }
        when {
            fade.pausePending -> {
                pendingPause?.cancel()
                pendingPause = null
                fade.beginResume(nowMs(), startImmediately = player.isPlaying)
                publishPausePending(false)
            }
            // A new queue, a pick, a restart: the opening plays as it is.
            player.currentPosition <= START_OF_ROW_MS -> fade.reset()
            // A paused row resumed rises once playback actually runs.
            else -> fade.beginResume(nowMs(), startImmediately = player.isPlaying)
        }
        applyVolume()
        player.play()
        updateGainLoop()
    }

    fun setPlayWhenReady(playWhenReady: Boolean, deferrable: Boolean) {
        if (playWhenReady) play() else pause(deferrable)
    }

    /** Next. [appOp]: the app asked; history is told here, at the change. */
    fun next(deferrable: Boolean, appOp: Boolean = false, plain: () -> Unit) {
        nextAtTail = null
        val action = action@{
            if (appOp && !player.hasNextMediaItem()) {
                nextAtTail = plain
                nextAtTailUntilMs = nowMs() + NEXT_AT_TAIL_WAIT_MS
                return@action
            }
            if (appOp) player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { announce(StartCause.SKIP_NEXT, it) }
            if (!landNext()) plain()
            if (appOp) prepareIfIdle()
        }
        // Nowhere to go: no dip for a Next that does nothing.
        if (!changes.pending && !player.hasNextMediaItem()) {
            action()
            return
        }
        change(ManualChange.TRACK, deferrable, action = action)
    }

    /**
     * Previous: to the row before near the start of a track, else a restart. The first change of a
     * batch decides at the press, so the fade-out cannot push it past the threshold, and becomes a
     * pick by mediaId; later ones read the player as the changes before them left it.
     */
    fun previous(deferrable: Boolean, appOp: Boolean = false) {
        if (deferrable && !changes.pending && sounding()) {
            val toRowBefore = player.hasPreviousMediaItem() &&
                player.currentPosition <= player.maxSeekToPreviousPosition
            val index = if (toRowBefore) player.previousMediaItemIndex else player.currentMediaItemIndex
            val mediaId = player.mediaIdAt(index)
            if (mediaId != null) {
                change(
                    if (toRowBefore) ManualChange.TRACK else ManualChange.SEEK,
                    deferrable = true,
                    survivesAutoAdvance = true,
                ) {
                    val target = player.indexNear(mediaId, index) ?: return@change
                    if (appOp && toRowBefore) announce(StartCause.SKIP_PREVIOUS, target)
                    player.seekTo(target, 0L)
                    if (appOp) prepareIfIdle()
                }
                return
            }
        }
        change(ManualChange.TRACK, deferrable) {
            if (appOp && player.hasPreviousMediaItem() &&
                player.currentPosition <= player.maxSeekToPreviousPosition
            ) {
                announce(StartCause.SKIP_PREVIOUS, player.previousMediaItemIndex)
            }
            player.seekToPrevious()
            if (appOp) prepareIfIdle()
        }
    }

    /** Another row: resolved by its mediaId at the change, so a queue edit meanwhile cannot shift it. */
    fun pick(
        index: Int,
        positionMs: Long,
        deferrable: Boolean,
        mediaId: String? = player.mediaIdAt(index),
        appOp: Boolean = false,
    ) {
        change(ManualChange.TRACK, deferrable, survivesAutoAdvance = true) {
            val target = if (mediaId == null) {
                index.takeIf { it in 0 until player.mediaItemCount }
            } else {
                player.indexNear(mediaId, index)
            } ?: return@change
            if (appOp) announce(StartCause.USER_PICK, target)
            if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition(target) else player.seekTo(target, positionMs)
            if (appOp) {
                prepareIfIdle()
                // Paused meanwhile by the system (becoming noisy, focus loss): the row is picked
                // but stays paused; a pause from a controller ran this pick before it.
                if (player.playWhenReady) play()
            }
        }
    }

    /** Another row by Media3's own traversal, e.g. Previous straight to the row before. */
    fun track(deferrable: Boolean, action: () -> Unit) = change(ManualChange.TRACK, deferrable, action = action)

    /** A move within the row. */
    fun seek(deferrable: Boolean, action: () -> Unit) = change(ManualChange.SEEK, deferrable, action = action)

    /**
     * A queue is installed or playback stops: what was asked before happens first, a pause on its
     * way lands now (the new queue must not start by itself), and the envelope starts over.
     */
    fun replaceQueue() {
        runNow { changes.flush() }
        if (fade.pausePending) {
            pendingPause?.cancel()
            pendingPause = null
            player.pause()
        }
        reset()
        updateGainLoop()
    }

    fun setVolume(volume: Float) {
        baseVolume = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f
        applyVolume()
    }

    fun mute() {
        if (baseVolume > 0f) unmutedVolume = baseVolume
        setVolume(0f)
    }

    fun unmute() = setVolume(unmutedVolume)

    private fun change(
        kind: ManualChange,
        deferrable: Boolean,
        survivesAutoAdvance: Boolean = false,
        action: () -> Unit,
    ) {
        nextAtTail = null
        if (!deferrable) {
            // Never waits, but what was asked before it happens first and the sound comes back.
            runNow { changes.settle() }
            action()
            return
        }
        if (!changes.pending) autoAdvanced = false
        // After an automatic advance the listener already has a new track; a skip or seek meant for
        // the old one would land in the wrong song. A pick names its row and still runs.
        runNow { changes.run(kind) { if (survivesAutoAdvance || !autoAdvanced) action() } }
    }

    private fun prepareIfIdle() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    private fun reset() {
        nextAtTail = null
        pendingPause?.cancel()
        pendingPause = null
        fade.reset()
        publishPausePending(false)
    }

    private fun applyVolume() {
        val volume = (baseVolume * fade.gainAt(nowMs())).coerceIn(0f, 1f)
        if (player.volume != volume) player.volume = volume
    }

    /** Samples the envelope at 20 ms while it moves; idle otherwise. */
    private fun updateGainLoop() {
        gainJob?.cancel()
        gainJob = null
        applyVolume()
        if (!player.isPlaying || !fade.active) return
        gainJob = scope.launch {
            while (isActive && fade.active) {
                applyVolume()
                delay(GAIN_RAMP_TICK_MS)
            }
            applyVolume()
        }
    }

    /** Runs [block] before returning when it does not suspend, as every at-once path here does. */
    private fun runNow(block: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
    }
}

private fun Player.mediaIdAt(index: Int): String? =
    index.takeIf { it in 0 until mediaItemCount }?.let { getMediaItemAt(it).mediaId }

/** The row holding [mediaId] closest to [index], or null when it left the queue. */
private fun Player.indexNear(mediaId: String, index: Int): Int? {
    val count = mediaItemCount
    for (distance in 0 until count) {
        val before = index - distance
        if (before in 0 until count && getMediaItemAt(before).mediaId == mediaId) return before
        val after = index + distance
        if (after in 0 until count && getMediaItemAt(after).mediaId == mediaId) return after
    }
    return null
}

/**
 * The session's player: every transport command goes through [fades], so a pause, a resume, a skip
 * or a seek from the notification, a headset, a car or the tile fades like the app's own.
 * [deferrable] says whether the command now being handled may wait for its fade (see
 * [SessionTransportFades]).
 */
internal open class TransportFadingPlayer(
    player: Player,
    private val fades: SessionTransportFades,
    private val deferrable: () -> Boolean,
) : LoadedQueueKeeper(player) {

    override fun play() = fades.play()

    override fun pause() = fades.pause(deferrable())

    override fun setPlayWhenReady(playWhenReady: Boolean) =
        fades.setPlayWhenReady(playWhenReady, deferrable())

    override fun seekToNext() = fades.next(deferrable()) { wrappedPlayer.seekToNext() }

    override fun seekToNextMediaItem() = fades.next(deferrable()) { wrappedPlayer.seekToNextMediaItem() }

    override fun seekToPrevious() = fades.previous(deferrable())

    override fun seekToPreviousMediaItem() = fades.track(deferrable()) { wrappedPlayer.seekToPreviousMediaItem() }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) =
        fades.pick(mediaItemIndex, positionMs, deferrable())

    override fun seekToDefaultPosition(mediaItemIndex: Int) =
        fades.pick(mediaItemIndex, C.TIME_UNSET, deferrable())

    override fun seekTo(positionMs: Long) = fades.seek(deferrable()) { wrappedPlayer.seekTo(positionMs) }

    override fun seekToDefaultPosition() = fades.seek(deferrable()) { wrappedPlayer.seekToDefaultPosition() }

    override fun seekBack() = fades.seek(deferrable()) { wrappedPlayer.seekBack() }

    override fun seekForward() = fades.seek(deferrable()) { wrappedPlayer.seekForward() }

    override fun stop() {
        fades.replaceQueue()
        super.stop()
    }

    override fun onReplaceQueue() = fades.replaceQueue()

    override fun getVolume(): Float = fades.baseVolume

    override fun setVolume(volume: Float) = fades.setVolume(volume)

    override fun mute() = fades.mute()

    override fun unmute() = fades.unmute()
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.HistorySessionTracker
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListenStartSignal
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PlaybackStart
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Bridges playback to the listening history — the single place the two meet
 * (mirror of [EngineNextTrackChooser] on the engine side). Collects the
 * now-playing snapshots, lets the pure [HistorySessionTracker] detect track
 * transitions, and records the finished sessions.
 *
 * The ticker inside the playback controller refreshes positions ~2×/s while
 * playing, which keeps the tracker's furthest-position measurement honest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun CoroutineScope.launchPlaybackHistoryRecorder(
    playback: PlaybackController,
    history: ListeningHistory,
    enabled: StateFlow<Boolean>,
    /** Each emission asks the gate to finalize the in-progress session (see [PlaybackHistoryGate.flush]). */
    flushRequests: Flow<Unit> = emptyFlow(),
    /** Each emission drops the in-progress session unrecorded: the history was cleared (see [PlaybackHistoryGate.forget]). */
    forgetRequests: Flow<Unit> = emptyFlow(),
    /**
     * Stable id of the collection the current queue was started from, or null. Sampled with each
     * snapshot rather than combined into the stream: an extra snapshot for a label change would
     * look like a stalled playhead and shave listening time off the session.
     */
    parentId: () -> String? = { null },
    onRecorded: () -> Unit = {},
    /** Each snapshot while history is on (SMART's impression log follows the queue and the playhead). */
    onSnapshot: suspend (NowPlaying) -> Unit = {},
    /** Each listen once recorded, and whether a pause closed it (it may then go on later as a second listen). */
    onListen: suspend (event: ListenEvent, endedByPause: Boolean) -> Unit = { _, _ -> },
    /**
     * Whether a pause now ends the sitting: the app is not on screen, so nothing would ask for a flush when the
     * listener walks away. A pause from a headset, the notification, a widget, the sleep timer, or the queue running
     * out then closes the listen itself; without it the last track of such a sitting was written only if the app
     * was opened again before the process was reclaimed.
     */
    sittingEndsAtPause: () -> Boolean = { false },
    /** Adds what the player cannot see to a finished listen before it is recorded (PersonalSignals). */
    annotate: (ListenEvent) -> ListenEvent = { it },
    /** Wall time, read when a snapshot or a flush is emitted. */
    clock: () -> Long = ::epochMillis,
): Job = launch {
    val gate = PlaybackHistoryGate(initiallyEnabled = enabled.value)
    // One merged stream, one collector: snapshots and flushes are serialized through the same
    // coroutine, so the gate needs no synchronization.
    merge(
        playback.state.combine(enabled) { now, isEnabled ->
            // The time is read here, with the position it belongs to. Read when the snapshot was processed, the
            // snapshots that had waited behind a slow write (the log being loaded, an import holding it) all got
            // one time: the tracker counts no more listening than wall time allows, and counted none for them.
            HistoryCommand.Snapshot(now, isEnabled, parentId(), clock())
        },
        flushRequests.map { HistoryCommand.Flush(clock()) },
        forgetRequests.map { HistoryCommand.Forget(playback.state.value) },
        playback.state.map { it.isPlaying }.distinctUntilChanged().transformLatest<Boolean, HistoryCommand> { playing ->
            if (playing) return@transformLatest
            // A pause taken back within a moment (a call that is declined, a wrong press) is not an end.
            delay(PAUSE_SETTLE_MS)
            if (sittingEndsAtPause()) emit(HistoryCommand.Flush(clock()))
        },
    ).collect { command ->
        if (command is HistoryCommand.Snapshot && command.enabled) runCatching { onSnapshot(command.now) }
        val finished = when (command) {
            is HistoryCommand.Snapshot ->
                gate.onSnapshot(command.now, command.enabled, command.atMs, command.parentId)
            is HistoryCommand.Flush ->
                // A flush while sound is still playing is premature: the foreground service
                // keeps the process alive and the eventual transition records the session
                // with its full played time. Backgrounding while PAUSED is the real signal
                // that the sitting is over.
                if (playback.state.value.isPlaying) null else gate.flush(command.nowMs)
            is HistoryCommand.Forget -> {
                gate.forget(command.now)
                null
            }
        }
        finished?.let(annotate)?.let { event ->
            try {
                history.record(event)
                onRecorded()
                runCatching { onListen(event, command is HistoryCommand.Flush) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // This is the one process-lifetime history collector. A transient private-storage
                // failure may cost this finished session, but must not cancel the collector and
                // silently discard every later session until the app is restarted. Keep consuming
                // playback snapshots so the next transition gets an independent durable attempt.
                println(
                    "Listening history append failed; recording will continue: " +
                        (failure.message ?: failure::class.simpleName.orEmpty()),
                )
            }
        }
    }
}

/**
 * Privacy boundary between the always-live player state and the durable listening log.
 *
 * Disabling history drops the in-progress session immediately. Re-enabling during a track waits for
 * the next track instead of treating the existing absolute playhead position as newly listened time.
 * This makes the switch honest even when it is changed with music already playing.
 */
internal class PlaybackHistoryGate(initiallyEnabled: Boolean) {

    private var recording = initiallyEnabled
    private var tracker = HistorySessionTracker()
    private var ignoredTrackAfterEnabling: TrackId? = null

    fun onSnapshot(now: NowPlaying, enabled: Boolean, nowMs: Long, parentId: String? = null) = when {
        !enabled -> {
            if (recording) tracker = HistorySessionTracker()
            recording = false
            ignoredTrackAfterEnabling = null
            null
        }

        !recording -> {
            recording = true
            tracker = HistorySessionTracker()
            // A null current track arms the tracker immediately; otherwise begin with the next one.
            ignoredTrackAfterEnabling = now.track?.id
            null
        }

        ignoredTrackAfterEnabling != null && ignoredTrackAfterEnabling == now.track?.id -> null

        else -> {
            ignoredTrackAfterEnabling = null
            val trackId = now.track?.id
            tracker.onSnapshot(
                trackId = trackId,
                positionMs = now.positionMs,
                trackDurationMs = now.durationMs,
                currentShuffleMode = now.shuffleMode.name,
                nowMs = nowMs,
                isPlaying = now.isPlaying,
                start = now.playbackStart?.toListenStartSignal(),
                smartPlanPosition = trackId?.let(now.smartPlanPositions::get),
                parentId = parentId,
            )
        }
    }

    /**
     * Finalizes the in-progress session without waiting for a track transition — the
     * backgrounding hook. Without it the log only gains a session when playback moves OFF a
     * track, so the last track of every sitting was silently dropped when the app was killed.
     *
     * Honors the privacy boundary exactly as [onSnapshot] does: nothing is recorded while
     * recording is off, and a track that is being ignored until the next one stays ignored —
     * the tracker simply has no session open for it.
     */
    fun flush(nowMs: Long? = null): ListenEvent? {
        if (!recording) return null
        return tracker.flush(nowMs)
    }

    /**
     * The listening history was cleared: the session in progress began before that, and is not written back into the
     * emptied log when its track ends. Recording goes on with the next track, as after the switch is turned on.
     */
    fun forget(now: NowPlaying) {
        if (!recording) return
        tracker = HistorySessionTracker()
        ignoredTrackAfterEnabling = now.track?.id
    }
}

/** The log's own words for how a play began: history does not depend on playback. */
private fun PlaybackStart.toListenStartSignal() = ListenStartSignal(
    sequence = sequence,
    trackId = trackId,
    start = when (cause) {
        StartCause.USER_PICK -> ListenStart.USER_PICK
        StartCause.AUTO_ADVANCE -> ListenStart.AUTO_ADVANCE
        StartCause.SKIP_NEXT -> ListenStart.SKIP_NEXT
        StartCause.SKIP_PREVIOUS -> ListenStart.SKIP_PREVIOUS
        StartCause.REPEAT -> ListenStart.REPEAT
        StartCause.QUEUED -> ListenStart.QUEUED
        null -> null
    },
)

/** How long playback must stay paused before the pause may close the listen. */
private const val PAUSE_SETTLE_MS = 1_000L

/** The recorder's single-collector input alphabet: a playback snapshot or a flush request. */
private sealed interface HistoryCommand {
    data class Snapshot(val now: NowPlaying, val enabled: Boolean, val parentId: String?, val atMs: Long) : HistoryCommand
    data class Flush(val nowMs: Long) : HistoryCommand
    data class Forget(val now: NowPlaying) : HistoryCommand
}

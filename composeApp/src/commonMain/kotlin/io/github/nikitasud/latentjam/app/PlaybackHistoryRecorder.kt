/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.HistorySessionTracker
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
fun CoroutineScope.launchPlaybackHistoryRecorder(
    playback: PlaybackController,
    history: ListeningHistory,
    enabled: StateFlow<Boolean>,
    /** Each emission asks the gate to finalize the in-progress session (see [PlaybackHistoryGate.flush]). */
    flushRequests: Flow<Unit> = emptyFlow(),
    onRecorded: () -> Unit = {},
): Job = launch {
    val gate = PlaybackHistoryGate(initiallyEnabled = enabled.value)
    // One merged stream, one collector: snapshots and flushes are serialized through the same
    // coroutine, so the gate needs no synchronization.
    merge(
        playback.state.combine(enabled) { now, isEnabled -> HistoryCommand.Snapshot(now, isEnabled) },
        flushRequests.map { HistoryCommand.Flush },
    ).collect { command ->
        val finished = when (command) {
            is HistoryCommand.Snapshot ->
                gate.onSnapshot(command.now, command.enabled, epochMillis())
            HistoryCommand.Flush ->
                // A flush while sound is still playing is premature: the foreground service
                // keeps the process alive and the eventual transition records the session
                // with its full played time. Backgrounding while PAUSED is the real signal
                // that the sitting is over.
                if (playback.state.value.isPlaying) null else gate.flush()
        }
        finished?.let { event ->
            try {
                history.record(event)
                onRecorded()
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

    fun onSnapshot(now: NowPlaying, enabled: Boolean, nowMs: Long) = when {
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

        ignoredTrackAfterEnabling == now.track?.id -> null

        else -> {
            ignoredTrackAfterEnabling = null
            tracker.onSnapshot(
                trackId = now.track?.id,
                positionMs = now.positionMs,
                trackDurationMs = now.durationMs,
                currentShuffleMode = now.shuffleMode.name,
                nowMs = nowMs,
                isPlaying = now.isPlaying,
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
    fun flush(): ListenEvent? {
        if (!recording) return null
        return tracker.flush()
    }
}

/** The recorder's single-collector input alphabet: a playback snapshot or a flush request. */
private sealed interface HistoryCommand {
    data class Snapshot(val now: NowPlaying, val enabled: Boolean) : HistoryCommand
    data object Flush : HistoryCommand
}

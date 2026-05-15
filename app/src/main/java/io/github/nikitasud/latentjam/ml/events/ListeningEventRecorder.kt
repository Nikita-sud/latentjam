/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.events

import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.data.LikedSongRepository
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import io.github.nikitasud.latentjam.ml.data.ListeningEventEntity
import io.github.nikitasud.latentjam.ml.session.SessionTracker
import io.github.nikitasud.latentjam.playback.state.PlaybackStateManager
import io.github.nikitasud.latentjam.playback.state.Progression
import io.github.nikitasud.latentjam.playback.state.ShuffleMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber

/**
 * Persists one row per finalized track listen so the predictor's on-device retrain pass has
 * fresh ground truth. Listens to [PlaybackStateManager] events directly — no UI dependency,
 * runs even when the app is in the background as long as the playback service is alive.
 *
 * Privacy gate: every write is gated on [MlSettings.enableEventLogging]; if the user hasn't
 * opted in, we still update the in-memory [SessionTracker] (so feature snapshots stay correct
 * for any concurrent recommendation work) but we do NOT touch Room.
 */
@Singleton
class ListeningEventRecorder @Inject constructor(
    private val playbackStateManager: PlaybackStateManager,
    private val sessionTracker: SessionTracker,
    private val listeningEventDao: ListeningEventDao,
    private val mlSettings: MlSettings,
    private val smartSessionLog: io.github.nikitasud.latentjam.ml.predictor.SmartSessionLog,
    private val likedSongRepository: LikedSongRepository,
) : PlaybackStateManager.Listener {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentSong: Song? = null
    private var currentParent: MusicParent? = null
    private var currentTrack: SessionTracker.TrackContext? = null
    private var lastObservedPositionMs: Long = 0L
    /**
     * True iff the currently-tracked track started via `onNewPlayback` (user
     * tapped a song / opened a parent) rather than `onIndexMoved` (queue
     * advance). Drives the `wasUserStarted` column on the event we write when
     * this track finalizes.
     */
    private var currentWasUserStarted: Boolean = false

    /** Last 4 completed plays in current session, newest first. */
    private val sessionContext: ArrayDeque<Music.UID> = ArrayDeque()

    fun attach() {
        playbackStateManager.addListener(this)
        Timber.d("ListeningEventRecorder attached")
    }

    fun detach() {
        playbackStateManager.removeListener(this)
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        shuffleMode: ShuffleMode,
    ) {
        finalizeCurrent(reason = FinalizeReason.NEW_PLAYBACK)
        currentParent = parent
        val song = queue.getOrNull(index) ?: return
        startTracking(song, userStarted = true)
    }

    override fun onIndexMoved(index: Int) {
        finalizeCurrent(reason = FinalizeReason.INDEX_MOVED)
        val song = playbackStateManager.queue.getOrNull(index) ?: return
        startTracking(song, userStarted = false)
    }

    override fun onProgressionChanged(progression: Progression) {
        // We can't read playback position directly from PlaybackStateManager — it lives
        // in the Progression. Cache the latest known position so finalize() can compute
        // played_ms accurately when the index moves.
        lastObservedPositionMs = progression.calculateElapsedPositionMs()
    }

    override fun onSessionEnded() {
        finalizeCurrent(reason = FinalizeReason.SESSION_END)
        sessionTracker.onSessionEnded()
    }

    private fun startTracking(song: Song, userStarted: Boolean) {
        val nowMs = System.currentTimeMillis()
        currentSong = song
        currentTrack = sessionTracker.onTrackStart(nowMs)
        lastObservedPositionMs = 0L
        currentWasUserStarted = userStarted
    }

    private fun finalizeCurrent(reason: FinalizeReason) {
        val song = currentSong ?: return
        val track = currentTrack ?: return
        val nowMs = System.currentTimeMillis()
        val durationMs = song.durationMs.coerceAtLeast(0L)
        val playedMs = lastObservedPositionMs.coerceAtLeast(0L).coerceAtMost(durationMs)
        val completed = durationMs > 0 && playedMs >= (durationMs * 9 / 10)
        val skipped = !completed
        val shuffleMode = playbackStateManager.shuffleMode
        val wasSmartPick = shuffleMode == ShuffleMode.SMART
        // Derive finalize reason. SESSION_END is explicit. Otherwise distinguish a
        // natural track-end (within 2 s of duration) from a user-initiated move-off:
        // - INDEX_MOVED within the same queue → either auto-advance (track ended)
        //   or user tapped Next. Use position to disambiguate.
        // - NEW_PLAYBACK → user started fresh playback (different parent / search /
        //   etc.). Always counts as a context shift, never natural completion.
        val finalizeReason = when (reason) {
            FinalizeReason.SESSION_END -> "SESSION_END"
            FinalizeReason.NEW_PLAYBACK -> "NEW_PLAYBACK"
            FinalizeReason.INDEX_MOVED -> {
                val nearEnd = durationMs > 0 && (durationMs - playedMs) <= NATURAL_END_TOLERANCE_MS
                if (nearEnd) "TRACK_ENDED" else "USER_SKIPPED"
            }
        }
        val event = ListeningEventEntity(
            songUid = song.uid,
            startedAtMs = track.startedAtMs,
            endedAtMs = nowMs,
            playedMs = playedMs,
            trackDurationMs = durationMs,
            completed = completed,
            skipped = skipped,
            sessionId = track.sessionId,
            sessionPos = track.sessionPos,
            parentUid = currentParent?.uid,
            ctxUid0 = sessionContext.getOrNull(0),
            ctxUid1 = sessionContext.getOrNull(1),
            ctxUid2 = sessionContext.getOrNull(2),
            ctxUid3 = sessionContext.getOrNull(3),
            wasSmartPick = wasSmartPick,
            finalizeReason = finalizeReason,
            shuffleMode = shuffleMode.name,
            repeatMode = playbackStateManager.repeatMode.name,
            wasUserStarted = currentWasUserStarted,
            liked = likedSongRepository.isLikedNow(song.uid),
        )
        sessionTracker.onTrackFinalized(event)
        if (completed) {
            sessionContext.addFirst(song.uid)
            while (sessionContext.size > CONTEXT_K) sessionContext.removeLast()
        }

        // Debug-side outcome log. Writes to app-private external files dir so we
        // can `adb pull` SMART sessions for review without relying on the user's
        // `enableEventLogging` opt-in. Privacy: same files dir Room already uses
        // for the cache, deletable via the app's normal storage management.
        smartSessionLog.recordOutcome(
            event = event,
            songName = song.name.toString().takeIf { it.isNotBlank() },
            songArtists = song.artists.joinToString(", ") { it.name.toString() }
                .takeIf { it.isNotBlank() },
        )

        // Reset state regardless of whether we persist; the in-memory bookkeeping must
        // advance even when event logging is opted out of.
        currentSong = null
        currentTrack = null
        lastObservedPositionMs = 0L
        currentWasUserStarted = false

        if (!mlSettings.enableEventLogging) {
            Timber.v("event-log opt-out: dropping %s (reason=%s)", song.uid, reason)
            return
        }
        ioScope.launch {
            try {
                listeningEventDao.insert(event)
            } catch (e: Exception) {
                Timber.w(e, "failed to insert listening event")
            }
        }
    }

    private enum class FinalizeReason { INDEX_MOVED, NEW_PLAYBACK, SESSION_END }

    companion object {
        const val CONTEXT_K = 4
        /** Within this much of duration counts as "track ended naturally". */
        const val NATURAL_END_TOLERANCE_MS = 2_000L
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.SmartImpression
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.history.SmartOutcome
import io.github.nikitasud.latentjam.history.SmartOutcomeTracker
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One planned row as the chooser hands it to the impression log. */
class SmartPlanRow(
    val trackId: TrackId,
    /** [io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentHash] of the track's content key. */
    val trackKey: String,
    val score: Double,
    val probability: Double,
    val tie: Boolean,
    val source: String,
)

/** What [EngineNextTrackChooser] tells the impression log: each plan's rows, and each row it serves. */
interface SmartPlanLog {
    /** A plan of queue [queue] ([plan] = its index in the queue); the first row sits [rankOffset] + 1 rows after the queue's start. */
    suspend fun planned(queue: String, plan: Int, rankOffset: Int, model: String, rows: List<SmartPlanRow>)

    suspend fun served(id: TrackId)
}

/**
 * Bridges the chooser and playback to [SmartImpressionLog]: impressions from the chooser's plans, outcomes from
 * [SmartOutcomeTracker] fed with the history recorder's snapshots and finished listens — the same service-side
 * playback state the v4 origin logging classifies from, never a MediaController's view.
 *
 * Follows the "save listening history" setting ([enabled]): with it off nothing is logged, as nothing is played into the
 * history either.
 *
 * Callers never wait on storage: the tracker's bookkeeping is in memory, and every write goes through one queue
 * drained by one coroutine in [scope] (oldest writes dropped if it ever backs up past [WRITE_BUFFER]).
 */
class SmartLogRecorder(
    private val log: SmartImpressionLog,
    scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = ::epochMillis,
) : SmartPlanLog {
    private val mutex = Mutex()
    private val tracker = SmartOutcomeTracker()
    private val writes = Channel<suspend () -> Unit>(WRITE_BUFFER, BufferOverflow.DROP_OLDEST)
    private var lastStart: Long? = null
    private var queueRef: List<TrackDescriptor>? = null
    private var queueIds: Set<TrackId> = emptySet()

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The log is best effort; SmartImpressionLog already reports its own failures.
                }
            }
        }
    }

    override suspend fun planned(queue: String, plan: Int, rankOffset: Int, model: String, rows: List<SmartPlanRow>) {
        if (rows.isEmpty() || !enabled()) return
        val at = clock()
        val impressions = rows.mapIndexed { i, r ->
            r.trackId to SmartImpression(at, queue, plan, i + 1, r.trackKey, rankOffset + i + 1, r.score, r.probability, r.tie, r.source, model)
        }
        val outcomes = mutex.withLock { tracker.onPlan(impressions, at) }
        val lines = impressions.map { it.second }
        writes.trySend {
            if (outcomes.isNotEmpty()) log.recordOutcomes(outcomes)
            log.recordImpressions(lines)
        }
    }

    override suspend fun served(id: TrackId): Unit = mutex.withLock { tracker.onServed(id) }

    /** Every playback snapshot the history recorder sees (~2 per second while playing). */
    suspend fun onNowPlaying(now: NowPlaying) {
        val outcomes = mutex.withLock {
            val start = now.playbackStart
            val userPick = start != null && start.sequence != lastStart && start.cause == StartCause.USER_PICK &&
                start.trackId == now.track?.id
            lastStart = start?.sequence
            if (tracker.openRows == 0 || !enabled()) return
            // Android reuses one immutable queue snapshot between edits: the id set is rebuilt only when it changes.
            if (now.queue !== queueRef) {
                queueRef = now.queue
                queueIds = now.queue.mapTo(HashSet(now.queue.size * 2)) { it.id }
            }
            tracker.onSnapshot(now.track?.id, queueIds, userPick, clock())
        }
        emit(outcomes)
    }

    /** A listen the history recorder just recorded. */
    suspend fun onListen(event: ListenEvent) {
        emit(mutex.withLock { tracker.onListen(event, clock()) })
    }

    private fun emit(outcomes: List<SmartOutcome>) {
        if (outcomes.isNotEmpty()) writes.trySend { log.recordOutcomes(outcomes) }
    }

    private companion object {
        const val WRITE_BUFFER = 256
    }
}

/**
 * The impression log as one text for the debug export (product checks only): a header naming the format, then every
 * line, oldest first. Called off the main thread by the export's [LocalBackupFileExchange]; the log is capped
 * ([SmartImpressionLog.DEFAULT_CAP_BYTES]), so the text is bounded too.
 */
internal fun exportSmartLogText(log: SmartImpressionLog): String = kotlinx.coroutines.runBlocking {
    buildString {
        append("# LatentJam SMART impression log; si1 = offered track, so1 = its outcome (see SmartImpressionLog.kt)\n")
        log.forEachLine { append(it).append('\n') }
    }
}

/** What a favourites change added and removed, in [after]'s / [before]'s order. */
internal fun favoriteChanges(before: Collection<TrackId>, after: Collection<TrackId>): Pair<List<TrackId>, List<TrackId>> {
    val was = before.toHashSet()
    val now = after.toHashSet()
    return after.filter { it !in was }.distinct() to before.filter { it !in now }.distinct()
}

/**
 * Follows the favourites through every path that changes them (the heart, the notification, bulk edits, duplicate
 * merges, a backup restore): each durable change is diffed against the last list seen, the heart times are kept in
 * [times] (FULL's favourited_days_ago), and the hearts go to SMART's impression log while [logEnabled] (the history
 * setting). The first list seen is the baseline: it records nothing, and drops times of tracks no longer hearted.
 */
fun CoroutineScope.launchFavoriteRecorder(
    favorites: io.github.nikitasud.latentjam.history.Favorites,
    times: io.github.nikitasud.latentjam.history.FavoriteTimes,
    log: SmartImpressionLog?,
    logEnabled: () -> Boolean,
    trackKey: (List<TrackId>) -> Map<TrackId, String>,
    clock: () -> Long = ::epochMillis,
): kotlinx.coroutines.Job = launch {
    var previous: List<TrackId>? = null
    favorites.revision.collect {
        val current = try {
            favorites.all()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@collect
        }
        val before = previous
        previous = current
        if (before == null) {
            val stale = times.all().keys - current.toHashSet()
            if (stale.isNotEmpty()) times.update(emptyList(), stale, clock())
            return@collect
        }
        val (added, removed) = favoriteChanges(before, current)
        if (added.isEmpty() && removed.isEmpty()) return@collect
        val at = clock()
        times.update(added, removed, at)
        if (log != null && logEnabled()) {
            val keys = trackKey(added + removed)
            log.recordFavorites(
                added.map { io.github.nikitasud.latentjam.history.SmartFavoriteEvent(at, keys[it].orEmpty(), true) } +
                    removed.map { io.github.nikitasud.latentjam.history.SmartFavoriteEvent(at, keys[it].orEmpty(), false) },
            )
        }
    }
}

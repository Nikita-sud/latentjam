/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.ListeningNormalization
import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.history.localTimePoint
import io.github.nikitasud.latentjam.history.personalListen
import io.github.nikitasud.latentjam.history.personalTrack
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SMART's personal evidence ([PersonalModel]) as a cache of the listening history: rebuilt when the log or the library
 * changes, never stored, so clearing the history clears it (PLAN_PERSONAL §3).
 */
internal class PersonalModelCache(
    private val history: ListeningHistory,
    /** The track's artist keys, as SMART's chain reads them (multi-valued). */
    private val artistKeysOf: (TrackDescriptor) -> List<String>,
    /** The listener's calendar day of a time: a habit's clock moves by the days A was played (PLAN_PERSONAL v6.1). */
    private val localDay: (Long) -> Long = { localTimePoint(it).epochDay },
    /** Now: evidence of both sources ages to it (the LB-import fixes, D3), so the cache is rebuilt once a local day. */
    private val clock: () -> Long = ::epochMillis,
) {
    private val mutex = Mutex()
    private var stamp: List<Long>? = null
    private var model: PersonalModel = PersonalModel.EMPTY

    /** The history size and library hash of a build that did not fit in memory: not retried until either moves on. */
    private var failedEvents = -1
    private var failedLibraryHash = 0L

    /**
     * The same memory for a build the Statistics page asked for. That page holds more of the heap while it loads, so a
     * build that did not fit there says nothing about SMART's own next press.
     */
    private var displayFailedEvents = -1
    private var displayFailedLibraryHash = 0L

    /** The model, or null when building it did not fit in memory (a long imported history on a small heap). */
    suspend fun model(library: List<TrackDescriptor>, forDisplay: Boolean = false): PersonalModel? = mutex.withLock {
        val events = history.allEvents()
        var libraryHash = 0L
        for (t in library) libraryHash = libraryHash * 31 + t.id.value.hashCode()
        val nowMs = clock()
        val now = listOf(events.size.toLong(), events.maxOfOrNull { it.startedAtMs } ?: 0L, library.size.toLong(), libraryHash, localDay(nowMs))
        if (now != stamp) {
            // A quarter more history, a smaller one (cleared, restored) or another library before the next attempt, so
            // top-ups do not keep allocating toward the same failure.
            if (failedEvents >= 0 && libraryHash == failedLibraryHash && events.size in failedEvents until failedEvents + failedEvents / 4) {
                return@withLock null
            }
            if (forDisplay && displayFailedEvents >= 0 && libraryHash == displayFailedLibraryHash &&
                events.size in displayFailedEvents until displayFailedEvents + displayFailedEvents / 4
            ) {
                return@withLock null
            }
            try {
                model = withContext(Dispatchers.Default) {
                    PersonalModel.build(
                        ListeningNormalization.joinResumed(events.sortedBy { it.startedAtMs }).map(::personalListen),
                        library.map { personalTrack(it, artistKeysOf(it)) },
                        localDay,
                        nowMs = maxOf(nowMs, events.maxOfOrNull { it.startedAtMs } ?: 0L),
                    )
                }
            } catch (exhausted: Error) {
                // Android: OutOfMemoryError, which common Kotlin cannot name; any other Error is a bug and goes on.
                // (Kotlin/Native aborts on a failed allocation, but iOS has no small fixed heap.) The half-built maps
                // are local and unreachable now; SMART plans without the personal term (CORE), as with habits off.
                if (exhausted::class.simpleName != "OutOfMemoryError") throw exhausted
                if (forDisplay) {
                    displayFailedEvents = events.size
                    displayFailedLibraryHash = libraryHash
                } else {
                    failedEvents = events.size
                    failedLibraryHash = libraryHash
                }
                stamp = null
                model = PersonalModel.EMPTY
                println("SMART: personal evidence did not fit in memory (${exhausted::class.simpleName}, ${events.size} listens)")
                return@withLock null
            }
            failedEvents = -1
            displayFailedEvents = -1
            stamp = now
        }
        model
    }
}

/**
 * The two facts the history line learns from outside the player (PLAN_PERSONAL §6): where the listener picked a track,
 * marked by the screen right before it starts playback, and which SMART picks the personal term changed, reported by
 * the chooser when it plans them. In memory only: after a restart both read as unknown.
 */
internal object PersonalSignals {
    private class Mark(val surface: PickSurface, val atMs: Long)

    /**
     * The last few marks, oldest first. A listen is annotated when it ends, and a pick ended by another pick ends after
     * that pick's mark: with one mark kept, such a listen was compared with the next pick's mark and lost its surface.
     */
    @Volatile
    private var marks: List<Mark> = emptyList()

    @Volatile
    private var planned: Map<TrackId, Boolean> = emptyMap()

    /** How many picks were marked so far: a start that plans first reads it to learn of a pick made meanwhile. */
    @Volatile
    var pickCount: Long = 0L
        private set

    /** The listener is about to start playback from [surface]. */
    fun pick(surface: PickSurface, atMs: Long = epochMillis()) {
        marks = (marks + Mark(surface, atMs)).takeLast(MARKS_KEPT)
        pickCount++
    }

    /** A SMART plan's rows, and which of them the personal term changed. */
    fun planned(rows: Collection<TrackId>, changed: Set<TrackId>) {
        val next = LinkedHashMap(planned)
        for (id in rows) {
            next.remove(id)
            next[id] = id in changed
        }
        while (next.size > PLANNED_KEPT) next.remove(next.keys.first())
        planned = next
    }

    /** [event] with the pick surface and the personal flag, where known. */
    fun annotate(event: ListenEvent): ListenEvent {
        val origin = event.origin ?: return event
        // The mark nearest to the start, among those the start may belong to: its own, not a neighbour's.
        val surface = if (origin.start != ListenStart.USER_PICK) null else marks
            .filter { event.startedAtMs - it.atMs in -PICK_AFTER_MS..PICK_BEFORE_MS }
            .minByOrNull { abs(event.startedAtMs - it.atMs) }
            ?.surface
        val changed = if (origin.smartPlanPosition != null) planned[event.trackId] else null
        if (surface == null && changed == null) return event
        return event.copy(origin = origin.copy(pickSurface = surface ?: origin.pickSurface, personalChanged = changed ?: origin.personalChanged))
    }

    /** Tests: forget everything. */
    fun reset() {
        marks = emptyList()
        planned = emptyMap()
    }

    private const val PLANNED_KEPT = 200
    private const val MARKS_KEPT = 8
    /** A start this long after the mark is still that pick (the player may take a moment to open a file). */
    private const val PICK_BEFORE_MS = 10_000L
    /** A clock that ticked between the mark and the start. */
    private const val PICK_AFTER_MS = 2_000L
}

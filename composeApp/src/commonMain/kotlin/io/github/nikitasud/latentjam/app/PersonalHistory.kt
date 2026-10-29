/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.history.isImported
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalListen
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import io.github.nikitasud.latentjam.smart.personal.PersonalStart
import io.github.nikitasud.latentjam.smart.personal.PersonalSurface
import io.github.nikitasud.latentjam.smart.personal.PersonalTrack
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker
import kotlin.concurrent.Volatile
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
) {
    private val mutex = Mutex()
    private var stamp: List<Long>? = null
    private var model: PersonalModel = PersonalModel.EMPTY

    /** The history size and library hash of a build that did not fit in memory: not retried until either moves on. */
    private var failedEvents = -1
    private var failedLibraryHash = 0L

    /** The model, or null when building it did not fit in memory (a long imported history on a small heap). */
    suspend fun model(library: List<TrackDescriptor>): PersonalModel? = mutex.withLock {
        val events = history.allEvents()
        var libraryHash = 0L
        for (t in library) libraryHash = libraryHash * 31 + t.id.value.hashCode()
        val now = listOf(events.size.toLong(), events.maxOfOrNull { it.startedAtMs } ?: 0L, library.size.toLong(), libraryHash)
        if (now != stamp) {
            // A quarter more history, a smaller one (cleared, restored) or another library before the next attempt, so
            // top-ups do not keep allocating toward the same failure.
            if (failedEvents >= 0 && libraryHash == failedLibraryHash && events.size in failedEvents until failedEvents + failedEvents / 4) {
                return@withLock null
            }
            try {
                model = withContext(Dispatchers.Default) {
                    PersonalModel.build(
                        events.sortedBy { it.startedAtMs }.map(::personalListen),
                        library.map { personalTrack(it, artistKeysOf(it)) },
                    )
                }
            } catch (exhausted: Error) {
                // Android: OutOfMemoryError, which common Kotlin cannot name; any other Error is a bug and goes on.
                // (Kotlin/Native aborts on a failed allocation, but iOS has no small fixed heap.) The half-built maps
                // are local and unreachable now; SMART plans without the personal term (CORE), as with habits off.
                if (exhausted::class.simpleName != "OutOfMemoryError") throw exhausted
                failedEvents = events.size
                failedLibraryHash = libraryHash
                stamp = null
                model = PersonalModel.EMPTY
                println("SMART: personal evidence did not fit in memory (${exhausted::class.simpleName}, ${events.size} listens)")
                return@withLock null
            }
            failedEvents = -1
            stamp = now
        }
        model
    }
}

/** A library track as the personal evidence keys it: its version group, artists and release. */
internal fun personalTrack(track: TrackDescriptor, artists: List<String>): PersonalTrack = PersonalTrack(
    id = track.id,
    key = SmartRanker.versionKey(track, artists.firstOrNull().orEmpty(), v2 = true),
    artists = artists,
    album = track.album?.trim()?.takeIf(String::isNotEmpty)?.let { "${it.lowercase()}|${track.albumArtist?.trim()?.lowercase().orEmpty()}" },
    trackNumber = track.trackNumber,
    discNumber = track.discNumber,
    durationMs = track.durationMs,
)

/** The shuffle mode SMART playback logs (ShuffleMode.SMART.name). */
private const val SMART_SHUFFLE_MODE = "SMART"

/** A history listen as the personal evidence reads it. */
internal fun personalListen(event: ListenEvent): PersonalListen {
    val origin = event.origin
    val start = when {
        event.isImported -> PersonalStart.IMPORTED
        origin == null -> PersonalStart.UNKNOWN
        else -> when (origin.start) {
            ListenStart.USER_PICK -> PersonalStart.PICK
            ListenStart.QUEUED -> PersonalStart.QUEUED
            ListenStart.AUTO_ADVANCE -> PersonalStart.AUTO
            ListenStart.SKIP_NEXT -> PersonalStart.SKIP_NEXT
            ListenStart.SKIP_PREVIOUS -> PersonalStart.SKIP_PREVIOUS
            ListenStart.REPEAT -> PersonalStart.REPEAT
            null -> PersonalStart.UNKNOWN
        }
    }
    // A For You suggestion: started from For You without a plan position, unless queued by hand (PERSONALISATION_RULES v5.5).
    val forYou = origin?.parentId == FOR_YOU_PARENT_ID && origin.smartPlanPosition == null && origin.start != ListenStart.QUEUED
    return PersonalListen(
        trackId = event.trackId,
        startedAtMs = event.startedAtMs,
        listenedMs = event.listenedMs,
        completed = event.completed,
        skipped = event.skipped,
        start = start,
        surface = when (origin?.pickSurface) {
            PickSurface.SEARCH -> PersonalSurface.SEARCH
            PickSurface.QUEUE -> PersonalSurface.QUEUE
            PickSurface.COLLECTION -> PersonalSurface.COLLECTION
            PickSurface.FOR_YOU -> PersonalSurface.FOR_YOU
            PickSurface.EXTERNAL -> PersonalSurface.EXTERNAL
            null -> null
        },
        smartSourced = origin?.smartPlanPosition != null || forYou ||
            (origin == null && event.shuffleMode == SMART_SHUFFLE_MODE && start != PersonalStart.IMPORTED),
        forYou = forYou,
        albumParent = origin?.parentId?.startsWith("album:") == true,
        shuffleOff = event.shuffleMode == "OFF",
        playedMs = event.playedMs,
        personalChanged = origin?.personalChanged == true,
    )
}

/**
 * The two facts the history line learns from outside the player (PLAN_PERSONAL §6): where the listener picked a track,
 * marked by the screen right before it starts playback, and which SMART picks the personal term changed, reported by
 * the chooser when it plans them. In memory only: after a restart both read as unknown.
 */
internal object PersonalSignals {
    private class Mark(val surface: PickSurface, val atMs: Long)

    @Volatile
    private var mark: Mark? = null

    @Volatile
    private var planned: Map<TrackId, Boolean> = emptyMap()

    /** The listener is about to start playback from [surface]. */
    fun pick(surface: PickSurface, atMs: Long = epochMillis()) {
        mark = Mark(surface, atMs)
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
        val surface = mark?.takeIf {
            origin.start == ListenStart.USER_PICK && event.startedAtMs - it.atMs in -PICK_AFTER_MS..PICK_BEFORE_MS
        }?.surface
        val changed = if (origin.smartPlanPosition != null) planned[event.trackId] else null
        if (surface == null && changed == null) return event
        return event.copy(origin = origin.copy(pickSurface = surface ?: origin.pickSurface, personalChanged = changed ?: origin.personalChanged))
    }

    /** Tests: forget everything. */
    fun reset() {
        mark = null
        planned = emptyMap()
    }

    private const val PLANNED_KEPT = 200
    /** A start this long after the mark is still that pick (the player may take a moment to open a file). */
    private const val PICK_BEFORE_MS = 10_000L
    /** A clock that ticked between the mark and the start. */
    private const val PICK_AFTER_MS = 2_000L
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** How a process of this app ended, as far as the platform tells. */
internal enum class LastExit {
    /** It crashed, in the app's own code or in native code. */
    CRASH,

    /** The platform does not say (Android before 11), or names an end a file can cause as well as anything else. */
    UNKNOWN,

    /** An end that is no sign of a crash: the user, the system, an update. */
    OTHER,
}

/** This process's id, as [analysisExitOf] takes it; 0 where the platform keeps no exit record. */
internal expect fun analysisProcessId(): Int

/** How the earlier process [pid] of this app ended. */
internal expect fun analysisExitOf(pid: Int): LastExit

/** What the audio pass tells about the chunk it analyses, so a chunk that ends the process can be found. */
internal interface InFlightMarker {
    /** [chunk] is about to be analysed and has tracks that need it. */
    fun begin(chunk: List<TrackDescriptor>) = Unit

    /** [chunk] is over; [completed]: the engine returned for it (whatever each track's outcome). */
    fun end(chunk: List<TrackDescriptor>, completed: Boolean) = Unit

    companion object None : InFlightMarker
}

/** What a marker found at the start of a pass means: see [settleInFlight]. */
internal class InFlightVerdict(
    /** The tracks that get one attempt in the retry ledger. */
    val charged: Set<TrackId>,
    /** The tracks to analyse one per chunk from now on, until each has been through a chunk. */
    val suspects: Set<TrackId>,
    /** The tracks found in flight once with no word on how the process ended; kept for the next launch. */
    val seen: Set<TrackId>,
)

/**
 * A file that ends the process while it is analysed was analysed again at every launch: progress is stored after a
 * chunk, never before a track, and the pass walks the same order each time. [marker] names the tracks of the chunk
 * that was in flight when an earlier process ended (empty: none was).
 *
 * - The process crashed ([LastExit.CRASH]): those tracks get one ledger attempt and become [suspects], which the pass
 *   then analyses one per chunk, so the next crash names one track. The ledger's own schedule does the rest: after
 *   two attempts a track waits an hour and the pass goes on without it, after the third it is remembered as failed.
 * - Nothing says how it ended ([LastExit.UNKNOWN]): a process also ends mid-chunk when the phone restarts or the
 *   system needs its memory, so one such end costs nothing. Only tracks in flight at two launches in a row
 *   ([seenBefore]) are treated as above.
 * - Any other end ([LastExit.OTHER]) costs nothing.
 */
internal fun settleInFlight(
    marker: Set<TrackId>,
    seenBefore: Set<TrackId>,
    suspectsBefore: Set<TrackId>,
    exit: LastExit,
): InFlightVerdict {
    if (marker.isEmpty()) return InFlightVerdict(emptySet(), suspectsBefore, emptySet())
    val blamed = when (exit) {
        LastExit.CRASH -> marker
        LastExit.UNKNOWN -> marker.intersect(seenBefore)
        LastExit.OTHER -> emptySet()
    }
    return InFlightVerdict(
        charged = blamed,
        suspects = suspectsBefore + blamed,
        seen = if (exit == LastExit.UNKNOWN && blamed.isEmpty()) marker else emptySet(),
    )
}

/**
 * [settleInFlight] over the stored marker ([AnalysisControlStore.inFlight] and its two companions). One per process:
 * the marker of an earlier process is read once, by the first pass that knows the library.
 */
internal class AnalysisInFlight(
    private val store: AnalysisControlStore,
    private val pid: () -> Int = ::analysisProcessId,
    private val exitOf: (Int) -> LastExit = ::analysisExitOf,
) : InFlightMarker {
    private var settled = false
    private var marked = false
    private var suspects: Set<TrackId> = decodeIds(store.crashSuspects)

    /** The tracks the pass analyses one per chunk. */
    val alone: Set<TrackId> get() = suspects

    /**
     * The tracks of [tracks] that get one ledger attempt for the marker an earlier process left; empty from the second
     * call on. [authoritative]: [tracks] is the whole library, so suspects no longer in it are dropped.
     */
    fun charged(tracks: List<TrackDescriptor>, authoritative: Boolean): List<TrackDescriptor> {
        if (settled || tracks.isEmpty()) return emptyList()
        settled = true
        val stored = store.inFlight
        val marker = decodeIds(stored?.substringAfter('\n', ""))
        val exit = if (marker.isEmpty()) LastExit.OTHER else exitOf(stored?.substringBefore('\n')?.toIntOrNull() ?: 0)
        val verdict = settleInFlight(marker, decodeIds(store.inFlightSeen), suspects, exit)
        if (stored != null) store.inFlight = null
        store.inFlightSeen = encodeIds(verdict.seen)
        val live = if (authoritative || verdict.charged.isNotEmpty()) tracks.associateBy { it.id } else emptyMap()
        keep(if (authoritative) verdict.suspects.filterTo(LinkedHashSet()) { it in live } else verdict.suspects)
        return verdict.charged.mapNotNull(live::get)
    }

    /** [ids] are settled some other way (remembered as failed): no longer suspects. */
    fun forget(ids: Collection<TrackId>) {
        if (suspects.any { it in ids }) keep(suspects - ids.toSet())
    }

    override fun begin(chunk: List<TrackDescriptor>) {
        marked = true
        store.inFlight = pid().toString() + "\n" + encodeIds(chunk.map { it.id })
    }

    override fun end(chunk: List<TrackDescriptor>, completed: Boolean) {
        if (marked) {
            marked = false
            store.inFlight = null
        }
        // A suspect that went through a chunk without ending the process is none any more.
        if (completed && suspects.isNotEmpty()) forget(chunk.map { it.id })
    }

    private fun keep(now: Set<TrackId>) {
        if (now == suspects) return
        suspects = now
        store.crashSuspects = encodeIds(now)
    }

    private companion object {
        // Ids never hold a newline (uri-derived), as the retry ledger relies on too.
        fun encodeIds(ids: Collection<TrackId>): String? = ids.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.value }

        fun decodeIds(stored: String?): Set<TrackId> =
            stored.orEmpty().lineSequence().filter(String::isNotEmpty).mapTo(LinkedHashSet(), ::TrackId)
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IndexReport
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.isAnalysisTimeout

/**
 * The declared deviations D1 and D2 (docs/analysis-094-plan.md §0, 1d) for tracks whose analysis timed out.
 *
 * 0.9.3 remembered a timeout as invalid audio until Retry, even when its cause was passing. Now a timeout is retried:
 * at the next pass, then no sooner than an hour after the second, and after the third it is handed back to the engine to
 * remember, so it shows as failed with Retry and a really hung file cannot cost a pass forever. Timeouts count, and
 * from 0.9.6 failures on the file's own data ([countsInRetryLedger]); every other outcome is 0.9.3's. An entry belongs to the file it was made for: a changed file starts again.
 */
internal class AnalysisRetryLedger(encoded: String?, private val nowMs: () -> Long) {

    private data class Entry(val identity: String, val attempts: Int, val lastAtMs: Long)

    private val entries = LinkedHashMap<TrackId, Entry>()

    init {
        encoded.orEmpty().lineSequence().forEach { line ->
            val fields = line.split('\t')
            if (fields.size != 4) return@forEach
            val attempts = fields[2].toIntOrNull()?.takeIf { it in 1 until CAP } ?: return@forEach
            val last = fields[3].toLongOrNull() ?: return@forEach
            entries[TrackId(fields[0])] = Entry(fields[1], attempts, last)
        }
    }

    /** For the settings string; ids and identities never hold a tab or a newline (uri-derived). */
    fun encoded(): String = entries.entries.joinToString("\n") { (id, entry) ->
        "${id.value}\t${entry.identity}\t${entry.attempts}\t${entry.lastAtMs}"
    }

    /** Whether [track] waits out its backoff now: left out of this pass, and not counted as missing. */
    fun holding(track: TrackDescriptor): Boolean {
        val entry = entries[track.id] ?: return false
        if (entry.identity != identityOf(track)) return false
        val now = nowMs()
        // A clock moved back never holds a track forever.
        if (now < entry.lastAtMs) return false
        return entry.attempts >= 2 && now - entry.lastAtMs < SECOND_RETRY_AFTER_MS
    }

    /**
     * How long until the first of [tracks] that waits out its backoff may be tried again; null when none waits. A pass
     * that left such tracks out is finished for now, and has to be entered once more when this has passed.
     */
    fun nextReleaseInMs(tracks: Collection<TrackDescriptor>): Long? {
        val now = nowMs()
        return tracks.filter(::holding).mapNotNull { track ->
            entries[track.id]?.let { (SECOND_RETRY_AFTER_MS - (now - it.lastAtMs)).coerceAtLeast(0L) }
        }.minOrNull()
    }

    /** [track] timed out once more; true when that was the last attempt and the engine should remember it. */
    fun timedOut(track: TrackDescriptor): Boolean {
        val identity = identityOf(track)
        val before = entries[track.id]?.takeIf { it.identity == identity }?.attempts ?: 0
        val attempts = before + 1
        if (attempts >= CAP) {
            entries.remove(track.id)
            return true
        }
        entries[track.id] = Entry(identity, attempts, nowMs())
        return false
    }

    /**
     * [track] waits out the backoff from now, as after its second timeout, whatever its count was: a pass that stalled
     * on it must get past it at once, in this pass and the next. Its next timeout is then the last.
     */
    fun hold(track: TrackDescriptor) {
        val identity = identityOf(track)
        val before = entries[track.id]?.takeIf { it.identity == identity }?.attempts ?: 0
        entries[track.id] = Entry(identity, maxOf(before, CAP - 1), nowMs())
    }

    /**
     * [track] has one try left and does not wait for it: for a remembered failure that is handed out once more with
     * no word on why it failed. The ledger let go of a file it gave up after three timeouts, so tried from nothing it
     * would cost its three 90 s waits again; with this entry a timeout is its last, and a success ends the entry.
     */
    fun lastTry(track: TrackDescriptor) {
        // As if its second timeout were the whole wait ago: [holding] says no.
        entries[track.id] = Entry(identityOf(track), CAP - 1, lastAtMs = nowMs() - SECOND_RETRY_AFTER_MS)
    }

    fun has(id: TrackId): Boolean = id in entries

    /** [id] was analysed, failed some other way, or left the library. */
    fun forget(id: TrackId) {
        entries.remove(id)
    }

    /** Keeps only [live] tracks. */
    fun prune(live: Collection<TrackId>) {
        val keep = live.toHashSet()
        entries.keys.retainAll(keep)
    }

    val size: Int get() = entries.size

    companion object {
        const val CAP = 3
        const val SECOND_RETRY_AFTER_MS = 60 * 60_000L

        private fun identityOf(track: TrackDescriptor): String =
            // The file's revision too, as the engine's own identity: an edited file starts again.
            "${io.github.nikitasud.latentjam.smart.ContainerPaths.relative(track.audioUri)}|${track.durationMs}|${track.sourceRevision}"
    }
}

/**
 * Whether a track's failure in a chunk is an attempt in the ledger: its analysis timed out, or it failed on the file's
 * own data ([EngineError.AudioUnavailable.trackLocal]). The second kind was retried at every launch for good, each
 * time starting the service and loading the model, and kept the Map's rebuild waiting; counted, it ends as a timeout
 * does, after the third try. Every other passing failure (a source that is away, busy codecs, the model) is no attempt.
 */
internal fun EngineError.countsInRetryLedger(): Boolean =
    isAnalysisTimeout() || (this is EngineError.AudioUnavailable && trackLocal)

/**
 * What the D2 ledger keeps at the start of a pass, or null to leave it as it is: the recovery Retry clears it, and
 * otherwise only an authoritative library says which tracks left. A partial scan (a source that came back empty, a
 * permission not yet granted, a background launch) must not wipe the timeout counts of the tracks it did not see.
 */
internal fun ledgerKeeps(
    trackIds: List<TrackId>,
    recoveryRetry: Boolean,
    librarySnapshotAuthoritative: Boolean,
    /**
     * The pass before this one was stopped by a stall. The Retry offered on a stopped pass is the recovery Retry, and
     * clearing the ledger there sent the next pass straight back into the tracks it had stalled on, every time.
     */
    afterStall: Boolean = false,
): List<TrackId>? = when {
    recoveryRetry && !afterStall -> emptyList()
    librarySnapshotAuthoritative -> trackIds
    else -> null
}

/**
 * D1/D2 after each chunk of a pass: what the chunk's report means for the retry ledger. A timeout (or a failure on the
 * file's own data) is an attempt; the third hands the track to the engine to remember, shown as failed with Retry. Any
 * other answer about the file ends its entry.
 *
 * A stalled chunk ([IndexReport.stalled]: three timeouts in a row) holds every track that timed out in this pass
 * ([AnalysisRetryLedger.hold]): hung files that sit together are then behind the pass, for this one and the next.
 */
internal class AnalysisRetrySettling(
    private val ledger: AnalysisRetryLedger,
    /** Whether a track still has no audio vector. */
    private val stillMissing: suspend (TrackDescriptor) -> Boolean,
    /** Hands tracks the ledger gave up on to the engine, to remember as failed. */
    private val remember: suspend (List<TrackDescriptor>) -> Unit,
    /** Stores the ledger. */
    private val save: () -> Unit,
) {
    /** Tracks whose analysis timed out in this pass and that are neither held nor given up yet. */
    private val timedOut = ArrayList<TrackDescriptor>()

    /** A pass begins. */
    fun passStarted() {
        timedOut.clear()
    }

    suspend fun settle(chunk: List<TrackDescriptor>, report: IndexReport): IndexReport {
        val exhausted = ArrayList<TrackDescriptor>()
        for (track in chunk) {
            val error = report.errors[track.id]
            when {
                error != null && error.countsInRetryLedger() -> when {
                    ledger.timedOut(track) -> exhausted += track
                    error.isAnalysisTimeout() -> timedOut += track
                }
                // Only an answer about the file ends its count: analysed, or failed for good. A passing failure
                // (codec contention, a suspension on iOS, the model) or a track the chunk skipped keeps it.
                error is EngineError.InvalidAudio -> ledger.forget(track.id)
                // Decided per track, and only for one the ledger holds: analysed means no longer missing.
                error == null && ledger.has(track.id) && !stillMissing(track) -> ledger.forget(track.id)
            }
        }
        if (report.stalled) {
            timedOut.forEach(ledger::hold)
            timedOut.clear()
        }
        if (exhausted.isEmpty()) {
            save()
            return report
        }
        // The engine's failure rows first, then the ledger that let go of them: a kill between keeps the count.
        remember(exhausted)
        save()
        val errors = LinkedHashMap(report.errors)
        for (track in exhausted) errors[track.id] = EngineError.InvalidAudio("Analysis failed ${AnalysisRetryLedger.CAP} times")
        return report.copy(errors = errors)
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * The declared deviations D1 and D2 (docs/analysis-094-plan.md §0, 1d) for tracks whose analysis timed out.
 *
 * 0.9.3 remembered a timeout as invalid audio until Retry, even when its cause was passing. Now a timeout is retried:
 * at the next pass, then no sooner than an hour after the second, and after the third it is handed back to the engine to
 * remember, so it shows as failed with Retry and a really hung file cannot cost a pass forever. Only timeouts count;
 * every other outcome is 0.9.3's. An entry belongs to the file it was made for: a changed file starts again.
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

        private fun identityOf(track: TrackDescriptor): String = "${track.audioUri}|${track.durationMs}"
    }
}

/**
 * What the D2 ledger keeps at the start of a pass, or null to leave it as it is: the recovery Retry clears it, and
 * otherwise only an authoritative library says which tracks left. A partial scan (a source that came back empty, a
 * permission not yet granted, a background launch) must not wipe the timeout counts of the tracks it did not see.
 */
internal fun ledgerKeeps(
    trackIds: List<TrackId>,
    recoveryRetry: Boolean,
    librarySnapshotAuthoritative: Boolean,
): List<TrackId>? = when {
    recoveryRetry -> emptyList()
    librarySnapshotAuthoritative -> trackIds
    else -> null
}

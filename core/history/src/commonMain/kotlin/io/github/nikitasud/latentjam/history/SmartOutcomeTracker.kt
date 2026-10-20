/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Decides what became of each track a SMART plan offered ([SmartImpression]), from the chooser's plans and served
 * rows and the playback snapshots and finished listens the history recorder sees. Pure and in memory: it holds only
 * the open rows of recent plans (at most [maxRows]); the outcomes it returns go to [SmartImpressionLog].
 *
 * - superseded before it was served (a newer plan, or a new queue) → [SmartOutcomeKind.UNREACHED]
 * - the listener started other music before it played → [SmartOutcomeKind.REPLACED]
 * - in the queue once, then gone before it played → [SmartOutcomeKind.REMOVED]
 * - it played → [SmartOutcomeKind.PLAYED] or [SmartOutcomeKind.SKIPPED] from its [ListenEvent] (the history's own
 *   skip rule), with the time listened and the fraction of the track reached
 *
 * A row that started but never produced a listen (history recording off, the process died) is dropped without an
 * outcome rather than guessed.
 */
public class SmartOutcomeTracker(private val maxRows: Int = 500) {
    private class Row(val impression: SmartImpression) {
        var served = false
        var seenInQueue = false
        var started = false
    }

    private val rows = LinkedHashMap<TrackId, Row>()
    private var queue: String? = null

    /** A new plan's rows; earlier rows of the queue that were never served are superseded. */
    public fun onPlan(planned: List<Pair<TrackId, SmartImpression>>, atMs: Long): List<SmartOutcome> {
        val out = ArrayList<SmartOutcome>()
        val newQueue = planned.firstOrNull()?.second?.queue
        val iterator = rows.entries.iterator()
        while (iterator.hasNext()) {
            val row = iterator.next().value
            when {
                !row.served && !row.started -> { out += outcome(row, SmartOutcomeKind.UNREACHED, atMs); iterator.remove() }
                newQueue != null && newQueue != queue && !row.started -> { out += outcome(row, SmartOutcomeKind.UNREACHED, atMs); iterator.remove() }
            }
        }
        if (newQueue != null) queue = newQueue
        for ((id, impression) in planned) {
            rows.remove(id)?.let { old -> if (!old.started) out += outcome(old, SmartOutcomeKind.UNREACHED, atMs) }
            rows[id] = Row(impression)
        }
        while (rows.size > maxRows) rows.remove(rows.keys.first())
        return out
    }

    /** The chooser handed [id] to the queue. */
    public fun onServed(id: TrackId) {
        rows[id]?.served = true
    }

    /**
     * One playback snapshot: what plays, what the queue holds, and whether the listener just started [current]
     * themselves (a new start whose cause is a user pick).
     */
    public fun onSnapshot(current: TrackId?, queueIds: Set<TrackId>, userPick: Boolean, atMs: Long): List<SmartOutcome> {
        val out = ArrayList<SmartOutcome>()
        val playing = current?.let(rows::get)
        if (playing != null) {
            playing.served = true
            playing.started = true
        } else if (userPick && current != null) {
            // Other music, chosen by the listener: nothing still waiting in this queue will be reached through it.
            val iterator = rows.entries.iterator()
            while (iterator.hasNext()) {
                val row = iterator.next().value
                if (row.started) continue
                out += outcome(row, SmartOutcomeKind.REPLACED, atMs)
                iterator.remove()
            }
            return out
        }
        if (queueIds.isEmpty()) return out
        val iterator = rows.entries.iterator()
        while (iterator.hasNext()) {
            val (id, row) = iterator.next()
            if (row.started) continue
            if (id in queueIds) {
                row.seenInQueue = true
            } else if (row.seenInQueue) {
                out += outcome(row, SmartOutcomeKind.REMOVED, atMs)
                iterator.remove()
            }
        }
        return out
    }

    /** A finished listen from the history recorder. */
    public fun onListen(event: ListenEvent, atMs: Long): List<SmartOutcome> {
        val row = rows[event.trackId]?.takeIf { it.started } ?: return emptyList()
        rows.remove(event.trackId)
        val fraction = event.trackDurationMs?.takeIf { it > 0 }?.let { (event.playedMs.toDouble() / it).coerceIn(0.0, 1.0) }
        val kind = if (event.skipped) SmartOutcomeKind.SKIPPED else SmartOutcomeKind.PLAYED
        return listOf(outcome(row, kind, atMs, event.effectiveListenedMs, fraction))
    }

    /** Open rows (tests). */
    public val openRows: Int get() = rows.size

    private fun outcome(row: Row, kind: SmartOutcomeKind, atMs: Long, elapsedMs: Long? = null, fraction: Double? = null) =
        row.impression.let { SmartOutcome(atMs, it.queue, it.plan, it.hop, it.track, kind, elapsedMs, fraction) }
}

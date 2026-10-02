/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.AudioCarryOver
import io.github.nikitasud.latentjam.smart.AudioCarryOverResult
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carry-overs waiting for the index sync that applies them (spec §6.5), kept across restarts: the
 * sync may come at the next launch. One per track; a newer save of the track replaces the older.
 * A carry-over whose rescan has not landed after [MAX_WAITS] syncs is dropped. The worst case is
 * one re-analysis, never a vector kept for audio that changed.
 */
internal class AudioCarryOverStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private class Entry(val carry: AudioCarryOver, val waits: Int)

    private val mutex = Mutex()
    private var entries: LinkedHashMap<TrackId, Entry>? = null

    suspend fun add(carryOvers: List<AudioCarryOver>) {
        if (carryOvers.isEmpty()) return
        mutex.withLock {
            val map = loaded()
            carryOvers.forEach { map[it.trackId] = Entry(it, 0) }
            save(map)
        }
    }

    suspend fun pending(): List<AudioCarryOver> = mutex.withLock { loaded().values.map { it.carry } }

    /** Records what a sync did with [offered]; a carry-over replaced by a newer save meanwhile is left alone. */
    suspend fun settle(offered: List<AudioCarryOver>, result: AudioCarryOverResult) = mutex.withLock {
        val map = loaded()
        for (carry in offered) {
            val entry = map[carry.trackId]?.takeIf { it.carry == carry } ?: continue
            if (carry.trackId in result.settled || entry.waits + 1 >= MAX_WAITS) {
                map.remove(carry.trackId)
            } else {
                map[carry.trackId] = Entry(carry, entry.waits + 1)
            }
        }
        save(map)
    }

    private fun loaded(): LinkedHashMap<TrackId, Entry> {
        entries?.let { return it }
        val map = LinkedHashMap<TrackId, Entry>()
        read()?.lineSequence()?.forEach { line ->
            val parts = line.split('|')
            if (parts.size != 5 || parts[0] != FORMAT) return@forEach
            val id = parts[1].unhex() ?: return@forEach
            val revision = when {
                parts[2] == NULL -> null
                parts[2].startsWith(VALUE) -> parts[2].removePrefix(VALUE).unhex() ?: return@forEach
                else -> return@forEach
            }
            val length = parts[3].toLongOrNull() ?: return@forEach
            val waits = parts[4].toIntOrNull() ?: return@forEach
            map[TrackId(id)] = Entry(AudioCarryOver(TrackId(id), revision, length), waits)
        }
        entries = map
        return map
    }

    /** A carry-over that fails to persist is only a re-analysis later; it never fails the save. */
    private fun save(map: Map<TrackId, Entry>) {
        try {
            write(
                map.values.joinToString("\n") { entry ->
                    val revision = entry.carry.oldRevision?.let { VALUE + it.hex() } ?: NULL
                    listOf(FORMAT, entry.carry.trackId.value.hex(), revision, entry.carry.newLength, entry.waits)
                        .joinToString("|")
                },
            )
        } catch (_: Exception) {
            // Kept in memory for this run.
        }
    }

    private companion object {
        const val FORMAT = "v1"
        const val NULL = "n"
        const val VALUE = "s"
        const val MAX_WAITS = 3
    }
}

/** What a finished save lets SMART keep: every file written, with its new length, keyed by its track. */
internal fun audioCarryOversOf(
    tracks: List<TrackDescriptor>,
    result: TagSaveResult,
    keyOf: (TrackDescriptor) -> String?,
): List<AudioCarryOver> {
    val written = result.entries
        .filter { it.status == FileWriteStatus.SAVED && it.newLength != null }
        .associateBy { it.key }
    return tracks.mapNotNull { track ->
        val entry = keyOf(track)?.let(written::get) ?: return@mapNotNull null
        AudioCarryOver(track.id, track.sourceRevision, entry.newLength!!)
    }
}

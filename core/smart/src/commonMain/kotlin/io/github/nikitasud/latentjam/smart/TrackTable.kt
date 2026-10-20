/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * TrackId → TrackDescriptor in insertion order, as the engine's `LinkedHashMap` of known tracks kept it (a replaced id
 * keeps its place), without an entry and a boxed id per song. Not thread-safe; the engine's mutex serializes access.
 */
internal class TrackTable {
    private val cells = OrderedIdMap()
    private var tracks = arrayOfNulls<TrackDescriptor>(16)
    private var free = IntArray(16)
    private var freeCount = 0
    private var cellCount = 0

    val size: Int get() = cells.size

    operator fun get(id: TrackId): TrackDescriptor? = cells[id]?.let { tracks[it] }

    operator fun contains(id: TrackId): Boolean = id in cells

    operator fun set(id: TrackId, track: TrackDescriptor) {
        val existing = cells[id]
        if (existing != null) {
            tracks[existing] = track
            return
        }
        val cell = if (freeCount > 0) free[--freeCount] else {
            if (cellCount == tracks.size) tracks = tracks.copyOf(tracks.size * 2)
            cellCount++
        }
        tracks[cell] = track
        cells.put(id, cell)
    }

    /** The tracks in insertion order. */
    fun values(): List<TrackDescriptor> {
        val out = ArrayList<TrackDescriptor>(cells.size)
        cells.forEachEntry { _, cell, _ -> out += tracks[cell]!! }
        return out
    }

    /** Keeps only the ids [keep] accepts. */
    fun retainAll(keep: (TrackId) -> Boolean) {
        val gone = ArrayList<TrackId>()
        cells.forEachEntry { id, _, _ -> if (!keep(id)) gone += id }
        for (id in gone) {
            val cell = cells.remove(id) ?: continue
            tracks[cell] = null
            if (freeCount == free.size) free = free.copyOf(free.size * 2)
            free[freeCount++] = cell
        }
    }

    fun clear() {
        cells.clear()
        tracks = arrayOfNulls(16)
        free = IntArray(16)
        freeCount = 0
        cellCount = 0
    }
}

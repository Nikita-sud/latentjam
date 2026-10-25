/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.LibraryListeningStats
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.StoredLibraryLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One library layout and one presentation, bounded by LibraryLayout.MAX_TRACKS. No bitmap cache:
 * lenses, song colors and zoom continue to draw sharply without storing full-screen images.
 * The disk layout survives launches; this layer avoids disk/stat reads on a warm tab return.
 */
internal class MapPageCache(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private val mutex = Mutex()
    private var stored: StoredLibraryLayout? = null
    /** When [stored] was last computed here, and from which vectors; null when it came from disk. */
    private var computedAt: TimeMark? = null
    private var computedSource: Any? = null
    private var pageKey: PageKey? = null
    private var cachedPage: MapPage? = null

    suspend fun layout(
        trackIds: List<TrackId>,
        fingerprint: Long,
        load: suspend () -> StoredLibraryLayout,
        compute: suspend (StoredLibraryLayout) -> StoredLibraryLayout?,
        /**
         * A layout this cache computed less than this long ago for the same tracks is shown again
         * although the vectors moved since. While an analysis runs, every listen re-asks for the
         * page and every analysed track moves the fingerprint, so without this the Map would redo
         * its whole projection after each track.
         */
        reuseRecentFor: Duration = Duration.ZERO,
        /** The vectors the layout is drawn from; a layout from another source is never reused. */
        source: Any? = null,
    ): StoredLibraryLayout? = mutex.withLock {
        fun StoredLibraryLayout.matches() = this.fingerprint == fingerprint &&
            LibraryLayout.covers(positions, trackIds)
        stored?.takeIf { it.matches() }?.let { return@withLock it }
        val recent = computedAt?.let { it.elapsedNow() < reuseRecentFor } == true && computedSource == source
        stored?.takeIf { recent && it.positions.size == trackIds.size && LibraryLayout.covers(it.positions, trackIds) }
            ?.let { return@withLock it }
        val disk = load()
        currentCoroutineContext().ensureActive()
        if (disk.matches()) {
            stored = disk
            computedAt = null
            return@withLock disk
        }
        // Prefer the last valid in-memory coordinates as the warm start if a previous disk save
        // failed. Never cache a partially computed or mismatched layout.
        val built = compute(stored ?: disk) ?: return@withLock null
        currentCoroutineContext().ensureActive()
        if (!built.matches()) return@withLock null
        stored = built
        computedAt = timeSource.markNow()
        computedSource = source
        built
    }

    suspend fun page(
        layout: StoredLibraryLayout,
        regionOf: Map<TrackId, Int>,
        regionNames: List<String>,
        historyRevision: Long,
        readStats: suspend () -> Map<TrackId, TrackStats>,
    ): MapPage = mutex.withLock {
        val key = PageKey(layout, regionOf, regionNames, historyRevision)
        cachedPage?.takeIf { pageKey == key }?.let { return@withLock it }
        val stats = readStats()
        currentCoroutineContext().ensureActive()
        val page = MapPage(
            dots = layout.positions.map { (id, position) ->
                val entry = stats[id]
                MapDot(
                    trackId = id,
                    x = position[0], y = position[1],
                    region = regionOf[id] ?: MapDot.NO_REGION,
                    plays = entry?.plays ?: 0,
                    skipRate = if (entry == null || entry.plays == 0) 0f
                        else entry.skips.toFloat() / entry.plays,
                )
            },
            regionNames = regionNames,
            // Keep regions without drawable dots in the same index space as labels/actions.
            listening = LibraryListeningStats.summarize(regionOf, stats),
        )
        pageKey = key
        cachedPage = page
        page
    }

    private data class PageKey(
        val layout: StoredLibraryLayout,
        val regionOf: Map<TrackId, Int>,
        val regionNames: List<String>,
        val historyRevision: Long,
    )
}

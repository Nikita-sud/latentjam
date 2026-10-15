/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One publication owns the rows, queue and rail; a pending sort never mixes their orders. */
internal class SongsBrowseDerivation private constructor(
    val source: List<TrackDescriptor>,
    val choice: SortChoice<SongSort>,
    val indexed: List<IndexedSection>,
    val displayOrder: List<TrackDescriptor>,
    val indexEnabled: Boolean,
    val emittedTracks: List<TrackDescriptor?>,
) {
    val sectionStarts: List<Int> = indexed.map { it.emitStartIndex }
    val sectionBuckets: List<String> = indexed.map { it.bucket }

    companion object {
        /** Call off Main. Catalog hydration uses this in its existing background transaction. */
        fun of(songs: List<TrackDescriptor>, choice: SortChoice<SongSort>): SongsBrowseDerivation {
            val sections = SongSorting.sections(songs, choice.sort, choice.direction)
            val headers = choice.sort != SongSort.RECENT && sections.size > 1
            val indexed = ArrayList<IndexedSection>(sections.size)
            val order = ArrayList<TrackDescriptor>(songs.size)
            val emitted = ArrayList<TrackDescriptor?>(songs.size + if (headers) sections.size else 0)
            for (section in sections) {
                indexed += IndexedSection(section.bucket, section.tracks, emitted.size, order.size)
                if (headers) emitted += null
                emitted.addAll(section.tracks)
                order.addAll(section.tracks)
            }
            return SongsBrowseDerivation(songs, choice, indexed, order, headers, emitted)
        }
    }
}

// Sorting itself is synchronous. A rapid series of choices must not run several abandoned
// whole-library sorts at once on Dispatchers.Default; cancelled waiters never enter the work.
private val songsBrowsePreparation = Mutex()

/** withContext's return is cancellable: superseded choices cannot publish a stale result. */
internal suspend fun prepareSongsBrowse(
    songs: List<TrackDescriptor>,
    choice: SortChoice<SongSort>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): SongsBrowseDerivation = withContext(dispatcher) {
    songsBrowsePreparation.withLock {
        currentCoroutineContext().ensureActive()
        SongsBrowseDerivation.of(songs, choice)
    }
}

internal data class IndexedSection(
    val bucket: String,
    val tracks: List<TrackDescriptor>,
    /** Header position if shown, otherwise the first row's position. */
    val emitStartIndex: Int,
    val firstTrackGlobalIndex: Int,
)

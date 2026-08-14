/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * The Songs tab for large libraries: sorted per [sort], sticky index headers,
 * and an A–Z rail in its own pill — position-based navigation instead of
 * endless flinging (Fitts's law for ~1000-row lists). While dragging, the
 * letter appears beside the finger rather than over the list, so the titles
 * being scrubbed past stay readable. Recency sort drops the index entirely.
 *
 * Playing from here queues the tracks in the displayed order.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SectionedSongsList(
    songs: List<TrackDescriptor>,
    sort: SongSort,
    currentTrackId: TrackId?,
    /** Whether the player is audibly running; animates the current row's badge. */
    currentTrackPlaying: Boolean = false,
    contentPadding: PaddingValues,
    selectedTrackIds: Set<TrackId> = emptySet(),
    onToggleSelection: (TrackDescriptor) -> Unit = {},
    onStartSelection: (TrackDescriptor) -> Unit = {},
    onPlay: (queue: List<TrackDescriptor>, index: Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    val sections = remember(songs, sort) { SongSorting.sections(songs, sort) }
    val displayOrder = remember(sections) { sections.flatMap { it.tracks } }
    val indexed = remember(sections) { indexSections(sections) }
    val listState = rememberLazyListState()
    val showIndex = sort != SongSort.RECENT && sections.size > 1
    val sectionStarts = remember(indexed) { indexed.map(IndexedSection::emitStartIndex) }
    val sectionBuckets = remember(indexed) { indexed.map(IndexedSection::bucket) }
    val activeBucket by remember(indexed, listState) {
        derivedStateOf {
            currentRailBucketIndex(
                itemIndex = listState.firstVisibleItemIndex,
                startIndexes = sectionStarts,
                atEnd = !listState.canScrollForward && listState.firstVisibleItemIndex > 0,
            )
                ?.let { indexed[it].bucket }
        }
    }
    val selectionMode = selectedTrackIds.isNotEmpty()
    val reduceMotion = rememberReduceMotion()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Keeps row content — especially the overflow buttons — clear of
            // the rail so neither is hard to hit. Keep the inset inside the
            // scrolling content: outer padding exposed the page background as
            // a full-width strip directly above the mini-player on Tracks only.
            contentPadding = PaddingValues(
                end = if (showIndex) RailWidth + RailGap else 0.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            indexed.forEach { section ->
                if (showIndex) {
                    stickyHeader(key = "header-${section.bucket}", contentType = "header") {
                        SectionHeader(section.bucket)
                    }
                }
                itemsIndexed(
                    items = section.tracks,
                    key = { _, track -> track.id.value },
                    contentType = { _, _ -> "track" },
                ) { indexInSection, track ->
                    Box(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                            ),
                            placementSpec = if (reduceMotion) null else tween(Motion.APPEAR_MS),
                            fadeOutSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                            ),
                        ),
                    ) {
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = currentTrackPlaying,
                        onClick = {
                            if (selectionMode) {
                                onToggleSelection(track)
                            } else {
                                onPlay(displayOrder, section.firstTrackGlobalIndex + indexInSection)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) onToggleSelection(track) else onStartSelection(track)
                        },
                        selectionState = if (selectionMode) track.id in selectedTrackIds else null,
                        onMenu = if (selectionMode) null else ({ onTrackMenu(track) }),
                    )
                    }
                }
            }
        }

        if (showIndex) {
            AlphabetRailOverlay(
                buckets = sectionBuckets,
                bottomPadding = contentPadding.calculateBottomPadding(),
                activeBucket = activeBucket,
                onJump = { bucketIndex ->
                    listState.scrollToItem(indexed[bucketIndex].emitStartIndex)
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(bucket: String) {
    Text(
        text = bucket,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// ------------------------------------------------------------------ indexing

/** A section plus the positions it occupies, for rail jumps and queue mapping. */
private data class IndexedSection(
    val bucket: String,
    val tracks: List<TrackDescriptor>,
    /** Index of this section's HEADER in LazyColumn emission order. */
    val emitStartIndex: Int,
    /** Index of this section's first track within the flattened display order. */
    val firstTrackGlobalIndex: Int,
)

private fun indexSections(
    sections: List<io.github.nikitasud.latentjam.library.SongSection>,
): List<IndexedSection> {
    val indexed = mutableListOf<IndexedSection>()
    var emitIndex = 0
    var globalIndex = 0
    for (section in sections) {
        indexed += IndexedSection(
            bucket = section.bucket,
            tracks = section.tracks,
            emitStartIndex = emitIndex,
            firstTrackGlobalIndex = globalIndex,
        )
        emitIndex += 1 + section.tracks.size // header + rows
        globalIndex += section.tracks.size
    }
    return indexed
}

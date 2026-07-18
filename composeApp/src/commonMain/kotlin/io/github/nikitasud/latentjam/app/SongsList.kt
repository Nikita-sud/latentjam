/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val RailWidth = 26.dp
private val RailGap = 10.dp
private val BubbleSize = 56.dp

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
    contentPadding: PaddingValues,
    onPlay: (queue: List<TrackDescriptor>, index: Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    val sections = remember(songs, sort) { SongSorting.sections(songs, sort) }
    val displayOrder = remember(sections) { sections.flatMap { it.tracks } }
    val indexed = remember(sections) { indexSections(sections) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var previewBucket by remember { mutableStateOf<String?>(null) }
    var touchY by remember { mutableStateOf(0f) }
    var railTopPx by remember { mutableStateOf(0f) }
    val showIndex = sort != SongSort.RECENT && sections.size > 1

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Keeps row content — especially the overflow buttons — clear of
            // the rail so neither is hard to hit.
            contentPadding = PaddingValues(
                end = if (showIndex) RailWidth + RailGap else 0.dp,
                bottom = 12.dp,
            ),
            modifier = Modifier.fillMaxSize().padding(contentPadding),
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
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onClick = { onPlay(displayOrder, section.firstTrackGlobalIndex + indexInSection) },
                        onMenu = { onTrackMenu(track) },
                    )
                }
            }
        }

        if (showIndex) {
            AlphabetRail(
                buckets = indexed.map { it.bucket },
                activeBucket = previewBucket,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(contentPadding)
                    .padding(vertical = 8.dp, horizontal = 2.dp)
                    .onGloballyPositioned { railTopPx = it.positionInParent().y },
                onSelect = { bucketIndex, y ->
                    touchY = y
                    previewBucket = indexed[bucketIndex].bucket
                    scope.launch { listState.scrollToItem(indexed[bucketIndex].emitStartIndex) }
                },
                onSelectionEnd = { previewBucket = null },
            )

            // Letter bubble tracks the finger and sits beside the rail.
            previewBucket?.let { bucket ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = -(RailWidth + RailGap).roundToPx(),
                                y = (railTopPx + touchY - (BubbleSize / 2).toPx()).roundToInt(),
                            )
                        }
                        .size(BubbleSize),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = bucket,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
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

/** Letter rail inside a soft pill; drag or tap maps y-position to a bucket. */
@Composable
private fun AlphabetRail(
    buckets: List<String>,
    activeBucket: String?,
    modifier: Modifier = Modifier,
    onSelect: (index: Int, y: Float) -> Unit,
    onSelectionEnd: () -> Unit,
) {
    var railHeightPx by remember { mutableStateOf(0) }

    fun bucketIndexAt(y: Float): Int? {
        if (railHeightPx <= 0 || buckets.isEmpty()) return null
        return ((y / railHeightPx) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
    }

    Surface(
        modifier = modifier.width(RailWidth),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .onSizeChanged { railHeightPx = it.height }
                .pointerInput(buckets) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            bucketIndexAt(offset.y)?.let { onSelect(it, offset.y) }
                        },
                        onDrag = { change, _ ->
                            bucketIndexAt(change.position.y)?.let { onSelect(it, change.position.y) }
                        },
                        onDragEnd = onSelectionEnd,
                        onDragCancel = onSelectionEnd,
                    )
                }
                .pointerInput(buckets) {
                    detectTapGestures(
                        onPress = { offset ->
                            bucketIndexAt(offset.y)?.let { onSelect(it, offset.y) }
                            tryAwaitRelease()
                            onSelectionEnd()
                        },
                    )
                }
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            buckets.forEach { bucket ->
                val active = bucket == activeBucket
                Text(
                    text = bucket,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
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

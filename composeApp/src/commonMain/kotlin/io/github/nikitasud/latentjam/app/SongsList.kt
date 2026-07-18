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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.launch

/**
 * The Songs tab for large libraries: sorted per [sort], sticky index headers,
 * and a draggable A–Z rail with a preview bubble — position-based navigation
 * instead of endless flinging (Fitts's law for ~1000-row lists). Recency sort
 * drops the headers and rail, which have no meaning there.
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
    onShowAlbum: (TrackDescriptor) -> Unit,
    onShowArtist: (TrackDescriptor) -> Unit,
) {
    val sections = remember(songs, sort) { SongSorting.sections(songs, sort) }
    val displayOrder = remember(sections) { sections.flatMap { it.tracks } }
    val indexed = remember(sections) { indexSections(sections) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var previewBucket by remember { mutableStateOf<String?>(null) }
    val showIndex = sort != SongSort.RECENT && sections.size > 1

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
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
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onClick = { onPlay(displayOrder, section.firstTrackGlobalIndex + indexInSection) },
                        onShowAlbum = { onShowAlbum(track) },
                        onShowArtist = { onShowArtist(track) },
                    )
                }
            }
        }

        if (showIndex) {
            AlphabetRail(
                buckets = indexed.map { it.bucket },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(contentPadding),
                onSelect = { bucketIndex ->
                    previewBucket = indexed[bucketIndex].bucket
                    scope.launch {
                        listState.scrollToItem(indexed[bucketIndex].emitStartIndex)
                    }
                },
                onSelectionEnd = { previewBucket = null },
            )
        }

        previewBucket?.let { bucket ->
            Surface(
                modifier = Modifier.align(Alignment.Center).size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = bucket,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
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

/** Slim letter rail; drag or tap maps y-position to a bucket. */
@Composable
private fun AlphabetRail(
    buckets: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
    onSelectionEnd: () -> Unit,
) {
    var railHeightPx by remember { mutableStateOf(0) }

    fun bucketIndexAt(y: Float): Int? {
        if (railHeightPx <= 0 || buckets.isEmpty()) return null
        return ((y / railHeightPx) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
    }

    Column(
        modifier = modifier
            .width(24.dp)
            .onSizeChanged { railHeightPx = it.height }
            .pointerInput(buckets) {
                detectDragGestures(
                    onDragStart = { offset -> bucketIndexAt(offset.y)?.let(onSelect) },
                    onDrag = { change, _ -> bucketIndexAt(change.position.y)?.let(onSelect) },
                    onDragEnd = onSelectionEnd,
                    onDragCancel = onSelectionEnd,
                )
            }
            .pointerInput(buckets) {
                detectTapGestures(
                    onPress = { offset ->
                        bucketIndexAt(offset.y)?.let(onSelect)
                        tryAwaitRelease()
                        onSelectionEnd()
                    },
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        buckets.forEach { bucket ->
            Text(
                text = bucket,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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

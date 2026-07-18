/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.launch

/**
 * The Songs tab for large libraries: punctuation-normalized alphabetical
 * order, a sticky header per initial (Latin, Cyrillic, and a '#' bucket),
 * and a draggable A–Z rail with a preview bubble — position-based
 * navigation instead of endless flinging (Fitts's law for ~1000-row lists).
 *
 * Playing from here queues the tracks in the displayed (normalized) order.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SectionedSongsList(
    songs: List<TrackDescriptor>,
    currentTrackId: TrackId?,
    contentPadding: PaddingValues,
    onPlay: (queue: List<TrackDescriptor>, index: Int) -> Unit,
) {
    val sections = remember(songs) { buildSections(songs) }
    val displayOrder = remember(sections) { sections.flatMap { it.tracks } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var previewBucket by remember { mutableStateOf<Char?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEach { section ->
                stickyHeader(key = "header-${section.bucket}", contentType = "header") {
                    SectionHeader(section.bucket)
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
                    )
                }
            }
        }

        AlphabetRail(
            buckets = sections.map { it.bucket },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(contentPadding),
            onSelect = { bucketIndex ->
                previewBucket = sections[bucketIndex].bucket
                scope.launch { listState.scrollToItem(sections[bucketIndex].emitStartIndex) }
            },
            onSelectionEnd = { previewBucket = null },
        )

        previewBucket?.let { bucket ->
            Surface(
                modifier = Modifier.align(Alignment.Center).size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = bucket.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(bucket: Char) {
    Text(
        text = bucket.toString(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Slim letter rail; drag or tap maps y-position to a bucket. */
@Composable
private fun AlphabetRail(
    buckets: List<Char>,
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
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        buckets.forEach { bucket ->
            Text(
                text = bucket.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ sections

private data class SongSection(
    val bucket: Char,
    val tracks: List<TrackDescriptor>,
    /** Index of this section's HEADER in LazyColumn emission order. */
    val emitStartIndex: Int,
    /** Index of this section's first track within the flattened display order. */
    val firstTrackGlobalIndex: Int,
)

private fun buildSections(songs: List<TrackDescriptor>): List<SongSection> {
    val sorted = songs.sortedBy(::sortKey)
    val grouped = sorted.groupBy(::bucketOf)
    val sections = mutableListOf<SongSection>()
    var emitIndex = 0
    var globalIndex = 0
    for ((bucket, tracks) in grouped) {
        sections += SongSection(
            bucket = bucket,
            tracks = tracks,
            emitStartIndex = emitIndex,
            firstTrackGlobalIndex = globalIndex,
        )
        emitIndex += 1 + tracks.size // header + rows
        globalIndex += tracks.size
    }
    return sections
}

/** Leading punctuation is ignored so "(I Just)…" files under I, not "(". */
private fun sortKey(track: TrackDescriptor): String =
    (track.title ?: "")
        .trimStart { !it.isLetterOrDigit() }
        .lowercase()
        .ifEmpty { "￿" }

private fun bucketOf(track: TrackDescriptor): Char {
    val first = track.title?.firstOrNull { it.isLetterOrDigit() } ?: return '#'
    return if (first.isLetter()) first.uppercaseChar() else '#'
}

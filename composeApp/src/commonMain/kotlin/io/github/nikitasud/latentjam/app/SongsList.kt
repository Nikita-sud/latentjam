/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

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
    sortDirection: SongSortDirection,
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
    val sections = remember(songs, sort, sortDirection) {
        SongSorting.sections(songs, sort, sortDirection)
    }
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
    // Identity, not bucket equality: track counts/content can change while the labels stay equal.
    val railCatalogKey = remember(indexed) { Any() }
    val artworkLoadGate = remember(railCatalogKey) { ArtworkLoadGate() }
    val emittedTracks = remember(indexed) {
        buildList<TrackDescriptor?> {
            indexed.forEach { section ->
                add(null) // Sticky header occupies one LazyColumn emission index.
                addAll(section.tracks)
            }
        }
    }
    var railScrubbing by remember(railCatalogKey) { mutableStateOf(false) }
    var railPreviewBucketIndex by remember(railCatalogKey) { mutableStateOf<Int?>(null) }
    val railPreviewListState = rememberLazyListState()
    val previewVisibility = remember(railCatalogKey) { MutableTransitionState(false) }
    val previewRequested = railScrubbing && railPreviewBucketIndex != null
    previewVisibility.targetState = previewRequested
    val previewOccluding = previewRequested ||
        previewVisibility.currentState || previewVisibility.targetState
    val unknownTitle = stringResource(Res.string.track_untitled)
    val unknownArtist = stringResource(Res.string.track_unknown_artist)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = !previewOccluding,
            // Keeps row content — especially the overflow buttons — clear of
            // the rail so neither is hard to hit. Keep the inset inside the
            // scrolling content: outer padding exposed the page background as
            // a full-width strip directly above the mini-player on Tracks only.
            contentPadding = PaddingValues(
                end = if (showIndex) RailWidth + RailGap else 0.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .inactiveForMotion(previewOccluding),
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
                                    onPlay(
                                        displayOrder,
                                        section.firstTrackGlobalIndex + indexInSection,
                                    )
                                }
                            },
                            onLongClick = {
                                if (selectionMode) {
                                    onToggleSelection(track)
                                } else {
                                    onStartSelection(track)
                                }
                            },
                            selectionState = if (selectionMode) {
                                track.id in selectedTrackIds
                            } else {
                                null
                            },
                            onMenu = if (selectionMode) null else ({ onTrackMenu(track) }),
                            onArtworkLoadStateChanged = if (railScrubbing) {
                                { requestUri, state ->
                                    artworkLoadGate.record(
                                        key = ArtworkLoadKey(track.id.value, requestUri),
                                        state = state,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        RailScrubPreviewLayer(
            visibility = previewVisibility,
            listState = railPreviewListState,
            indexed = indexed,
            currentTrackId = currentTrackId,
            selectedTrackIds = if (selectionMode) selectedTrackIds else null,
            unknownTitle = unknownTitle,
            unknownArtist = unknownArtist,
            reduceMotion = reduceMotion,
            bottomPadding = contentPadding.calculateBottomPadding() + 12.dp,
        )

        if (showIndex) {
            AlphabetRailOverlay(
                buckets = sectionBuckets,
                catalogKey = railCatalogKey,
                bottomPadding = contentPadding.calculateBottomPadding(),
                activeBucket = activeBucket,
                onPreviewJump = { bucketIndex ->
                    indexed.getOrNull(bucketIndex)?.let { section ->
                        railPreviewBucketIndex = bucketIndex
                        // Multiple pointer events before a frame naturally coalesce to the latest
                        // request; only the lightweight mirror is remeasured during the drag.
                        railPreviewListState.requestScrollToItem(section.emitStartIndex)
                    }
                },
                onJump = onJump@ { bucketIndex, _ ->
                    val targetIndex = indexed.getOrNull(bucketIndex)?.emitStartIndex
                        ?: return@onJump
                    val loadCycle = artworkLoadGate.begin()
                    try {
                        // First let the opaque preview cover the old viewport. The real list then
                        // moves exactly once underneath it. Starting the cycle before that move
                        // retains even an immediate memory-cache callback from the target rows.
                        withFrameNanos { }
                        listState.scrollToItem(targetIndex)
                        withFrameNanos { }
                        // The mirror and real list share sticky/content-padding/row geometry, so
                        // no approximate-to-exact correction occurs. Only the actual final
                        // viewport participates in the readiness gate; canceled transient
                        // thumbnail requests cannot delay the final reveal.
                        val expectedArtwork = listState.layoutInfo.visibleItemsInfo
                            .mapNotNull { itemInfo ->
                                emittedTracks.getOrNull(itemInfo.index)?.artworkLoadKey()
                            }
                            .toSet()
                        artworkLoadGate.awaitFinished(
                            cycle = loadCycle,
                            expected = expectedArtwork,
                            minimumWaitMillis = Motion.QUICK_MS.toLong(),
                            maximumWaitMillis = Motion.APPEAR_MS.toLong(),
                        )
                        // Let a terminal callback publish its painter/fallback before reveal.
                        withFrameNanos { }
                    } finally {
                        artworkLoadGate.end(loadCycle)
                    }
                },
                onScrubbingChange = { railScrubbing = it },
            )
        }
    }
}

internal data class ArtworkLoadKey(
    /** Stable identity within the rail-backed surface (track, album, artist, and so on). */
    val itemId: String,
    val uri: String,
)

private fun TrackDescriptor.artworkLoadKey(): ArtworkLoadKey? = artworkUri?.let { uri ->
    ArtworkLoadKey(itemId = id.value, uri = uri)
}

/**
 * Artwork states observed only during one rail-release handoff. It is deliberately not read as
 * Compose state: image callbacks must not recompose the songs list while a gesture is settling.
 * Outside that bounded cycle [record] is a no-op, so ordinary scrolling retains no cover history.
 */
internal class ArtworkLoadGate {
    private data class Cycle(
        val id: Long,
        val states: MutableMap<ArtworkLoadKey, ArtworkLoadState> = mutableMapOf(),
    )

    private val revision = MutableStateFlow(0L)
    private var nextCycleId = 0L
    private var activeCycle: Cycle? = null

    /** Enables a short collection window; normal list scrolling retains no artwork state. */
    fun begin(): Long {
        val cycle = Cycle(id = ++nextCycleId)
        activeCycle = cycle
        revision.value += 1L
        return cycle.id
    }

    fun record(key: ArtworkLoadKey, state: ArtworkLoadState) {
        val cycle = activeCycle ?: return
        cycle.states[key] = state
        revision.value += 1L
    }

    /** A stale gesture cannot close the collection window opened by a newer one. */
    fun end(cycle: Long) {
        if (activeCycle?.id == cycle) {
            activeCycle = null
            revision.value += 1L
        }
    }

    /** Waits for both the minimum handoff time and all expected terminals, capped absolutely. */
    suspend fun awaitFinished(
        cycle: Long,
        expected: Set<ArtworkLoadKey>,
        minimumWaitMillis: Long,
        maximumWaitMillis: Long,
    ) {
        require(minimumWaitMillis >= 0L)
        require(maximumWaitMillis >= minimumWaitMillis)
        if (expected.isEmpty()) return

        coroutineScope {
            activeCycle
                ?.takeIf { it.id == cycle }
                ?.states
                ?.keys
                ?.retainAll(expected)
            val allFinished = async {
                revision.first {
                    activeCycle
                        ?.takeIf { it.id == cycle }
                        ?.states
                        ?.let { states ->
                            expected.all { key -> states[key] == ArtworkLoadState.TERMINAL }
                        } == true
                }
            }
            delay(minimumWaitMillis)
            if (!allFinished.isCompleted) {
                withTimeoutOrNull(maximumWaitMillis - minimumWaitMillis) {
                    allFinished.await()
                }
            } else {
                allFinished.await()
            }
            allFinished.cancel()
        }
    }
}

/**
 * Opaque lightweight mirror of the real list. Identical LazyColumn/sticky-header geometry makes
 * end clamping stable throughout drag and release while the interactive real viewport stays
 * frozen. Its only asynchronous work is constrained 48dp artwork thumbnails.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailScrubPreviewLayer(
    visibility: MutableTransitionState<Boolean>,
    listState: LazyListState,
    indexed: List<IndexedSection>,
    currentTrackId: TrackId?,
    selectedTrackIds: Set<TrackId>?,
    unknownTitle: String,
    unknownArtist: String,
    reduceMotion: Boolean,
    bottomPadding: Dp,
) {
    AnimatedVisibility(
        visibleState = visibility,
        // It must cover in the first frame; a translucent enter exposes the hidden list jump on
        // a very fast gesture. Only the final reveal dissolves.
        enter = EnterTransition.None,
        exit = fadeOut(
            tween(if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surface)
                .inactiveForMotion(true),
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(
                    end = RailWidth + RailGap,
                    bottom = bottomPadding,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                indexed.forEach { section ->
                    stickyHeader(
                        key = "rail-preview-header-${section.bucket}",
                        contentType = "header",
                    ) {
                        SectionHeader(section.bucket)
                    }
                    itemsIndexed(
                        items = section.tracks,
                        key = { _, track -> "rail-preview-${track.id.value}" },
                        contentType = { _, _ -> "track" },
                    ) { _, track ->
                        RailScrubPreviewTrackRow(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            selectionState = selectedTrackIds?.let { track.id in it },
                            unknownTitle = unknownTitle,
                            unknownArtist = unknownArtist,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RailScrubPreviewTrackRow(
    track: TrackDescriptor,
    isCurrent: Boolean,
    selectionState: Boolean?,
    unknownTitle: String,
    unknownArtist: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionState != null) {
            Icon(
                imageVector = if (selectionState) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (selectionState) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        // The mirror stays cheap (no gestures, menus, selection motion, or row animations), but
        // still asks Coil only for the same 48dp thumbnail the user expects to follow the rail.
        // Disposed transient rows cancel their requests; the final viewport is then already warm.
        Artwork(uri = track.artworkUri, size = 48.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track.title ?: unknownTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist ?: unknownArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionState == null) {
            // TrackRow has a 12dp arrangement gap before its 48dp menu button.
            Spacer(modifier = Modifier.width(60.dp))
        } else {
            track.durationMs?.let { durationMs ->
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_deselect_all
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_select_all
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle
import io.github.nikitasud.latentjam.app.generated.resources.selection_count
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.jetbrains.compose.resources.stringResource

/**
 * What the user drilled into from a browse tab; playing from here scopes the
 * queue to exactly these tracks.
 */
/** One labelled stretch of a collection's track list — an artist's album, for instance. */
data class CollectionSection(
    val title: String,
    val tracks: List<TrackDescriptor>,
    /** Raw metadata used by the rail; null stays the final `?` bucket after localization. */
    val railTitle: String? = title,
)

/** What the detail rail indexes; semantic/manual orders deliberately use [NONE]. */
enum class CollectionRailMode {
    NONE,
    TRACK_TITLES,
    SECTION_TITLES,
}

data class CollectionSelection(
    val title: String,
    val subtitle: String?,
    val artworkUri: String?,
    val tracks: List<TrackDescriptor>,
    /**
     * When set, the list renders under these headers. Their concatenation IS [tracks] — playback
     * and selection keep working in flat indices, only the presentation gains structure.
     */
    val sections: List<CollectionSection>? = null,
    /** Explicit opt-in: not every collection order is alphabetic or safe to re-index. */
    val railMode: CollectionRailMode = CollectionRailMode.NONE,
    /**
     * Whether long-pressing a track starts checkbox multi-selection. True for every drill-in
     * today — playlists, albums, artists, genres, folders — so the same gesture means the same
     * thing on every track list. Kept as a flag for future read-only surfaces.
     */
    val allowsTrackSelection: Boolean = false,
    /**
     * Set only when this collection IS a user playlist. Albums, artists, genres, folders and
     * auto playlists leave it null — membership there is derived from tags or listening, so
     * "remove from this playlist" would be a lie the UI cannot honour.
     */
    val playlistId: String? = null,
    /**
     * Stable, kind-qualified navigation identity. A data-class [copy] retains the resolved value
     * while live edits reconcile without replaying the page transition.
     */
    val routeId: String = playlistId?.let { "playlist:$it" }
        ?: "collection:$title:${tracks.firstOrNull()?.id?.value.orEmpty()}",
)

/**
 * Reconciles a live collection atomically. Section rows and the flat playback queue must always
 * describe the same tracks; updating just one side makes rail anchors and click indices stale.
 */
internal fun CollectionSelection.filterTracksForCollection(
    retain: (TrackDescriptor) -> Boolean,
): CollectionSelection? {
    val retainedSections = sections?.mapNotNull { section ->
        section.copy(tracks = section.tracks.filter(retain))
            .takeIf { it.tracks.isNotEmpty() }
    }
    val retainedTracks = retainedSections
        ?.flatMap { it.tracks }
        ?: tracks.filter(retain)
    if (retainedTracks.isEmpty()) return null

    // A single section no longer needs a header; its tracks become the ordinary title rail.
    val shownSections = retainedSections?.takeIf { it.size > 1 }
    return copy(
        tracks = retainedTracks,
        sections = shownSections,
        railMode = if (
            railMode == CollectionRailMode.SECTION_TITLES && shownSections == null
        ) {
            CollectionRailMode.TRACK_TITLES
        } else {
            railMode
        },
    )
}

internal data class CollectionRailPresentation(
    val rail: RailIndex,
    /** One entry per LazyColumn emission: actions/header rows deliberately carry null. */
    val artworkKeys: List<ArtworkLoadKey?>,
)

internal fun collectionRailPresentation(
    selection: CollectionSelection,
): CollectionRailPresentation {
    if (selection.railMode == CollectionRailMode.NONE) {
        return CollectionRailPresentation(RailIndex(emptyList(), emptyList()), emptyList())
    }

    val artworkKeys = buildList<ArtworkLoadKey?> {
        add(null) // Actions row.
        val sections = selection.sections
        if (sections == null) {
            selection.tracks.forEachIndexed { index, track ->
                add(track.artworkUri?.let { uri ->
                    ArtworkLoadKey(
                        itemId = "${selection.routeId}:$index:${track.id.value}",
                        uri = uri,
                    )
                })
            }
        } else {
            var flatIndex = 0
            sections.forEach { section ->
                add(null) // Section header.
                section.tracks.forEach { track ->
                    add(track.artworkUri?.let { uri ->
                        ArtworkLoadKey(
                            itemId = "${selection.routeId}:$flatIndex:${track.id.value}",
                            uri = uri,
                        )
                    })
                    flatIndex++
                }
            }
        }
    }

    val rail = when (selection.railMode) {
        CollectionRailMode.NONE -> RailIndex(emptyList(), emptyList())
        CollectionRailMode.TRACK_TITLES -> {
            val direct = railIndexOf(selection.tracks.map { it.title })
            direct.copy(startIndexes = direct.startIndexes.map { it + 1 })
        }
        CollectionRailMode.SECTION_TITLES -> {
            val sections = selection.sections.orEmpty()
            val headerIndexes = buildList {
                var emitted = 1 // Actions row.
                sections.forEach { section ->
                    add(emitted)
                    emitted += 1 + section.tracks.size
                }
            }
            val direct = railIndexOf(sections.map { it.railTitle })
            direct.copy(
                startIndexes = direct.startIndexes.mapNotNull(headerIndexes::getOrNull),
            )
        }
    }
    return CollectionRailPresentation(rail = rail, artworkKeys = artworkKeys)
}

/**
 * Full-screen detail for one album / artist / genre: header with artwork and
 * play-all, then the track list. One screen serves all three collection
 * kinds — they differ only in header data.
 */
@Composable
fun CollectionDetailScreen(
    selection: CollectionSelection,
    currentTrackId: TrackId?,
    /** Whether the player is audibly running; animates the current row's badge. */
    currentTrackPlaying: Boolean = false,
    selectedTrackIds: Set<TrackId> = emptySet(),
    onToggleSelection: (TrackDescriptor) -> Unit = {},
    onStartSelection: (TrackDescriptor) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onToggleAllSelection: () -> Unit = {},
    onPlayTrack: (Int) -> Unit,
    onShuffle: () -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onClose: () -> Unit,
    /** Room at the foot of the list for the mini-player floating over this screen. */
    bottomInset: Dp = 0.dp,
) {
    val selectionMode = selection.allowsTrackSelection && selectedTrackIds.isNotEmpty()
    val reduceMotion = rememberReduceMotion()
    PlatformBackHandler(enabled = true) {
        if (selectionMode) onClearSelection() else onClose()
    }

    // Opening a collection that contains the player's track lands on that track — the reason to
    // open the album of what's playing is almost always to see where in it you are. Keyed on route
    // identity so reconciliation copies (a deleted track, a live playlist edit) never yank the
    // list, while go-to-album from another collection re-anchors.
    val listState = rememberLazyListState()
    LaunchedEffect(selection.routeId) {
        val flat = selection.tracks.indexOfFirst { it.id == currentTrackId }
        if (flat < 0) return@LaunchedEffect
        // Translate the flat track position into a list-item position: the actions row comes
        // first, and sectioned lists interleave one header before each section.
        val sections = selection.sections
        val item = if (sections == null) {
            flat + 1
        } else {
            var running = 0
            var headers = 1
            for (section in sections) {
                if (flat < running + section.tracks.size) break
                running += section.tracks.size
                headers++
            }
            flat + headers + 1
        }
        // Two rows of context above the anchored track.
        listState.scrollToItem((item - 2).coerceAtLeast(0))
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // Title and count sit in the bar itself rather than under a large cover: this screen
            // is a list you came to play, and a hero image would push the first track off-screen.
            AnimatedContent(
                targetState = SelectionBarPresentation(
                    selecting = selectionMode,
                    count = selectedTrackIds.size,
                    allSelected = selection.tracks.all { it.id in selectedTrackIds },
                ),
                contentKey = { it.selecting },
                transitionSpec = { motionFadeThrough(reduceMotion) },
                label = "collection-contextual-app-bar",
            ) { bar ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inactiveForMotion(bar.selecting != selectionMode),
                ) {
                if (bar.selecting) {
                    SelectionTopAppBar(
                        count = bar.count,
                        allSelected = bar.allSelected,
                        onClose = onClearSelection,
                        onToggleAll = onToggleAllSelection,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(Res.string.action_back),
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                text = selection.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            selection.subtitle?.let { subtitle ->
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                }
            }

            val railPresentation = remember(
                selection.routeId,
                selection.tracks,
                selection.sections,
                selection.railMode,
            ) {
                collectionRailPresentation(selection)
            }
            val railCatalogKey = remember(railPresentation) { Any() }
            val listContentPadding = PaddingValues(bottom = bottomInset)
            if (selection.railMode != CollectionRailMode.NONE &&
                railPresentation.rail.buckets.size > 1
            ) {
                ListWithRail(
                    rail = railPresentation.rail,
                    catalogKey = railCatalogKey,
                    artworkKeys = railPresentation.artworkKeys,
                    contentPadding = listContentPadding,
                    listState = listState,
                ) { railPadding, shownListState, artworkReporter, isPreview ->
                    CollectionTrackLazyColumn(
                        selection = selection,
                        listState = shownListState,
                        contentPadding = railPadding,
                        selectionMode = selectionMode,
                        selectedTrackIds = selectedTrackIds,
                        currentTrackId = currentTrackId,
                        currentTrackPlaying = currentTrackPlaying,
                        isPreview = isPreview,
                        artworkReporter = artworkReporter,
                        onToggleSelection = onToggleSelection,
                        onStartSelection = onStartSelection,
                        onPlayTrack = onPlayTrack,
                        onShuffle = onShuffle,
                        onTrackMenu = onTrackMenu,
                    )
                }
            } else {
                CollectionTrackLazyColumn(
                    selection = selection,
                    listState = listState,
                    contentPadding = listContentPadding,
                    selectionMode = selectionMode,
                    selectedTrackIds = selectedTrackIds,
                    currentTrackId = currentTrackId,
                    currentTrackPlaying = currentTrackPlaying,
                    isPreview = false,
                    artworkReporter = null,
                    onToggleSelection = onToggleSelection,
                    onStartSelection = onStartSelection,
                    onPlayTrack = onPlayTrack,
                    onShuffle = onShuffle,
                    onTrackMenu = onTrackMenu,
                )
            }
        }
    }
}

@Composable
private fun CollectionTrackLazyColumn(
    selection: CollectionSelection,
    listState: LazyListState,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    isPreview: Boolean,
    artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)?,
    onToggleSelection: (TrackDescriptor) -> Unit,
    onStartSelection: (TrackDescriptor) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onShuffle: () -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val unknownTitle = stringResource(Res.string.track_untitled)
    val unknownArtist = stringResource(Res.string.track_unknown_artist)
    LazyColumn(state = listState, contentPadding = contentPadding) {
        item(key = "actions") {
            // Shuffle and play live on their own rounded surface, mirroring the Tracks tab.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = onShuffle,
                        enabled = !selectionMode,
                    ) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = stringResource(Res.string.action_shuffle),
                        )
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                    FilledIconButton(
                        onClick = { onPlayTrack(0) },
                        enabled = !selectionMode,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(Res.string.action_play),
                        )
                    }
                }
            }
        }
        val sections = selection.sections
        if (sections == null) {
            itemsIndexed(
                selection.tracks,
                key = { _, track -> track.id.value },
            ) { index, track ->
                val artworkKey = track.artworkUri?.let { uri ->
                    ArtworkLoadKey(
                        itemId = "${selection.routeId}:$index:${track.id.value}",
                        uri = uri,
                    )
                }
                Box(
                    modifier = if (!isPreview) {
                        Modifier.animateItem(
                            fadeInSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                            ),
                            placementSpec = if (reduceMotion) {
                                null
                            } else {
                                tween(Motion.APPEAR_MS)
                            },
                            fadeOutSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                            ),
                        )
                    } else {
                        Modifier
                    },
                ) {
                    val showDivider = index < selection.tracks.lastIndex
                    if (isPreview) {
                        Column {
                            RailScrubPreviewTrackRow(
                                track = track,
                                isCurrent = track.id == currentTrackId,
                                selectionState = if (selectionMode) {
                                    track.id in selectedTrackIds
                                } else {
                                    null
                                },
                                unknownTitle = unknownTitle,
                                unknownArtist = unknownArtist,
                            )
                            if (showDivider) CollectionTrackDivider()
                        }
                    } else {
                        CollectionTrackRow(
                            track = track,
                            flatIndex = index,
                            showDivider = showDivider,
                            selection = selection,
                            selectionMode = selectionMode,
                            selectedTrackIds = selectedTrackIds,
                            currentTrackId = currentTrackId,
                            currentTrackPlaying = currentTrackPlaying,
                            onArtworkLoadStateChanged = artworkReporter?.let { report ->
                                artworkKey?.let { expectedKey ->
                                    { requestUri, state ->
                                        report(expectedKey.copy(uri = requestUri), state)
                                    }
                                }
                            },
                            onToggleSelection = onToggleSelection,
                            onStartSelection = onStartSelection,
                            onPlayTrack = onPlayTrack,
                            onTrackMenu = onTrackMenu,
                        )
                    }
                }
            }
        } else {
            var base = 0
            sections.forEachIndexed { sectionIndex, section ->
                val sectionBase = base
                item(key = "section-$sectionIndex") {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 4.dp,
                        ),
                    )
                }
                itemsIndexed(
                    section.tracks,
                    key = { _, track -> "$sectionIndex:${track.id.value}" },
                ) { indexInSection, track ->
                    val flatIndex = sectionBase + indexInSection
                    val artworkKey = track.artworkUri?.let { uri ->
                        ArtworkLoadKey(
                            itemId = "${selection.routeId}:$flatIndex:${track.id.value}",
                            uri = uri,
                        )
                    }
                    Box(
                        modifier = if (!isPreview) {
                            Modifier.animateItem(
                                fadeInSpec = tween(
                                    if (reduceMotion) {
                                        Motion.REDUCED_MS
                                    } else {
                                        Motion.APPEAR_MS
                                    },
                                ),
                                placementSpec = if (reduceMotion) {
                                    null
                                } else {
                                    tween(Motion.APPEAR_MS)
                                },
                                fadeOutSpec = tween(
                                    if (reduceMotion) {
                                        Motion.REDUCED_MS
                                    } else {
                                        Motion.REPLACE_MS
                                    },
                                ),
                            )
                        } else {
                            Modifier
                        },
                    ) {
                        val showDivider = indexInSection < section.tracks.lastIndex
                        if (isPreview) {
                            Column {
                                RailScrubPreviewTrackRow(
                                    track = track,
                                    isCurrent = track.id == currentTrackId,
                                    selectionState = if (selectionMode) {
                                        track.id in selectedTrackIds
                                    } else {
                                        null
                                    },
                                    unknownTitle = unknownTitle,
                                    unknownArtist = unknownArtist,
                                )
                                if (showDivider) CollectionTrackDivider()
                            }
                        } else {
                            CollectionTrackRow(
                                track = track,
                                flatIndex = flatIndex,
                                showDivider = showDivider,
                                selection = selection,
                                selectionMode = selectionMode,
                                selectedTrackIds = selectedTrackIds,
                                currentTrackId = currentTrackId,
                                currentTrackPlaying = currentTrackPlaying,
                                onArtworkLoadStateChanged = artworkReporter?.let { report ->
                                    artworkKey?.let { expectedKey ->
                                        { requestUri, state ->
                                            report(expectedKey.copy(uri = requestUri), state)
                                        }
                                    }
                                },
                                onToggleSelection = onToggleSelection,
                                onStartSelection = onStartSelection,
                                onPlayTrack = onPlayTrack,
                                onTrackMenu = onTrackMenu,
                            )
                        }
                    }
                }
                base += section.tracks.size
            }
        }
    }
}

/** One track row of a collection, flat or sectioned — the selection wiring is identical. */
@Composable
private fun CollectionTrackRow(
    track: TrackDescriptor,
    flatIndex: Int,
    showDivider: Boolean,
    selection: CollectionSelection,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)?,
    onToggleSelection: (TrackDescriptor) -> Unit,
    onStartSelection: (TrackDescriptor) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    TrackRow(
        track = track,
        isCurrent = track.id == currentTrackId,
        isPlaying = currentTrackPlaying,
        onArtworkLoadStateChanged = onArtworkLoadStateChanged,
        onClick = {
            if (selectionMode) onToggleSelection(track) else onPlayTrack(flatIndex)
        },
        onLongClick = if (selection.allowsTrackSelection) {
            {
                if (selectionMode) {
                    onToggleSelection(track)
                } else {
                    onStartSelection(track)
                }
            }
        } else {
            { onTrackMenu(track) }
        },
        selectionState = if (selectionMode) {
            track.id in selectedTrackIds
        } else {
            null
        },
        onMenu = if (selectionMode) null else ({ onTrackMenu(track) }),
    )
    if (showDivider) {
        CollectionTrackDivider()
    }
}

@Composable
private fun CollectionTrackDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 88.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** Data retained with a contextual bar while it exits, avoiding a visible "0 selected" frame. */
internal data class SelectionBarPresentation(
    val selecting: Boolean,
    val count: Int,
    val allSelected: Boolean,
)

/** Contextual app bar shared by the Tracks page and user-playlist multi-selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopAppBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    TopAppBar(
        title = {
            AnimatedContent(
                targetState = count,
                transitionSpec = { motionFadeThrough(reduceMotion) },
                label = "selection-count",
            ) { currentCount ->
                Text(
                    text = stringResource(Res.string.selection_count, currentCount),
                    modifier = Modifier.inactiveForMotion(currentCount != count),
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                )
            }
        },
        actions = {
            val description = stringResource(
                if (allSelected) {
                    Res.string.action_deselect_all
                } else {
                    Res.string.action_select_all
                },
            )
            IconButton(
                onClick = onToggleAll,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                AnimatedContent(
                    targetState = allSelected,
                    transitionSpec = { motionIconTransform(reduceMotion) },
                    label = "select-all-glyph",
                ) { selected ->
                    Icon(
                        imageVector = if (selected) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.inactiveForMotion(selected != allSelected),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

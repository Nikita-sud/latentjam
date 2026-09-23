/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.action_change_playlist_cover
import io.github.nikitasud.latentjam.app.generated.resources.action_reset_playlist_cover
import io.github.nikitasud.latentjam.app.generated.resources.action_smart_keep_together
import io.github.nikitasud.latentjam.app.generated.resources.cd_more_options
import io.github.nikitasud.latentjam.app.generated.resources.map_action_smart_here
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
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
import org.jetbrains.compose.resources.pluralStringResource
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
    /** Explicit Play action starts in order; tapping a track preserves the current mode. */
    onPlay: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onShuffle: () -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onClose: () -> Unit,
    /** Room at the foot of the list for the mini-player floating over this screen. */
    bottomInset: Dp = 0.dp,
    currentAccent: Color? = null,
    accent: TrackAccent? = null,
    onStartSmart: (() -> Unit)? = null,
    onChangeCover: (() -> Unit)? = null,
    onResetCover: (() -> Unit)? = null,
    coverEditBusy: Boolean = false,
    onToggleSmart: (() -> Unit)? = null,
    includeInSmart: Boolean = false,
    active: Boolean = true,
    /** Includes the shared cover transition, which can outlive the page's own entrance. */
    entrySettled: Boolean = true,
    artworkModifier: Modifier = Modifier,
) {
    val selectionMode = selection.allowsTrackSelection && selectedTrackIds.isNotEmpty()
    val reduceMotion = rememberReduceMotion()
    PlatformBackHandler(enabled = active) {
        if (selectionMode) onClearSelection() else onClose()
    }

    // A new collection opens with its artwork and actions. Content reconciliation keeps the
    // route identity, so a library refresh never resets a listener's deliberate scroll position.
    val listState = rememberLazyListState()
    var scrollRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selection.routeId) {
        // A return from Now Playing restores both values; only a different route starts at
        // its hero, rather than resetting the saved position when this screen re-enters.
        if (scrollRouteId != selection.routeId) {
            listState.scrollToItem(0)
            scrollRouteId = selection.routeId
        }
    }
    var optionsOpen by remember(selection.routeId) { mutableStateOf(false) }
    val entryRevealModifier = playlistEntryRevealModifier(
        selection = selection,
        currentTrackId = currentTrackId,
        listState = listState,
        active = active,
        entrySettled = entrySettled,
    )

    Surface(
        modifier = Modifier.fillMaxSize().then(entryRevealModifier)
            .background(MaterialTheme.colorScheme.surface).browseCloud(accent),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // The quiet navigation bar leaves the collection's name with its artwork.
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
                        modifier = Modifier.fillMaxWidth().statusBarsPadding()
                            .heightIn(min = 56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(Res.string.action_back))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { optionsOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(Res.string.cd_more_options))
                            }
                            DropdownMenu(
                                expanded = optionsOpen,
                                onDismissRequest = { optionsOpen = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                onChangeCover?.let { changeCover ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.action_change_playlist_cover)) },
                                        leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(20.dp)) },
                                        enabled = !coverEditBusy,
                                        onClick = { optionsOpen = false; changeCover() },
                                    )
                                }
                                onResetCover?.let { resetCover ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.action_reset_playlist_cover)) },
                                        leadingIcon = { Icon(Icons.Outlined.Restore, null, Modifier.size(20.dp)) },
                                        enabled = !coverEditBusy,
                                        onClick = { optionsOpen = false; resetCover() },
                                    )
                                }
                                onToggleSmart?.let { toggleSmart ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.action_smart_keep_together)) },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, Modifier.size(20.dp))
                                        },
                                        trailingIcon = if (includeInSmart) {
                                            { Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)) }
                                        } else null,
                                        onClick = { optionsOpen = false; toggleSmart() },
                                    )
                                }
                                if (onChangeCover != null) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.action_play)) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)) },
                                    onClick = { optionsOpen = false; onPlay() },
                                    enabled = selection.tracks.isNotEmpty(),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.action_shuffle)) },
                                    leadingIcon = { Icon(Icons.Outlined.Shuffle, null, Modifier.size(20.dp)) },
                                    onClick = { optionsOpen = false; onShuffle() },
                                    enabled = selection.tracks.isNotEmpty(),
                                )
                                if (selection.allowsTrackSelection) DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.action_select_all)) },
                                    leadingIcon = { Icon(Icons.Outlined.SelectAll, null, Modifier.size(20.dp)) },
                                    onClick = { optionsOpen = false; onToggleAllSelection() },
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (selection.railMode != CollectionRailMode.NONE &&
                    railPresentation.rail.buckets.size > 1
                ) {
                    ListWithRail(
                        rail = railPresentation.rail,
                        catalogKey = railCatalogKey,
                        artworkKeys = railPresentation.artworkKeys,
                        contentPadding = listContentPadding,
                        listState = listState,
                        // This screen is a full-bleed Surface, not the tab panel.
                    ) { railPadding, shownListState, artworkReporter, isPreview ->
                        CollectionTrackLazyColumn(
                            artworkModifier = if (isPreview) Modifier else artworkModifier,
                            onChangeCover = if (!isPreview && !coverEditBusy) onChangeCover else null,
                            selection = selection,
                            listState = shownListState,
                            contentPadding = railPadding,
                            selectionMode = selectionMode,
                            selectedTrackIds = selectedTrackIds,
                            currentTrackId = currentTrackId,
                            currentTrackPlaying = currentTrackPlaying,
                            currentAccent = currentAccent,
                            isPreview = isPreview,
                            artworkReporter = artworkReporter,
                            onToggleSelection = onToggleSelection,
                            onStartSelection = onStartSelection,
                            onPlay = onPlay,
                            onPlayTrack = onPlayTrack,
                            onShuffle = onShuffle,
                            onStartSmart = onStartSmart,
                            onTrackMenu = onTrackMenu,
                        )
                    }
                } else {
                    CollectionTrackLazyColumn(
                        artworkModifier = artworkModifier,
                        modifier = Modifier.fadingListTop { listState.canScrollBackward },
                        onChangeCover = if (!coverEditBusy) onChangeCover else null,
                        selection = selection,
                        listState = listState,
                        contentPadding = listContentPadding,
                        selectionMode = selectionMode,
                        selectedTrackIds = selectedTrackIds,
                        currentTrackId = currentTrackId,
                        currentTrackPlaying = currentTrackPlaying,
                        currentAccent = currentAccent,
                        isPreview = false,
                        artworkReporter = null,
                        onToggleSelection = onToggleSelection,
                        onStartSelection = onStartSelection,
                        onPlay = onPlay,
                        onPlayTrack = onPlayTrack,
                        onShuffle = onShuffle,
                        onStartSmart = onStartSmart,
                        onTrackMenu = onTrackMenu,
                    )
                }
                ScrollToTopButton(
                    listState = listState,
                    bottomInset = bottomInset,
                )
            }
        }
    }
}

@Composable
private fun CollectionHero(
    selection: CollectionSelection,
    artworkModifier: Modifier,
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onStartSmart: (() -> Unit)?,
    onChangeCover: (() -> Unit)?,
) {
    val albumYear = remember(selection.tracks) {
        selection.tracks.mapNotNull { it.year }.distinct().singleOrNull()?.toString()
    }
    val metadata = if (selection.routeId.startsWith("album:")) {
        listOfNotNull(selection.subtitle, albumYear,
            pluralStringResource(Res.plurals.count_tracks, selection.tracks.size, selection.tracks.size))
            .joinToString(" · ")
    } else selection.subtitle
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val changeCoverLabel = stringResource(Res.string.action_change_playlist_cover)
        Box(
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).then(
                if (onChangeCover != null && enabled) {
                    Modifier.clickable(onClickLabel = changeCoverLabel, onClick = onChangeCover)
                } else Modifier,
            ),
        ) {
            Artwork(
                uri = selection.artworkUri,
                size = 176.dp,
                cornerRadius = 24.dp,
                modifier = artworkModifier,
            )
            if (onChangeCover != null && enabled) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = changeCoverLabel,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                        .padding(7.dp).size(18.dp),
                )
            }
        }
        Text(
            text = selection.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
        metadata?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FilledTonalIconButton(
                onClick = onShuffle,
                enabled = enabled && selection.tracks.isNotEmpty(),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(Icons.Rounded.Shuffle, stringResource(Res.string.action_shuffle), modifier = Modifier.size(21.dp))
            }
            FilledIconButton(
                onClick = onPlay,
                enabled = enabled && selection.tracks.isNotEmpty(),
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(Icons.Rounded.PlayArrow, stringResource(Res.string.action_play), modifier = Modifier.size(25.dp))
            }
            if (onStartSmart != null) FilledTonalIconButton(
                onClick = onStartSmart,
                enabled = enabled && selection.tracks.isNotEmpty(),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Icon(LatentJamMark, stringResource(Res.string.map_action_smart_here), modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun CollectionTrackLazyColumn(
    selection: CollectionSelection,
    artworkModifier: Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    currentAccent: Color?,
    isPreview: Boolean,
    artworkReporter: ((ArtworkLoadKey, ArtworkLoadState) -> Unit)?,
    onToggleSelection: (TrackDescriptor) -> Unit,
    onStartSelection: (TrackDescriptor) -> Unit,
    onPlay: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onShuffle: () -> Unit,
    onStartSmart: (() -> Unit)?,
    onTrackMenu: (TrackDescriptor) -> Unit,
    modifier: Modifier = Modifier,
    onChangeCover: (() -> Unit)? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val unknownTitle = stringResource(Res.string.track_untitled)
    val unknownArtist = stringResource(Res.string.track_unknown_artist)
    LazyColumn(modifier = modifier, state = listState, contentPadding = contentPadding) {
        item(key = "collection-hero", contentType = "collection-hero") {
            CollectionHero(
                artworkModifier = artworkModifier,
                onChangeCover = onChangeCover,
                selection = selection,
                enabled = !selectionMode && !isPreview,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onStartSmart = onStartSmart,
            )
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
                    if (isPreview) {
                        Column {
                            RailScrubPreviewTrackRow(
                                track = track,
                                isCurrent = track.id == currentTrackId,
                                currentAccent = if (track.id == currentTrackId) currentAccent else null,
                                selectionState = if (selectionMode) {
                                    track.id in selectedTrackIds
                                } else {
                                    null
                                },
                                unknownTitle = unknownTitle,
                                unknownArtist = unknownArtist,
                                secondaryText = if (selection.routeId.startsWith("album:")) track.durationMs?.let(::formatDuration) else null,
                                showDuration = !selection.routeId.startsWith("album:"),
                            )
                        }
                    } else {
                        CollectionTrackRow(
                            track = track,
                            flatIndex = index,
                            selection = selection,
                            selectionMode = selectionMode,
                            selectedTrackIds = selectedTrackIds,
                            currentTrackId = currentTrackId,
                            currentTrackPlaying = currentTrackPlaying,
                            currentAccent = currentAccent,
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
                            start = 20.dp,
                            end = 20.dp,
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
                        if (isPreview) {
                            Column {
                                RailScrubPreviewTrackRow(
                                    track = track,
                                    isCurrent = track.id == currentTrackId,
                                    currentAccent = if (track.id == currentTrackId) currentAccent else null,
                                    selectionState = if (selectionMode) {
                                        track.id in selectedTrackIds
                                    } else {
                                        null
                                    },
                                    unknownTitle = unknownTitle,
                                    unknownArtist = unknownArtist,
                                    secondaryText = if (selection.routeId.startsWith("album:")) track.durationMs?.let(::formatDuration) else null,
                                    showDuration = !selection.routeId.startsWith("album:"),
                                )
                            }
                        } else {
                            CollectionTrackRow(
                                track = track,
                                flatIndex = flatIndex,
                                    selection = selection,
                                selectionMode = selectionMode,
                                selectedTrackIds = selectedTrackIds,
                                currentTrackId = currentTrackId,
                                currentTrackPlaying = currentTrackPlaying,
                                currentAccent = currentAccent,
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
    selection: CollectionSelection,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    currentAccent: Color?,
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)?,
    onToggleSelection: (TrackDescriptor) -> Unit,
    onStartSelection: (TrackDescriptor) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
) {
    TrackRow(
        track = track,
        isCurrent = track.id == currentTrackId,
        isPlaying = currentTrackPlaying && track.id == currentTrackId,
        currentAccent = if (track.id == currentTrackId) currentAccent else null,
        onArtworkLoadStateChanged = onArtworkLoadStateChanged,
        secondaryText = if (selection.routeId.startsWith("album:")) track.durationMs?.let(::formatDuration) else null,
        showDuration = !selection.routeId.startsWith("album:"),
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
}

/** Data retained with a contextual bar while it exits, avoiding a visible "0 selected" frame. */
internal data class SelectionBarPresentation(
    val selecting: Boolean,
    val count: Int,
    val allSelected: Boolean,
)

/** Contextual app bar shared by the Tracks page and user-playlist multi-selection. */
@Composable
internal fun SelectionTopAppBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding()
            .heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_close),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        AnimatedContent(
            targetState = count,
            transitionSpec = { motionFadeThrough(reduceMotion) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            label = "selection-count",
        ) { currentCount ->
            val description = stringResource(Res.string.selection_count, currentCount)
            Text(
                // Keep the number readable at large font sizes; accessibility still names the
                // selection context, while the visible count uses the existing compact plural.
                text = pluralStringResource(Res.plurals.count_tracks, currentCount, currentCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {
                    if (currentCount == count) contentDescription = description
                },
            )
        }
        val description = stringResource(
            if (allSelected) Res.string.action_deselect_all else Res.string.action_select_all,
        )
        IconButton(
            onClick = onToggleAll,
            modifier = Modifier.size(48.dp).semantics { contentDescription = description },
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
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.app.generated.resources.playlist_reorder_track
import io.github.nikitasud.latentjam.app.generated.resources.settings_page_move_up
import io.github.nikitasud.latentjam.app.generated.resources.settings_page_move_down
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.action_change_playlist_cover
import io.github.nikitasud.latentjam.app.generated.resources.action_add_all_favorites
import io.github.nikitasud.latentjam.app.generated.resources.action_export_m3u
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_reset_playlist_cover
import io.github.nikitasud.latentjam.app.generated.resources.action_smart_keep_together
import io.github.nikitasud.latentjam.app.generated.resources.cd_more_options
import io.github.nikitasud.latentjam.app.generated.resources.map_action_smart_here
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.playlist_unavailable_tracks
import io.github.nikitasud.latentjam.app.generated.resources.info_edit
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_edit_album
import io.github.nikitasud.latentjam.app.generated.resources.action_edit_artist
import io.github.nikitasud.latentjam.app.generated.resources.action_deselect_all
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_select_all
import io.github.nikitasud.latentjam.app.generated.resources.action_share
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle
import io.github.nikitasud.latentjam.app.generated.resources.selection_count
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.library.defaultDirection
import io.github.nikitasud.latentjam.library.hasDirection
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
     * The order [tracks] are in on a page that lets the listener choose one (a user playlist,
     * Favorites); null elsewhere. Set by [inChosenOrder], never by a page's builder.
     */
    val trackSort: SortChoice<PlaylistSort>? = null,
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

    // A single section no longer needs a header; its tracks keep the rail a lone album would get.
    val shownSections = retainedSections?.takeIf { it.size > 1 }
    return copy(
        tracks = retainedTracks,
        sections = shownSections,
        railMode = if (
            railMode == CollectionRailMode.SECTION_TITLES && shownSections == null
        ) {
            trackRailModeFor(retainedTracks)
        } else {
            railMode
        },
    )
}

/**
 * A letter rail over track titles only helps while the titles are alphabetical. In release order
 * the letters jump about, and a rail that sends "M" to track 2 and "B" to track 9 misleads.
 */
internal fun trackRailModeFor(tracks: List<TrackDescriptor>): CollectionRailMode {
    val keys = tracks.map { SongSorting.sortKey(it.title) }
    val alphabetical = keys.zipWithNext().all { (previous, next) -> previous <= next }
    return if (alphabetical) CollectionRailMode.TRACK_TITLES else CollectionRailMode.NONE
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
internal fun CollectionDetailScreen(
    selection: CollectionSelection,
    playlist: Playlist? = null,
    onPlaylistOrderChanged: suspend () -> Unit = {},
    currentTrackId: TrackId?,
    /** Whether the player is audibly running; animates the current row's badge. */
    currentTrackPlaying: Boolean = false,
    selectedTrackIds: Set<TrackId> = emptySet(),
    /** What the selection's contextual bar offers; the same object drives the bottom action bar. */
    selectionCommands: SelectionCommands,
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
    /** Hearts every track of this playlist; null where the page offers no such action. */
    onAddAllToFavorites: (() -> Unit)? = null,
    /** Read only while the menu is open: whether any track of the page is not a favourite yet. */
    canAddAllToFavorites: () -> Boolean = { true },
    /** Writes the page's list as an M3U file (Favorites, newest first); null where not offered. */
    onExportM3u: (() -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
    includeInSmart: Boolean = false,
    /** Entries of this playlist that are not on the device; they are never in [selection]. */
    unavailableCount: Int = 0,
    /** Forgets those entries; null where there is nothing the listener could remove. */
    onRemoveUnavailable: (() -> Unit)? = null,
    unavailableRemovalBusy: Boolean = false,
    active: Boolean = true,
    /** Includes the shared cover transition, which can outlive the page's own entrance. */
    entrySettled: Boolean = true,
    artworkModifier: Modifier = Modifier,
    /** How an artist's albums are ordered; null where the page has no albums to order. */
    albumSort: SortChoice<AlbumSort>? = null,
    onAlbumSortChange: (SortChoice<AlbumSort>) -> Unit = {},
    /** Chooses the order of a page that has one, [CollectionSelection.trackSort]. */
    onTrackSortChange: (SortChoice<PlaylistSort>) -> Unit = {},
) {
    val selectionMode = selection.allowsTrackSelection && selectedTrackIds.isNotEmpty()
    val reorder = rememberPlaylistTrackReorder(playlist, selection, onPlaylistOrderChanged)
    val canReorder = active && !selectionMode && !reorder.busy && playlist?.id == selection.playlistId &&
        playlist != null && selection.trackSort?.sort == PlaylistSort.CUSTOM && selection.tracks.size > 1
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
                    commands = selectionCommands,
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
                        commands = bar.commands,
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
                            AppDropdownMenu(
                                expanded = optionsOpen,
                                onDismissRequest = { optionsOpen = false },
                            ) {
                                onChangeCover?.let { changeCover ->
                                    AppMenuItem(
                                        text = stringResource(Res.string.action_change_playlist_cover),
                                        icon = Icons.Outlined.Image,
                                        enabled = !coverEditBusy,
                                        onClick = { optionsOpen = false; changeCover() },
                                    )
                                }
                                onResetCover?.let { resetCover ->
                                    AppMenuItem(
                                        text = stringResource(Res.string.action_reset_playlist_cover),
                                        icon = Icons.Outlined.Restore,
                                        enabled = !coverEditBusy,
                                        onClick = { optionsOpen = false; resetCover() },
                                    )
                                }
                                onToggleSmart?.let { toggleSmart ->
                                    AppMenuItem(
                                        text = stringResource(Res.string.action_smart_keep_together),
                                        icon = Icons.AutoMirrored.Outlined.QueueMusic,
                                        trailing = if (includeInSmart) {
                                            { Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)) }
                                        } else null,
                                        onClick = { optionsOpen = false; toggleSmart() },
                                    )
                                }
                                onAddAllToFavorites?.let { addAll ->
                                    AppMenuItem(
                                        text = stringResource(Res.string.action_add_all_favorites),
                                        icon = Icons.Outlined.FavoriteBorder,
                                        enabled = canAddAllToFavorites(),
                                        onClick = { optionsOpen = false; addAll() },
                                    )
                                }
                                onExportM3u?.let { export ->
                                    AppMenuItem(
                                        text = stringResource(Res.string.action_export_m3u),
                                        icon = Icons.Outlined.IosShare,
                                        enabled = selection.tracks.isNotEmpty(),
                                        onClick = { optionsOpen = false; export() },
                                    )
                                }
                                onEditTags?.let { editTags ->
                                    val label = when {
                                        selection.routeId.startsWith("album:") -> Res.string.action_edit_album
                                        selection.routeId.startsWith("artist:") -> Res.string.action_edit_artist
                                        else -> Res.string.info_edit
                                    }
                                    AppMenuItem(
                                        text = stringResource(label),
                                        icon = Icons.Outlined.Edit,
                                        enabled = selection.tracks.isNotEmpty(),
                                        onClick = { optionsOpen = false; editTags() },
                                    )
                                }
                                if (onChangeCover != null || onEditTags != null || onExportM3u != null) AppMenuDivider()
                                AppMenuItem(
                                    text = stringResource(Res.string.action_play),
                                    icon = Icons.Outlined.PlayArrow,
                                    onClick = { optionsOpen = false; onPlay() },
                                    enabled = selection.tracks.isNotEmpty(),
                                )
                                AppMenuItem(
                                    text = stringResource(Res.string.action_shuffle),
                                    icon = Icons.Outlined.Shuffle,
                                    onClick = { optionsOpen = false; onShuffle() },
                                    enabled = selection.tracks.isNotEmpty(),
                                )
                                if (selection.allowsTrackSelection) AppMenuItem(
                                    text = stringResource(Res.string.action_select_all),
                                    icon = Icons.Outlined.SelectAll,
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
                            albumSort = albumSort,
                            onAlbumSortChange = onAlbumSortChange,
                            onTrackSortChange = onTrackSortChange,
                            unavailableCount = unavailableCount,
                            onRemoveUnavailable = onRemoveUnavailable,
                            unavailableRemovalBusy = unavailableRemovalBusy,
                        )
                    }
                } else {
                    CollectionTrackLazyColumn(
                        artworkModifier = artworkModifier,
                        modifier = Modifier.fadingListTop { listState.canScrollBackward },
                        onMoveTrack = reorder.move.takeIf { canReorder },
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
                        albumSort = albumSort,
                        onAlbumSortChange = onAlbumSortChange,
                        onTrackSortChange = onTrackSortChange,
                        unavailableCount = unavailableCount,
                        onRemoveUnavailable = onRemoveUnavailable,
                        unavailableRemovalBusy = unavailableRemovalBusy,
                    )
                }
                ScrollToTopButton(
                    listState = listState,
                    bottomInset = bottomInset,
                )
                AppSnackbarHost(reorder.snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset))
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
    albumSort: SortChoice<AlbumSort>?,
    onAlbumSortChange: (SortChoice<AlbumSort>) -> Unit,
    onTrackSortChange: (SortChoice<PlaylistSort>) -> Unit,
    unavailableCount: Int = 0,
    onRemoveUnavailable: (() -> Unit)? = null,
) {
    val albumYear = remember(selection.tracks) { albumYearLabel(selection.tracks) }
    val metadata = if (selection.routeId.startsWith("album:")) {
        joinBidiWrapped(listOfNotNull(selection.subtitle, albumYear,
            pluralStringResource(Res.plurals.count_tracks, selection.tracks.size, selection.tracks.size)))
    } else if (selection.routeId.startsWith(FOLDER_ROUTE_PREFIX)) {
        // A folder's path is a file-system name: wrapped here, where it is shown, so it keeps its
        // own reading order next to the translated count in either layout direction.
        joinBidiWrapped(
            listOf(
                selection.routeId.removePrefix(FOLDER_ROUTE_PREFIX),
                pluralStringResource(Res.plurals.count_tracks, selection.tracks.size, selection.tracks.size),
            ),
            separator = " • ",
        )
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
            style = MaterialTheme.typography.headlineSmall.metadata(),
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
                colors = playButtonColors(),
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
        // Inside the hero, not a row of its own: the list's item indices stay those of its tracks.
        if (unavailableCount > 0) {
            UnavailableEntriesNotice(
                count = unavailableCount,
                onRemove = onRemoveUnavailable?.takeIf { enabled },
            )
        }
        // Start-aligned with the album headers it orders, the way the Tracks tab's pill heads its list.
        if (albumSort != null) {
            SortPill(
                options = ARTIST_ALBUM_SORTS,
                choice = albumSort,
                label = { it.label() },
                defaultDirection = { it.defaultDirection },
                enabled = enabled,
                onChoiceChange = onAlbumSortChange,
                modifier = Modifier.align(Alignment.Start).padding(top = 18.dp)
                    .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
            )
        }
        // A playlist's and Favorites' order: shown only, the playlist keeps its own.
        selection.trackSort?.let { trackSort ->
            SortPill(
                options = selection.trackSortOptions(),
                choice = trackSort,
                label = { it.label() },
                defaultDirection = { it.defaultDirection },
                hasDirection = { it.hasDirection },
                enabled = enabled,
                onChoiceChange = onTrackSortChange,
                modifier = Modifier.align(Alignment.Start).padding(top = 18.dp)
                    .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
            )
        }
    }
}

/**
 * Says how many of a playlist's songs are not on this device: the list and its count show only the
 * songs present, and without this the rest would vanish without a word. Removal is the listener's
 * choice, with Undo; [onRemove] is null while it cannot be offered.
 */
@Composable
private fun UnavailableEntriesNotice(count: Int, onRemove: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = pluralStringResource(Res.plurals.playlist_unavailable_tracks, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { onRemove?.invoke() }, enabled = onRemove != null) {
            Text(stringResource(Res.string.action_remove_from_playlist))
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
    onMoveTrack: ((Int, Int) -> Unit)? = null,
    onChangeCover: (() -> Unit)? = null,
    albumSort: SortChoice<AlbumSort>? = null,
    onAlbumSortChange: (SortChoice<AlbumSort>) -> Unit = {},
    onTrackSortChange: (SortChoice<PlaylistSort>) -> Unit = {},
    unavailableCount: Int = 0,
    onRemoveUnavailable: (() -> Unit)? = null,
    unavailableRemovalBusy: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val unknownTitle = stringResource(Res.string.track_untitled)
    val unknownArtist = stringResource(Res.string.track_unknown_artist)
    val identity = remember(selection.routeId, selection.tracks, selection.trackSort) { Any() }
    val repeatedIds = remember(selection.tracks) { selection.tracks.map { it.id }.toSet().size != selection.tracks.size }
    ReorderableLazyList(
        identity = identity,
        canReorder = onMoveTrack != null && !isPreview && !selectionMode,
        listState = listState,
        itemCount = selection.tracks.size,
        leadingItems = 1,
        dragHandleWidth = 56.dp,
        onMove = { from, to -> onMoveTrack?.invoke(from, to) },
        draggedItem = { index, floating ->
            CollectionTrackRow(
                track = selection.tracks[index], flatIndex = index, selection = selection,
                selectionMode = false, selectedTrackIds = emptySet(), currentTrackId = currentTrackId,
                currentTrackPlaying = currentTrackPlaying, currentAccent = currentAccent,
                onArtworkLoadStateChanged = null, onToggleSelection = {}, onStartSelection = {},
                onPlayTrack = {}, onTrackMenu = {}, onMoveTrack = { _, _ -> },
                modifier = floating.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clearAndSetSemantics {},
            )
        },
    ) { draggingIndex ->
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
                albumSort = albumSort,
                onAlbumSortChange = onAlbumSortChange,
                onTrackSortChange = onTrackSortChange,
                unavailableCount = unavailableCount,
                onRemoveUnavailable = onRemoveUnavailable?.takeIf { !unavailableRemovalBusy },
            )
        }
        val sections = selection.sections
        if (sections == null) {
            itemsIndexed(
                selection.tracks,
                key = { index, track -> if (repeatedIds) "track:$index:${track.id.value}" else "track:${track.id.value}" },
            ) { index, track ->
                val artworkKey = track.artworkUri?.let { uri ->
                    ArtworkLoadKey(
                        itemId = "${selection.routeId}:$index:${track.id.value}",
                        uri = uri,
                    )
                }
                Box(
                    modifier = (if (!isPreview && !repeatedIds) {
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
                    }).graphicsLayer { alpha = if (draggingIndex == index) 0f else 1f },
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
                            onMoveTrack = onMoveTrack,
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
                        style = MaterialTheme.typography.titleSmall.metadata(),
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
    modifier: Modifier = Modifier,
    onMoveTrack: ((Int, Int) -> Unit)? = null,
) {
    val title = track.title ?: stringResource(Res.string.track_untitled)
    val moveUp = stringResource(Res.string.settings_page_move_up, title)
    val moveDown = stringResource(Res.string.settings_page_move_down, title)
    // iOS retains native custom-action objects on a moved keyed lazy row. Recreate the
    // row's semantics when its position changes so both action labels and handlers update.
    androidx.compose.runtime.key(flatIndex) {
    TrackRow(
        modifier = modifier.semantics {
            customActions = buildList {
                if (onMoveTrack != null && flatIndex > 0) {
                    add(CustomAccessibilityAction(moveUp) { onMoveTrack(flatIndex, flatIndex - 1); true })
                }
                if (onMoveTrack != null && flatIndex < selection.tracks.lastIndex) {
                    add(CustomAccessibilityAction(moveDown) { onMoveTrack(flatIndex, flatIndex + 1); true })
                }
            }
        },
        trailingContent = if (onMoveTrack != null) {
            {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DragHandle,
                        contentDescription = stringResource(Res.string.playlist_reorder_track, title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp))
                }
            }
        } else null,
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
}

/**
 * Data retained with a contextual bar while it exits, avoiding a visible "0 selected" frame — and,
 * through [commands], a frame of greyed-out actions once the selection has emptied.
 */
internal data class SelectionBarPresentation(
    val selecting: Boolean,
    val count: Int,
    val allSelected: Boolean,
    val commands: SelectionCommands,
)

/**
 * Contextual app bar of every multi-selection: the browse tabs, Search and a collection's page.
 * The rarer actions live here, the destructive one behind the overflow, so the bottom bar keeps
 * room for full labels. [onToggleAll] null leaves out the select-all toggle.
 */
@Composable
internal fun SelectionTopAppBar(
    count: Int,
    allSelected: Boolean,
    commands: SelectionCommands,
    onClose: () -> Unit,
    onToggleAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    Row(
        modifier = modifier.fillMaxWidth().statusBarsPadding()
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
        SelectionTopActions(commands)
        if (onToggleAll != null) {
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
}

/** Edit tags, Share, and the destructive action behind an overflow, in that order. */
@Composable
private fun SelectionTopActions(commands: SelectionCommands) {
    // The icons take IconButton's content colour, so a disabled action greys out as in the bar.
    IconButton(
        onClick = commands.onEditTags,
        enabled = commands.canAct,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(Icons.Rounded.Edit, contentDescription = stringResource(Res.string.info_edit))
    }
    IconButton(
        onClick = commands.onShare,
        enabled = commands.canShare,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(Icons.Rounded.Share, contentDescription = stringResource(Res.string.action_share))
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuOpen = true },
            enabled = commands.canAct,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.cd_more_options))
        }
        AppDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            val destructive = commands.destructive
            AppMenuItem(
                text = stringResource(destructive.label),
                icon = destructive.icon,
                enabled = commands.canAct,
                destructive = true,
                onClick = {
                    menuOpen = false
                    commands.onRemove()
                },
            )
        }
    }
}

private const val FOLDER_ROUTE_PREFIX = "folder:"

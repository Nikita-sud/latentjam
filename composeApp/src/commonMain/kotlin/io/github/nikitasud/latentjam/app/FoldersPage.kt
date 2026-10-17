/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle
import io.github.nikitasud.latentjam.app.generated.resources.count_folders
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.folder_content_description
import io.github.nikitasud.latentjam.app.generated.resources.folder_root_internal_storage
import io.github.nikitasud.latentjam.app.generated.resources.folder_root_sd_card
import io.github.nikitasud.latentjam.app.generated.resources.folder_view_list
import io.github.nikitasud.latentjam.app.generated.resources.folder_view_tree
import io.github.nikitasud.latentjam.app.generated.resources.map_action_smart_here
import io.github.nikitasud.latentjam.app.generated.resources.tab_folders
import io.github.nikitasud.latentjam.library.FolderGroup
import io.github.nikitasud.latentjam.library.FolderNode
import io.github.nikitasud.latentjam.library.FolderTree
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How the Folders tab shows the library. Persisted by name; anything unknown reads as [TREE]. */
enum class FolderView { TREE, LIST }

internal fun folderViewFromPersisted(value: String?): FolderView =
    FolderView.entries.firstOrNull { it.name == value } ?: FolderView.TREE

internal val DEFAULT_FOLDER_VIEW: StateFlow<FolderView> = MutableStateFlow(FolderView.TREE)

/**
 * Route of a folder with everything below it, as a queue source and as the page that source opens
 * (`foldertree:<FolderNode.key>`). See [FOLDER_TRACKS_ROUTE_PREFIX] for a folder's own tracks.
 */
internal const val FOLDER_TREE_ROUTE_PREFIX = "foldertree:"

/**
 * Route of one folder's own tracks, without its subfolders', on its own storage volume, as a queue
 * source and as the page that source opens (`foldertracks:<FolderNode.key>`). The folder list's
 * `folder:<path>` names no volume, so it stands for every volume's folder at that path at once.
 */
internal const val FOLDER_TRACKS_ROUTE_PREFIX = "foldertracks:"

/**
 * What a track tapped in [node] of the tree leaves as the queue's source: [node]'s own tracks, the
 * ones played beside it, on [node]'s volume. A volume's root is a folder like any other here.
 */
internal fun folderTrackSource(node: FolderNode, label: String): QueueSource = QueueSource(
    kind = QueueSourceKind.LIBRARY_GROUP,
    name = label,
    reference = FOLDER_TRACKS_ROUTE_PREFIX + node.key,
)

/**
 * The tracks a selection on the Folders tab plays, in the order the tab shows them: in the tree,
 * the order a folder plays in ([FolderTree.inPlayOrder]); in the list, folder by folder.
 */
internal fun folderTabSelectionOrder(
    catalog: LibraryCatalog,
    direction: SongSortDirection,
    selected: Set<TrackId>,
    view: FolderView,
): List<TrackDescriptor> = when (view) {
    FolderView.TREE -> FolderTree.inPlayOrder(catalog.songs.filter { it.id in selected }, direction)
    FolderView.LIST -> catalog.folders.flatMap { it.tracks }
}

/**
 * The count beside the Folders tab's sort pill: the folders that hold tracks, one per path, which is
 * what the list view shows. The tree shows the same number, so switching views does not change it.
 */
internal fun folderTabCount(catalog: LibraryCatalog): Int = catalog.folders.size

/**
 * The Folders tab: the folder tree a file explorer would show (the default), or every folder in one
 * list as before. The tree walks down one folder at a time, its subfolders first and then the
 * tracks directly in it; Back and the breadcrumb walk up. Playing a folder plays everything below it.
 *
 * Its state stays here rather than in App, whose content lambda is near the size R8 can encode.
 */
@Composable
internal fun FoldersPage(
    catalog: LibraryCatalog,
    playback: PlaybackController,
    /** The page is on screen and nothing covers it, so the system Back is the tree's to take. */
    active: Boolean,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    onSelectionChange: (Set<TrackId>) -> Unit,
    onOpenFolder: (FolderGroup) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    currentAccent: Color?,
    contentPadding: PaddingValues,
    onRailScrubbingChange: (Boolean) -> Unit,
) {
    val settings = AppGraph.settings
    val view by settings.folderView.collectAsState()
    val orders by settings.groupSortOrders.collectAsState()
    val reduceMotion = rememberReduceMotion()
    // The tree's place, kept while the list is up and saved like the list's scroll position.
    var treeKey by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        NameSortHeader(
            direction = orders.folders,
            count = folderTabCount(catalog),
            countPlural = Res.plurals.count_folders,
            enabled = !selectionMode,
            onDirectionChange = { settings.setGroupSortOrders(orders.copy(folders = it)) },
        ) {
            FolderViewToggle(view = view, enabled = !selectionMode, onChange = settings::setFolderView)
        }
        AnimatedContent(
            targetState = view,
            transitionSpec = { motionFadeThrough(reduceMotion) },
            modifier = Modifier.fillMaxSize(),
            label = "folder-view",
        ) { shownView ->
            when (shownView) {
                FolderView.LIST -> FolderList(
                    folders = catalog.folders,
                    direction = orders.folders,
                    selectionMode = selectionMode,
                    selectedTrackIds = selectedTrackIds,
                    onSelectionChange = onSelectionChange,
                    onOpenFolder = onOpenFolder,
                    contentPadding = contentPadding,
                    onRailScrubbingChange = onRailScrubbingChange,
                )
                FolderView.TREE -> FolderTreeBrowser(
                    songs = catalog.songs,
                    direction = orders.folders,
                    placeKey = treeKey,
                    onPlaceChange = { treeKey = it },
                    playback = playback,
                    active = active && shownView == view,
                    selectionMode = selectionMode,
                    selectedTrackIds = selectedTrackIds,
                    onSelectionChange = onSelectionChange,
                    onTrackMenu = onTrackMenu,
                    currentTrackId = currentTrackId,
                    currentTrackPlaying = currentTrackPlaying,
                    currentAccent = currentAccent,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

/** Tree or list, as two segments of one outlined pill at the end of the sort row. */
@Composable
private fun FolderViewToggle(view: FolderView, enabled: Boolean, onChange: (FolderView) -> Unit) {
    Row(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clip(CircleShape)
            .selectableGroup()
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderViewOption(
            icon = Icons.Rounded.AccountTree,
            label = stringResource(Res.string.folder_view_tree),
            selected = view == FolderView.TREE,
            enabled = enabled,
            onClick = { onChange(FolderView.TREE) },
        )
        Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
        FolderViewOption(
            icon = Icons.AutoMirrored.Rounded.ViewList,
            label = stringResource(Res.string.folder_view_list),
            selected = view == FolderView.LIST,
            enabled = enabled,
            onClick = { onChange(FolderView.LIST) },
        )
    }
}

@Composable
private fun FolderViewOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Every folder in one alphabetical list, each opening on its own tracks: the view before the tree. */
@Composable
private fun FolderList(
    folders: List<FolderGroup>,
    direction: SongSortDirection,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    onSelectionChange: (Set<TrackId>) -> Unit,
    onOpenFolder: (FolderGroup) -> Unit,
    contentPadding: PaddingValues,
    onRailScrubbingChange: (Boolean) -> Unit,
) {
    GroupListWithRail(
        onScrubbingChange = onRailScrubbingChange,
        names = remember(folders) { folders.map { it.name } },
        contentPadding = contentPadding,
    ) { railPadding, listState, _ ->
        var previousOrder by remember { mutableStateOf(direction) }
        LaunchedEffect(direction) {
            if (previousOrder != direction) listState.scrollToItem(0)
            previousOrder = direction
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = railPadding,
        ) {
            items(folders, key = { it.path }) { folder ->
                FolderRow(
                    folder = folder,
                    subtitle = pluralStringResource(Res.plurals.count_tracks, folder.tracks.size, folder.tracks.size),
                    onLongClick = { onSelectionChange(selectedTrackIds.toggleTracks(folder.tracks)) },
                    selectionState = if (selectionMode) selectedTrackIds.selectsAllOf(folder.tracks) else null,
                ) {
                    if (selectionMode) {
                        onSelectionChange(selectedTrackIds.toggleTracks(folder.tracks))
                    } else {
                        onOpenFolder(folder)
                    }
                }
            }
        }
    }
}

/**
 * One level of the folder tree at a time, under a breadcrumb of the folders above it. Each level
 * keeps its own scroll position while the listener is deeper down, so going up lands where they were.
 */
@Composable
private fun FolderTreeBrowser(
    songs: List<TrackDescriptor>,
    direction: SongSortDirection,
    placeKey: String?,
    onPlaceChange: (String?) -> Unit,
    playback: PlaybackController,
    active: Boolean,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    onSelectionChange: (Set<TrackId>) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    currentAccent: Color?,
    contentPadding: PaddingValues,
) {
    // Built off the main thread like the catalog; a rescan keeps showing the last tree until then.
    val tree by produceState<FolderTree?>(initialValue = null, songs) {
        value = withContext(Dispatchers.Default) { FolderTree.build(songs) }
    }
    val volumeNames by produceState(initialValue = emptyMap<String, String>(), songs) {
        value = AppGraph.library.storageVolumeNames()
    }
    val shown = tree ?: return
    val current = shown.nearest(placeKey)
    val trail = remember(shown, current) { shown.trail(current) }
    val parent = trail.getOrNull(trail.size - 2)
    // A selection keeps Back for itself (App clears it), as do the screens over this page.
    PlatformBackHandler(enabled = active && !selectionMode && parent != null) {
        onPlaceChange(parent?.key)
    }
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val layoutDirection = LocalLayoutDirection.current
    val labels = folderLabels(volumeNames)

    /** Plays [node] and everything below it, in [FolderNode.allTracks] order. */
    fun playFolder(node: FolderNode, label: String, mode: ShuffleMode) {
        val tracks = node.allTracks(direction)
        if (tracks.isEmpty()) return
        AppGraph.queueSource.value = QueueSource(
            kind = QueueSourceKind.LIBRARY_GROUP,
            name = label,
            reference = FOLDER_TREE_ROUTE_PREFIX + node.key,
        )
        scope.launch {
            playback.setShuffleMode(mode)
            playback.play(tracks, if (mode == ShuffleMode.ON) tracks.indices.random() else 0)
        }
    }

    Column {
        FolderBreadcrumb(
            trail = trail,
            labels = labels,
            onNavigate = { onPlaceChange(it.key) },
            onUp = parent?.let { { onPlaceChange(it.key) } },
        )
        val levels = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = current.key,
            transitionSpec = {
                motionPageTransform(
                    forward = folderDepth(targetState) > folderDepth(initialState),
                    reduceMotion = reduceMotion,
                    layoutDirection = layoutDirection,
                )
            },
            modifier = Modifier.fillMaxSize(),
            label = "folder-level",
        ) { key ->
            val node = shown.nearest(key)
            levels.SaveableStateProvider(key) {
                FolderLevel(
                    node = node,
                    label = labels.of(node),
                    labels = labels,
                    direction = direction,
                    selectionMode = selectionMode,
                    selectedTrackIds = selectedTrackIds,
                    onSelectionChange = onSelectionChange,
                    onOpen = { onPlaceChange(it.key) },
                    onPlayFolder = ::playFolder,
                    onPlayTrack = { index ->
                        // A tapped track plays the tracks beside it, as the folder's own page does,
                        // and keeps the shuffle mode the listener chose.
                        AppGraph.queueSource.value = folderTrackSource(node, labels.of(node))
                        scope.launch { playback.play(node.tracks, index) }
                    },
                    onTrackMenu = onTrackMenu,
                    currentTrackId = currentTrackId,
                    currentTrackPlaying = currentTrackPlaying,
                    currentAccent = currentAccent,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

/** How deep a [FolderNode.key] sits: 0 for the library root, 1 for a volume's root, and so on. */
private fun folderDepth(key: String): Int {
    if (key == FolderTree.LIBRARY_ROOT_KEY) return 0
    val path = key.substringAfter(':', missingDelimiterValue = "")
    return if (path.isEmpty()) 1 else 2 + path.count { it == '/' }
}

/** Names for the tree's nodes: a folder's own name, and the localized names of the roots. */
private class FolderLabels(
    val library: String,
    val internalStorage: String,
    val sdCard: String,
    val volumeNames: Map<String, String>,
) {
    fun of(node: FolderNode): String = when {
        node.isLibraryRoot -> library
        node.isVolumeRoot -> volume(node.volume)
        else -> node.name
    }

    /** The device's own storage by LatentJam's word for it, a card by the system's name for it. */
    fun volume(volume: String?): String = when (volume) {
        null -> library
        FolderTree.PRIMARY_VOLUME -> internalStorage
        else -> volumeNames[volume] ?: sdCard
    }
}

@Composable
private fun folderLabels(volumeNames: Map<String, String>): FolderLabels {
    val library = stringResource(Res.string.tab_folders)
    val internalStorage = stringResource(Res.string.folder_root_internal_storage)
    val sdCard = stringResource(Res.string.folder_root_sd_card)
    return remember(library, internalStorage, sdCard, volumeNames) {
        FolderLabels(library, internalStorage, sdCard, volumeNames)
    }
}

/** Where the tree is, root first, each folder above the current one a step back up. */
@Composable
private fun FolderBreadcrumb(
    trail: List<FolderNode>,
    labels: FolderLabels,
    onNavigate: (FolderNode) -> Unit,
    onUp: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .padding(start = if (onUp != null) 8.dp else 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onUp != null) {
            IconButton(onClick = onUp) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
        }
        val scroll = rememberScrollState()
        // The current folder is the end that matters: a long trail shows it, not the root.
        LaunchedEffect(trail.last().key) {
            snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trail.forEachIndexed { index, node ->
                val isCurrent = index == trail.lastIndex
                if (index > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = labels.of(node),
                    style = if (isCurrent) {
                        MaterialTheme.typography.titleSmall.metadata()
                    } else {
                        MaterialTheme.typography.bodyMedium.metadata()
                    },
                    fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = if (isCurrent) {
                        Modifier.padding(horizontal = 6.dp, vertical = 12.dp).semantics { heading() }
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(8.dp.corner()))
                            .clickable(role = Role.Button) { onNavigate(node) }
                            .padding(horizontal = 6.dp, vertical = 12.dp)
                    },
                )
            }
        }
    }
}

/** One folder's content: what it holds and the means to play all of it, its subfolders, its tracks. */
@Composable
private fun FolderLevel(
    node: FolderNode,
    label: String,
    labels: FolderLabels,
    direction: SongSortDirection,
    selectionMode: Boolean,
    selectedTrackIds: Set<TrackId>,
    onSelectionChange: (Set<TrackId>) -> Unit,
    onOpen: (FolderNode) -> Unit,
    onPlayFolder: (FolderNode, String, ShuffleMode) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    currentTrackId: TrackId?,
    currentTrackPlaying: Boolean,
    currentAccent: Color?,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    // A new name order starts the level from its top, as it does the flat list.
    var shownDirection by rememberSaveable { mutableStateOf(direction) }
    LaunchedEffect(direction) {
        if (shownDirection != direction) listState.scrollToItem(0)
        shownDirection = direction
    }
    val folders = node.folders(direction)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "folder-actions", contentType = "folder-actions") {
            FolderLevelActions(
                summary = folderSummary(folders = node.folders.size, tracks = node.trackCount),
                enabled = !selectionMode && node.trackCount > 0,
                onPlay = { onPlayFolder(node, label, ShuffleMode.OFF) },
                onShuffle = { onPlayFolder(node, label, ShuffleMode.ON) },
                onStartSmart = { onPlayFolder(node, label, ShuffleMode.SMART) },
            )
        }
        items(folders, key = { "folder:${it.key}" }, contentType = { "folder" }) { folder ->
            val all = remember(folder, direction) { folder.allTracks(direction) }
            val title = labels.of(folder)
            FolderTreeRow(
                title = title,
                subtitle = folderSummary(folders = folder.folders.size, tracks = folder.trackCount),
                icon = when {
                    !folder.isVolumeRoot -> Icons.Rounded.Folder
                    folder.volume == FolderTree.PRIMARY_VOLUME -> Icons.Rounded.Smartphone
                    else -> Icons.Rounded.SdCard
                },
                selectionState = if (selectionMode) selectedTrackIds.selectsAllOf(all) else null,
                onClick = {
                    if (selectionMode) onSelectionChange(selectedTrackIds.toggleTracks(all)) else onOpen(folder)
                },
                onLongClick = { onSelectionChange(selectedTrackIds.toggleTracks(all)) },
                onPlay = { onPlayFolder(folder, title, ShuffleMode.OFF) },
                onShuffle = { onPlayFolder(folder, title, ShuffleMode.ON) },
                onStartSmart = { onPlayFolder(folder, title, ShuffleMode.SMART) },
            )
        }
        itemsIndexed(node.tracks, key = { _, track -> "track:${track.id.value}" }, contentType = { _, _ -> "track" }) { index, track ->
            val isCurrent = track.id == currentTrackId
            TrackRow(
                track = track,
                isCurrent = isCurrent,
                isPlaying = currentTrackPlaying && isCurrent,
                currentAccent = if (isCurrent) currentAccent else null,
                onClick = {
                    if (selectionMode) onSelectionChange(selectedTrackIds.toggleTrack(track)) else onPlayTrack(index)
                },
                onLongClick = {
                    onSelectionChange(if (selectionMode) selectedTrackIds.toggleTrack(track) else setOf(track.id))
                },
                selectionState = if (selectionMode) track.id in selectedTrackIds else null,
                onMenu = if (selectionMode) null else ({ onTrackMenu(track) }),
            )
        }
    }
}

private fun Set<TrackId>.toggleTrack(track: TrackDescriptor): Set<TrackId> =
    if (track.id in this) this - track.id else this + track.id

/** "3 folders • 42 tracks", or only the tracks when no folder is inside. */
@Composable
private fun folderSummary(folders: Int, tracks: Int): String {
    val trackCount = pluralStringResource(Res.plurals.count_tracks, tracks, tracks)
    if (folders == 0) return trackCount
    return pluralStringResource(Res.plurals.count_folders, folders, folders) + FOLDER_SUMMARY_SEPARATOR + trackCount
}

/** Punctuation rather than a word, like the other counted subtitles: nothing to translate. */
private const val FOLDER_SUMMARY_SEPARATOR = " • "

/** What the current folder holds, and Shuffle / Play / SMART for all of it, subfolders included. */
@Composable
private fun FolderLevelActions(
    summary: String,
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onStartSmart: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(
            onClick = onShuffle,
            enabled = enabled,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(Icons.Rounded.Shuffle, stringResource(Res.string.action_shuffle), modifier = Modifier.size(20.dp))
        }
        FilledIconButton(
            onClick = onPlay,
            enabled = enabled,
            modifier = Modifier.size(52.dp),
            colors = playButtonColors(),
        ) {
            Icon(Icons.Rounded.PlayArrow, stringResource(Res.string.action_play), modifier = Modifier.size(24.dp))
        }
        FilledTonalIconButton(
            onClick = onStartSmart,
            enabled = enabled,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.tertiary,
            ),
        ) {
            Icon(LatentJamMark, stringResource(Res.string.map_action_smart_here), modifier = Modifier.size(22.dp))
        }
    }
}

/** Folder rows use a stable folder glyph; an arbitrary first cover would misrepresent the source. */
@Composable
private fun FolderRow(
    folder: FolderGroup,
    subtitle: String,
    onLongClick: (() -> Unit)? = null,
    /** `null` outside selection mode; otherwise whether every track of this folder is selected. */
    selectionState: Boolean? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectionState != null) {
                    Modifier.semantics {
                        selected = selectionState
                        role = Role.Checkbox
                    }
                } else {
                    Modifier
                },
            )
            .groupClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GroupSelectionMark(selectionState)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp.corner()))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = stringResource(Res.string.folder_content_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge.metadata(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folder.path,
                style = MaterialTheme.typography.bodySmall.metadata(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A subfolder (or a storage volume) of the current folder; its menu plays everything below it. */
@Composable
private fun FolderTreeRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    /** `null` outside selection mode; otherwise whether every track below this folder is selected. */
    selectionState: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onStartSmart: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectionState != null) {
                    Modifier.semantics {
                        selected = selectionState
                        role = Role.Checkbox
                    }
                } else {
                    Modifier
                },
            )
            .groupClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GroupSelectionMark(selectionState)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp.corner()))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(Res.string.folder_content_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.metadata(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionState == null) {
            OverflowButton { dismiss ->
                AppMenuItem(
                    text = stringResource(Res.string.action_play),
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { dismiss(); onPlay() },
                )
                AppMenuItem(
                    text = stringResource(Res.string.action_shuffle),
                    icon = Icons.Rounded.Shuffle,
                    onClick = { dismiss(); onShuffle() },
                )
                AppMenuItem(
                    text = stringResource(Res.string.map_action_smart_here),
                    icon = LatentJamMark,
                    onClick = { dismiss(); onStartSmart() },
                )
            }
        }
    }
}

/**
 * A folder and everything below it as a page, for the queue source a folder's Play leaves behind:
 * its tracks in the order the folder played them, a section for each folder that holds some.
 */
internal suspend fun FolderNode.toTreeSelection(direction: SongSortDirection): CollectionSelection {
    val title = pageTitle()
    val sections = withContext(Dispatchers.Default) { folderBranchSections(this@toTreeSelection, direction, title) }
    val ordered = sections.flatMap { it.tracks }
    return CollectionSelection(
        title = title,
        subtitle = listOfNotNull(
            path.takeIf(String::isNotEmpty),
            getPluralString(Res.plurals.count_tracks, ordered.size, ordered.size),
        ).joinToString(FOLDER_SUMMARY_SEPARATOR),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        sections = sections.takeIf { it.size > 1 },
        railMode = if (sections.size > 1) CollectionRailMode.NONE else trackRailModeFor(ordered),
        allowsTrackSelection = true,
        routeId = FOLDER_TREE_ROUTE_PREFIX + key,
    )
}

/**
 * A folder's own tracks as a page, for the queue source a track tapped in it leaves behind: the
 * tracks played beside that track, in the order they played ([FolderNode.tracks], folder order).
 */
internal suspend fun FolderNode.toTracksSelection(): CollectionSelection =
    CollectionSelection(
        title = pageTitle(),
        subtitle = listOfNotNull(
            path.takeIf(String::isNotEmpty),
            getPluralString(Res.plurals.count_tracks, tracks.size, tracks.size),
        ).joinToString(FOLDER_SUMMARY_SEPARATOR),
        artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
        tracks = tracks,
        railMode = trackRailModeFor(tracks),
        allowsTrackSelection = true,
        routeId = FOLDER_TRACKS_ROUTE_PREFIX + key,
    )

/** A folder page's title: the folder's name, or the name of the storage volume whose root it is. */
private suspend fun FolderNode.pageTitle(): String = when {
    isLibraryRoot -> getString(Res.string.tab_folders)
    isVolumeRoot -> when (volume) {
        null -> getString(Res.string.tab_folders)
        FolderTree.PRIMARY_VOLUME -> getString(Res.string.folder_root_internal_storage)
        else -> AppGraph.library.storageVolumeNames()[volume] ?: getString(Res.string.folder_root_sd_card)
    }
    else -> name
}

/**
 * [branch]'s tracks as one section per folder holding some, in [FolderNode.allTracks] order: each
 * titled by its path below [branch], the branch's own tracks by [ownTitle].
 */
internal fun folderBranchSections(
    branch: FolderNode,
    direction: SongSortDirection,
    ownTitle: String,
): List<CollectionSection> = buildList {
    fun visit(node: FolderNode) {
        if (node.tracks.isNotEmpty()) {
            val relative = node.path.removePrefix(branch.path).trimStart('/')
            add(CollectionSection(title = relative.ifEmpty { ownTitle }, tracks = node.tracks, railTitle = null))
        }
        node.folders(direction).forEach(::visit)
    }
    visit(branch)
}

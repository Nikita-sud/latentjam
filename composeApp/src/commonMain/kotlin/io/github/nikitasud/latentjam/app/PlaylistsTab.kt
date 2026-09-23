/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_cancel
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_export_m3u
import io.github.nikitasud.latentjam.app.generated.resources.action_import_m3u
import io.github.nikitasud.latentjam.app.generated.resources.action_smart_keep_together
import io.github.nikitasud.latentjam.app.generated.resources.action_rename
import io.github.nikitasud.latentjam.app.generated.resources.action_change_playlist_cover
import io.github.nikitasud.latentjam.app.generated.resources.action_reset_playlist_cover
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_most_played
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_favorites
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_never_played
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_recently_added
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_recently_played
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_rediscover
import io.github.nikitasud.latentjam.app.generated.resources.cd_playlist_options
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.playlist_add_to_title
import io.github.nikitasud.latentjam.app.generated.resources.playlist_add_to_title_generic
import io.github.nikitasud.latentjam.app.generated.resources.playlist_name_placeholder
import io.github.nikitasud.latentjam.app.generated.resources.playlist_new
import io.github.nikitasud.latentjam.app.generated.resources.playlists_empty_body
import io.github.nikitasud.latentjam.app.generated.resources.playlists_yours
import io.github.nikitasud.latentjam.app.generated.resources.settings_page_move_up
import io.github.nikitasud.latentjam.app.generated.resources.settings_page_move_down
import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Playlists tab: the playlists the app derives for you across the top,
 * then the ones you made. Auto playlists lead because they are useful on day
 * one, before anything has been curated.
 */
@Composable
internal fun PlaylistsTabContent(
    autoPlaylists: List<AutoPlaylist>,
    playlists: List<Playlist>,
    tracksOf: (Playlist) -> List<TrackDescriptor>,
    contentPadding: PaddingValues,
    onOpenAuto: (AutoPlaylist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onRename: (Playlist) -> Unit,
    onChangeCover: (Playlist) -> Unit,
    onResetCover: (Playlist) -> Unit,
    coverEditBusy: Boolean = false,
    onExport: (Playlist) -> Unit = {},
    onToggleSmart: (Playlist) -> Unit = {},
    onDelete: (Playlist) -> Unit,
    /** Commits a long-press drag: the playlist at [from] drops at [to], both list positions. */
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onCreate: () -> Unit = {},
    onImport: () -> Unit = {},
    currentAccent: androidx.compose.ui.graphics.Color? = null,
    artworkModifier: @Composable (routeId: String) -> Modifier = { Modifier },
) {
    // The scroll position SURVIVES leaving and re-entering the tab: an earlier build reset to
    // the top on every return "so the auto playlists stay visible", and the losing side of
    // that trade was every listener who had scrolled somewhere on purpose. The way back up is
    // the fast-travel button instead of a forced teleport.
    val listState = remember { LazyListState() }
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val featuredPlaylists = remember(autoPlaylists) {
        listOf(
            AutoPlaylistKind.FAVORITES,
            AutoPlaylistKind.RECENTLY_ADDED,
            AutoPlaylistKind.MOST_PLAYED,
            AutoPlaylistKind.RECENTLY_PLAYED,
        ).map { kind ->
            autoPlaylists.firstOrNull { it.kind == kind } ?: AutoPlaylist(kind, emptyList())
        }
    }
    // Same float-and-drop contract as the player's queue: the pressed row floats, ONE move
    // commits on release — mutating mid-drag would re-key the row under the finger.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().fadingListTop { listState.canScrollBackward },
            contentPadding = contentPadding,
        ) {
            if (featuredPlaylists.isNotEmpty()) {
                item(key = "auto") {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(featuredPlaylists, key = { it.kind.name }) { auto ->
                                AutoPlaylistCard(
                                    auto = auto,
                                    artworkModifier = artworkModifier("auto:${auto.kind.name}"),
                                    onClick = { onOpenAuto(auto) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "your-playlists") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistSectionLabel(
                        text = stringResource(Res.string.playlists_yours),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onImport, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Rounded.FileOpen,
                            contentDescription = stringResource(Res.string.action_import_m3u),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item(key = "new-playlist") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(16.dp)).clickable(onClick = onCreate)
                        .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier.size(48.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        text = stringResource(Res.string.playlist_new),
                        style = MaterialTheme.typography.bodyLarge,
                        color = currentAccent ?: MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (playlists.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                                ),
                                placementSpec = if (reduceMotion) null else tween(Motion.APPEAR_MS),
                                fadeOutSpec = tween(
                                    if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                                ),
                            )
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.playlists_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                Box(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                            ),
                            placementSpec = if (reduceMotion) null else tween(Motion.APPEAR_MS),
                            fadeOutSpec = tween(
                                if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                            ),
                        )
                        .then(
                            if (draggingIndex == index) {
                                Modifier
                                    .zIndex(1f)
                                    .graphicsLayer { translationY = dragOffsetY }
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(index, playlists.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingIndex = index
                                    dragTargetIndex = index
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    val rowHeight = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == playlist.id }
                                        ?.size
                                        ?.takeIf { it > 0 }
                                    if (rowHeight != null) {
                                        dragTargetIndex =
                                            (index + (dragOffsetY / rowHeight).roundToInt())
                                                .coerceIn(0, playlists.lastIndex)
                                    }
                                },
                                onDragEnd = {
                                    val from = draggingIndex
                                    val to = dragTargetIndex
                                    draggingIndex = null
                                    dragTargetIndex = null
                                    dragOffsetY = 0f
                                    if (from != null && to != null && from != to) {
                                        onMove(from, to)
                                        // A completed move gets one quiet landing cue. Holding,
                                        // cancelling, or returning to the same slot does not.
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    dragTargetIndex = null
                                    dragOffsetY = 0f
                                },
                            )
                        },
                ) {
                    PlaylistRow(
                        playlist = playlist,
                        artworkModifier = artworkModifier("playlist:${playlist.id}"),
                        tracks = tracksOf(playlist),
                        onClick = { onOpenPlaylist(playlist) },
                        onRename = { onRename(playlist) },
                        onChangeCover = { onChangeCover(playlist) },
                        onResetCover = { onResetCover(playlist) },
                        coverEditBusy = coverEditBusy,
                        onExport = { onExport(playlist) },
                        onToggleSmart = { onToggleSmart(playlist) },
                        onDelete = { onDelete(playlist) },
                        onMoveUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
                        onMoveDown = if (index < playlists.lastIndex) ({ onMove(index, index + 1) }) else null,
                    )
                }
            }
        }
        ScrollToTopButton(
            listState = listState,
            bottomInset = contentPadding.calculateBottomPadding(),
        )
    }
}

/**
 * The builder emits a playlist KIND; the title is written here.
 *
 * Same split as the For You sections: the derivation stays a pure, unit-tested
 * function in core, and the words it implies arrive in the reader's language.
 *
 * The mapping lives on its own so both readers can share it — cards resolve it
 * in composition, while opening a collection resolves it inside a coroutine.
 */
internal fun AutoPlaylistKind.titleRes(): StringResource = when (this) {
    AutoPlaylistKind.FAVORITES -> Res.string.auto_playlist_favorites
    AutoPlaylistKind.RECENTLY_ADDED -> Res.string.auto_playlist_recently_added
    AutoPlaylistKind.MOST_PLAYED -> Res.string.auto_playlist_most_played
    AutoPlaylistKind.RECENTLY_PLAYED -> Res.string.auto_playlist_recently_played
    AutoPlaylistKind.NEVER_PLAYED -> Res.string.auto_playlist_never_played
    AutoPlaylistKind.REDISCOVER -> Res.string.auto_playlist_rediscover
}

@Composable
private fun AutoPlaylistKind.title(): String = stringResource(titleRes())

@Composable
private fun PlaylistSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 0.4.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun AutoPlaylistCard(
    auto: AutoPlaylist,
    artworkModifier: Modifier,
    onClick: () -> Unit,
) {
    val artworkUri = remember(auto.tracks) { auto.tracks.firstNotNullOfOrNull { it.artworkUri } }
    Column(
        modifier = Modifier.width(150.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clickable(onClick = onClick),
    ) {
        Artwork(uri = artworkUri, size = 150.dp, cornerRadius = 18.dp, modifier = artworkModifier)
        Text(
            text = auto.kind.title(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = pluralStringResource(Res.plurals.count_tracks, auto.tracks.size, auto.tracks.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    artworkModifier: Modifier,
    tracks: List<TrackDescriptor>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onChangeCover: () -> Unit,
    onResetCover: () -> Unit,
    coverEditBusy: Boolean,
    onExport: () -> Unit,
    onToggleSmart: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val moveUpLabel = stringResource(Res.string.settings_page_move_up, playlist.name)
    val moveDownLabel = stringResource(Res.string.settings_page_move_down, playlist.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics {
                customActions = listOfNotNull(
                    onMoveUp?.let { move -> CustomAccessibilityAction(moveUpLabel) { move(); true } },
                    onMoveDown?.let { move -> CustomAccessibilityAction(moveDownLabel) { move(); true } },
                )
            }
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(
            uri = playlistCoverUri(playlist.customArtworkRef)
                ?: tracks.firstNotNullOfOrNull { it.artworkUri },
            size = 48.dp,
            cornerRadius = 12.dp,
            modifier = artworkModifier,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (playlist.includeInSmart) {
                    Icon(
                        imageVector = LatentJamMark,
                        contentDescription = stringResource(Res.string.action_smart_keep_together),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                // Resolved tracks, not stored ids: a playlist keeps the id of a track deleted
                // from the device (so it self-heals if the file returns), but the number shown
                // must count what tapping the row will actually show.
                text = pluralStringResource(Res.plurals.count_tracks, tracks.size, tracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(Res.string.cd_playlist_options, playlist.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                val iconModifier = Modifier.size(20.dp)
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                DropdownMenuItem(
                    text = {
                        Text(stringResource(Res.string.action_rename),
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null,
                            modifier = iconModifier, tint = iconTint)
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(Res.string.action_change_playlist_cover),
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Image, contentDescription = null,
                            modifier = iconModifier, tint = iconTint)
                    },
                    enabled = !coverEditBusy,
                    onClick = {
                        menuOpen = false
                        onChangeCover()
                    },
                )
                if (playlist.customArtworkRef != null) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(Res.string.action_reset_playlist_cover),
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Restore, contentDescription = null,
                                modifier = iconModifier, tint = iconTint)
                        },
                        enabled = !coverEditBusy,
                        onClick = {
                            menuOpen = false
                            onResetCover()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(stringResource(Res.string.action_export_m3u),
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.IosShare, contentDescription = null,
                            modifier = iconModifier, tint = iconTint)
                    },
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(Res.string.action_smart_keep_together),
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = null,
                            modifier = iconModifier, tint = iconTint)
                    },
                    trailingIcon = if (playlist.includeInSmart) {
                        {
                            Icon(Icons.Outlined.Check, contentDescription = null,
                                modifier = iconModifier, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        null
                    },
                    onClick = {
                        menuOpen = false
                        onToggleSmart()
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.action_delete),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = iconModifier,
                            tint = iconTint,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Create or rename — the same one-field question either way. */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    initialName: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    errorMessage: String? = null,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (name.isNotBlank() && !busy) {
            focus.clearFocus()
            keyboard?.hide()
            onConfirm(name.trim())
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.imePadding().padding(24.dp).widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, Modifier.size(24.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                EditorTextField(
                    label = stringResource(Res.string.playlist_name_placeholder),
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    isError = errorMessage != null,
                    modifier = Modifier.focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                errorMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                EditorActions(
                    cancelLabel = stringResource(Res.string.action_cancel),
                    confirmLabel = confirmLabel,
                    onCancel = onDismiss,
                    onConfirm = submit,
                    confirmEnabled = name.isNotBlank(),
                    busy = busy,
                )
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
}

/** Picks a destination playlist for one or more tracks, or makes a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    tracks: List<TrackDescriptor>,
    playlists: List<Playlist>,
    /** Resolved (playable) track count per playlist; stored ids may include deleted files. */
    resolvedSize: (Playlist) -> Int = { it.trackIds.size },
    onAddTo: (Playlist) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    errorMessage: String? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissalInFlight by remember { mutableStateOf(false) }

    fun dismissThen(action: () -> Unit) {
        if (dismissalInFlight || busy) return
        dismissalInFlight = true
        if (reduceMotion) {
            onDismiss()
            action()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
                action()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissThen {} },
        sheetState = sheetState,
        sheetGesturesEnabled = !busy && !dismissalInFlight,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Two whole sentences rather than a fragment plus an interpolated
            // fallback noun: "track" would have had to decline with the verb in
            // half the languages this ships in.
            val trackTitle = tracks.singleOrNull()?.title
            Text(
                text = if (tracks.size > 1) {
                    stringResource(Res.string.action_add_to_playlist) + " · " +
                        pluralStringResource(Res.plurals.count_tracks, tracks.size, tracks.size)
                } else if (trackTitle != null) {
                    stringResource(Res.string.playlist_add_to_title, trackTitle)
                } else {
                    stringResource(Res.string.playlist_add_to_title_generic)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy && !dismissalInFlight) {
                        dismissThen(onCreateNew)
                    }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.playlist_new),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy && !dismissalInFlight) {
                                onAddTo(playlist)
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = pluralStringResource(Res.plurals.count_tracks, resolvedSize(playlist), resolvedSize(playlist)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

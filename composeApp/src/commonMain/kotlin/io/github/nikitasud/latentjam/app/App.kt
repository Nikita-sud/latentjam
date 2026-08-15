/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_add
import io.github.nikitasud.latentjam.app.generated.resources.action_create
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_next
import io.github.nikitasud.latentjam.app.generated.resources.action_pause
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_play_all
import io.github.nikitasud.latentjam.app.generated.resources.action_previous
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_rename
import io.github.nikitasud.latentjam.app.generated.resources.action_import_m3u
import io.github.nikitasud.latentjam.app.generated.resources.action_undo
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle_all
import io.github.nikitasud.latentjam.app.generated.resources.action_share
import io.github.nikitasud.latentjam.app.generated.resources.cd_more_options
import io.github.nikitasud.latentjam.app.generated.resources.cd_search_library
import io.github.nikitasud.latentjam.app.generated.resources.count_albums
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_progress
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_progress_eta
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_title
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_discovery
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_instrumental
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_meme_viral
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_sound_effects
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_spoken_audio
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_title
import io.github.nikitasud.latentjam.app.generated.resources.library_empty
import io.github.nikitasud.latentjam.app.generated.resources.library_import_action
import io.github.nikitasud.latentjam.app.generated.resources.library_import_hint
import io.github.nikitasud.latentjam.app.generated.resources.library_import_none
import io.github.nikitasud.latentjam.app.generated.resources.library_import_partial
import io.github.nikitasud.latentjam.app.generated.resources.library_imported
import io.github.nikitasud.latentjam.app.generated.resources.folder_content_description
import io.github.nikitasud.latentjam.app.generated.resources.playlist_imported_name
import io.github.nikitasud.latentjam.app.generated.resources.playlist_new
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_import_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_imported
import io.github.nikitasud.latentjam.app.generated.resources.playlist_rename_title
import io.github.nikitasud.latentjam.app.generated.resources.settings_title
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_added_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.snack_artist_excluded_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_artist_included_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_created
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_track_removed
import io.github.nikitasud.latentjam.app.generated.resources.snack_smart_exclusion_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_excluded_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_included_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_removed_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.snack_hidden_tracks_restored
import io.github.nikitasud.latentjam.app.generated.resources.snack_library_refreshed
import io.github.nikitasud.latentjam.app.generated.resources.sort_direction_ascending
import io.github.nikitasud.latentjam.app.generated.resources.sort_direction_descending
import io.github.nikitasud.latentjam.app.generated.resources.sort_recently_added
import io.github.nikitasud.latentjam.app.generated.resources.tab_albums
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.tab_for_you
import io.github.nikitasud.latentjam.app.generated.resources.tab_folders
import io.github.nikitasud.latentjam.app.generated.resources.tab_genres
import io.github.nikitasud.latentjam.app.generated.resources.tab_map
import io.github.nikitasud.latentjam.app.generated.resources.tab_playlists
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_album
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_genre
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.history.LibraryListeningStats
import io.github.nikitasud.latentjam.history.SmartExclusionState
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.history.excludes
import io.github.nikitasud.latentjam.history.excludesArtist
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylists
import io.github.nikitasud.latentjam.library.FolderGroup
import io.github.nikitasud.latentjam.library.GenreGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistTrackChange
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.library.defaultDirection
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import io.github.nikitasud.latentjam.smart.cluster.LayoutPoint
import io.github.nikitasud.latentjam.smart.cluster.StoredLibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.loadStoredLayout
import io.github.nikitasud.latentjam.smart.cluster.saveLayout
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Shared-element keys for the mini-player → now-playing morph. */
internal const val ARTWORK_KEY = "now-playing-artwork"
internal const val PLAYER_SURFACE_KEY = "now-playing-surface"
internal const val OVERFLOW_KEY = "overflow-button"

/**
 * Root-pager state captured when a hide command starts and again when its Undo finishes.
 * [navigationRevision] is an invalidation token, not a count shown to users: it may advance both
 * when navigation is requested and when a swipe settles so neither input path can be missed.
 */
internal data class RootTabSnapshot(
    val navigationRevision: Long,
    val currentTab: Int,
    val settledTab: Int,
)

/**
 * A hide Undo may restore the persisted track regardless of navigation, but it may only restore
 * the detail-page snapshot if the listener has stayed on the exact root page that owned it.
 */
internal fun shouldRestoreCollectionAfterHideUndo(
    appliedCollectionRevision: Long?,
    currentCollectionRevision: Long,
    sourceRootTab: RootTabSnapshot,
    currentRootTab: RootTabSnapshot,
): Boolean =
    appliedCollectionRevision != null &&
        currentCollectionRevision == appliedCollectionRevision &&
        sourceRootTab.navigationRevision == currentRootTab.navigationRevision &&
        sourceRootTab.currentTab == sourceRootTab.settledTab &&
        currentRootTab.currentTab == sourceRootTab.currentTab &&
        currentRootTab.settledTab == sourceRootTab.settledTab

/**
 * What a set of discovered library worlds was built from.
 *
 * Clustering is deterministic, so equal keys mean an equal result and the work can be skipped.
 * The vector source is part of the key because upgrading metadata-only vectors to fused
 * audio+metadata ones genuinely changes the answer, and that rebuild must still happen.
 */
private data class LibraryWorldDiscoveryKey(
    val trackIds: List<TrackId>,
    val source: LibraryVectorSource,
    /** Raw selected-vector contents, not just their population/source. */
    val vectorFingerprint: Long,
    /** Metadata that names/routes worlds even when its embedding happens to stay equal. */
    val descriptorIdentity: List<WorldTrackIdentity>,
    val semanticsCount: Int,
)

private data class LibraryWorldsKey(
    val discovery: LibraryWorldDiscoveryKey,
    /** Ordered user names/membership used to name the already-discovered worlds. */
    val playlistIdentity: List<PlaylistPresentationIdentity>,
)

/** Playlist fields that can change a For You collection or a discovered-world title. */
internal data class PlaylistPresentationIdentity(
    val id: String,
    val name: String,
    val trackIds: List<String>,
)

internal fun List<Playlist>.presentationIdentity(): List<PlaylistPresentationIdentity> = map { playlist ->
    PlaylistPresentationIdentity(
        id = playlist.id,
        name = playlist.name,
        trackIds = playlist.trackIds.toList(),
    )
}

/** Membership policy consumed by both foreground and background SMART planners. */
internal fun List<Playlist>.smartCompanionMemberships(): List<Set<TrackId>> {
    val normalized = asSequence()
        .filter(Playlist::includeInSmart)
        .map { playlist -> playlist.trackIds.distinct().sorted().map(::TrackId) }
        // A singleton cannot keep anything together. Omitting it also avoids throwing away a
        // perfectly good generated future when toggling an ineffective one-track playlist.
        .filter { it.size >= 2 }
        // Core SMART deliberately collapses identical membership groups. Mirror its effective
        // policy here so adding a second playlist with the same tracks does not prune/refill an
        // unchanged future (or wake the lazy model) for no behavioral gain.
        .distinct()
        .toList()
    // Playlist drag order is presentation, not recommendation policy. Quota rotation is ordered,
    // so give core SMART a canonical membership order or merely reorganizing the Playlists tab
    // changes the next recommendation and needlessly refills the queue.
    return normalized.sortedWith { left, right ->
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = left[index].value.compareTo(right[index].value)
            if (comparison != 0) return@sortedWith comparison
        }
        left.size.compareTo(right.size)
    }.map { it.toCollection(LinkedHashSet()) }
}

/** Initial hydration reconstructs saved policy; only a later real change invalidates lookahead. */
internal fun shouldInvalidateSmartFuture(
    policyInitialized: Boolean,
    previous: List<Set<TrackId>>,
    updated: List<Set<TrackId>>,
): Boolean = policyInitialized && previous != updated

/** An externally resumed platform queue always wins over reloading and pausing the saved queue. */
internal fun shouldApplySavedPlaybackAfterPlatformSync(now: NowPlaying): Boolean =
    now.track == null

/** Resolves a canonical resume source without mistaking generated SMART rows for that source. */
internal fun resolveResumeSourceQueue(
    saved: ResumePlayback,
    library: List<TrackDescriptor>,
    playlists: List<Playlist>,
    playlistsAvailable: Boolean = true,
): List<TrackDescriptor>? {
    val byId = library.associateBy { it.id.value }
    return when {
        saved.sourceQueuePersisted -> saved.sourceQueueTrackIds.mapNotNull(byId::get)
        saved.sourceKind == QueueSourceKind.TRACKS.name -> library
        saved.sourceKind == QueueSourceKind.COLLECTION.name &&
            saved.sourceReference != null && playlistsAvailable ->
            playlists.firstOrNull { it.id == saved.sourceReference }
                ?.trackIds
                ?.mapNotNull(byId::get)
                .orEmpty()
        else -> null
    }
}

internal data class ResumeFallbackQueue(
    val liveQueue: List<TrackDescriptor>,
    val currentIndex: Int,
)

/**
 * Reconstructs a coherent live queue when an old or oversized session did not persist one.
 *
 * SMART must restart from the current seed alone so the chooser plans a real recommendation
 * future; treating the complete source as a saved SMART plan would play it linearly. ON creates a
 * fresh traversal with current first, while OFF restores canonical source order. The injectable
 * shuffler keeps this state transition deterministic in tests.
 */
internal fun fallbackResumeQueue(
    mode: ShuffleMode?,
    current: TrackDescriptor,
    source: List<TrackDescriptor>,
    shuffle: (List<TrackDescriptor>) -> List<TrackDescriptor> = { it.shuffled() },
): ResumeFallbackQueue {
    val distinctSource = source.distinctBy(TrackDescriptor::id)
    return when (mode) {
        ShuffleMode.SMART -> ResumeFallbackQueue(listOf(current), 0)
        ShuffleMode.ON -> ResumeFallbackQueue(
            liveQueue = listOf(current) + shuffle(distinctSource.filterNot { it.id == current.id }),
            currentIndex = 0,
        )
        ShuffleMode.OFF, null -> {
            val currentIndex = distinctSource.indexOfFirst { it.id == current.id }
            if (currentIndex >= 0) {
                ResumeFallbackQueue(distinctSource, currentIndex)
            } else {
                ResumeFallbackQueue(listOf(current) + distinctSource, 0)
            }
        }
    }
}

private data class WorldTrackIdentity(
    val id: TrackId,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
)

private fun TrackDescriptor.worldIdentity() = WorldTrackIdentity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    genre = genre,
    year = year,
)

/** The exact surface that raised a track menu; hidden screens never lend it their actions. */
private data class TrackMenuRequest(
    val track: TrackDescriptor,
    val sourcePlaylistId: String? = null,
    val sourcePlaylistTitle: String? = null,
)

/** Exit-frame styling retained without observable state: the live branch never reads it. */
private class LastMiniPresentation(
    var track: TrackDescriptor?,
    var accent: TrackAccent,
    var isPlaying: Boolean,
)

/** Map positions are a derived cache: storage trouble must never make the app or Map unusable. */
private suspend fun IndexStore.loadMapLayoutOrEmpty(): StoredLibraryLayout = try {
    loadStoredLayout()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    println("SMART: could not load the cached map layout: $failure")
    StoredLibraryLayout(positions = emptyMap(), fingerprint = null)
}

/** Returns after a best-effort cache write; the freshly computed in-memory layout remains valid. */
private suspend fun IndexStore.saveMapLayoutBestEffort(
    points: List<LayoutPoint>,
    fingerprint: Long,
) {
    try {
        saveLayout(points, fingerprint)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("SMART: could not save the derived map layout: $failure")
    }
}

/**
 * Root composable, shared by Android and iOS: the player shell.
 *
 * A carousel switches between library sections, the content sits on one
 * full-bleed rounded surface, and the mini-player floats on that surface —
 * expanding into the now-playing screen through a shared-element morph, so
 * the pill visibly becomes the player rather than being replaced by it.
 * All Material 3, all original expression — the legacy app's look is never
 * consulted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun App(engine: SimilarityEngine, library: MusicLibrary, playback: PlaybackController) {
    val settings = AppGraph.settings
    val themeMode by settings.themeMode.collectAsState()
    val startPage by settings.startPage.collectAsState()
    val trackColorMode by settings.trackColorMode.collectAsState()
    val smartQueueLength by settings.smartQueueLength.collectAsState()
    val includeNoveltyMixes by settings.includeNoveltyMixes.collectAsState()
    val historyRevision by AppGraph.historyRevision.collectAsState()
    val smartExclusions = AppGraph.smartExclusions
    val smartExclusionState by smartExclusions.state.collectAsState()
    val reduceMotion = rememberPlatformReduceMotion()
    val layoutDirection = LocalLayoutDirection.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    PlatformThemeEffect(darkTheme = darkTheme)
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
    MaterialTheme(colorScheme = latentJamColorScheme(darkTheme = darkTheme)) {
        val scope = rememberCoroutineScope()
        val sleepTimer = remember { AppGraph.sleepTimer }
        val sleepTimerState by sleepTimer.state.collectAsState()
        LaunchedEffect(playback, smartQueueLength) {
            playback.setSmartQueueLength(smartQueueLength)
        }
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        // SMART reconciliation needs more than the rows: an unavailable media permission is
        // represented by an empty/partial list, which must not be mistaken for deletion. Keep the
        // authority bit bound to the scan that produced these exact rows so a later permission
        // transition cannot retroactively reclassify an older empty result.
        var libraryIndexingRequest by remember {
            mutableStateOf<AutomaticIndexingRequest?>(null)
        }
        fun publishLibraryTracks(
            value: List<TrackDescriptor>,
            authoritative: Boolean,
        ) {
            tracks = value
            libraryIndexingRequest = AutomaticIndexingRequest(value, authoritative)
        }
        suspend fun scanLibrary(): List<TrackDescriptor> {
            val scan = library.scan()
            // iOS may resolve its Music-library prompt inside scan(); Android refreshes here as
            // a second line of defence around a return from system settings.
            AppGraph.permissions.refresh()
            publishLibraryTracks(
                value = scan.tracks,
                authoritative = authoritativeLibrarySnapshot(
                    scanCompleted = scan.complete,
                    permissionStatus = AppGraph.permissions.audioLibraryStatus.value,
                ),
            )
            return scan.tracks
        }
        // The pager owns the section position; everything else reads it. One source of truth means
        // the strip and the content can never disagree about where a half-finished swipe is.
        val pagerState = rememberPagerState(initialPage = startPage.tabIndex()) { BROWSE_TABS.size }
        val selectedTab = pagerState.currentPage
        var rootTabNavigationRevision by remember { mutableLongStateOf(0L) }
        fun rootTabSnapshot() = RootTabSnapshot(
            navigationRevision = rootTabNavigationRevision,
            currentTab = pagerState.currentPage,
            settledTab = pagerState.settledPage,
        )
        fun navigateToRootTab(tab: Int) {
            if (tab == pagerState.currentPage && tab == pagerState.settledPage) return
            // Invalidate immediately so an Undo racing an animated tab change cannot restore a
            // detail page during the first frame, before currentPage has moved.
            rootTabNavigationRevision++
            scope.launch {
                if (reduceMotion) pagerState.scrollToPage(tab) else pagerState.animateScrollToPage(tab)
            }
        }
        LaunchedEffect(pagerState) {
            var previousSettledTab = pagerState.settledPage
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { settledTab ->
                    if (settledTab != previousSettledTab) {
                        previousSettledTab = settledTab
                        // Swipes do not pass through navigateToRootTab, so settling independently
                        // invalidates restoration. A requested animation may advance twice; this
                        // value is deliberately a monotonic token rather than a navigation count.
                        rootTabNavigationRevision++
                    }
                }
        }
        var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
        var playlistsLoaded by remember { mutableStateOf(false) }
        var playlistsAvailableForSourceRestore by remember { mutableStateOf(false) }
        var companionGroupsInitialized by remember { mutableStateOf(false) }
        fun publishPlaylists(value: List<Playlist>) {
            // On first hydration, publish the planning policy before allowing queue resume. A
            // short saved SMART queue may top itself up immediately; letting restore win this race
            // would generate that future with an empty policy and then preserve it as “initial”.
            if (!playlistsLoaded) {
                AppGraph.smartCompanionGroups.value = value.smartCompanionMemberships()
                companionGroupsInitialized = true
            }
            playlists = value
            playlistsLoaded = true
            playlistsAvailableForSourceRestore = true
        }
        var playCounts by remember { mutableStateOf<Map<TrackId, Int>>(emptyMap()) }
        var lastPlayedAt by remember { mutableStateOf<Map<TrackId, Long>>(emptyMap()) }
        // Rediscover is time-derived even when the library and history are unchanged. Refresh the
        // clock when the listener revisits Playlists or returns to the foreground so a track that
        // crossed the rest threshold while the app was idle appears without a process restart.
        var autoPlaylistClockMs by remember { mutableLongStateOf(epochMillis()) }
        var showCreatePlaylist by remember { mutableStateOf(false) }
        var renameTarget by remember { mutableStateOf<Playlist?>(null) }
        var addToPlaylistSelection by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        var pendingPlaylistTracks by remember { mutableStateOf<List<TrackDescriptor>>(emptyList()) }
        var selectedTrackIds by remember { mutableStateOf<Set<TrackId>>(emptySet()) }
        var selectionRevision by remember { mutableLongStateOf(0L) }
        fun updateTrackSelection(value: Set<TrackId>) {
            if (selectedTrackIds != value) {
                selectedTrackIds = value
                selectionRevision++
            }
        }
        var savedSongSort by rememberSaveable { mutableStateOf(SongSort.TITLE.name) }
        val songSort = SongSort.entries.firstOrNull { it.name == savedSongSort } ?: SongSort.TITLE
        var savedSongSortDirection by rememberSaveable {
            mutableStateOf(songSort.defaultDirection.name)
        }
        val songSortDirection = SongSortDirection.entries
            .firstOrNull { it.name == savedSongSortDirection }
            ?: songSort.defaultDirection
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var infoTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var showNowPlaying by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var selectedCollection by remember { mutableStateOf<CollectionSelection?>(null) }
        var collectionRevision by remember { mutableLongStateOf(0L) }
        var collectionOpenJob by remember { mutableStateOf<Job?>(null) }
        fun applySelectedCollection(value: CollectionSelection?) {
            if (selectedCollection !== value) {
                selectedCollection = value
                collectionRevision++
            }
        }
        fun updateSelectedCollection(value: CollectionSelection?) {
            collectionOpenJob?.cancel()
            collectionOpenJob = null
            applySelectedCollection(value)
        }
        fun openCollection(
            build: suspend () -> CollectionSelection,
            afterOpen: () -> Unit = {},
        ) {
            val sourceRoot = rootTabSnapshot()
            val sourceTracks = tracks
            val sourceSearch = showSearch
            val sourceSettings = showSettings
            val sourcePlayer = showNowPlaying
            collectionOpenJob?.cancel()
            collectionOpenJob = scope.launch {
                val built = build()
                if (
                    !isActive ||
                    rootTabSnapshot() != sourceRoot ||
                    tracks !== sourceTracks ||
                    showSearch != sourceSearch ||
                    showSettings != sourceSettings ||
                    showNowPlaying != sourcePlayer
                ) return@launch
                applySelectedCollection(built)
                afterOpen()
            }
        }
        var playlistMutationInProgress by remember { mutableStateOf(false) }
        var playlistMutationFailed by remember { mutableStateOf(false) }
        var trackMenuRequest by remember { mutableStateOf<TrackMenuRequest?>(null) }
        var deleteTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var deleteSelection by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        var showSelectionRemoval by remember { mutableStateOf(false) }
        var hasHiddenTracks by remember { mutableStateOf(false) }
        var libraryRefreshing by remember { mutableStateOf(false) }
        // The player emits position twice per second. The browse shell only needs to know when the
        // TRACK changes; collecting the complete snapshot here used to invalidate this entire
        // pager and every visible list for each seek-bar tick.
        val currentTrack by remember(playback) {
            playback.state.map { it.track }.distinctUntilChanged()
        }.collectAsState(playback.state.value.track)
        // Play/pause transitions only — never the position ticks — so the now-playing badge's
        // motion state recomposes exactly when sound starts or stops.
        val currentTrackPlaying by remember(playback) {
            playback.state.map { it.isPlaying }.distinctUntilChanged()
        }.collectAsState(playback.state.value.isPlaying)
        val selectionMode = selectedTrackIds.isNotEmpty() && (
            selectedTab == TRACKS_TAB || selectedTab in GROUP_TABS || showSearch ||
                selectedCollection?.allowsTrackSelection == true
        )
        val accent = rememberTrackAccent(
            track = currentTrack,
            mode = trackColorMode,
            darkTheme = darkTheme,
        )
        // AnimatedVisibility retains its composition for exit frames. Keep the last presentation
        // in an ordinary main-thread holder: while a live track exists none of these fallback
        // fields are read, so updating them must not itself invalidate the shell.
        val lastMiniPresentation = remember {
            LastMiniPresentation(currentTrack, accent, currentTrackPlaying)
        }
        SideEffect {
            if (currentTrack != null) {
                // Accent animates over several frames. This value is only consulted after the
                // live track disappears, so ordinary fields retain the latest exit frame without
                // scheduling a second whole-shell recomposition for every colour-animation tick.
                lastMiniPresentation.track = currentTrack
                lastMiniPresentation.accent = accent
                lastMiniPresentation.isPlaying = currentTrackPlaying
            }
        }
        val shareTracks = rememberTrackSharer()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The lists run to the bottom edge of an edge-to-edge window, so the navigation bar is
        // theirs to clear — nothing below them does it. The pill adds its own height on top of
        // that when it is up; it sits ON the content rather than beside it, so the two add.
        val listPadding = PaddingValues(
            bottom = navBottom + when {
                selectionMode -> SELECTION_ACTION_BAR_HEIGHT
                currentTrack != null -> MINI_PLAYER_HEIGHT
                else -> 12.dp
            },
        )
        // Screens that already inset themselves against the navigation bar only need the pill's
        // own height on top.
        val floatingPlayerInset = if (currentTrack != null) MINI_PLAYER_HEIGHT else 0.dp
        // AnimatedContent removes the browse branch after the player finishes opening. Keep a
        // dedicated saveable bucket for that branch so every LazyColumn/Grid/Row returns to its
        // exact item and pixel offset when the player closes, across every tab and detail screen.
        val browseStateHolder = rememberSaveableStateHolder()

        val importAudio = rememberAudioImporter { result ->
            scope.launch {
                scanLibrary()
                val message = when {
                    result.failed > 0 || result.skipped > 0 -> getString(
                        Res.string.library_import_partial,
                        result.imported,
                        result.skipped,
                        result.failed,
                    )
                    result.imported > 0 -> getPluralString(
                        Res.plurals.library_imported,
                        result.imported,
                        result.imported,
                    )
                    else -> getString(Res.string.library_import_none)
                }
                snackbar.showSnackbar(message)
            }
        }

        var favoriteIds by remember { mutableStateOf<List<TrackId>>(emptyList()) }
        fun toggleFavorite(id: TrackId) {
            scope.launch {
                AppGraph.favorites.toggle(id)
                favoriteIds = AppGraph.favorites.all()
            }
        }
        LaunchedEffect(Unit) {
            scanLibrary()
            hasHiddenTracks = library.hasHiddenTracks()
            favoriteIds = AppGraph.favorites.all()
            // Playlists load at launch, not first tab visit: the SMART companion groups and mix
            // names derive from them, and both must work in a session that never opens the tab.
            try {
                publishPlaylists(AppGraph.playlists.all())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Playlists: could not load at launch: $failure")
                // Playlist storage is optional to transport restore. Mark an empty policy ready so
                // a read failure cannot suppress the saved player for the whole session; if a
                // later retry succeeds, the initialized-policy effect prunes/refills SMART once.
                if (!playlistsLoaded) {
                    AppGraph.smartCompanionGroups.value = emptyList()
                    companionGroupsInitialized = true
                    playlistsLoaded = true
                    playlistsAvailableForSourceRestore = false
                }
            }
        }
        // A track downloaded while the app sat in the background shows up on return instead of
        // on the next cold start. An unchanged library re-queries into an equal list, which the
        // state holder swallows — no downstream effect re-runs, so the no-change case costs one
        // MediaStore query and nothing else.
        PlatformForegroundEffect(
            // Leaving while paused ends the sitting: finalize the in-progress listening session
            // so the last track of the day stops vanishing from the history log.
            onLeave = { AppGraph.flushListeningSession() },
        ) {
            scope.launch {
                if (selectedTab == PLAYLISTS_TAB) autoPlaylistClockMs = epochMillis()
                scanLibrary()
                hasHiddenTracks = library.hasHiddenTracks()
            }
        }
        // Whenever the library changes, drop queue entries for tracks that no longer EXIST.
        // Reconciled against all known tracks — visible plus hidden — deliberately: hiding a
        // track means "stop recommending it", not "yank it out of the queue mid-session";
        // only genuine deletion removes it. Keyed on the resolved list, so an unchanged
        // library (the common foreground return) runs nothing.
        LaunchedEffect(tracks) {
            collectionOpenJob?.cancel()
            collectionOpenJob = null
            val loaded = tracks ?: return@LaunchedEffect
            // The visible half of allKnownTracks() is the list already in hand; only the hidden
            // half needs a query, saving one full MediaStore pass per library change.
            val known = loaded.mapTo(mutableSetOf()) { it.id }
            library.hiddenTracks().mapTo(known) { it.id }
            // A permission failure is also represented by an empty library snapshot. Keep a
            // persisted queue in that ambiguous case; confirmed deletion reconciles explicitly
            // below, where an empty set really does mean "the final track was removed".
            if (known.isNotEmpty()) playback.retainQueue(known)
            // An open collection screen is a snapshot from when it was opened; a track deleted
            // meanwhile must fall out of it (and its count) the same way it falls out of the
            // queue. Filtered against the VISIBLE library: this runs for albums and playlists
            // alike, and a track hidden mid-visit should also stop being shown.
            selectedCollection?.let { selection ->
                val visible = loaded.mapTo(mutableSetOf()) { it.id }
                val reconciled = selection.filterTracksForCollection { it.id in visible }
                if (reconciled != selection) {
                    updateSelectedCollection(
                        reconciled?.copy(subtitle = trackCountLabel(reconciled.tracks.size)),
                    )
                }
            }
        }
        LaunchedEffect(smartExclusions) {
            try {
                smartExclusions.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A corrupt/unreadable preference must never strand first-run library loading.
                // Keep the safe empty in-memory state and expose a retryable error unobtrusively.
                snackbar.showSnackbar(getString(Res.string.snack_smart_exclusion_failed))
            }
        }

        val smartEligibleTracks = remember(tracks, smartExclusionState) {
            tracks.orEmpty().filterNot { smartExclusionState.excludes(it) }
        }

        LaunchedEffect(rootTabNavigationRevision, showSearch, showSettings, showNowPlaying) {
            // A pending background sort belongs to the page/overlay route that launched it.
            // Navigating elsewhere must not install that old collection underneath the new route.
            collectionOpenJob?.cancel()
            collectionOpenJob = null
        }

        LaunchedEffect(selectedTab, selectedCollection) {
            // A swipe between root tabs ends a Tracks-page selection. A collection detail is a
            // full-screen child of its source tab, however; entering playlist selection there must
            // not be cancelled merely because that source tab is not Tracks.
            if (selectedCollection == null && selectedTab != TRACKS_TAB) {
                updateTrackSelection(emptySet())
            }
        }

        PlatformBackHandler(enabled = selectionMode) { updateTrackSelection(emptySet()) }

        // Arm SMART in the background as soon as the library is known: restore persisted vectors
        // and backfill genuinely new/changed rows. The audio model remains lazy when everything is
        // already indexed or remembered as an unchanged decode failure, so a permanently bad file
        // cannot wake tens of MB of model state on every launch.
        var forYou by remember { mutableStateOf(ForYouPage()) }
        var worlds by remember { mutableStateOf<List<LibraryWorld>>(emptyList()) }
        var worldLibraryIds by remember { mutableStateOf<List<TrackId>>(emptyList()) }
        var builtWorldsKey by remember { mutableStateOf<LibraryWorldsKey?>(null) }
        var builtWorldDiscoveryKey by remember { mutableStateOf<LibraryWorldDiscoveryKey?>(null) }
        var builtDiscoveredWorlds by remember { mutableStateOf<List<LibraryWorld>?>(null) }
        var builtWorlds by remember { mutableStateOf<List<LibraryWorld>?>(null) }
        var builtForYouLibraryIds by remember { mutableStateOf<List<TrackId>?>(null) }
        var builtForYouPlaylistIdentity by remember {
            mutableStateOf<List<PlaylistPresentationIdentity>?>(null)
        }
        var builtForYouExclusions by remember { mutableStateOf<SmartExclusionState?>(null) }
        var builtIncludeNoveltyMixes by remember { mutableStateOf<Boolean?>(null) }
        var personalizationRevision by remember { mutableStateOf(0) }
        var builtPersonalizationRevision by remember { mutableStateOf(-1) }
        var builtHistoryRevision by remember { mutableStateOf(-1L) }
        var forYouRefreshing by remember { mutableStateOf(false) }
        var metadataVectorsReady by remember { mutableStateOf(false) }
        var audioVectorsReady by remember { mutableStateOf(false) }
        var worldTarget by remember { mutableStateOf<ForYouCard?>(null) }
        val discoveryMixLabel = stringResource(Res.string.foryou_mix_discovery)
        val memeViralMixLabel = stringResource(Res.string.foryou_mix_meme_viral)
        val soundEffectsMixLabel = stringResource(Res.string.foryou_mix_sound_effects)
        val spokenAudioMixLabel = stringResource(Res.string.foryou_mix_spoken_audio)
        val instrumentalMixLabel = stringResource(Res.string.foryou_mix_instrumental)
        val semanticMixLabels = remember(
            memeViralMixLabel,
            soundEffectsMixLabel,
            spokenAudioMixLabel,
            instrumentalMixLabel,
        ) {
            mapOf(
                LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO to memeViralMixLabel,
                LibraryWorldSemanticTitle.SOUND_EFFECTS to soundEffectsMixLabel,
                LibraryWorldSemanticTitle.SPOKEN_AUDIO to spokenAudioMixLabel,
                LibraryWorldSemanticTitle.INSTRUMENTAL to instrumentalMixLabel,
            )
        }
        val playlistPresentationIdentity = remember(playlists) { playlists.presentationIdentity() }

        LaunchedEffect(
            tracks,
            selectedTab == FOR_YOU_TAB,
            worlds,
            worldLibraryIds,
            discoveryMixLabel,
            semanticMixLabels,
            includeNoveltyMixes,
            personalizationRevision,
            historyRevision,
            smartExclusionState,
            playlistPresentationIdentity,
        ) {
            val loaded = tracks ?: return@LaunchedEffect
            if (selectedTab != FOR_YOU_TAB) return@LaunchedEffect
            val loadedIds = loaded.map(TrackDescriptor::id)
            val requestedWorlds = if (worldLibraryIds == loadedIds) worlds else emptyList()
            // Built once, and again only when the worlds arrive — the text index fills in the
            // background, so on a cold library they are simply not ready at first visit. Never
            // rebuilt on a mere return to the tab: a page that regroups itself while being read is
            // indistinguishable from a broken one.
            if (
                !forYou.isEmpty &&
                builtWorlds == requestedWorlds &&
                builtForYouLibraryIds == loadedIds &&
                builtForYouPlaylistIdentity == playlistPresentationIdentity &&
                builtForYouExclusions == smartExclusionState &&
                builtIncludeNoveltyMixes == includeNoveltyMixes &&
                builtPersonalizationRevision == personalizationRevision &&
                builtHistoryRevision == historyRevision
            ) {
                forYouRefreshing = false
                return@LaunchedEffect
            }
            val historySnapshot = try {
                AppGraph.history.stats() to
                    AppGraph.history.recentEvents(RECENT_EVENTS_FOR_YOU)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Keep the last good page and retry on the next keyed refresh. Treating a failed
                // read as empty history would silently replace personalized cards with cold-start
                // recommendations and mark that false page as successfully built.
                println("For You: could not load listening history: $failure")
                forYouRefreshing = false
                snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                return@LaunchedEffect
            }
            val (stats, recentEvents) = historySnapshot
            val excluded = buildSet {
                loaded.asSequence()
                    .filter { smartExclusionState.excludes(it) }
                    .mapTo(this) { it.id }
                currentTrack?.id?.let(::add)
            }
            val rebuiltPage = withContext(Dispatchers.Default) {
                ForYouBuilder.build(
                    library = loaded,
                    stats = stats,
                    recentEvents = recentEvents,
                    nowMs = epochMillis(),
                    excluded = excluded,
                    playlists = playlists,
                    worlds = requestedWorlds,
                    discoveryMixLabel = discoveryMixLabel,
                    includeNoveltyMixes = includeNoveltyMixes,
                    semanticMixLabels = semanticMixLabels,
                )
            }
            // Commit the page and its input key together. If this effect is cancelled while the
            // background build runs, neither value advances and the next visit retries normally.
            forYou = rebuiltPage
            builtWorlds = requestedWorlds
            builtForYouLibraryIds = loadedIds
            builtForYouPlaylistIdentity = playlistPresentationIdentity
            builtForYouExclusions = smartExclusionState
            builtIncludeNoveltyMixes = includeNoveltyMixes
            builtPersonalizationRevision = personalizationRevision
            builtHistoryRevision = historyRevision
            forYouRefreshing = false
        }

        LaunchedEffect(libraryIndexingRequest, smartEligibleTracks) {
            val request = libraryIndexingRequest ?: return@LaunchedEffect
            val loaded = request.tracks
            // Search, playlists, and collection screens often start playback from a filtered
            // subset. SMART is a library journey, so keep its candidate universe independent of
            // whichever small list happened to contain the track the listener tapped.
            playback.setSmartLibrary(smartEligibleTracks)
            val indexing = AppGraph.automaticIndexing.value
            metadataVectorsReady =
                indexing.trackIds == loaded.map(TrackDescriptor::id) && indexing.metadataReady
            audioVectorsReady =
                indexing.trackIds == loaded.map(TrackDescriptor::id) && indexing.complete
            val notificationTitle = getString(Res.string.indexing_notification_title)
            AppGraph.ensureAutomaticIndexing(
                tracks = loaded,
                librarySnapshotAuthoritative = request.librarySnapshotAuthoritative,
                notificationTitle = notificationTitle,
            ) { done, total, etaMinutes ->
                if (etaMinutes == null) {
                    getString(Res.string.indexing_notification_progress, done, total)
                } else {
                    getString(
                        Res.string.indexing_notification_progress_eta,
                        done,
                        total,
                        etaMinutes,
                    )
                }
            }
        }

        // The UI observes the app-lifetime worker; it does not own it. Recreating MainActivity
        // therefore reconnects to the same scan instead of starting a duplicate or cancelling it.
        LaunchedEffect(libraryIndexingRequest, smartEligibleTracks) {
            val loaded = libraryIndexingRequest?.tracks ?: return@LaunchedEffect
            val eligible = smartEligibleTracks
            val trackIds = loaded.map(TrackDescriptor::id)
            AppGraph.automaticIndexing.collect { indexing ->
                if (indexing.trackIds != trackIds) return@collect
                if (metadataVectorsReady != indexing.metadataReady) {
                    metadataVectorsReady = indexing.metadataReady
                }
                if (audioVectorsReady != indexing.complete) {
                    audioVectorsReady = indexing.complete
                }
                // Failure formatting and the opt-in quality audit are only useful once the run is
                // complete. Keeping per-batch progress out of Compose avoids a second source of
                // whole-shell invalidation while the background worker is busy.
                if (!indexing.complete) return@collect
                println("SMART: progressive local index complete (${engine.state.value})")

                smartQualityDiagnosticSeeds().forEach { query ->
                    val seed = eligible.firstOrNull { track ->
                        track.title.orEmpty().contains(query, ignoreCase = true) ||
                            "${track.artist.orEmpty()} - ${track.title.orEmpty()}"
                                .contains(query, ignoreCase = true)
                    }
                    if (seed == null) {
                        println("[SMART_QUALITY] missing seed=$query")
                    } else {
                        val queue = engine.smartQueue(
                            seed,
                            eligible,
                            smartQueueLength,
                            smartHistoryFor(AppGraph.history, seed),
                            AppGraph.smartCompanionGroups.value,
                        )
                        val byId = eligible.associateBy { it.id }
                        val recommendations = queue.mapNotNull(byId::get)
                            .joinToString(" | ") { track ->
                                "${track.artist.orEmpty()} — ${track.title.orEmpty()}"
                            }
                        println(
                            "[SMART_QUALITY] seed=${seed.artist.orEmpty()} — " +
                                "${seed.title.orEmpty()} queue=$recommendations",
                        )
                    }
                }
            }
        }

        // The regions the library falls into. Metadata gives a fast first-launch answer. Once the
        // acoustic scan covers the library, conservative late fusion adds how the tracks actually
        // sound; if either encoder is sparse, LibraryVectorFusion retains the better-covered
        // single space instead of manufacturing zero vectors or hiding most of the collection.
        // Readiness is OBSERVED here rather than keyed on. Keying the effect on the two flags
        // restarted it the moment the second one flipped — which lands mid-clustering, so the
        // coroutine was cancelled before it could record what it had just built, and the next
        // pass repeated the identical work (measured: 280 ms then a redundant 217 ms on a cold
        // launch). Collecting instead lets each pass finish and remember its inputs, so the
        // metadata -> audio upgrade still rebuilds while a same-inputs repeat does not.
        // Puts the previous session back in the player — same track, same shuffle mode, PAUSED —
        // so SMART survives an app restart without being re-armed by hand. One attempt per
        // process: a restore that raced a user tap must not fire again later and yank the queue
        // out from under whatever the user chose instead.
        var resumeAttempted by remember { mutableStateOf(false) }
        LaunchedEffect(tracks, playlistsLoaded) {
            if (resumeAttempted) return@LaunchedEffect
            val loaded = tracks ?: return@LaunchedEffect
            // Restore may asynchronously top up a short SMART future. Wait until its marked
            // playlist policy has been hydrated so those new rows honor the saved preference.
            if (!playlistsLoaded) return@LaunchedEffect
            // An empty snapshot is the pre-permission state, not a library: consuming the one
            // restore attempt on it would silently skip the restore the first REAL load earns.
            if (loaded.isEmpty()) return@LaunchedEffect
            resumeAttempted = true
            val saved = AppGraph.settings.resumePlayback.value ?: return@LaunchedEffect
            // A widget, Quick Settings, Bluetooth, or Android Auto may have resumed the platform
            // session before this composition existed. Import that state before deciding whether
            // launch restore is needed; replacing it here would pause live playback.
            playback.synchronizeWithPlatformSession()
            // Something already sounding (an external controller, a fast user) wins outright.
            if (!shouldApplySavedPlaybackAfterPlatformSync(playback.state.value)) {
                return@LaunchedEffect
            }
            // The mode is the more important half — restore it even when the track is gone
            // (deleted, SD card unmounted): SMART being on is what the user asked to keep.
            val savedMode = ShuffleMode.entries.firstOrNull { it.name == saved.shuffleMode }
            savedMode?.let { playback.setShuffleMode(it) }
            // Prefer the queue that was ACTUALLY playing — a playlist stays that playlist across
            // a restart. Tracks deleted meanwhile drop out; if the saved track itself is gone or
            // no queue was saved, fall back to wrapping the track in the whole library.
            val byId = loaded.associateBy { it.id.value }
            val resolvedSavedQueue = saved.queueTrackIds.mapIndexedNotNull { persistedIndex, id ->
                byId[id]?.let { persistedIndex to it }
            }
            val savedQueue = resolvedSavedQueue.map { it.second }
            val savedQueueIndex = resolvedSavedQueue.indexOfFirst { (persistedIndex, track) ->
                persistedIndex == saved.queueIndex && track.id.value == saved.trackId
            }.takeIf { it >= 0 } ?: savedQueue.indexOfFirst { it.id.value == saved.trackId }
            // Oversized sources are omitted from frequently-written preferences. Tracks rebuild
            // from the library; a stable playlist id rebuilds current membership. A deleted
            // playlist resolves to known-empty, never to the generated SMART tail.
            val savedSourceQueue = resolveResumeSourceQueue(
                saved = saved,
                library = loaded,
                playlists = playlists,
                playlistsAvailable = playlistsAvailableForSourceRestore,
            )
            if (savedQueueIndex >= 0) {
                playback.restoreQueue(
                    tracks = savedQueue,
                    startIndex = savedQueueIndex,
                    positionMs = saved.positionMs,
                    // Null is the legacy state (no source was ever persisted); an empty v2 source
                    // is meaningful when every original playlist row was deleted while a
                    // generated SMART current row survived.
                    sourceTracks = savedSourceQueue,
                )
            }
            val index = loaded.indexOfFirst { it.id.value == saved.trackId }
            if (savedQueueIndex < 0 && index >= 0) {
                val fallbackSource = savedSourceQueue ?: loaded
                val fallback = fallbackResumeQueue(
                    mode = savedMode,
                    current = loaded[index],
                    source = fallbackSource,
                )
                playback.restoreQueue(
                    tracks = fallback.liveQueue,
                    startIndex = fallback.currentIndex,
                    positionMs = saved.positionMs,
                    sourceTracks = fallbackSource,
                )
            }
            if (savedQueueIndex >= 0 || index >= 0) {
                // The restored player keeps saying where its queue came from. An unknown kind
                // from another build degrades to no label, same as the mode above.
                AppGraph.queueSource.value = saved.sourceKind
                    ?.let { kind -> QueueSourceKind.entries.firstOrNull { it.name == kind } }
                    ?.let { QueueSource(it, saved.sourceName, saved.sourceReference) }
            }
        }

        LaunchedEffect(tracks, playlistPresentationIdentity, includeNoveltyMixes) {
            val loaded = tracks ?: return@LaunchedEffect
            val loadedIds = loaded.map(TrackDescriptor::id)
            // Observe the readiness tier, not a boolean OR. Metadata becomes ready first; when
            // audio later completes, `true || true` is still true and used to suppress the fused
            // rebuild forever.
            snapshotFlow { metadataVectorsReady to audioVectorsReady }
                .distinctUntilChanged()
                .collect { (metadataReady, audioReady) ->
                    if (!metadataReady && !audioReady) return@collect
                    val features = engine.libraryMixFeatures(
                        ids = loadedIds,
                        // This preference is the listener's explicit request for semantic novelty
                        // and effect routing, so loading the shared model here is useful work. The
                        // default path keeps restored/failed-only libraries completely lazy.
                        loadMissingSemantics = includeNoveltyMixes,
                    ) ?: return@collect
                    val discoveryKey = LibraryWorldDiscoveryKey(
                        trackIds = loadedIds,
                        source = features.vectorSpace.source,
                        vectorFingerprint = features.vectorSpace.fingerprint,
                        descriptorIdentity = loaded.map(TrackDescriptor::worldIdentity),
                        semanticsCount = features.semantics.size,
                    )
                    val worldsKey = LibraryWorldsKey(
                        discovery = discoveryKey,
                        playlistIdentity = playlistPresentationIdentity,
                    )
                    if (worldsKey == builtWorldsKey) return@collect
                    // Snapshot for the background pass; a world mostly inside one named group
                    // takes that group's name. Playlists come first — the listener's own word
                    // outranks the artist's on a containment tie — then real (multi-track)
                    // albums, whose titles are the artist's own curation.
                    val playlistGroups = playlists.map { playlist ->
                        playlist.name to playlist.trackIds.mapTo(HashSet()) { TrackId(it) }
                    }
                    val cachedDiscovery = builtDiscoveredWorlds
                        ?.takeIf { builtWorldDiscoveryKey == discoveryKey }
                    // Discovery is derived library work, not an interaction prerequisite. Keep it
                    // on the Map's low-priority serial lane so a cold restore cannot make an
                    // immediately opened page compete with clustering at normal CPU priority.
                    val discovered = cachedDiscovery ?: withContext(AppGraph.mapLayoutDispatcher) {
                        val started = TimeSource.Monotonic.markNow()
                        LibraryWorlds.discover(
                            library = loaded,
                            vectorSpace = features.vectorSpace,
                            semantics = features.semantics,
                        ).also { mixes ->
                            val routed = mixes.groupingBy { it.content }.eachCount()
                            println(
                                "SMART: built ${mixes.size} ${features.vectorSpace.source} " +
                                    "local mixes (semantic=${features.semantics.size}, " +
                                    "routes=$routed) in " +
                                    "${started.elapsedNow().inWholeMilliseconds} ms",
                            )
                        }
                    }
                    val namedWorlds = withContext(AppGraph.mapLayoutDispatcher) {
                        val albumGroups = LibraryCatalog.build(loaded).albums
                            .filter { it.tracks.size > 1 && !it.title.isNullOrBlank() }
                            .map { album ->
                                album.title.orEmpty() to album.tracks.mapTo(HashSet()) { it.id }
                            }
                        LibraryWorlds.namedAfterGroups(
                            discovered,
                            playlistGroups + albumGroups,
                        )
                    }
                    worldLibraryIds = loadedIds
                    worlds = namedWorlds
                    builtWorldDiscoveryKey = discoveryKey
                    builtDiscoveredWorlds = discovered
                    builtWorldsKey = worldsKey
                }
        }

        // Serializes replacement layout computes when a keyed Map build is cancelled and a new
        // one starts before the old CPU loop has observed cancellation.
        val mapLayoutRefresh = remember { Mutex() }

        // The Map's positions. A SECOND libraryMixFeatures call on purpose: LibraryVectorSpace is
        // one-shot, and the instance above was consumed by LibraryWorlds.discover.
        //
        // Gated on the tab itself, not just on (tracks, worlds): this is the only thing standing
        // between "the user opened the app" and a PCA-50 + t-SNE pass (see the cache-miss branch
        // below), so it must never run for someone who never visits the Map. Keyed on
        // `pagerState.settledPage`, NOT `pagerState.currentPage` (what `selectedTab` reads): an
        // animated scroll to a tab past the Map advances `currentPage` through every intermediate
        // page on the way there, including MAP_TAB, so keying on it stacked a full t-SNE run onto
        // Dispatchers.Default for every tab tap that merely passed through the Map en route
        // somewhere else. `settledPage` only updates once a scroll actually settles, and settles
        // directly on the final destination -- transit through the Map never trips it. Settling on
        // the Map also means the listening numbers refresh on every genuine return to the tab
        // instead of only when the track/world set changes -- otherwise a session of plays would
        // sit stale here indefinitely. Deliberately NOT keyed on `historyRevision`: that advances
        // in the background during playback started from anywhere, including this page's own
        // "Play region"/"SMART from here" buttons, and MapTab's contract is that it never re-ranks
        // under a reader who is actively looking at it. Rebuilding on tab entry does not fight that
        // contract: `beyondViewportPageCount = 0` already disposes the page (and every
        // `remember(page)` value on it -- lens, selected region, zoom, pan) the moment it scrolls
        // out of view, so there is no reader state left to disturb by the next time this fires.
        var mapState by remember { mutableStateOf<MapPageState>(MapPageState.Indexing) }
        // "Show on the Map" carries a track from any list to its dot; cleared by the next request.
        var mapFocusTrackId by remember { mutableStateOf<TrackId?>(null) }
        // The regions actually behind the currently shown MapPage. Not always `worlds` itself --
        // spec section 8's smallest-library fallback (mapFallbackRegions) can stand in for it -- so
        // the "Play region"/"SMART from here" callbacks below read this, not `worlds` directly, or
        // they would silently no-op whenever the Map is showing that fallback.
        var mapRegions by remember { mutableStateOf<List<LibraryWorld>>(emptyList()) }
        // Keyed on the region labels too, exactly as the For You effect above is: they are
        // stringResource reads, so a locale change produces new ones and the page they name has to be
        // rebuilt to pick them up. Cheap when that is all that changed -- LibraryLayout.covers finds
        // the stored layout still valid, so no PCA/t-SNE runs.
        LaunchedEffect(
            tracks,
            worlds,
            worldLibraryIds,
            builtWorldsKey,
            pagerState.settledPage,
            discoveryMixLabel,
            semanticMixLabels,
        ) {
            // A previous keyed run may have been cancelled after publishing its transient build
            // state. Normalize before any precondition can return, so neither the first-build
            // placeholder nor a warm-page spinner can survive an interrupted transaction.
            mapState = mapState.afterInterruptedMapBuild()
            if (pagerState.settledPage != MAP_TAB) return@LaunchedEffect
            val loaded = tracks ?: return@LaunchedEffect
            val loadedIds = loaded.map(TrackDescriptor::id)
            val layoutStore = AppGraph.layoutStore

            // Cheap prep only: the vector space and whatever layout is already cached. Neither does
            // the O(n^2) PCA/t-SNE pass, so a cache hit below never has to tell the reader anything
            // is "building" -- only a genuine recompute, further down, does that.
            val prepared = withContext(Dispatchers.Default) {
                // `covers` (below) must be asked about the population the layout was actually
                // computed for -- LibraryVectorFusion drops any track without a usable audio or
                // metadata vector, so that population is the fused space's own ids, a filtered
                // subset of `loadedIds`, not `loadedIds` itself. Comparing against the full library
                // instead would stay "stale" forever the moment even one track fails to encode: the
                // sizes would never agree again, so every visit would pay for a full PCA+t-SNE
                // recompute and re-save a set that fails the very same check next time.
                //
                // Coverage, not the space: everything this effect needs before the cache check is
                // the id list, and building a space to read it allocates 877x1344 floats -- 4.7 MB
                // -- that nothing here reads. The rows are built below, only when a recompute
                // actually needs them. Same selection either way, so `covers` sees no difference.
                val coverage = engine.libraryMixCoverage(loadedIds) ?: return@withContext null
                coverage to layoutStore.loadMapLayoutOrEmpty()
            } ?: return@LaunchedEffect
            val (coverage, stored) = prepared

            // Finding (a) of the final review: Tsne/Pca are O(n^2) per array with nothing capping n
            // anywhere upstream. Refuse outright above the ceiling rather than silently draw a
            // truncated map that looks complete -- see LibraryLayout.MAX_TRACKS's doc for why this
            // number and what it costs at it.
            if (coverage.size > LibraryLayout.MAX_TRACKS) {
                mapState = MapPageState.TooLarge(
                    trackCount = coverage.size,
                    limit = LibraryLayout.MAX_TRACKS,
                )
                return@LaunchedEffect
            }

            // Spec section 8, row 3: a library too small for TrackClustering to form even one
            // region must not leave the Map stuck showing the indexing state forever for a user
            // whose indexing already finished -- see mapFallbackRegions. Final review finding
            // (IMPORTANT): gated on mapFallbackShouldApply, not bare `worlds.isEmpty()` -- `worlds`
            // reads empty both when discovery genuinely found nothing for this library AND when it
            // simply has not reported on this library yet (see that function's doc), and only the
            // first may borrow the fallback. When it does not apply, `regions` falls through to
            // `worlds` itself (empty or not) and the existing `regions.isEmpty()` check below keeps
            // this effect's prior state rather than treating "not ready" as "ready".
            // Worlds are a snapshot with their own library key. A non-empty result for yesterday's
            // library is no safer than an empty one: it can name deleted tracks and route region
            // actions into a stale queue. Wait until discovery reports on this exact population.
            val currentWorlds = worlds.takeIf { worldLibraryIds == loadedIds }.orEmpty()
            val regions = if (mapFallbackShouldApply(currentWorlds, worldLibraryIds, loadedIds)) {
                mapFallbackRegions(loaded, coverage.trackIds)
            } else {
                currentWorlds
            }
            if (regions.isEmpty()) {
                mapRegions = emptyList()
                mapState = MapPageState.Indexing
                return@LaunchedEffect
            }

            // Region ids are indices into `regions`: every listening/headline lookup below keys off
            // this same index, so it must stay the one place a track's region id is assigned.
            val regionOf = buildMap {
                regions.forEachIndexed { index, world ->
                    for (track in world.tracks) put(track.id, index)
                }
            }

            val needsCompute = !LibraryLayout.covers(stored, coverage)
            val stableStateBeforeBuild = mapState
            var transientBuildState: MapPageState? = null
            if (needsCompute) {
                // Final review finding (MINOR 1): a warm map -- one already drawn from a previous
                // visit -- must not blank to Building's text placeholder while a routine recompute
                // (e.g. one album added) runs underneath it; the stale page is still correct enough
                // to look at in the meantime. Only the very first build for a library, when there is
                // no previous page to keep showing, earns the bare placeholder.
                transientBuildState = stableStateBeforeBuild.duringMapBuild()
                mapState = transientBuildState
            }

            val built = try {
                withContext(AppGraph.mapLayoutDispatcher) {
                    val positions = if (needsCompute) mapLayoutRefresh.withLock {
                    // Shared with the background refresh above. Losing the race to it is the GOOD
                    // case: block here while it finishes, then adopt its result below instead of
                    // repeating the pass.
                    val fresh = layoutStore.loadMapLayoutOrEmpty()
                    if (LibraryLayout.covers(fresh, coverage)) return@withLock fresh.positions
                    // Only a genuine recompute needs the rows, so this is where they get built --
                    // and libraryMixVectors rather than libraryMixFeatures, because the Map reads
                    // no semantics and would otherwise also pay for a universal-head pass over
                    // every track the semantic cache is missing.
                    val space = engine.libraryMixVectors(loadedIds) ?: return@withContext null
                    // The stale layout is the warm start, so an added album nudges the map instead
                    // of redrawing it. `isActive` is this CoroutineScope's own cancellation state --
                    // passing it as the abort hook (finding (b)) lets a reader who navigates away
                    // mid-compute stop paying for iterations nobody will see, instead of the whole
                    // 1000-iteration pass running to completion regardless and only being noticed
                    // (and discarded) afterward.
                    val computed = LibraryLayout.compute(
                        space,
                        fresh.positions,
                        isActive = { isActive },
                    )
                    // No partial state persisted: only save and adopt a layout that actually
                    // finished. An aborted compute's result is well-formed but under-converged, and
                    // must never reach the cache or a reader.
                    if (!isActive) return@withContext null
                    // Cache persistence is not part of the user-visible transaction. The computed
                    // positions are complete and safe to draw even when this best-effort write
                    // fails; the next visit may simply recompute them.
                    layoutStore.saveMapLayoutBestEffort(computed, space.fingerprint)
                    computed.associate { it.trackId to floatArrayOf(it.x, it.y) }
                    } else {
                        stored.positions
                    }

                    val stats = AppGraph.history.stats()
                // The population the map is entitled to draw: laid out AND still in the library.
                // `positions` may be the stored layout, which outlives the tracks it was computed
                // for -- LibraryLayout.covers accepts a superset, so a track deleted since the last
                // compute keeps its saved position and no recompute clears it. Measured on a real
                // device: 878 saved positions for 877 indexed tracks, one of them a ghost. Before
                // unclaimed tracks were drawn at all, regionOf's own lookup filtered such ghosts out
                // as a side effect; now that a missing region no longer skips a dot, they have to be
                // excluded on purpose or the map would draw a track the library no longer has.
                val mappable = coverage.trackIds.toSet()
                    MapPage(
                        dots = positions.mapNotNull { (id, position) ->
                        if (id !in mappable) return@mapNotNull null
                        val entry = stats[id]
                        MapDot(
                            trackId = id,
                            x = position[0],
                            y = position[1],
                            // No region is a fact about the track, not a reason to hide it: see
                            // MapDot.NO_REGION for the five filters that leave a track unclaimed and
                            // why a tenth of a library going undrawn was the wrong answer to it.
                            region = regionOf[id] ?: MapDot.NO_REGION,
                            plays = entry?.plays ?: 0,
                            skipRate = if (entry == null || entry.plays == 0) {
                                0f
                            } else {
                                entry.skips.toFloat() / entry.plays
                            },
                        )
                        },
                    // Same index as regionOf's values above -- a short noun-phrase name per world,
                    // never a bare number or the word "region": see MapPage.regionNames' contract
                    // in MapTab.kt.
                    // Localized, and distinct even where LibraryWorlds had one shared label to give:
                    // see regionDisplayNames. The same discoveryMixLabel/semanticMixLabels the For
                    // You cards use, so one region reads the same on both surfaces.
                    regionNames = regionDisplayNames(
                        regions = regions,
                        discoveryMixLabel = discoveryMixLabel,
                        semanticLabels = semanticMixLabels,
                    ),
                    // Unfiltered: `regionOf`'s values already span every index 0 until
                    // regions.size (LibraryWorlds forbids an empty world), so summarize's
                    // compacted-not-dense region list stays dense here regardless of which tracks
                    // ended up with a laid-out position. Filtering this down to
                    // `positions.keys` first would let a world that lost every dot between the two
                    // independently-built vector spaces drop out of the id space entirely,
                    // silently shifting every higher region's stats down by one against
                    // `regionNames`, which is never compacted.
                        listening = LibraryListeningStats.summarize(regionOf = regionOf, stats = stats),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Map: could not assemble the current page: $failure")
                null
            }

            if (built == null || built.dots.isEmpty()) {
                // This run is still authoritative only while its exact transient value remains
                // installed. An external/newer transition wins; otherwise roll the transaction
                // back to the last stable page (or Indexing for a cold first build).
                if (
                    isActive &&
                    transientBuildState != null &&
                    mapState === transientBuildState
                ) {
                    mapState = stableStateBeforeBuild
                }
                return@LaunchedEffect
            }

            // Final review finding (MINOR 3): MapPageState.Ready is documented to always carry a
            // non-empty page -- reachable only when `positions` (from this visit's own
            // libraryMixFeatures call) and `regionOf` (built from `regions`, which may trace back to
            // a differently-timed libraryMixFeatures/LibraryWorlds.discover call) end up disjoint.
            // Guarded here, at the one place Ready is ever constructed, so MapTab.kt never has to
            // decide what to tell a reader about a Ready state that -- by definition -- means
            // indexing already finished; it keeps whatever state was already showing instead.
            mapRegions = regions
            mapState = MapPageState.Ready(built)
        }

        var catalog by remember { mutableStateOf<LibraryCatalog?>(null) }
        var albumSections by remember { mutableStateOf<List<AlbumRailSection>>(emptyList()) }
        var alphabeticAlbumTracks by remember {
            mutableStateOf<List<TrackDescriptor>>(emptyList())
        }
        LaunchedEffect(tracks) {
            val loaded = tracks
            // Rebuild in place — assign the finished catalog rather than clearing it first. Nulling
            // it swapped the whole songs list for a loading box mid-rescan, which disposed the
            // list's scroll state, so deleting a track teleported the list back to the top. The
            // finished catalog is swapped in atomically instead, and stable row keys keep every
            // surviving row exactly where it was. The sliver of time where a tap could land on a
            // just-deleted row resolves to a harmless no-op — a far better trade than the jump.
            if (loaded != null) {
                val derived = withContext(Dispatchers.Default) {
                    val builtCatalog = LibraryCatalog.build(loaded)
                    val sections = albumRailSections(builtCatalog.albums)
                    val orderedAlbums = sections.flatMap { it.albums }
                    LibraryBrowseDerivation(
                        catalog = builtCatalog,
                        albumSections = sections,
                        alphabeticAlbumTracks = orderedAlbums.flatMap { it.tracks },
                    )
                }
                catalog = derived.catalog
                albumSections = derived.albumSections
                alphabeticAlbumTracks = derived.alphabeticAlbumTracks
            } else {
                catalog = null
                albumSections = emptyList()
                alphabeticAlbumTracks = emptyList()
            }
        }
        val tracksById = remember(catalog) { catalog?.songs?.associateBy { it.id }.orEmpty() }
        val selectedTracks = remember(
            catalog, alphabeticAlbumTracks, songSort, songSortDirection, selectedTrackIds,
            selectedCollection, selectedTab,
        ) {
            // Selection is empty during ordinary browsing. Avoid duplicating the Songs list's
            // whole-library O(n log n) sort on every direction change just to produce emptyList().
            if (selectedTrackIds.isEmpty()) return@remember emptyList()
            // On a group tab the selection was made album-by-album (folder-by-folder, …), so it
            // plays in group order — a selected album keeps its own track order instead of being
            // reshuffled into the Songs sort.
            val groupOrderedSource = selectedCollection
                ?.takeIf { it.allowsTrackSelection }
                ?.tracks
                ?: when (selectedTab) {
                    ALBUMS_TAB -> alphabeticAlbumTracks
                    ARTISTS_TAB -> catalog?.artists?.flatMap { it.tracks }
                    GENRES_TAB -> catalog?.genres?.flatMap { it.tracks }
                    FOLDERS_TAB -> catalog?.folders?.flatMap { it.tracks }
                    else -> null
                }
            groupOrderedSource?.filter { it.id in selectedTrackIds }
                ?: SongSorting.sort(
                    catalog?.songs.orEmpty().filter { it.id in selectedTrackIds },
                    songSort,
                    songSortDirection,
                )
        }
        LaunchedEffect(catalog) {
            updateTrackSelection(selectedTrackIds.intersect(tracksById.keys))
        }

        suspend fun refreshPlaylistMemberships() {
            publishPlaylists(AppGraph.playlists.all())
        }

        suspend fun refreshPlaylistStats() {
            val stats = AppGraph.history.stats()
            playCounts = stats.mapValues { it.value.plays }
            lastPlayedAt = stats.mapValues { it.value.lastPlayedAtMs }
        }

        suspend fun refreshPlaylistStatsBestEffort() {
            try {
                refreshPlaylistStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Membership was already durably changed and reflected locally. Statistics are a
                // derived enhancement, so preserve the last good values and retry on tab return.
                println("Playlists: could not refresh listening statistics: $failure")
            }
        }

        suspend fun refreshPlaylistMembershipsBestEffort() {
            try {
                refreshPlaylistMemberships()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                println("Playlists: could not refresh memberships: $failure")
            }
        }

        suspend fun refreshPlaylistsWithFeedback() {
            var failed = false
            try {
                refreshPlaylistMemberships()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failed = true
            }
            try {
                refreshPlaylistStats()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failed = true
            }
            if (failed) {
                snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
            }
        }

        suspend fun runPlaylistMutation(
            showFailureSnackbar: Boolean = true,
            change: suspend () -> Unit,
        ): Boolean {
            return try {
                change()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (showFailureSnackbar) {
                    snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                }
                false
            }
        }

        LaunchedEffect(Unit) { refreshPlaylistsWithFeedback() }
        // Auto playlists are derived from listening, so refresh them whenever
        // the user comes back to the tab rather than only at startup.
        LaunchedEffect(selectedTab) {
            if (selectedTab == PLAYLISTS_TAB) {
                autoPlaylistClockMs = epochMillis()
                refreshPlaylistsWithFeedback()
            }
        }
        // A listen can complete while this tab stays visible. Republish the derived auto lists
        // immediately instead of requiring a tab round-trip before Never Played/Rediscover move.
        LaunchedEffect(historyRevision) {
            if (selectedTab == PLAYLISTS_TAB) refreshPlaylistStatsBestEffort()
        }

        // Every SMART planner (in-app and the playback chooser) reads the marked playlists from
        // this flow; kept in lockstep with the playlists state.
        LaunchedEffect(playlistsLoaded, playlists) {
            // The first successful read reconstructs the policy that produced a persisted SMART
            // future. Treat it as launch state, not as a user edit: pruning the saved lookahead at
            // this point would defeat exact resume and could load the lazy model for no reason.
            if (!playlistsLoaded) return@LaunchedEffect
            val groups = playlists.smartCompanionMemberships()
            val changed = groups != AppGraph.smartCompanionGroups.value
            val shouldInvalidateFuture = shouldInvalidateSmartFuture(
                policyInitialized = companionGroupsInitialized,
                previous = AppGraph.smartCompanionGroups.value,
                updated = groups,
            )
            companionGroupsInitialized = true
            if (changed) {
                AppGraph.smartCompanionGroups.value = groups
            }
            if (shouldInvalidateFuture) {
                // Controllers keep a bounded generated lookahead. A policy or membership change
                // must prune that stale future now; clearing only the chooser's unserved plan
                // would postpone the user's explicit choice for roughly twenty tracks.
                playback.invalidateSmartFuture()
            }
        }
        var autoPlaylists by remember { mutableStateOf<List<AutoPlaylist>>(emptyList()) }
        LaunchedEffect(catalog, playCounts, lastPlayedAt, favoriteIds, autoPlaylistClockMs) {
            val songs = catalog?.songs
            autoPlaylists = if (songs == null) {
                emptyList()
            } else {
                withContext(Dispatchers.Default) {
                    AutoPlaylists.build(
                        songs,
                        playCounts,
                        lastPlayedAt,
                        favoriteIds,
                        autoPlaylistClockMs,
                    )
                }
            }
        }

        fun tracksOf(playlist: Playlist): List<TrackDescriptor> =
            playlist.trackIds.mapNotNull { tracksById[TrackId(it)] }

        // Playlists as an open format: export goes through the platform's document picker with
        // real file paths where the platform has them; import matches entries back against the
        // library by what survives a device change (filenames and metadata, not paths).
        val m3uExchange = rememberLocalBackupFileExchange(
            exportMimeType = M3U_MIME_TYPE,
            importMimeTypes = listOf(
                M3U_MIME_TYPE,
                "audio/mpegurl",
                "application/x-mpegurl",
                "application/vnd.apple.mpegurl",
                "text/plain",
                "application/octet-stream",
            ),
            onExportResult = { result ->
                if (result is LocalBackupFileResult.Failure) {
                    scope.launch {
                        snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                    }
                }
            },
            onImportResult = { result ->
                when (result) {
                    is LocalBackupFileResult.Success -> scope.launch {
                        val text = result.value
                        val entries = parseM3u(text)
                        val matched = matchM3uEntries(entries, catalog?.songs.orEmpty())
                            .filterNotNull()
                            .distinctBy { it.id }
                        if (entries.isEmpty() || matched.isEmpty()) {
                            snackbar.showSnackbar(getString(Res.string.snack_m3u_import_failed))
                            return@launch
                        }
                        val name = parseM3uName(text)
                            ?: getString(Res.string.playlist_imported_name)
                        if (!runPlaylistMutation {
                                AppGraph.playlists.create(name, matched.map { it.id })
                            }
                        ) {
                            return@launch
                        }
                        refreshPlaylistMembershipsBestEffort()
                        snackbar.showSnackbar(
                            getString(
                                Res.string.snack_m3u_imported,
                                matched.size,
                                entries.size,
                            ),
                        )
                    }
                    is LocalBackupFileResult.Failure -> scope.launch {
                        snackbar.showSnackbar(getString(Res.string.snack_m3u_import_failed))
                    }
                    LocalBackupFileResult.Cancelled -> Unit
                }
            },
        )
        fun exportPlaylistAsM3u(playlist: Playlist) {
            scope.launch {
                val resolved = tracksOf(playlist)
                val paths = runCatching { library.filePaths(resolved.map { it.id }) }
                    .getOrDefault(emptyMap())
                val safeName = playlist.name
                    .trim()
                    .replace('/', '-')
                    .replace('\\', '-')
                    .take(120)
                    .ifEmpty { "playlist" }
                m3uExchange.export(encodeM3u(playlist.name, resolved, paths), "$safeName.m3u8")
            }
        }

        // Rescan after a delete so the removed track leaves every list at once.
        val deleteTrack = rememberTrackDeleter {
            scope.launch {
                // A successful deletion proves only that target is gone, not that every media
                // source was readable during the follow-up scan (iOS app-owned files remain
                // editable while Music-library access is denied). Let the refreshed permission
                // state decide whether absence is authoritative; the explicit known-track set
                // below still clears playback after deleting the final real track.
                scanLibrary()
                playback.retainQueue(
                    library.allKnownTracks().mapTo(mutableSetOf()) { track -> track.id },
                )
                snackbar.showSnackbar(getString(Res.string.snack_track_deleted))
            }
        }

        fun retryAutomaticIndexing() {
            val loaded = tracks ?: return
            scope.launch {
                val notificationTitle = getString(Res.string.indexing_notification_title)
                AppGraph.ensureAutomaticIndexing(
                    tracks = loaded,
                    librarySnapshotAuthoritative =
                        libraryIndexingRequest?.librarySnapshotAuthoritative ?: false,
                    notificationTitle = notificationTitle,
                    force = true,
                ) { done, total, etaMinutes ->
                    if (etaMinutes == null) {
                        getString(Res.string.indexing_notification_progress, done, total)
                    } else {
                        getString(
                            Res.string.indexing_notification_progress_eta,
                            done,
                            total,
                            etaMinutes,
                        )
                    }
                }
            }
        }

        fun showAlbumOf(track: TrackDescriptor) {
            val album = catalog?.albums
                ?.firstOrNull { group -> group.tracks.any { it.id == track.id } } ?: return
            openCollection(
                build = { album.toSelection() },
                afterOpen = {
                // Going somewhere must also leave where you were: the collection renders in the
                // browse branch, so a full player or search screen left open would keep covering
                // the destination — the tap would look like it did nothing.
                showNowPlaying = false
                showSearch = false
                },
            )
        }

        fun showArtistOf(track: TrackDescriptor) {
            val artist = catalog?.artists?.firstOrNull { it.name == track.artist } ?: return
            openCollection(
                build = { artist.toSelection() },
                afterOpen = {
                // Same as showAlbumOf: navigation closes the surfaces above the destination.
                showNowPlaying = false
                showSearch = false
                },
            )
        }

        fun invalidateSmartRecommendationCaches() {
            // The exclusion store publishes only after its durable write succeeds. Clearing these
            // keys at that point removes stale cards immediately, while the state-keyed effect
            // above rebuilds the page from the latest rules even when a change came from elsewhere.
            forYou = ForYouPage()
            builtWorlds = null
            builtForYouLibraryIds = null
            builtForYouPlaylistIdentity = null
            builtForYouExclusions = null
            forYouRefreshing = selectedTab == FOR_YOU_TAB
        }

        suspend fun applySmartExclusion(change: suspend () -> Unit): Boolean {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                snackbar.showSnackbar(getString(Res.string.snack_smart_exclusion_failed))
                return false
            }

            invalidateSmartRecommendationCaches()
            // Recomposition will run the state-keyed synchronization effect as well. Updating the
            // controller here closes the small window in which an active SMART queue could ask for
            // another candidate using the old eligible library.
            try {
                val state = smartExclusions.state.value
                playback.setSmartLibrary(tracks.orEmpty().filterNot { state.excludes(it) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The observable state remains authoritative; its LaunchedEffect retries this sync.
            }
            return true
        }

        fun changeSmartExclusion(
            successMessage: StringResource,
            change: suspend () -> Unit,
            undo: suspend () -> Unit,
        ) {
            scope.launch {
                if (!applySmartExclusion(change)) return@launch
                val result = snackbar.showSnackbar(
                    message = getString(successMessage),
                    actionLabel = getString(Res.string.action_undo),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    applySmartExclusion(undo)
                }
            }
        }

        val settingsVisibilityState = remember { MutableTransitionState(false) }
        settingsVisibilityState.targetState = showSettings
        val settingsOverlayActive = settingsVisibilityState.currentState ||
            settingsVisibilityState.targetState

        // Opaque floor under the whole shell: during the morph the animating
        // content is smaller than the window, and without this the platform
        // window background shows through as a flash.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
        SharedTransitionLayout {
            val sharedScope = this
            AnimatedContent(
                targetState = showNowPlaying,
                transitionSpec = {
                    val duration = if (reduceMotion) 0 else Motion.EMPHASIZED_MS
                    fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
                },
                modifier = Modifier.fillMaxSize(),
                label = "player-morph",
            ) { expanded ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .inactiveDuringTransition(
                            expanded != showNowPlaying || settingsOverlayActive,
                        ),
                ) {
                if (expanded) {
                    val queueSource by AppGraph.queueSource.collectAsState()
                    NowPlayingScreen(
                        playback = playback,
                        accent = accent,
                        queueSourceLabel = queueSource?.let { source ->
                            source.name ?: source.kind.fallbackLabelRes()?.let { stringResource(it) }
                        },
                        sharedScope = sharedScope,
                        animatedScope = this@AnimatedContent,
                        sleepTimerState = sleepTimerState,
                        onStartSleepTimer = sleepTimer::startCountdown,
                        onSleepAtEndOfTrack = sleepTimer::startAtEndOfTrack,
                        onCancelSleepTimer = sleepTimer::cancel,
                        onTrackMenu = { track -> trackMenuRequest = TrackMenuRequest(track) },
                        onAddQueueToPlaylist = {
                            playback.state.value.queue
                                .takeIf { it.isNotEmpty() }
                                ?.let { addToPlaylistSelection = it }
                        },
                        isFavorite = currentTrack?.id?.let { it in favoriteIds } == true,
                        onToggleFavorite = { currentTrack?.id?.let(::toggleFavorite) },
                        onClose = { showNowPlaying = false },
                    )
                } else {
                    browseStateHolder.SaveableStateProvider(BROWSE_SHELL_STATE_KEY) {
                        val animatedScope = this@AnimatedContent
                        // Everything that is NOT the full player shares one stack, and the pill is the
                        // last thing in it. Search and collection detail are full-screen surfaces laid
                        // OVER the browse shell rather than pages swapped into it, so anything drawn
                        // after them stays visible — which is how the pill reaches screens it used to
                        // disappear behind.
                        Box(modifier = Modifier.fillMaxSize()) {
                    val searchVisibilityState = remember {
                        MutableTransitionState(showSearch)
                    }
                    searchVisibilityState.targetState = showSearch
                    val searchOverlayActive = searchVisibilityState.currentState ||
                        searchVisibilityState.targetState
                    val collectionTransition = updateTransition(
                        targetState = selectedCollection,
                        label = "collection-detail-state",
                    )
                    val collectionOverlayActive = collectionTransition.currentState != null ||
                        collectionTransition.targetState != null
                    Scaffold(
                        // Collection detail and Search are full-screen layers drawn after this
                        // browse shell. Hide the covered tree from accessibility so TalkBack never
                        // reaches duplicate/underlying tabs and selection controls.
                        modifier = if (collectionOverlayActive || searchOverlayActive) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                        topBar = {
                            Column {
                                AnimatedContent(
                                    targetState = SelectionBarPresentation(
                                        selecting = selectionMode && selectedCollection == null,
                                        count = selectedTrackIds.size,
                                        allSelected =
                                            selectedTrackIds.size == catalog?.songs?.size,
                                    ),
                                    contentKey = { it.selecting },
                                    transitionSpec = { motionFadeThrough(reduceMotion) },
                                    label = "root-contextual-app-bar",
                                ) { bar ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .inactiveDuringTransition(
                                            bar.selecting != (
                                                selectionMode && selectedCollection == null
                                            ),
                                        ),
                                ) {
                                if (bar.selecting) {
                                    SelectionTopAppBar(
                                        count = bar.count,
                                        allSelected = bar.allSelected,
                                        onClose = { updateTrackSelection(emptySet()) },
                                        onToggleAll = {
                                            updateTrackSelection(if (
                                                selectedTrackIds.size == catalog?.songs?.size
                                            ) {
                                                emptySet()
                                            } else {
                                                catalog?.songs.orEmpty()
                                                    .mapTo(LinkedHashSet()) { it.id }
                                            })
                                        },
                                    )
                                } else TopAppBar(
                                    title = { Text("LatentJam") },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                    actions = {
                                        // Creating belongs to the tab that shows
                                        // what you'd create, so it appears there — growing in
                                        // rather than teleporting when the tab settles.
                                        AnimatedVisibility(
                                            visible = selectedTab == PLAYLISTS_TAB,
                                            enter = if (reduceMotion) {
                                                fadeIn(tween(120))
                                            } else {
                                                fadeIn(tween(Motion.APPEAR_MS)) +
                                                    expandHorizontally(tween(Motion.APPEAR_MS))
                                            },
                                            exit = if (reduceMotion) {
                                                fadeOut(tween(90))
                                            } else {
                                                fadeOut(tween(Motion.REPLACE_MS)) +
                                                    shrinkHorizontally(tween(Motion.REPLACE_MS))
                                            },
                                        ) {
                                            Row(
                                                modifier = Modifier.inactiveDuringTransition(
                                                    selectedTab != PLAYLISTS_TAB,
                                                ),
                                            ) {
                                            IconButton(onClick = { m3uExchange.import() }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.FileOpen,
                                                    contentDescription =
                                                        stringResource(Res.string.action_import_m3u),
                                                )
                                            }
                                            IconButton(onClick = { showCreatePlaylist = true }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Add,
                                                    contentDescription =
                                                        stringResource(Res.string.playlist_new),
                                                )
                                            }
                                            }
                                        }
                                        IconButton(onClick = { showSearch = true }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription =
                                                    stringResource(Res.string.cd_search_library),
                                            )
                                        }
                                        // Shuffle lives with the transport in the
                                        // player, not up here — the header is for
                                        // library-level actions.
                                        // The settings shortcut occupies the same shared slot as
                                        // the player's overflow, so the header does not jump during
                                        // the mini-player morph.
                                        SharedSettingsButton(
                                            sharedScope = sharedScope,
                                            animatedScope = animatedScope,
                                            shareElement = !searchOverlayActive &&
                                                !collectionOverlayActive,
                                            onClick = { showSettings = true },
                                        )
                                    },
                                )
                                }
                                }
                                BrowseCarousel(
                                    pagerState = pagerState,
                                    enabled = !selectionMode,
                                    onSelect = ::navigateToRootTab,
                                )
                            }
                        },
                        // The root floor already paints the window. Leaving Scaffold transparent
                        // avoids a second full-screen fill underneath every page.
                        containerColor = Color.Transparent,
                        // A transparent container has no inferable Material content colour.
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) { padding ->
                        // Snapshot the asynchronously-built catalog once for this
                        // composition. Delegated state cannot be smart-cast after a
                        // null check because it may change between reads.
                        val visibleCatalog = catalog
                        // One full-bleed surface: it runs to the bottom edge so the
                        // mini-player floats ON the content rather than sitting on a
                        // separate band of background.
                        // A background shape is enough here. Material Surface also clips every
                        // descendant to this screen-sized rounded path, which is expensive on
                        // high-resolution devices while a list is moving.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                                ),
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val presentation = when {
                                    visibleCatalog == null -> LibraryPresentation.LOADING
                                    visibleCatalog.songs.isEmpty() -> LibraryPresentation.EMPTY
                                    else -> LibraryPresentation.CONTENT
                                }
                                AnimatedContent(
                                    // Keep the catalog that belongs to each transition frame. If
                                    // the live value becomes null, the outgoing CONTENT page still
                                    // has its rows available while it fades away.
                                    targetState = presentation to visibleCatalog,
                                    contentKey = { it.first },
                                    transitionSpec = { motionFadeThrough(reduceMotion) },
                                    modifier = Modifier.fillMaxSize(),
                                    label = "library-presentation",
                                ) { (shownPresentation, shownCatalog) ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .inactiveDuringTransition(shownPresentation != presentation),
                                ) {
                                when (shownPresentation) {
                                    LibraryPresentation.LOADING -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }

                                    LibraryPresentation.EMPTY -> Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(stringResource(Res.string.library_empty))
                                        if (audioImportAvailable) {
                                            Text(
                                                text = stringResource(Res.string.library_import_hint),
                                                modifier = Modifier.padding(
                                                    start = 32.dp,
                                                    top = 8.dp,
                                                    end = 32.dp,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Button(
                                                onClick = importAudio,
                                                modifier = Modifier.padding(top = 20.dp),
                                            ) {
                                                Text(stringResource(Res.string.library_import_action))
                                            }
                                        }
                                    }

                                    // Pages follow the finger. The carousel strip above reads the
                                    // same pager position, so label and content move together
                                    // rather than one chasing the other after the fact.
                                    LibraryPresentation.CONTENT -> shownCatalog?.let { visibleCatalog ->
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                        userScrollEnabled = !selectionMode,
                                        // Compose the destination as the gesture reaches it. Keeping
                                        // both neighbours alive eagerly built album grids and loaded
                                        // their covers while the user was merely scrolling Tracks.
                                        beyondViewportPageCount = 0,
                                        key = { page -> page },
                                    ) { tab ->
                                        when (tab) {
                                        FOR_YOU_TAB -> AnimatedContent(
                                            targetState = forYou,
                                            contentKey = { if (it.isEmpty) "empty" else "content" },
                                            transitionSpec = { motionFadeThrough(reduceMotion) },
                                            modifier = Modifier.fillMaxSize(),
                                            label = "for-you-presentation",
                                        ) { shownForYou ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .inactiveDuringTransition(
                                                    shownForYou.isEmpty != forYou.isEmpty,
                                                ),
                                        ) {
                                        ForYouTab(
                                            page = shownForYou,
                                            contentPadding = listPadding,
                                            isRefreshing = forYouRefreshing,
                                            onRefresh = {
                                                if (!forYouRefreshing) {
                                                    forYouRefreshing = true
                                                    personalizationRevision += 1
                                                }
                                            },
                                            onPlay = { list, index ->
                                                AppGraph.queueSource.value =
                                                    QueueSource(QueueSourceKind.FOR_YOU)
                                                scope.launch { playback.play(list, index) }
                                            },
                                            onPlayHero = { hero ->
                                                scope.launch {
                                                    // Personal signal picked the seed; SMART decides
                                                    // what follows it.
                                                    val queue = engine.smartQueue(
                                                        hero.track,
                                                        smartEligibleTracks,
                                                        smartQueueLength,
                                                        smartHistoryFor(AppGraph.history, hero.track),
                                                        AppGraph.smartCompanionGroups.value,
                                                    )
                                                    val byId = visibleCatalog.songs.associateBy { it.id }
                                                    val tail = queue.mapNotNull(byId::get)
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.FOR_YOU)
                                                    playback.play(listOf(hero.track) + tail, 0)
                                                    hero.resumeAtMs?.let { playback.seekTo(it) }
                                                }
                                            },
                                            onTrackMenu = { trackMenuRequest = TrackMenuRequest(it) },
                                            onOpenWorld = { worldTarget = it },
                                        )
                                        }
                                        }

                                        MAP_TAB -> AnimatedContent(
                                            targetState = mapState,
                                            contentKey = { state: MapPageState ->
                                                state.presentationKey()
                                            },
                                            transitionSpec = { motionFadeThrough(reduceMotion) },
                                            modifier = Modifier.fillMaxSize(),
                                            label = "map-presentation",
                                        ) { shownMapState ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .inactiveDuringTransition(
                                                    shownMapState.presentationKey() !=
                                                        mapState.presentationKey(),
                                                ),
                                        ) {
                                        MapTab(
                                            state = shownMapState,
                                            contentPadding = listPadding,
                                            focusTrackId = mapFocusTrackId,
                                            onPlayRegion = { region ->
                                                mapRegions.getOrNull(region)?.let { world ->
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.MAP)
                                                    scope.launch { playback.play(world.tracks, 0) }
                                                }
                                            },
                                            onSmartFromRegion = { region ->
                                                mapRegions.getOrNull(region)?.let { world ->
                                                    scope.launch {
                                                        val seed = world.representative
                                                        val queue = engine.smartQueue(
                                                            seed,
                                                            smartEligibleTracks,
                                                            smartQueueLength,
                                                            smartHistoryFor(AppGraph.history, seed),
                                                            AppGraph.smartCompanionGroups.value,
                                                        )
                                                        val tail = queue.mapNotNull(tracksById::get)
                                                        AppGraph.queueSource.value =
                                                            QueueSource(QueueSourceKind.MAP)
                                                        playback.play(listOf(seed) + tail, 0)
                                                    }
                                                }
                                            },
                                            onOpenTrack = { id ->
                                                tracksById[id]?.let {
                                                    trackMenuRequest = TrackMenuRequest(it)
                                                }
                                            },
                                        )
                                        }
                                        }

                                        PLAYLISTS_TAB -> PlaylistsTabContent(
                                            autoPlaylists = autoPlaylists,
                                            playlists = playlists,
                                            tracksOf = ::tracksOf,
                                            contentPadding = listPadding,
                                            // Building a selection resolves a track count, and a
                                            // count is a plural — so it happens in a coroutine
                                            // rather than in the click handler itself.
                                            onOpenAuto = { auto ->
                                                scope.launch {
                                                    updateTrackSelection(emptySet())
                                                    updateSelectedCollection(CollectionSelection(
                                                        title = getString(auto.kind.titleRes()),
                                                        subtitle = trackCountLabel(auto.tracks.size),
                                                        artworkUri = auto.tracks
                                                            .firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = auto.tracks,
                                                        allowsTrackSelection = true,
                                                        routeId = "auto:${auto.kind.name}",
                                                    ))
                                                }
                                            },
                                            onOpenPlaylist = { playlist ->
                                                scope.launch {
                                                    updateTrackSelection(emptySet())
                                                    val resolved = tracksOf(playlist)
                                                    updateSelectedCollection(CollectionSelection(
                                                        title = playlist.name,
                                                        // Resolved count: stored ids may include
                                                        // tracks deleted from the device, and the
                                                        // subtitle must match the list below it.
                                                        subtitle = trackCountLabel(resolved.size),
                                                        artworkUri = resolved
                                                            .firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = resolved,
                                                        allowsTrackSelection = true,
                                                        playlistId = playlist.id,
                                                    ))
                                                }
                                            },
                                            onExport = ::exportPlaylistAsM3u,
                                            onToggleSmart = { playlist ->
                                                scope.launch {
                                                    if (!runPlaylistMutation {
                                                            AppGraph.playlists
                                                                .toggleIncludeInSmart(playlist.id)
                                                        }
                                                    ) return@launch
                                                    refreshPlaylistMembershipsBestEffort()
                                                }
                                            },
                                            onMove = { from, to ->
                                                val moving = playlists.getOrNull(from)
                                                if (moving != null) {
                                                    // Optimistic: the row lands where it was
                                                    // dropped; a failed write restores truth.
                                                    playlists = playlists.toMutableList()
                                                        .apply { add(to, removeAt(from)) }
                                                    scope.launch {
                                                        if (!runPlaylistMutation {
                                                                AppGraph.playlists.move(
                                                                    moving.id,
                                                                    to,
                                                                )
                                                            }
                                                        ) {
                                                            refreshPlaylistMembershipsBestEffort()
                                                        }
                                                    }
                                                }
                                            },
                                            onRename = { renameTarget = it },
                                            onDelete = { playlist ->
                                                scope.launch {
                                                    if (!runPlaylistMutation {
                                                            AppGraph.playlists.delete(playlist.id)
                                                        }
                                                    ) return@launch
                                                    playlists = playlists.filterNot {
                                                        it.id == playlist.id
                                                    }
                                                    snackbar.showSnackbar(
                                                        getString(
                                                            Res.string.snack_playlist_deleted,
                                                            playlist.name,
                                                        ),
                                                    )
                                                }
                                            },
                                        )

                                        TRACKS_TAB -> Column {
                                            SongsHeader(
                                                sort = songSort,
                                                direction = songSortDirection,
                                                enabled = !selectionMode,
                                                onSortChange = { selectedSort ->
                                                    val selectedDirection = directionAfterSongSortSelection(
                                                        currentSort = songSort,
                                                        currentDirection = songSortDirection,
                                                        selectedSort = selectedSort,
                                                    )
                                                    savedSongSort = selectedSort.name
                                                    savedSongSortDirection = selectedDirection.name
                                                },
                                                onShuffleAll = {
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.TRACKS)
                                                    scope.launch {
                                                        playback.play(visibleCatalog.songs.shuffled(), 0)
                                                    }
                                                },
                                                onPlayAll = {
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.TRACKS)
                                                    scope.launch {
                                                        playback.play(
                                                            SongSorting.sort(
                                                                visibleCatalog.songs,
                                                                songSort,
                                                                songSortDirection,
                                                            ),
                                                            0,
                                                        )
                                                    }
                                                },
                                            )
                                            SectionedSongsList(
                                                songs = visibleCatalog.songs,
                                                sort = songSort,
                                                sortDirection = songSortDirection,
                                                currentTrackId = currentTrack?.id,
                                                currentTrackPlaying = currentTrackPlaying,
                                                contentPadding = listPadding,
                                                selectedTrackIds = selectedTrackIds,
                                                onToggleSelection = { track ->
                                                    updateTrackSelection(if (track.id in selectedTrackIds) {
                                                        selectedTrackIds - track.id
                                                    } else {
                                                        selectedTrackIds + track.id
                                                    })
                                                },
                                                onStartSelection = { track ->
                                                    updateTrackSelection(setOf(track.id))
                                                },
                                                onPlay = { queue, index ->
                                                    AppGraph.queueSource.value =
                                                        QueueSource(QueueSourceKind.TRACKS)
                                                    scope.launch { playback.play(queue, index) }
                                                },
                                                onTrackMenu = {
                                                    trackMenuRequest = TrackMenuRequest(it)
                                                },
                                            )
                                        }

                                        ALBUMS_TAB -> {
                                            val albumRail = remember(albumSections) {
                                                RailIndex(
                                                    buckets = albumSections.map { it.bucket },
                                                    startIndexes = albumSections.map {
                                                        it.emitStartIndex
                                                    },
                                                )
                                            }
                                            val albumArtworkKeys = remember(albumSections) {
                                                buildList<ArtworkLoadKey?> {
                                                    albumSections.forEach { section ->
                                                        add(null) // Full-span section header.
                                                        section.albums.forEach { album ->
                                                            add(album.artworkUri?.let { uri ->
                                                                ArtworkLoadKey(
                                                                    itemId = "album:${album.key}",
                                                                    uri = uri,
                                                                )
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                            GridListWithRail(
                                                rail = albumRail,
                                                catalogKey = albumSections,
                                                artworkKeys = albumArtworkKeys,
                                                contentPadding = PaddingValues(
                                                    start = 8.dp,
                                                    end = 8.dp,
                                                    top = 8.dp,
                                                    bottom = listPadding.calculateBottomPadding(),
                                                ),
                                            ) { railPadding, gridState, artworkReporter, isPreview ->
                                                LazyVerticalGrid(
                                                    columns = GridCells.Fixed(2),
                                                    state = gridState,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentPadding = railPadding,
                                                ) {
                                                    albumSections.forEach { section ->
                                                        item(
                                                            key = "album-header-${section.bucket}",
                                                            span = { GridItemSpan(maxLineSpan) },
                                                            contentType = "header",
                                                        ) {
                                                            AlbumSectionHeader(section.bucket)
                                                        }
                                                        items(
                                                            section.albums,
                                                            key = { it.key },
                                                            contentType = { "album" },
                                                        ) { album ->
                                                            val expectedKey = album.artworkUri
                                                                ?.let { uri ->
                                                                    ArtworkLoadKey(
                                                                        itemId = "album:${album.key}",
                                                                        uri = uri,
                                                                    )
                                                                }
                                                            AlbumCard(
                                                                album = album,
                                                                railPreview = isPreview,
                                                                onArtworkLoadStateChanged =
                                                                    if (isPreview) {
                                                                        null
                                                                    } else artworkReporter?.let { report ->
                                                                        expectedKey?.let { key ->
                                                                            { requestUri, state ->
                                                                                report(
                                                                                    key.copy(
                                                                                        uri = requestUri,
                                                                                    ),
                                                                                    state,
                                                                                )
                                                                            }
                                                                        }
                                                                    },
                                                                onLongClick = {
                                                                    updateTrackSelection(
                                                                        selectedTrackIds
                                                                            .toggleTracks(
                                                                                album.tracks,
                                                                            ),
                                                                    )
                                                                },
                                                                selectionState =
                                                                    if (selectionMode) {
                                                                        selectedTrackIds
                                                                            .selectsAllOf(
                                                                                album.tracks,
                                                                            )
                                                                    } else {
                                                                        null
                                                                    },
                                                            ) {
                                                                if (selectionMode) {
                                                                    updateTrackSelection(
                                                                        selectedTrackIds
                                                                            .toggleTracks(
                                                                                album.tracks,
                                                                            ),
                                                                    )
                                                                } else {
                                                                    openCollection(
                                                                        build = { album.toSelection() },
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        ARTISTS_TAB -> GroupListWithRail(
                                            names = visibleCatalog.artists.map { it.name },
                                            artworkKeys = remember(visibleCatalog.artists) {
                                                visibleCatalog.artists.map { artist ->
                                                    artist.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri }
                                                        ?.let { uri ->
                                                            ArtworkLoadKey(
                                                                itemId = stableGroupKey(
                                                                    "artist",
                                                                    artist.name,
                                                                ),
                                                                uri = uri,
                                                            )
                                                        }
                                                }
                                            },
                                            contentPadding = listPadding,
                                        ) { railPadding, listState, artworkReporter ->
                                            LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = railPadding,
                                        ) {
                                            items(
                                                visibleCatalog.artists,
                                                key = { artist -> stableGroupKey("artist", artist.name) },
                                            ) { artist ->
                                                val artworkKey = artist.tracks
                                                    .firstNotNullOfOrNull { it.artworkUri }
                                                    ?.let { uri ->
                                                        ArtworkLoadKey(
                                                            itemId = stableGroupKey("artist", artist.name),
                                                            uri = uri,
                                                        )
                                                    }
                                                GroupRow(
                                                    title = artist.name
                                                        ?: stringResource(Res.string.track_unknown_artist),
                                                    subtitle = artistSubtitle(
                                                        tracks = artist.tracks.size,
                                                        albums = artist.albumCount,
                                                    ),
                                                    artworkUri = artist.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                    onArtworkLoadStateChanged =
                                                        artworkReporter?.let { report ->
                                                            artworkKey?.let { expectedKey ->
                                                                { requestUri, state ->
                                                                    report(
                                                                        expectedKey.copy(uri = requestUri),
                                                                        state,
                                                                    )
                                                                }
                                                            }
                                                        },
                                                    onLongClick = {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(artist.tracks),
                                                        )
                                                    },
                                                    selectionState = if (selectionMode) {
                                                        selectedTrackIds.selectsAllOf(artist.tracks)
                                                    } else {
                                                        null
                                                    },
                                                ) {
                                                    if (selectionMode) {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(artist.tracks),
                                                        )
                                                    } else {
                                                        openCollection(
                                                            build = { artist.toSelection() },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        }

                                        GENRES_TAB -> GroupListWithRail(
                                            names = visibleCatalog.genres.map { it.name },
                                            artworkKeys = remember(visibleCatalog.genres) {
                                                visibleCatalog.genres.map { genre ->
                                                    genre.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri }
                                                        ?.let { uri ->
                                                            ArtworkLoadKey(
                                                                itemId = stableGroupKey(
                                                                    "genre",
                                                                    genre.name,
                                                                ),
                                                                uri = uri,
                                                            )
                                                        }
                                                }
                                            },
                                            contentPadding = listPadding,
                                        ) { railPadding, listState, artworkReporter ->
                                            LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = railPadding,
                                        ) {
                                            items(
                                                visibleCatalog.genres,
                                                key = { genre -> stableGroupKey("genre", genre.name) },
                                            ) { genre ->
                                                val artworkKey = genre.tracks
                                                    .firstNotNullOfOrNull { it.artworkUri }
                                                    ?.let { uri ->
                                                        ArtworkLoadKey(
                                                            itemId = stableGroupKey("genre", genre.name),
                                                            uri = uri,
                                                        )
                                                    }
                                                GroupRow(
                                                    title = genre.name
                                                        ?: stringResource(Res.string.track_unknown_genre),
                                                    subtitle = pluralStringResource(
                                                        Res.plurals.count_tracks,
                                                        genre.tracks.size,
                                                        genre.tracks.size,
                                                    ),
                                                    artworkUri = genre.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                    onArtworkLoadStateChanged =
                                                        artworkReporter?.let { report ->
                                                            artworkKey?.let { expectedKey ->
                                                                { requestUri, state ->
                                                                    report(
                                                                        expectedKey.copy(uri = requestUri),
                                                                        state,
                                                                    )
                                                                }
                                                            }
                                                        },
                                                    onLongClick = {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(genre.tracks),
                                                        )
                                                    },
                                                    selectionState = if (selectionMode) {
                                                        selectedTrackIds.selectsAllOf(genre.tracks)
                                                    } else {
                                                        null
                                                    },
                                                ) {
                                                    if (selectionMode) {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(genre.tracks),
                                                        )
                                                    } else {
                                                        openCollection(
                                                            build = { genre.toSelection() },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        }

                                        FOLDERS_TAB -> GroupListWithRail(
                                            names = visibleCatalog.folders.map { it.name },
                                            contentPadding = listPadding,
                                        ) { railPadding, listState, _ ->
                                            LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = railPadding,
                                        ) {
                                            items(visibleCatalog.folders, key = { it.path }) { folder ->
                                                FolderRow(
                                                    folder = folder,
                                                    subtitle = pluralStringResource(
                                                        Res.plurals.count_tracks,
                                                        folder.tracks.size,
                                                        folder.tracks.size,
                                                    ),
                                                    onLongClick = {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(folder.tracks),
                                                        )
                                                    },
                                                    selectionState = if (selectionMode) {
                                                        selectedTrackIds.selectsAllOf(folder.tracks)
                                                    } else {
                                                        null
                                                    },
                                                ) {
                                                    if (selectionMode) {
                                                        updateTrackSelection(
                                                            selectedTrackIds.toggleTracks(folder.tracks),
                                                        )
                                                    } else {
                                                        openCollection(
                                                            build = { folder.toSelection() },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        }
                                        }
                                    }
                                    }
                                }
                                }
                                }
                            }
                        }
                    }

                    // Drilling into a collection slides forward; backing out releases the same
                    // way. AnimatedContent keeps the outgoing screen alive for its exit frames.
                    if (collectionOverlayActive) ModalPointerBlocker()
                    collectionTransition.AnimatedContent(
                        // Track removal, hide/undo, and subtitle reconciliation copy this value.
                        // Route identity stays fixed so those data updates never replay a page push.
                        contentKey = { it?.routeId },
                        transitionSpec = {
                            motionPageTransform(
                                forward = targetState != null,
                                reduceMotion = reduceMotion,
                                layoutDirection = layoutDirection,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) { animatedSelection ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .inactiveDuringTransition(
                                animatedSelection?.routeId != selectedCollection?.routeId,
                            ),
                    ) {
                    animatedSelection?.let { selection ->
                        CollectionDetailScreen(
                            selection = selection,
                            currentTrackId = currentTrack?.id,
                            currentTrackPlaying = currentTrackPlaying,
                            selectedTrackIds = selectedTrackIds,
                            onToggleSelection = { track ->
                                updateTrackSelection(if (track.id in selectedTrackIds) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                })
                            },
                            onStartSelection = { track ->
                                updateTrackSelection(setOf(track.id))
                            },
                            onClearSelection = { updateTrackSelection(emptySet()) },
                            onToggleAllSelection = {
                                val playlistIds = selection.tracks.mapTo(LinkedHashSet()) { it.id }
                                updateTrackSelection(if (playlistIds.all(selectedTrackIds::contains)) {
                                    emptySet()
                                } else {
                                    playlistIds
                                })
                            },
                            onPlayTrack = { index ->
                                AppGraph.queueSource.value =
                                    QueueSource(
                                        QueueSourceKind.COLLECTION,
                                        selection.title,
                                        selection.playlistId,
                                    )
                                scope.launch { playback.play(selection.tracks, index) }
                            },
                            onShuffle = {
                                AppGraph.queueSource.value =
                                    QueueSource(
                                        QueueSourceKind.COLLECTION,
                                        selection.title,
                                        selection.playlistId,
                                    )
                                scope.launch { playback.play(selection.tracks.shuffled(), 0) }
                            },
                            onTrackMenu = { track ->
                                trackMenuRequest = TrackMenuRequest(
                                    track = track,
                                    sourcePlaylistId = selection.playlistId,
                                    sourcePlaylistTitle = selection.title
                                        .takeIf { selection.playlistId != null },
                                )
                            },
                            onClose = {
                                updateTrackSelection(emptySet())
                                updateSelectedCollection(null)
                            },
                            bottomInset = if (selectionMode) {
                                SELECTION_ACTION_BAR_HEIGHT
                            } else {
                                floatingPlayerInset
                            },
                        )
                    }
                    }
                    }

                    // Search settles in from under the top bar rather than teleporting whole.
                    if (searchOverlayActive) ModalPointerBlocker()
                    AnimatedVisibility(
                        visibleState = searchVisibilityState,
                        enter = if (reduceMotion) {
                            fadeIn(tween(120))
                        } else {
                            fadeIn(tween(Motion.APPEAR_MS)) +
                                slideInVertically(tween(Motion.APPEAR_MS)) { -it / 10 }
                        },
                        exit = if (reduceMotion) {
                            fadeOut(tween(90))
                        } else {
                            fadeOut(tween(Motion.REPLACE_MS)) +
                                slideOutVertically(tween(Motion.REPLACE_MS)) { -it / 10 }
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .inactiveDuringTransition(!searchVisibilityState.targetState),
                        ) {
                        SearchScreen(
                            songs = catalog?.songs.orEmpty(),
                            currentTrackId = currentTrack?.id,
                            currentTrackPlaying = currentTrackPlaying,
                            selectedTrackIds = selectedTrackIds,
                            onToggleSelection = { track ->
                                updateTrackSelection(if (track.id in selectedTrackIds) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                })
                            },
                            onStartSelection = { track ->
                                updateTrackSelection(setOf(track.id))
                            },
                            onClearSelection = { updateTrackSelection(emptySet()) },
                            onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                            onTrackMenu = { trackMenuRequest = TrackMenuRequest(it) },
                            // A selection made among search results has no surface to live on once
                            // the results are gone.
                            onClose = {
                                updateTrackSelection(emptySet())
                                showSearch = false
                            },
                            bottomInset = floatingPlayerInset,
                        )
                        }
                    }

                    AnimatedVisibility(
                        visible = selectionMode,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(if (selectionMode) 1f else 0f),
                        enter = if (reduceMotion) {
                            fadeIn(tween(120))
                        } else {
                            fadeIn(tween(Motion.APPEAR_MS)) +
                                slideInVertically(tween(Motion.APPEAR_MS)) { it / 2 }
                        },
                        exit = if (reduceMotion) {
                            fadeOut(tween(90))
                        } else {
                            fadeOut(tween(Motion.REPLACE_MS)) +
                                slideOutVertically(tween(Motion.REPLACE_MS)) { it / 2 }
                        },
                    ) {
                        Box(
                            modifier = Modifier.inactiveDuringTransition(!selectionMode),
                        ) {
                        SelectionActionBar(
                            canAct = selectedTracks.isNotEmpty(),
                            canShare = selectedTracks.isNotEmpty() &&
                                selectedTracks.all { it.audioUri != null },
                            removeFromPlaylist = selectedCollection?.playlistId != null,
                            onPlay = {
                                val selection = selectedTracks
                                // The surface the selection was made on: a hand-picked set from
                                // search or a group tab has no single honest name.
                                AppGraph.queueSource.value = when {
                                    selectedCollection?.allowsTrackSelection == true ->
                                        QueueSource(
                                            QueueSourceKind.COLLECTION,
                                            selectedCollection?.title,
                                            selectedCollection?.playlistId,
                                        )
                                    showSearch || selectedTab in GROUP_TABS -> null
                                    else -> QueueSource(QueueSourceKind.TRACKS)
                                }
                                updateTrackSelection(emptySet())
                                scope.launch { playback.play(selection, 0) }
                            },
                            onAdd = {
                                addToPlaylistSelection = selectedTracks
                            },
                            onShare = {
                                shareTracks(selectedTracks)
                                updateTrackSelection(emptySet())
                            },
                            onRemove = {
                                val playlist = selectedCollection?.takeIf { it.playlistId != null }
                                if (playlist != null) {
                                    val selection = selectedTracks
                                    val removedIds = selection.mapTo(LinkedHashSet()) { it.id }
                                    val playlistId = checkNotNull(playlist.playlistId)
                                    val sourceCollectionRevision = collectionRevision
                                    // Freeze the command before the first suspension. A user can
                                    // otherwise change selection while the playlist store writes,
                                    // making persistence remove A while the visible list hides B.
                                    updateTrackSelection(emptySet())
                                    val commandSelectionRevision = selectionRevision
                                    scope.launch {
                                        var membershipChange: PlaylistTrackChange? = null
                                        val persisted = try {
                                            membershipChange = AppGraph.playlists.removeTracks(
                                                playlistId,
                                                selection.map(TrackDescriptor::id),
                                            )
                                            membershipChange != null
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Throwable) {
                                            false
                                        }
                                        if (!persisted) {
                                            if (
                                                collectionRevision == sourceCollectionRevision &&
                                                selectionRevision == commandSelectionRevision &&
                                                selectedCollection?.playlistId == playlistId
                                            ) {
                                                updateTrackSelection(removedIds)
                                            }
                                            snackbar.showSnackbar(
                                                getString(Res.string.settings_library_manage_failed),
                                            )
                                            return@launch
                                        }
                                        val currentPlaylist = selectedCollection
                                            ?.takeIf { it.playlistId == playlistId }
                                        if (currentPlaylist != null) {
                                            val remaining = currentPlaylist.tracks
                                                .filterNot { it.id in removedIds }
                                            updateSelectedCollection(currentPlaylist.copy(
                                                subtitle = trackCountLabel(remaining.size),
                                                tracks = remaining,
                                            ))
                                            // Preserve a newer selection made while the store was
                                            // writing, but never leave it pointing at a removed row.
                                            updateTrackSelection(selectedTrackIds - removedIds)
                                        }
                                        val change = checkNotNull(membershipChange)
                                        playlists = playlists.map { stored ->
                                            if (stored.id == playlistId) {
                                                stored.copy(
                                                    trackIds = change.after.map(TrackId::value),
                                                )
                                            } else {
                                                stored
                                            }
                                        }
                                        refreshPlaylistStatsBestEffort()
                                        val result = snackbar.showSnackbar(
                                            message = getString(
                                                Res.string.snack_playlist_track_removed,
                                                playlist.title,
                                            ),
                                            actionLabel = if (change.before != change.after) {
                                                getString(Res.string.action_undo)
                                            } else {
                                                null
                                            },
                                            withDismissAction = change.before != change.after,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            val restored = try {
                                                AppGraph.playlists.replaceTracksIfUnchanged(
                                                    playlistId,
                                                    expected = change.after,
                                                    replacement = change.before,
                                                )
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Throwable) {
                                                false
                                            }
                                            if (!restored) {
                                                snackbar.showSnackbar(
                                                    getString(
                                                        Res.string.settings_library_manage_failed,
                                                    ),
                                                )
                                                return@launch
                                            }
                                            playlists = playlists.map { stored ->
                                                if (stored.id == playlistId) {
                                                    stored.copy(
                                                        trackIds = change.before.map(TrackId::value),
                                                    )
                                                } else {
                                                    stored
                                                }
                                            }
                                            selectedCollection
                                                ?.takeIf { it.playlistId == playlistId }
                                                ?.let { current ->
                                                    val restoredTracks = change.before
                                                        .mapNotNull(tracksById::get)
                                                    updateSelectedCollection(current.copy(
                                                        subtitle = trackCountLabel(
                                                            restoredTracks.size,
                                                        ),
                                                        tracks = restoredTracks,
                                                    ))
                                                }
                                        }
                                    }
                                } else {
                                    showSelectionRemoval = true
                                }
                            },
                            modifier = Modifier,
                        )
                        }
                    }
                    AnimatedVisibility(
                        visible = !selectionMode && currentTrack != null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(if (!selectionMode) 1f else 0f),
                        enter = if (reduceMotion) {
                            fadeIn(tween(Motion.REDUCED_MS))
                        } else {
                            fadeIn(tween(Motion.APPEAR_MS)) +
                                slideInVertically(tween(Motion.APPEAR_MS)) { it / 3 }
                        },
                        exit = if (reduceMotion) {
                            fadeOut(tween(Motion.REDUCED_MS))
                        } else {
                            fadeOut(tween(Motion.REPLACE_MS)) +
                                slideOutVertically(tween(Motion.REPLACE_MS)) { it / 3 }
                        },
                    ) {
                        Box(
                            modifier = Modifier.inactiveDuringTransition(
                                selectionMode || currentTrack == null,
                            ),
                        ) {
                        (currentTrack ?: lastMiniPresentation.track)?.let { current ->
                            MiniPlayerPill(
                                track = current,
                                accent = if (currentTrack != null) {
                                    accent
                                } else {
                                    lastMiniPresentation.accent
                                },
                                isPlaying = if (currentTrack != null) {
                                    currentTrackPlaying
                                } else {
                                    lastMiniPresentation.isPlaying
                                },
                                playback = playback,
                                sharedScope = sharedScope,
                                animatedScope = animatedScope,
                                onTogglePlayPause = { scope.launch { playback.togglePlayPause() } },
                                onPrevious = { scope.launch { playback.previous() } },
                                onNext = { scope.launch { playback.next() } },
                                onOpen = { showNowPlaying = true },
                            )
                    }
                        }
                        }
                    }
                }
            }
                }
            }
        }
            // One host above both AnimatedContent branches: collection/search surfaces and the
            // full player otherwise cover the Scaffold-owned host, making Undo technically exist
            // but impossible to see or tap.
            if (!settingsOverlayActive) SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(10f)
                    .padding(
                        bottom = if (
                            !settingsOverlayActive && !showNowPlaying && currentTrack != null
                        ) {
                            MINI_PLAYER_HEIGHT + navBottom
                        } else {
                            navBottom
                        },
                    ),
            )
        }

        trackMenuRequest?.let { request ->
            val target = request.track
            val trackExcluded = target.id in smartExclusionState.trackIds
            val artist = target.artist?.trim()?.takeIf(String::isNotEmpty)
            val artistExcluded = smartExclusionState.excludesArtist(artist)
            TrackActionsSheet(
                track = target,
                onPlay = {
                    // A one-track queue from the menu has no browsing source to point at.
                    AppGraph.queueSource.value = null
                    scope.launch { playback.play(listOf(target), 0) }
                },
                onPlayNext = { scope.launch { playback.playNext(target) } },
                onAddToQueue = { scope.launch { playback.addToQueue(target) } },
                isFavorite = target.id in favoriteIds,
                onToggleFavorite = { toggleFavorite(target.id) },
                // The page assembles on arrival at the tab, and MapTab's focus effect fires as
                // soon as it is ready — so the action works even before the first Map visit.
                onShowOnMap = {
                    mapFocusTrackId = target.id
                    updateTrackSelection(emptySet())
                    updateSelectedCollection(null)
                    showSearch = false
                    showNowPlaying = false
                    navigateToRootTab(MAP_TAB)
                },
                onAddToPlaylist = { addToPlaylistSelection = listOf(target) },
                // Only from inside a user playlist whose list actually holds this track. The
                // sheet can be raised over a playlist from other surfaces (the queue sheet, For
                // You); removal must never appear there and silently edit an unrelated playlist.
                onRemoveFromPlaylist = request.sourcePlaylistId?.let { playlistId ->
                    {
                        scope.launch {
                            var membershipChange: PlaylistTrackChange? = null
                            val persisted = try {
                                membershipChange = AppGraph.playlists.removeTrack(
                                    playlistId,
                                    target.id,
                                )
                                membershipChange != null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                false
                            }
                            if (!persisted) {
                                snackbar.showSnackbar(
                                    getString(Res.string.settings_library_manage_failed),
                                )
                                return@launch
                            }
                            // The open screen keeps its own copy of the list; drop the row
                            // immediately after the durable write succeeds.
                            val currentPlaylist = selectedCollection
                                ?.takeIf { it.playlistId == playlistId }
                            if (currentPlaylist != null) {
                                val remaining = currentPlaylist.tracks
                                    .filterNot { it.id == target.id }
                                updateSelectedCollection(currentPlaylist.copy(
                                    subtitle = trackCountLabel(remaining.size),
                                    tracks = remaining,
                                ))
                                updateTrackSelection(selectedTrackIds - target.id)
                            }
                            val change = checkNotNull(membershipChange)
                            playlists = playlists.map { stored ->
                                if (stored.id == playlistId) {
                                    stored.copy(trackIds = change.after.map(TrackId::value))
                                } else {
                                    stored
                                }
                            }
                            refreshPlaylistStatsBestEffort()
                            val result = snackbar.showSnackbar(
                                message = getString(
                                    Res.string.snack_playlist_track_removed,
                                    request.sourcePlaylistTitle.orEmpty(),
                                ),
                                actionLabel = if (change.before != change.after) {
                                    getString(Res.string.action_undo)
                                } else {
                                    null
                                },
                                withDismissAction = change.before != change.after,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                val restored = try {
                                    AppGraph.playlists.replaceTracksIfUnchanged(
                                        playlistId,
                                        expected = change.after,
                                        replacement = change.before,
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    false
                                }
                                if (!restored) {
                                    snackbar.showSnackbar(
                                        getString(Res.string.settings_library_manage_failed),
                                    )
                                    return@launch
                                }
                                playlists = playlists.map { stored ->
                                    if (stored.id == playlistId) {
                                        stored.copy(
                                            trackIds = change.before.map(TrackId::value),
                                        )
                                    } else {
                                        stored
                                    }
                                }
                                selectedCollection
                                    ?.takeIf { it.playlistId == playlistId }
                                    ?.let { current ->
                                        val restoredTracks = change.before
                                            .mapNotNull(tracksById::get)
                                        updateSelectedCollection(current.copy(
                                            subtitle = trackCountLabel(restoredTracks.size),
                                            tracks = restoredTracks,
                                        ))
                                    }
                            }
                        }
                    }
                },
                onGoToAlbum = target.album?.takeIf(String::isNotBlank)?.let {
                    { showAlbumOf(target) }
                },
                onGoToArtist = target.artist?.takeIf(String::isNotBlank)?.let {
                    { showArtistOf(target) }
                },
                onInfo = { infoTarget = target },
                isTrackExcludedFromSmart = trackExcluded,
                isArtistExcludedFromSmart = artistExcluded,
                onToggleTrackSmartExclusion = {
                    if (trackExcluded) {
                        changeSmartExclusion(
                            successMessage = Res.string.snack_track_included_in_smart,
                            change = { smartExclusions.includeTrack(target.id) },
                            undo = { smartExclusions.excludeTrack(target.id) },
                        )
                    } else {
                        changeSmartExclusion(
                            successMessage = Res.string.snack_track_excluded_from_smart,
                            change = { smartExclusions.excludeTrack(target.id) },
                            undo = { smartExclusions.includeTrack(target.id) },
                        )
                    }
                },
                onToggleArtistSmartExclusion = artist?.let { artistName ->
                    {
                        if (artistExcluded) {
                            changeSmartExclusion(
                                successMessage = Res.string.snack_artist_included_in_smart,
                                change = { smartExclusions.includeArtist(artistName) },
                                undo = { smartExclusions.excludeArtist(artistName) },
                            )
                        } else {
                            changeSmartExclusion(
                                successMessage = Res.string.snack_artist_excluded_from_smart,
                                change = { smartExclusions.excludeArtist(artistName) },
                                undo = { smartExclusions.includeArtist(artistName) },
                            )
                        }
                    }
                },
                onHide = {
                    scope.launch {
                        val collectionBeforeHide = selectedCollection
                        val sourceCollectionRevision = collectionRevision
                        val sourceRootTab = rootTabSnapshot()
                        val hidden = try {
                            library.hide(target.id)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        if (!hidden) {
                            snackbar.showSnackbar(
                                getString(Res.string.settings_library_manage_failed),
                            )
                            return@launch
                        }
                        tracks?.filterNot { it.id == target.id }?.let { remaining ->
                            publishLibraryTracks(
                                remaining,
                                libraryIndexingRequest?.librarySnapshotAuthoritative ?: false,
                            )
                        }
                        hasHiddenTracks = true
                        val collectionAfterHide = collectionBeforeHide?.let { selection ->
                            selection.filterTracksForCollection { it.id != target.id }
                                ?.let { remaining ->
                                    remaining.copy(
                                        subtitle = trackCountLabel(remaining.tracks.size),
                                    )
                                }
                        }
                        val appliedCollectionRevision =
                            if (collectionRevision == sourceCollectionRevision) {
                                updateSelectedCollection(collectionAfterHide)
                                collectionRevision
                            } else {
                                null
                            }
                        val result = snackbar.showSnackbar(
                            message = getString(Res.string.snack_removed_from_latentjam),
                            actionLabel = getString(Res.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            val restored = try {
                                library.unhide(target.id)
                                scanLibrary()
                                hasHiddenTracks = library.hasHiddenTracks()
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                false
                            }
                            if (!restored) {
                                snackbar.showSnackbar(
                                    getString(Res.string.settings_library_manage_failed),
                                )
                                return@launch
                            }
                            // Persisted visibility and page navigation are independent. The track
                            // is unhidden above, but a root-tab journey while the snackbar was up
                            // must not reopen the auto-playlist detail that this hide emptied.
                            if (shouldRestoreCollectionAfterHideUndo(
                                    appliedCollectionRevision = appliedCollectionRevision,
                                    currentCollectionRevision = collectionRevision,
                                    sourceRootTab = sourceRootTab,
                                    currentRootTab = rootTabSnapshot(),
                                )
                            ) {
                                updateSelectedCollection(collectionBeforeHide)
                            }
                        }
                    }
                },
                canDelete = target.audioUri != null &&
                    !target.id.value.startsWith("ios-media:"),
                onDelete = { deleteTarget = target },
                onDismiss = { trackMenuRequest = null },
            )
        }

        if (showSelectionRemoval) {
            val selection = selectedTracks
            SelectionRemovalSheet(
                count = selection.size,
                canDeleteFromDevice = selection.isNotEmpty() && selection.all(::canDeleteTrack),
                onHide = {
                    scope.launch {
                        val ids = selection.map { it.id }
                        val collectionBeforeHide = selectedCollection
                        val sourceCollectionRevision = collectionRevision
                        val sourceRootTab = rootTabSnapshot()
                        updateTrackSelection(emptySet())
                        val commandSelectionRevision = selectionRevision
                        val hidden = try {
                            // Platform implementations persist the whole membership change in one
                            // atomic write, so disk failure cannot hide only half the selection.
                            library.hide(ids)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                        if (!hidden) {
                            if (
                                collectionRevision == sourceCollectionRevision &&
                                selectionRevision == commandSelectionRevision
                            ) {
                                updateTrackSelection(ids.toSet())
                            }
                            snackbar.showSnackbar(
                                getString(Res.string.settings_library_manage_failed),
                            )
                            return@launch
                        }
                        val hiddenIds = ids.toHashSet()
                        tracks?.filterNot { it.id in hiddenIds }?.let { remaining ->
                            publishLibraryTracks(
                                remaining,
                                libraryIndexingRequest?.librarySnapshotAuthoritative ?: false,
                            )
                        }
                        hasHiddenTracks = true
                        val collectionAfterHide = collectionBeforeHide?.let { collection ->
                            collection.filterTracksForCollection { it.id !in hiddenIds }
                                ?.let { remaining ->
                                    remaining.copy(
                                        subtitle = trackCountLabel(remaining.tracks.size),
                                    )
                                }
                        }
                        val appliedCollectionRevision =
                            if (collectionRevision == sourceCollectionRevision) {
                                updateSelectedCollection(collectionAfterHide)
                                collectionRevision
                            } else {
                                null
                            }
                        val result = snackbar.showSnackbar(
                            message = getString(Res.string.snack_removed_from_latentjam),
                            actionLabel = getString(Res.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            val restored = try {
                                library.unhide(ids)
                                scanLibrary()
                                hasHiddenTracks = library.hasHiddenTracks()
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                false
                            }
                            if (!restored) {
                                snackbar.showSnackbar(
                                    getString(Res.string.settings_library_manage_failed),
                                )
                                return@launch
                            }
                            if (shouldRestoreCollectionAfterHideUndo(
                                    appliedCollectionRevision = appliedCollectionRevision,
                                    currentCollectionRevision = collectionRevision,
                                    sourceRootTab = sourceRootTab,
                                    currentRootTab = rootTabSnapshot(),
                                )
                            ) {
                                updateSelectedCollection(collectionBeforeHide)
                            }
                        }
                    }
                },
                onDeleteFromDevice = { deleteSelection = selection },
                onDismiss = { showSelectionRemoval = false },
            )
        }

        worldTarget?.let { target ->
            val world = target.collection
            WorldActionsSheet(
                card = target,
                onOpen = {
                    scope.launch {
                        updateSelectedCollection(CollectionSelection(
                            title = world?.title.orEmpty(),
                            subtitle = trackCountLabel(world?.tracks?.size ?: 0),
                            artworkUri = target.track.artworkUri,
                            tracks = world?.tracks.orEmpty(),
                            routeId = "world:${target.track.id.value}",
                        ))
                    }
                },
                onStartSmart = {
                    scope.launch {
                        // The same track the card showed, so the journey starts from the record
                        // the listener was looking at when they chose this.
                        val songs = catalog?.songs.orEmpty()
                        val queue = engine.smartQueue(
                            target.track,
                            smartEligibleTracks,
                            smartQueueLength,
                            smartHistoryFor(AppGraph.history, target.track),
                            AppGraph.smartCompanionGroups.value,
                        )
                        val byId = songs.associateBy { it.id }
                        AppGraph.queueSource.value = QueueSource(QueueSourceKind.FOR_YOU)
                        playback.play(listOf(target.track) + queue.mapNotNull(byId::get), 0)
                    }
                },
                onDismiss = { worldTarget = null },
            )
        }

        val playlistMutationErrorMessage = if (playlistMutationFailed) {
            stringResource(Res.string.settings_library_manage_failed)
        } else {
            null
        }

        addToPlaylistSelection?.let { selection ->
            AddToPlaylistSheet(
                tracks = selection,
                playlists = playlists,
                resolvedSize = { tracksOf(it).size },
                onAddTo = { playlist ->
                    if (playlistMutationInProgress) return@AddToPlaylistSheet
                    val selectedBefore = selectedTrackIds
                    val sourceCollectionRevision = collectionRevision
                    updateTrackSelection(emptySet())
                    val commandSelectionRevision = selectionRevision
                    playlistMutationInProgress = true
                    playlistMutationFailed = false
                    scope.launch {
                        val added = try {
                            runPlaylistMutation(showFailureSnackbar = false) {
                                AppGraph.playlists.addTracks(
                                    playlist.id,
                                    selection.map(TrackDescriptor::id),
                                )
                            }
                        } finally {
                            // Also runs for CancellationException; a cancelled child must never
                            // leave every playlist dialog disabled in a still-live composition.
                            playlistMutationInProgress = false
                        }
                        if (!added) {
                            playlistMutationFailed = true
                            if (
                                selectionRevision == commandSelectionRevision &&
                                collectionRevision == sourceCollectionRevision
                            ) {
                                updateTrackSelection(selectedBefore)
                            }
                            return@launch
                        }
                        playlistMutationFailed = false
                        addToPlaylistSelection = null
                        refreshPlaylistMembershipsBestEffort()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_added_to_playlist, playlist.name),
                        )
                    }
                },
                onCreateNew = {
                    // Remember the selection so the new playlist starts with every chosen track.
                    pendingPlaylistTracks = selection
                    playlistMutationFailed = false
                    showCreatePlaylist = true
                },
                onDismiss = {
                    addToPlaylistSelection = null
                    playlistMutationFailed = false
                },
                busy = playlistMutationInProgress,
                errorMessage = playlistMutationErrorMessage,
            )
        }

        if (showCreatePlaylist) {
            val tracksToSeed = pendingPlaylistTracks
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_new),
                confirmLabel = stringResource(Res.string.action_create),
                onConfirm = { name ->
                    if (playlistMutationInProgress) return@PlaylistNameDialog
                    val selectedBefore = selectedTrackIds
                    val sourceCollectionRevision = collectionRevision
                    updateTrackSelection(emptySet())
                    val commandSelectionRevision = selectionRevision
                    playlistMutationInProgress = true
                    playlistMutationFailed = false
                    scope.launch {
                        var created: Playlist? = null
                        val saved = try {
                            runPlaylistMutation(showFailureSnackbar = false) {
                                created = AppGraph.playlists.create(
                                    name,
                                    tracksToSeed.map(TrackDescriptor::id),
                                )
                            }
                        } finally {
                            playlistMutationInProgress = false
                        }
                        if (!saved) {
                            playlistMutationFailed = true
                            if (
                                selectionRevision == commandSelectionRevision &&
                                collectionRevision == sourceCollectionRevision
                            ) {
                                updateTrackSelection(selectedBefore)
                            }
                            return@launch
                        }
                        playlistMutationFailed = false
                        val persisted = checkNotNull(created)
                        playlists = listOf(persisted) + playlists.filterNot {
                            it.id == persisted.id
                        }
                        showCreatePlaylist = false
                        pendingPlaylistTracks = emptyList()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_playlist_created, persisted.name),
                        )
                    }
                },
                onDismiss = {
                    showCreatePlaylist = false
                    pendingPlaylistTracks = emptyList()
                    playlistMutationFailed = false
                },
                busy = playlistMutationInProgress,
                errorMessage = playlistMutationErrorMessage,
            )
        }

        renameTarget?.let { target ->
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_rename_title),
                initialName = target.name,
                confirmLabel = stringResource(Res.string.action_rename),
                onConfirm = { name ->
                    if (playlistMutationInProgress) return@PlaylistNameDialog
                    playlistMutationInProgress = true
                    playlistMutationFailed = false
                    scope.launch {
                        val renamed = try {
                            runPlaylistMutation(showFailureSnackbar = false) {
                                AppGraph.playlists.rename(target.id, name)
                            }
                        } finally {
                            playlistMutationInProgress = false
                        }
                        if (!renamed) {
                            playlistMutationFailed = true
                            return@launch
                        }
                        playlistMutationFailed = false
                        val normalizedName = name.trim().ifEmpty { "Untitled playlist" }
                        playlists = playlists.map { playlist ->
                            if (playlist.id == target.id) {
                                playlist.copy(name = normalizedName)
                            } else {
                                playlist
                            }
                        }
                        renameTarget = null
                    }
                },
                onDismiss = {
                    renameTarget = null
                    playlistMutationFailed = false
                },
                busy = playlistMutationInProgress,
                errorMessage = playlistMutationErrorMessage,
            )
        }

        deleteTarget?.let { target ->
            DeleteTrackDialog(
                track = target,
                onConfirm = {
                    deleteTarget = null
                    deleteTrack(listOf(target))
                },
                onDismiss = { deleteTarget = null },
            )
        }


        deleteSelection?.let { selection ->
            DeleteTracksDialog(
                count = selection.size,
                onConfirm = {
                    deleteSelection = null
                    updateTrackSelection(emptySet())
                    deleteTrack(selection)
                },
                onDismiss = { deleteSelection = null },
            )
        }

        infoTarget?.let { target ->
            TrackInfoSheet(
                track = target,
                // Without this the list keeps the old title until relaunch — the write lands, the
                // rescan finishes, and the UI is still holding the pre-edit snapshot.
                onSaved = { scope.launch { scanLibrary() } },
                onDismiss = { infoTarget = null },
            )
        }

        AnimatedVisibility(
            visibleState = settingsVisibilityState,
            enter = motionPageEnter(
                forward = true,
                reduceMotion = reduceMotion,
                layoutDirection = layoutDirection,
            ),
            exit = motionPageExit(
                forward = false,
                reduceMotion = reduceMotion,
                layoutDirection = layoutDirection,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .inactiveDuringTransition(!settingsVisibilityState.targetState),
            ) {
            SettingsScreen(
                settings = settings,
                equalizer = AppGraph.equalizer,
                engine = engine,
                history = AppGraph.history,
                recentSearches = AppGraph.recentSearches,
                tracks = tracks.orEmpty(),
                libraryLoading = tracks == null,
                libraryRefreshing = libraryRefreshing,
                hasHiddenTracks = hasHiddenTracks,
                canImportAudio = audioImportAvailable,
                onRefreshLibrary = {
                    if (!libraryRefreshing) {
                        libraryRefreshing = true
                        scope.launch {
                            try {
                                scanLibrary()
                                hasHiddenTracks = library.hasHiddenTracks()
                                snackbar.showSnackbar(getString(Res.string.snack_library_refreshed))
                            } finally {
                                libraryRefreshing = false
                            }
                        }
                    }
                },
                onImportAudio = importAudio,
                onRetryIndexing = ::retryAutomaticIndexing,
                onRebuildAnalysis = {
                    engine.clearAnalysis()
                    metadataVectorsReady = false
                    audioVectorsReady = false
                    worlds = emptyList()
                    worldLibraryIds = emptyList()
                    // The key is the cache of the very thing being invalidated: leaving it set
                    // lets a same-inputs rebuild short-circuit, and worlds would stay empty.
                    builtWorldsKey = null
                    invalidateSmartRecommendationCaches()
                    retryAutomaticIndexing()
                },
                onHideTrack = { target ->
                    library.hide(target.id)
                    scanLibrary()
                    hasHiddenTracks = true
                    updateSelectedCollection(selectedCollection?.let { selection ->
                        selection.filterTracksForCollection { it.id != target.id }
                            ?.let { remaining ->
                                remaining.copy(
                                    subtitle = trackCountLabel(remaining.tracks.size),
                                )
                            }
                    })
                    invalidateSmartRecommendationCaches()
                },
                onDuplicateDataChanged = {
                    favoriteIds = AppGraph.favorites.all()
                    refreshPlaylistMemberships()
                },
                onBackupRestored = {
                    scanLibrary()
                    hasHiddenTracks = library.hasHiddenTracks()
                    updateTrackSelection(emptySet())
                    updateSelectedCollection(null)
                    metadataVectorsReady = false
                    audioVectorsReady = false
                    worlds = emptyList()
                    worldLibraryIds = emptyList()
                    builtWorldsKey = null
                    personalizationRevision += 1
                    invalidateSmartRecommendationCaches()
                    refreshPlaylistsWithFeedback()
                },
                onClearListeningHistory = {
                    AppGraph.history.clear()
                    forYou = ForYouPage()
                    builtWorlds = null
                    builtForYouLibraryIds = null
                    builtForYouPlaylistIdentity = null
                    personalizationRevision += 1
                    refreshPlaylistsWithFeedback()
                },
                onClearRecentSearches = {
                    AppGraph.recentSearches.clear()
                },
                snackbarHostState = snackbar,
                onClose = { showSettings = false },
            )
        }
    }
    }
}
}

/**
 * The carousel's sections, as resources rather than words: the strip reads them through
 * `stringResource`, so the list is an order, not a set of labels.
 */
private val BROWSE_TABS: List<StringResource> = listOf(
    Res.string.tab_for_you,
    Res.string.tab_map,
    Res.string.tab_playlists,
    Res.string.tab_tracks,
    Res.string.tab_albums,
    Res.string.tab_artists,
    Res.string.tab_genres,
    Res.string.tab_folders,
)

/** Coarse library states only; catalog refreshes within CONTENT must never replay its entrance. */
private enum class LibraryPresentation { LOADING, EMPTY, CONTENT }

/** Outgoing full-screen content stays drawable but cannot retain focus or receive a late tap. */
private fun Modifier.inactiveDuringTransition(inactive: Boolean): Modifier = if (!inactive) {
    this
} else {
    clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
}

/** Static modal floor beneath a moving page, preventing taps through temporarily uncovered space. */
@Composable
private fun ModalPointerBlocker() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/** Map data may refresh within READY; only a real status boundary should fade the surface. */
private fun MapPageState.presentationKey(): String = when (this) {
    MapPageState.Indexing -> "indexing"
    MapPageState.Building -> "building"
    is MapPageState.TooLarge -> "too-large"
    is MapPageState.Ready -> "ready"
}

/** Stable pager indices for the fixed browse destinations. */
private const val FOR_YOU_TAB = 0
private const val MAP_TAB = 1
private const val PLAYLISTS_TAB = 2
private const val TRACKS_TAB = 3
private const val ALBUMS_TAB = 4
private const val ARTISTS_TAB = 5
private const val GENRES_TAB = 6
private const val FOLDERS_TAB = 7

/** The browse tabs whose group items (albums, artists, genres, folders) support selection. */
private val GROUP_TABS = ALBUMS_TAB..FOLDERS_TAB

private fun StartPage.tabIndex(): Int = when (this) {
    StartPage.FOR_YOU -> FOR_YOU_TAB
    StartPage.MAP -> MAP_TAB
    StartPage.PLAYLISTS -> PLAYLISTS_TAB
    StartPage.TRACKS -> TRACKS_TAB
    StartPage.ALBUMS -> ALBUMS_TAB
    StartPage.ARTISTS -> ARTISTS_TAB
    StartPage.GENRES -> GENRES_TAB
    StartPage.FOLDERS -> FOLDERS_TAB
}

/** Persist-and-report granularity for library indexing. */
// Weeks of context, not days: the daypart and phase sections fold over this window, and at a
// heavy listener's ~80 events/day, 500 events was six days — too short to know what a morning is.
private const val RECENT_EVENTS_FOR_YOU = 4000

// The full player owns precise seeking; the browse pill updates this glanceable hint less often so
// playback does not invalidate the browse shell every 500 ms.
private const val MINI_PLAYER_PROGRESS_STEP_MS = 1_000L

/** Stable saveable-state bucket for the browse stack while the full player owns the screen. */
private const val BROWSE_SHELL_STATE_KEY = "browse-shell"

/**
 * The pill's own height, above whatever navigation-bar inset it is sitting on.
 *
 * Lists add this to the inset so their last row clears the pill instead of hiding under it. A
 * constant rather than a measurement: the pill is a fixed piece of furniture, and measuring it
 * would make every list's padding depend on a layout pass it does not otherwise wait for.
 */
private val MINI_PLAYER_HEIGHT = 76.dp

/** Height of the contextual action row above the system navigation inset. */
private val SELECTION_ACTION_BAR_HEIGHT = 76.dp

private fun canDeleteTrack(track: TrackDescriptor): Boolean =
    track.audioUri != null && !track.id.value.startsWith("ios-media:")

// A selection is assembled in a click handler, which is not composition — so these resolve their
// strings through the suspending resource API and are called from a coroutine. The alternative,
// keeping English in the model and translating it on the way out, is what this pass exists to undo.

private suspend fun AlbumGroup.toSelection(): CollectionSelection {
    val ordered = withContext(Dispatchers.Default) {
        SongSorting.sort(tracks, SongSort.TITLE, SongSortDirection.ASCENDING)
    }
    return CollectionSelection(
        title = title ?: getString(Res.string.track_unknown_album),
        subtitle = artist,
        artworkUri = artworkUri,
        tracks = ordered,
        railMode = CollectionRailMode.TRACK_TITLES,
        allowsTrackSelection = true,
        routeId = "album:$key",
    )
}

private suspend fun ArtistGroup.toSelection(): CollectionSelection {
    // Albums are an artist's natural chapters. The flat list stays exactly the sections'
    // concatenation, so playback and selection keep working in flat indices.
    val unknownAlbum = getString(Res.string.track_unknown_album)
    val sections = withContext(Dispatchers.Default) {
        tracks
            .groupBy { it.album }
            .entries
            .sortedBy { (album, _) -> SongSorting.sortKey(album) }
            .map { (album, grouped) ->
                CollectionSection(
                    title = album ?: unknownAlbum,
                    tracks = SongSorting.sort(
                        grouped,
                        SongSort.TITLE,
                        SongSortDirection.ASCENDING,
                    ),
                    railTitle = album,
                )
            }
    }
    val ordered = sections.flatMap { it.tracks }
    return CollectionSelection(
        title = name ?: getString(Res.string.track_unknown_artist),
        subtitle = getPluralString(Res.plurals.count_tracks, tracks.size, tracks.size) +
            SUBTITLE_SEPARATOR +
            getPluralString(Res.plurals.count_albums, albumCount, albumCount),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        // A single bucket is a flat list wearing a pointless header.
        sections = sections.takeIf { it.size > 1 },
        railMode = if (sections.size > 1) {
            CollectionRailMode.SECTION_TITLES
        } else {
            CollectionRailMode.TRACK_TITLES
        },
        allowsTrackSelection = true,
        routeId = "artist:${name.orEmpty()}",
    )
}

private suspend fun GenreGroup.toSelection(): CollectionSelection {
    val ordered = withContext(Dispatchers.Default) {
        SongSorting.sort(tracks, SongSort.TITLE, SongSortDirection.ASCENDING)
    }
    return CollectionSelection(
        title = name ?: getString(Res.string.track_unknown_genre),
        subtitle = trackCountLabel(tracks.size),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        railMode = CollectionRailMode.TRACK_TITLES,
        allowsTrackSelection = true,
        routeId = "genre:${name.orEmpty()}",
    )
}

private suspend fun FolderGroup.toSelection(): CollectionSelection {
    val ordered = withContext(Dispatchers.Default) {
        SongSorting.sort(tracks, SongSort.TITLE, SongSortDirection.ASCENDING)
    }
    return CollectionSelection(
        title = name,
        subtitle = path + SUBTITLE_SEPARATOR + trackCountLabel(tracks.size),
        artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
        tracks = ordered,
        railMode = CollectionRailMode.TRACK_TITLES,
        allowsTrackSelection = true,
        routeId = "folder:$path",
    )
}

private suspend fun trackCountLabel(count: Int): String =
    getPluralString(Res.plurals.count_tracks, count, count)

/**
 * Joins two counted phrases in a subtitle.
 *
 * Punctuation rather than a word, so it needs no translation and survives being read
 * right-to-left; the two halves around it are each separately pluralised.
 */
private const val SUBTITLE_SEPARATOR = " • "

@Composable
private fun artistSubtitle(tracks: Int, albums: Int): String =
    pluralStringResource(Res.plurals.count_tracks, tracks, tracks) +
        SUBTITLE_SEPARATOR +
        pluralStringResource(Res.plurals.count_albums, albums, albums)

// ---------------------------------------------------------------- components

/**
 * Compact section switcher: the active section sits centred at full size,
 * its neighbours shrink and fade with distance so they stay readable but
 * clearly secondary. Items are only as wide as their label, which keeps the
 * strip tight instead of eating a band of the screen.
 */
@Composable
private fun BrowseCarousel(
    pagerState: PagerState,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val sidePadding = (maxWidth / 2 - 56.dp).coerceAtLeast(0.dp)

        fun offsetFromCentre(index: Int): Float? {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            return (item.offset + item.size / 2f) - centre
        }

        suspend fun centerTab(index: Int) {
            // A fast multi-page swipe can put both interpolation endpoints outside LazyRow's
            // composed window. Bring the destination into the layout first, then centre it in the
            // same pass. The old code stopped after scrollToItem(), so no pager state changed to
            // trigger a second pass and the strip could remain permanently one page behind.
            if (offsetFromCentre(index) == null) listState.scrollToItem(index)
            val delta = offsetFromCentre(index) ?: return
            if (abs(delta) > 0.5f) listState.scrollBy(delta)
        }

        // Only the pager owns continuous motion while the finger is down. Recenter once when its
        // active page changes, rather than waiting for the fling to settle; this keeps the label
        // responsive without restoring the old per-frame feedback loop and jitter.
        LaunchedEffect(pagerState.currentPage, sidePadding) {
            centerTab(pagerState.currentPage)
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            itemsIndexed(BROWSE_TABS) { index, title ->
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .selectable(
                            enabled = enabled,
                            selected = index == pagerState.currentPage,
                            // No ripple: the default selectable paints a grey rectangle around the
                            // tab's box on every tap, which sat unclipped over the scaled text and
                            // looked like a glitch. This carousel already answers a tap by scaling
                            // the chosen tab up and dimming the rest, so that animation is the
                            // feedback and the rectangle was only noise.
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(horizontal = 14.dp)
                        .graphicsLayer {
                            // Style follows the pager directly; it never feeds the LazyRow's own
                            // layout back into the same gesture, so scaling cannot jitter the strip.
                            val pagerPosition =
                                pagerState.currentPage + pagerState.currentPageOffsetFraction
                            val distance = abs(index - pagerPosition).coerceIn(0f, 1f)
                            val scale = 1f - 0.26f * distance
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.6f * distance
                        },
                )
            }
        }
    }
}

/** Thumb-zone actions for the current track selection; replaces the mini-player temporarily. */
@Composable
private fun SelectionActionBar(
    canAct: Boolean,
    canShare: Boolean = canAct,
    removeFromPlaylist: Boolean = false,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(SELECTION_ACTION_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SelectionAction(
            icon = Icons.Rounded.PlayArrow,
            label = stringResource(Res.string.action_play),
            enabled = canAct,
            onClick = onPlay,
            modifier = Modifier.weight(1f),
        )
        SelectionAction(
            icon = Icons.Rounded.LibraryAdd,
            label = stringResource(Res.string.action_add),
            enabled = canAct,
            onClick = onAdd,
            modifier = Modifier.weight(1f),
        )
        SelectionAction(
            icon = Icons.Rounded.Share,
            label = stringResource(Res.string.action_share),
            enabled = canShare,
            onClick = onShare,
            modifier = Modifier.weight(1f),
        )
        SelectionAction(
            icon = if (removeFromPlaylist) {
                Icons.Rounded.PlaylistRemove
            } else {
                Icons.Rounded.DeleteOutline
            },
            label = stringResource(
                if (removeFromPlaylist) {
                    Res.string.action_remove_from_playlist
                } else {
                    Res.string.action_delete
                },
            ),
            enabled = canAct,
            onClick = onRemove,
            modifier = Modifier.weight(1f),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint.copy(alpha = contentAlpha),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The app's overflow affordance. Rendered from the same shared element in
 * both the library and the player and placed in the same slot, so the morph
 * between them leaves it untouched — a fixed point the eye can hold on to
 * while everything else moves.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun OverflowButton(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    menuItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    Box {
        IconButton(
            onClick = { open = true },
            modifier = if (reduceMotion) {
                Modifier
            } else with(sharedScope) {
                Modifier.sharedElement(
                    rememberSharedContentState(OVERFLOW_KEY),
                    animatedScope,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.cd_more_options),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menuItems { open = false }
        }
    }
}

/** Direct access to the app control centre; maintenance actions live in Settings > Library. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedSettingsButton(
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    shareElement: Boolean,
    onClick: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    IconButton(
        onClick = onClick,
        modifier = if (reduceMotion || !shareElement) {
            Modifier
        } else with(sharedScope) {
            Modifier.sharedElement(
                rememberSharedContentState(OVERFLOW_KEY),
                animatedScope,
            )
        },
    ) {
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = stringResource(Res.string.settings_title),
        )
    }
}

/** Sort selector plus shuffle-all / play-all, above the songs list. */
@Composable
private fun SongsHeader(
    sort: SongSort,
    direction: SongSortDirection,
    enabled: Boolean = true,
    onSortChange: (SongSort) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TextButton(onClick = { sortMenuOpen = true }, enabled = enabled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = sort.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                SortDirectionIcon(direction)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                SongSort.entries.forEach { option ->
                    val selected = option == sort
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        onClick = {
                            sortMenuOpen = false
                            onSortChange(option)
                        },
                        trailingIcon = if (selected) {
                            { SortDirectionIcon(direction) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        FilledIconButton(
            onClick = onShuffleAll,
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Shuffle,
                contentDescription = stringResource(Res.string.action_shuffle_all),
            )
        }
        FilledIconButton(
            onClick = onPlayAll,
            enabled = enabled,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(Res.string.action_play_all),
            )
        }
    }
}

@Composable
private fun SortDirectionIcon(direction: SongSortDirection) {
    Icon(
        imageVector = when (direction) {
            SongSortDirection.ASCENDING -> Icons.Rounded.ArrowUpward
            SongSortDirection.DESCENDING -> Icons.Rounded.ArrowDownward
        },
        contentDescription = stringResource(
            when (direction) {
                SongSortDirection.ASCENDING -> Res.string.sort_direction_ascending
                SongSortDirection.DESCENDING -> Res.string.sort_direction_descending
            },
        ),
        modifier = Modifier
            .padding(start = 4.dp)
            .size(16.dp),
    )
}

/**
 * A second tap on the active option reverses it. Moving to another option restores the useful
 * default for that field (A–Z for text, newest first for recently added).
 */
internal fun directionAfterSongSortSelection(
    currentSort: SongSort,
    currentDirection: SongSortDirection,
    selectedSort: SongSort,
): SongSortDirection = if (selectedSort == currentSort) {
    currentDirection.toggled()
} else {
    selectedSort.defaultDirection
}

@Composable
private fun SongSort.label(): String = stringResource(
    when (this) {
        SongSort.TITLE -> Res.string.info_title
        SongSort.ARTIST -> Res.string.info_artist
        SongSort.RECENT -> Res.string.sort_recently_added
    },
)

/** The shared browse-group gesture: tap, plus [TrackRow]'s long-press haptic when selectable. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.groupClickable(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val haptics = LocalHapticFeedback.current
    return combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick?.let { longClick ->
            {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                longClick()
            }
        },
    )
}

/** The leading check ring group rows share with [TrackRow]; absent outside selection mode. */
@Composable
private fun GroupSelectionMark(selectionState: Boolean?) {
    if (selectionState == null) return
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
}

private data class KeyedAlbum(
    val album: AlbumGroup,
    val titleKey: String,
    val artistKey: String,
)

/** Stable, saveable Lazy item identity; null and an actual empty name never alias. */
private fun stableGroupKey(kind: String, name: String?): String =
    if (name == null) "$kind:null" else "$kind:value:$name"

private data class LibraryBrowseDerivation(
    val catalog: LibraryCatalog,
    val albumSections: List<AlbumRailSection>,
    val alphabeticAlbumTracks: List<TrackDescriptor>,
)

/** Albums use one global A–Z presentation order so one rail letter always has one anchor. */
internal fun albumsInRailOrder(albums: List<AlbumGroup>): List<AlbumGroup> = albums
    .map { album ->
        KeyedAlbum(
            album = album,
            titleKey = SongSorting.sortKey(album.title),
            artistKey = SongSorting.sortKey(album.artist),
        )
    }
    .sortedWith(compareBy<KeyedAlbum>({ it.titleKey }, { it.artistKey }, { it.album.key }))
    .map { it.album }

internal data class AlbumRailSection(
    val bucket: String,
    val albums: List<AlbumGroup>,
    /** Index of this section's full-span header in LazyVerticalGrid emission order. */
    val emitStartIndex: Int,
)

internal fun albumRailSections(albums: List<AlbumGroup>): List<AlbumRailSection> {
    var emitIndex = 0
    return albumsInRailOrder(albums)
        .groupBy { album -> SongSorting.bucket(album.title) }
        .map { (bucket, grouped) ->
            AlbumRailSection(
                bucket = bucket,
                albums = grouped,
                emitStartIndex = emitIndex,
            ).also {
                emitIndex += 1 + grouped.size
            }
        }
}

@Composable
private fun AlbumSectionHeader(bucket: String) {
    Text(
        text = bucket,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun AlbumCard(
    album: AlbumGroup,
    /** Rail mirrors cap decode work; the actual card still resolves from its layout constraints. */
    railPreview: Boolean = false,
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** `null` outside selection mode; otherwise whether every track of this album is selected. */
    selectionState: Boolean? = null,
    onClick: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    Column(
        modifier = Modifier
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
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            album.artworkUri?.let { requestUri ->
                val imageModel = remember(requestUri, railPreview, platformContext) {
                    if (railPreview) {
                        ImageRequest.Builder(platformContext)
                            .data(requestUri)
                            .size(ALBUM_RAIL_PREVIEW_PX)
                            .build()
                    } else {
                        requestUri
                    }
                }
                AsyncImage(
                    model = imageModel,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onLoading = {
                        onArtworkLoadStateChanged?.invoke(
                            requestUri,
                            ArtworkLoadState.LOADING,
                        )
                    },
                    onSuccess = {
                        onArtworkLoadStateChanged?.invoke(
                            requestUri,
                            ArtworkLoadState.TERMINAL,
                        )
                    },
                    onError = {
                        onArtworkLoadStateChanged?.invoke(
                            requestUri,
                            ArtworkLoadState.TERMINAL,
                        )
                    },
                )
            }
            if (selectionState != null) {
                // A grid card has no leading slot, so the check sits on the artwork; the scrim
                // circle keeps the unchecked outline visible over any cover.
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
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            CircleShape,
                        )
                        .padding(2.dp)
                        .size(24.dp),
                )
            }
        }
        Text(
            text = album.title ?: stringResource(Res.string.track_unknown_album),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = album.artist ?: stringResource(Res.string.track_unknown_artist),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Deliberately modest: enough for a transient card, far cheaper than source-resolution art. */
private const val ALBUM_RAIL_PREVIEW_PX = 256

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    artworkUri: String?,
    onArtworkLoadStateChanged: ((requestUri: String, state: ArtworkLoadState) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** `null` outside selection mode; otherwise whether every track of this group is selected. */
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupSelectionMark(selectionState)
        Artwork(
            uri = artworkUri,
            size = 48.dp,
            cornerRadius = 24.dp,
            onLoadStateChanged = onArtworkLoadStateChanged,
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupSelectionMark(selectionState)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folder.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The mini-player: a tinted bar sealed to the bottom edge, rounded only at
 * the top. It spans the full width and extends behind the navigation bar on
 * purpose — any inset would leave a strip of background around it, which
 * reads as a panel pasted onto a rectangle rather than part of the app. Its
 * artwork and container are shared elements, so opening the player grows
 * this bar into the full screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerPill(
    track: TrackDescriptor,
    accent: TrackAccent,
    isPlaying: Boolean,
    playback: PlaybackController,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val playerShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    CompositionLocalProvider(LocalContentColor provides accent.onContainer) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (reduceMotion) Modifier else with(sharedScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(PLAYER_SURFACE_KEY),
                            animatedScope,
                            boundsTransform = motionBoundsTransform(),
                        )
                    },
                )
                // Material Surface still creates a separate rendered surface at zero elevation. On
                // some Android renderers its boundary is visible as a full-width grey hairline.
                // A shaped background paints the same pill without that extra surface boundary.
                .background(accent.container, playerShape)
                .clickable(onClick = onOpen)
                // The pill answers a flick the way every player taught thumbs to expect:
                // left for the next track, right for the previous one. Decided on release
                // from the accumulated travel, so a wobbly tap never skips.
                .pointerInput(Unit) {
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            travelled += dragAmount
                        },
                        onDragEnd = {
                            val threshold = 56.dp.toPx()
                            when {
                                travelled <= -threshold -> onNext()
                                travelled >= threshold -> onPrevious()
                            }
                        },
                    )
                },
        ) {
            Box(modifier = Modifier.navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RetainedArtwork(
                        uri = track.artworkUri,
                        size = 44.dp,
                        cornerRadius = 22.dp,
                        modifier = if (reduceMotion) Modifier else with(sharedScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(ARTWORK_KEY),
                                animatedScope,
                                boundsTransform = motionBoundsTransform(),
                            )
                        },
                    )
                    AnimatedContent(
                        targetState = track,
                        contentKey = { it.id },
                        transitionSpec = { motionFadeThrough(reduceMotion) },
                        modifier = Modifier.weight(1f),
                        label = "mini-track-metadata",
                    ) { shownTrack ->
                        Column(
                            modifier = Modifier.inactiveForMotion(shownTrack.id != track.id),
                        ) {
                            Text(
                                text = shownTrack.title
                                    ?: stringResource(Res.string.track_untitled),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = shownTrack.artist
                                    ?: stringResource(Res.string.track_unknown_artist),
                                style = MaterialTheme.typography.bodySmall,
                                color = accent.onContainer.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = onPrevious) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(Res.string.action_previous),
                        )
                    }
                    val playPauseDescription = stringResource(
                        if (isPlaying) Res.string.action_pause else Res.string.action_play,
                    )
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.semantics {
                            contentDescription = playPauseDescription
                        },
                    ) {
                        AnimatedContent(
                            targetState = isPlaying,
                            transitionSpec = { motionIconTransform(reduceMotion) },
                            label = "mini-play-pause",
                        ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.inactiveForMotion(playing != isPlaying),
                        )
                        }
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(Res.string.action_next),
                        )
                    }
                }
                MiniPlayerProgress(
                    playback = playback,
                    accent = accent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

/** Isolates the playhead ticker from the rest of the browse hierarchy. */
@Composable
private fun MiniPlayerProgress(
    playback: PlaybackController,
    accent: TrackAccent,
    modifier: Modifier = Modifier,
) {
    val liveProgressSample by remember(playback) {
        playback.state.map { now ->
            now.track?.id to if (now.durationMs > 0) {
                val coarsePosition =
                    now.positionMs / MINI_PLAYER_PROGRESS_STEP_MS * MINI_PLAYER_PROGRESS_STEP_MS
                (coarsePosition.toFloat() / now.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }.distinctUntilChanged()
    }.collectAsState(
        playback.state.value.let { now ->
            now.track?.id to if (now.durationMs > 0) {
                val coarsePosition =
                    now.positionMs / MINI_PLAYER_PROGRESS_STEP_MS * MINI_PLAYER_PROGRESS_STEP_MS
                (coarsePosition.toFloat() / now.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        },
    )
    var progressSample by remember { mutableStateOf(liveProgressSample) }
    LaunchedEffect(liveProgressSample) {
        if (liveProgressSample.first != null) progressSample = liveProgressSample
    }
    val reduceMotion = rememberReduceMotion()
    androidx.compose.runtime.key(progressSample.first) {
        val animatedProgress = remember { Animatable(progressSample.second) }
        LaunchedEffect(progressSample.second, reduceMotion) {
            if (reduceMotion || progressSample.second < animatedProgress.value) {
                // Track replacement, repeat-one and explicit restart all regress. Sweeping the
                // bar backward for a second misrepresents playback, so regressions snap.
                animatedProgress.snapTo(progressSample.second)
            } else {
                animatedProgress.animateTo(
                    targetValue = progressSample.second,
                    animationSpec = tween(
                        MINI_PLAYER_PROGRESS_STEP_MS.toInt(),
                        easing = LinearEasing,
                    ),
                )
            }
        }
        LinearProgressIndicator(
            progress = { animatedProgress.value },
            modifier = modifier,
            color = accent.onContainer.copy(alpha = 0.9f),
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )
    }
}

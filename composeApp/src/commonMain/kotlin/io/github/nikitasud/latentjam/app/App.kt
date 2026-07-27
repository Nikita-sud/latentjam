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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_add
import io.github.nikitasud.latentjam.app.generated.resources.action_create
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_next
import io.github.nikitasud.latentjam.app.generated.resources.action_pause
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_play_all
import io.github.nikitasud.latentjam.app.generated.resources.action_previous
import io.github.nikitasud.latentjam.app.generated.resources.action_rename
import io.github.nikitasud.latentjam.app.generated.resources.action_undo
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle_all
import io.github.nikitasud.latentjam.app.generated.resources.action_share
import io.github.nikitasud.latentjam.app.generated.resources.action_select_all
import io.github.nikitasud.latentjam.app.generated.resources.action_deselect_all
import io.github.nikitasud.latentjam.app.generated.resources.cd_more_options
import io.github.nikitasud.latentjam.app.generated.resources.cd_search_library
import io.github.nikitasud.latentjam.app.generated.resources.count_albums
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.selection_count
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
import io.github.nikitasud.latentjam.app.generated.resources.playlist_new
import io.github.nikitasud.latentjam.app.generated.resources.playlist_rename_title
import io.github.nikitasud.latentjam.app.generated.resources.settings_title
import io.github.nikitasud.latentjam.app.generated.resources.snack_added_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.snack_artist_excluded_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_artist_included_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_created
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_smart_exclusion_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_excluded_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_included_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.snack_removed_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.snack_hidden_tracks_restored
import io.github.nikitasud.latentjam.app.generated.resources.snack_library_refreshed
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
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import io.github.nikitasud.latentjam.smart.cluster.loadLayout
import io.github.nikitasud.latentjam.smart.cluster.saveLayout
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val reduceMotion = rememberReduceMotion()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    PlatformThemeEffect(darkTheme = darkTheme)
    MaterialTheme(colorScheme = latentJamColorScheme(darkTheme = darkTheme)) {
        val scope = rememberCoroutineScope()
        val sleepTimer = remember { AppGraph.sleepTimer }
        val sleepTimerState by sleepTimer.state.collectAsState()
        LaunchedEffect(playback, smartQueueLength) {
            playback.setSmartQueueLength(smartQueueLength)
        }
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        // The pager owns the section position; everything else reads it. One source of truth means
        // the strip and the content can never disagree about where a half-finished swipe is.
        val pagerState = rememberPagerState(initialPage = startPage.tabIndex()) { BROWSE_TABS.size }
        val selectedTab = pagerState.currentPage
        var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
        var playCounts by remember { mutableStateOf<Map<TrackId, Int>>(emptyMap()) }
        var lastPlayedAt by remember { mutableStateOf<Map<TrackId, Long>>(emptyMap()) }
        var showCreatePlaylist by remember { mutableStateOf(false) }
        var renameTarget by remember { mutableStateOf<Playlist?>(null) }
        var addToPlaylistSelection by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        var pendingPlaylistTracks by remember { mutableStateOf<List<TrackDescriptor>>(emptyList()) }
        var selectedTrackIds by remember { mutableStateOf<Set<TrackId>>(emptySet()) }
        var savedSongSort by rememberSaveable { mutableStateOf(SongSort.TITLE.name) }
        val songSort = SongSort.entries.firstOrNull { it.name == savedSongSort } ?: SongSort.TITLE
        var selectedCollection by remember { mutableStateOf<CollectionSelection?>(null) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var infoTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var showNowPlaying by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var trackMenuTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
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
        val selectionMode = selectedTrackIds.isNotEmpty() && selectedTab == TRACKS_TAB
        val accent = rememberTrackAccent(
            track = currentTrack,
            mode = trackColorMode,
            darkTheme = darkTheme,
        )
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
                tracks = library.tracks()
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

        LaunchedEffect(Unit) {
            tracks = library.tracks()
            hasHiddenTracks = library.hasHiddenTracks()
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

        LaunchedEffect(selectedTab) {
            if (selectedTab != TRACKS_TAB) selectedTrackIds = emptySet()
        }

        PlatformBackHandler(enabled = selectionMode) { selectedTrackIds = emptySet() }

        // Arm SMART in the background as soon as the library is known: load the models, restore the
        // persisted index, and backfill any missing metadata-text vectors. All idempotent. Doing it
        // here rather than on first press means the SMART button is instant when it is finally
        // tapped, instead of stalling on tens of MB of model loading.
        var forYou by remember { mutableStateOf(ForYouPage()) }
        var worlds by remember { mutableStateOf<List<LibraryWorld>>(emptyList()) }
        var worldLibraryIds by remember { mutableStateOf<List<TrackId>>(emptyList()) }
        var builtWorlds by remember { mutableStateOf<List<LibraryWorld>?>(null) }
        var builtForYouLibraryIds by remember { mutableStateOf<List<TrackId>?>(null) }
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
                builtForYouExclusions == smartExclusionState &&
                builtIncludeNoveltyMixes == includeNoveltyMixes &&
                builtPersonalizationRevision == personalizationRevision &&
                builtHistoryRevision == historyRevision
            ) {
                forYouRefreshing = false
                return@LaunchedEffect
            }
            val stats = AppGraph.history.stats()
            val recentEvents = AppGraph.history.recentEvents(RECENT_EVENTS_FOR_YOU)
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
            builtForYouExclusions = smartExclusionState
            builtIncludeNoveltyMixes = includeNoveltyMixes
            builtPersonalizationRevision = personalizationRevision
            builtHistoryRevision = historyRevision
            forYouRefreshing = false
        }

        LaunchedEffect(tracks, smartEligibleTracks) {
            val loaded = tracks ?: return@LaunchedEffect
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
        LaunchedEffect(tracks, smartEligibleTracks) {
            val loaded = tracks ?: return@LaunchedEffect
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
        LaunchedEffect(tracks, metadataVectorsReady, audioVectorsReady) {
            val loaded = tracks ?: return@LaunchedEffect
            if (!metadataVectorsReady && !audioVectorsReady) return@LaunchedEffect
            val features =
                engine.libraryMixFeatures(loaded.map(TrackDescriptor::id)) ?: return@LaunchedEffect
            val discovered = withContext(Dispatchers.Default) {
                val started = TimeSource.Monotonic.markNow()
                LibraryWorlds.discover(
                    library = loaded,
                    vectorSpace = features.vectorSpace,
                    semantics = features.semantics,
                ).also { mixes ->
                    val routed = mixes.groupingBy { it.content }.eachCount()
                    println(
                        "SMART: built ${mixes.size} ${features.vectorSpace.source} local mixes " +
                            "(semantic=${features.semantics.size}, routes=$routed) in " +
                            "${started.elapsedNow().inWholeMilliseconds} ms",
                    )
                }
            }
            worldLibraryIds = loaded.map(TrackDescriptor::id)
            worlds = discovered
        }

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
        // The regions actually behind the currently shown MapPage. Not always `worlds` itself --
        // spec section 8's smallest-library fallback (mapFallbackRegions) can stand in for it -- so
        // the "Play region"/"SMART from here" callbacks below read this, not `worlds` directly, or
        // they would silently no-op whenever the Map is showing that fallback.
        var mapRegions by remember { mutableStateOf<List<LibraryWorld>>(emptyList()) }
        LaunchedEffect(tracks, worlds, pagerState.settledPage) {
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
                // metadata vector, so that population is `features.vectorSpace.trackIds`, a
                // filtered subset of `loadedIds`, not `loadedIds` itself. Comparing against the
                // full library instead would stay "stale" forever the moment even one track fails
                // to encode: the sizes would never agree again, so every visit would pay for a full
                // PCA+t-SNE recompute and re-save a set that fails the very same check next time.
                val features = engine.libraryMixFeatures(loadedIds) ?: return@withContext null
                features to layoutStore.loadLayout()
            } ?: return@LaunchedEffect
            val (features, stored) = prepared

            // Finding (a) of the final review: Tsne/Pca are O(n^2) per array with nothing capping n
            // anywhere upstream. Refuse outright above the ceiling rather than silently draw a
            // truncated map that looks complete -- see LibraryLayout.MAX_TRACKS's doc for why this
            // number and what it costs at it.
            if (features.vectorSpace.size > LibraryLayout.MAX_TRACKS) {
                mapState = MapPageState.TooLarge(
                    trackCount = features.vectorSpace.size,
                    limit = LibraryLayout.MAX_TRACKS,
                )
                return@LaunchedEffect
            }

            // Spec section 8, row 3: a library too small for TrackClustering to form even one
            // region must not leave the Map stuck showing the indexing state forever for a user
            // whose indexing already finished -- see mapFallbackRegions.
            val regions = worlds.ifEmpty { mapFallbackRegions(loaded, features.vectorSpace.trackIds) }
            if (regions.isEmpty()) return@LaunchedEffect

            // Region ids are indices into `regions`: every listening/headline lookup below keys off
            // this same index, so it must stay the one place a track's region id is assigned.
            val regionOf = buildMap {
                regions.forEachIndexed { index, world ->
                    for (track in world.tracks) put(track.id, index)
                }
            }

            val needsCompute = !LibraryLayout.covers(stored, features.vectorSpace.trackIds)
            // Finding (d): indexing has already finished by the time this can be reached, so a
            // recompute gets its own honest state instead of reusing the "still reading your
            // library" copy -- that copy is only true before indexing completes.
            if (needsCompute) mapState = MapPageState.Building

            val built = withContext(Dispatchers.Default) {
                val positions = if (needsCompute) {
                    // The stale layout is the warm start, so an added album nudges the map instead
                    // of redrawing it. `isActive` is this CoroutineScope's own cancellation state --
                    // passing it as the abort hook (finding (b)) lets a reader who navigates away
                    // mid-compute stop paying for iterations nobody will see, instead of the whole
                    // 1000-iteration pass running to completion regardless and only being noticed
                    // (and discarded) afterward.
                    val computed = LibraryLayout.compute(
                        features.vectorSpace,
                        stored,
                        isActive = { isActive },
                    )
                    // No partial state persisted: only save and adopt a layout that actually
                    // finished. An aborted compute's result is well-formed but under-converged, and
                    // must never reach the cache or a reader.
                    if (!isActive) return@withContext null
                    layoutStore.saveLayout(computed)
                    computed.associate { it.trackId to floatArrayOf(it.x, it.y) }
                } else {
                    stored
                }

                val stats = AppGraph.history.stats()
                MapPage(
                    dots = positions.mapNotNull { (id, position) ->
                        val region = regionOf[id] ?: return@mapNotNull null
                        val entry = stats[id]
                        MapDot(
                            trackId = id,
                            x = position[0],
                            y = position[1],
                            region = region,
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
                    regionNames = regions.map { it.name },
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
            } ?: return@LaunchedEffect

            mapRegions = regions
            mapState = MapPageState.Ready(built)
        }

        var catalog by remember { mutableStateOf<LibraryCatalog?>(null) }
        LaunchedEffect(tracks) {
            val loaded = tracks
            // Rebuild in place — assign the finished catalog rather than clearing it first. Nulling
            // it swapped the whole songs list for a loading box mid-rescan, which disposed the
            // list's scroll state, so deleting a track teleported the list back to the top. The
            // finished catalog is swapped in atomically instead, and stable row keys keep every
            // surviving row exactly where it was. The sliver of time where a tap could land on a
            // just-deleted row resolves to a harmless no-op — a far better trade than the jump.
            catalog = if (loaded != null) {
                withContext(Dispatchers.Default) { LibraryCatalog.build(loaded) }
            } else {
                null
            }
        }
        val tracksById = remember(catalog) { catalog?.songs?.associateBy { it.id }.orEmpty() }
        val selectedTracks = remember(catalog, songSort, selectedTrackIds) {
            SongSorting.sort(catalog?.songs.orEmpty(), songSort)
                .filter { it.id in selectedTrackIds }
        }
        LaunchedEffect(catalog) {
            selectedTrackIds = selectedTrackIds.intersect(tracksById.keys)
        }

        suspend fun refreshPlaylists() {
            playlists = AppGraph.playlists.all()
            val stats = AppGraph.history.stats()
            playCounts = stats.mapValues { it.value.plays }
            lastPlayedAt = stats.mapValues { it.value.lastPlayedAtMs }
        }

        LaunchedEffect(Unit) { refreshPlaylists() }
        // Auto playlists are derived from listening, so refresh them whenever
        // the user comes back to the tab rather than only at startup.
        LaunchedEffect(selectedTab) { if (selectedTab == PLAYLISTS_TAB) refreshPlaylists() }

        var autoPlaylists by remember { mutableStateOf<List<AutoPlaylist>>(emptyList()) }
        LaunchedEffect(catalog, playCounts, lastPlayedAt) {
            val songs = catalog?.songs
            autoPlaylists = if (songs == null) {
                emptyList()
            } else {
                withContext(Dispatchers.Default) {
                    AutoPlaylists.build(songs, playCounts, lastPlayedAt)
                }
            }
        }

        fun tracksOf(playlist: Playlist): List<TrackDescriptor> =
            playlist.trackIds.mapNotNull { tracksById[TrackId(it)] }

        // Rescan after a delete so the removed track leaves every list at once.
        val deleteTrack = rememberTrackDeleter {
            scope.launch {
                tracks = library.tracks()
                snackbar.showSnackbar(getString(Res.string.snack_track_deleted))
            }
        }

        fun retryAutomaticIndexing() {
            val loaded = tracks ?: return
            scope.launch {
                val notificationTitle = getString(Res.string.indexing_notification_title)
                AppGraph.ensureAutomaticIndexing(
                    tracks = loaded,
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
            scope.launch { selectedCollection = album.toSelection() }
        }

        fun showArtistOf(track: TrackDescriptor) {
            val artist = catalog?.artists?.firstOrNull { it.name == track.artist } ?: return
            scope.launch { selectedCollection = artist.toSelection() }
        }

        fun invalidateSmartRecommendationCaches() {
            // The exclusion store publishes only after its durable write succeeds. Clearing these
            // keys at that point removes stale cards immediately, while the state-keyed effect
            // above rebuilds the page from the latest rules even when a change came from elsewhere.
            forYou = ForYouPage()
            builtWorlds = null
            builtForYouLibraryIds = null
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
                    val duration = if (reduceMotion) 0 else MORPH_MILLIS
                    fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
                },
                label = "player-morph",
            ) { expanded ->
                if (expanded) {
                    NowPlayingScreen(
                        playback = playback,
                        accent = accent,
                        sharedScope = sharedScope,
                        animatedScope = this@AnimatedContent,
                        sleepTimerState = sleepTimerState,
                        onStartSleepTimer = sleepTimer::startCountdown,
                        onSleepAtEndOfTrack = sleepTimer::startAtEndOfTrack,
                        onCancelSleepTimer = sleepTimer::cancel,
                        onTrackMenu = { track -> trackMenuTarget = track },
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
                    Scaffold(
                        topBar = {
                            Column {
                                if (selectionMode) {
                                    SelectionTopAppBar(
                                        count = selectedTrackIds.size,
                                        allSelected = selectedTrackIds.size == catalog?.songs?.size,
                                        onClose = { selectedTrackIds = emptySet() },
                                        onToggleAll = {
                                            selectedTrackIds = if (
                                                selectedTrackIds.size == catalog?.songs?.size
                                            ) {
                                                emptySet()
                                            } else {
                                                catalog?.songs.orEmpty().mapTo(LinkedHashSet()) { it.id }
                                            }
                                        },
                                    )
                                } else TopAppBar(
                                    title = { Text("LatentJam") },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                    actions = {
                                        // Creating belongs to the tab that shows
                                        // what you'd create, so it appears there.
                                        if (selectedTab == PLAYLISTS_TAB) {
                                            IconButton(onClick = { showCreatePlaylist = true }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Add,
                                                    contentDescription =
                                                        stringResource(Res.string.playlist_new),
                                                )
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
                                            onClick = { showSettings = true },
                                        )
                                    },
                                )
                                BrowseCarousel(pagerState, enabled = !selectionMode) { tab ->
                                    scope.launch { pagerState.animateScrollToPage(tab) }
                                }
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
                                when {
                                    visibleCatalog == null -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }

                                    visibleCatalog.songs.isEmpty() -> Column(
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
                                    else -> HorizontalPager(
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
                                        FOR_YOU_TAB -> ForYouTab(
                                            page = forYou,
                                            contentPadding = listPadding,
                                            isRefreshing = forYouRefreshing,
                                            onRefresh = {
                                                if (!forYouRefreshing) {
                                                    forYouRefreshing = true
                                                    personalizationRevision += 1
                                                }
                                            },
                                            onPlay = { list, index ->
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
                                                    )
                                                    val byId = visibleCatalog.songs.associateBy { it.id }
                                                    val tail = queue.mapNotNull(byId::get)
                                                    playback.play(listOf(hero.track) + tail, 0)
                                                    hero.resumeAtMs?.let { playback.seekTo(it) }
                                                }
                                            },
                                            onTrackMenu = { trackMenuTarget = it },
                                            onOpenWorld = { worldTarget = it },
                                        )

                                        MAP_TAB -> MapTab(
                                            state = mapState,
                                            contentPadding = listPadding,
                                            onPlayRegion = { region ->
                                                mapRegions.getOrNull(region)?.let { world ->
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
                                                        )
                                                        val tail = queue.mapNotNull(tracksById::get)
                                                        playback.play(listOf(seed) + tail, 0)
                                                    }
                                                }
                                            },
                                            onOpenTrack = { id ->
                                                tracksById[id]?.let { trackMenuTarget = it }
                                            },
                                        )

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
                                                    selectedCollection = CollectionSelection(
                                                        title = getString(auto.kind.titleRes()),
                                                        subtitle = trackCountLabel(auto.tracks.size),
                                                        artworkUri = auto.tracks
                                                            .firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = auto.tracks,
                                                    )
                                                }
                                            },
                                            onOpenPlaylist = { playlist ->
                                                scope.launch {
                                                    selectedCollection = CollectionSelection(
                                                        title = playlist.name,
                                                        subtitle = trackCountLabel(
                                                            playlist.trackIds.size,
                                                        ),
                                                        artworkUri = tracksOf(playlist)
                                                            .firstNotNullOfOrNull { it.artworkUri },
                                                        tracks = tracksOf(playlist),
                                                    )
                                                }
                                            },
                                            onRename = { renameTarget = it },
                                            onDelete = { playlist ->
                                                scope.launch {
                                                    AppGraph.playlists.delete(playlist.id)
                                                    refreshPlaylists()
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
                                                enabled = !selectionMode,
                                                onSortChange = { savedSongSort = it.name },
                                                onShuffleAll = {
                                                    scope.launch {
                                                        playback.play(visibleCatalog.songs.shuffled(), 0)
                                                    }
                                                },
                                                onPlayAll = {
                                                    scope.launch {
                                                        playback.play(
                                                            SongSorting.sort(visibleCatalog.songs, songSort),
                                                            0,
                                                        )
                                                    }
                                                },
                                            )
                                            SectionedSongsList(
                                                songs = visibleCatalog.songs,
                                                sort = songSort,
                                                currentTrackId = currentTrack?.id,
                                                contentPadding = listPadding,
                                                selectedTrackIds = selectedTrackIds,
                                                onToggleSelection = { track ->
                                                    selectedTrackIds = if (track.id in selectedTrackIds) {
                                                        selectedTrackIds - track.id
                                                    } else {
                                                        selectedTrackIds + track.id
                                                    }
                                                },
                                                onStartSelection = { track ->
                                                    selectedTrackIds = setOf(track.id)
                                                },
                                                onPlay = { queue, index ->
                                                    scope.launch { playback.play(queue, index) }
                                                },
                                                onTrackMenu = { trackMenuTarget = it },
                                            )
                                        }

                                        ALBUMS_TAB -> LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(
                                                start = 8.dp,
                                                end = 8.dp,
                                                top = 8.dp,
                                                bottom = listPadding.calculateBottomPadding(),
                                            ),
                                        ) {
                                            items(visibleCatalog.albums, key = { it.key }) { album ->
                                                AlbumCard(album) {
                                                    scope.launch {
                                                        selectedCollection = album.toSelection()
                                                    }
                                                }
                                            }
                                        }

                                        ARTISTS_TAB -> LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listPadding,
                                        ) {
                                            items(visibleCatalog.artists, key = { it.name ?: "?" }) { artist ->
                                                GroupRow(
                                                    title = artist.name
                                                        ?: stringResource(Res.string.track_unknown_artist),
                                                    subtitle = artistSubtitle(
                                                        tracks = artist.tracks.size,
                                                        albums = artist.albumCount,
                                                    ),
                                                    artworkUri = artist.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                ) {
                                                    scope.launch {
                                                        selectedCollection = artist.toSelection()
                                                    }
                                                }
                                            }
                                        }

                                        GENRES_TAB -> LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listPadding,
                                        ) {
                                            items(visibleCatalog.genres, key = { it.name ?: "?" }) { genre ->
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
                                                ) {
                                                    scope.launch {
                                                        selectedCollection = genre.toSelection()
                                                    }
                                                }
                                            }
                                        }

                                        FOLDERS_TAB -> LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listPadding,
                                        ) {
                                            items(visibleCatalog.folders, key = { it.path }) { folder ->
                                                FolderRow(
                                                    folder = folder,
                                                    subtitle = pluralStringResource(
                                                        Res.plurals.count_tracks,
                                                        folder.tracks.size,
                                                        folder.tracks.size,
                                                    ),
                                                ) {
                                                    scope.launch {
                                                        selectedCollection = folder.toSelection()
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

                    selectedCollection?.let { selection ->
                        CollectionDetailScreen(
                            selection = selection,
                            currentTrackId = currentTrack?.id,
                            onPlayTrack = { index ->
                                scope.launch { playback.play(selection.tracks, index) }
                            },
                            onShuffle = {
                                scope.launch { playback.play(selection.tracks.shuffled(), 0) }
                            },
                            onTrackMenu = { trackMenuTarget = it },
                            onClose = { selectedCollection = null },
                            bottomInset = floatingPlayerInset,
                        )
                    }

                    if (showSearch) {
                        SearchScreen(
                            songs = catalog?.songs.orEmpty(),
                            currentTrackId = currentTrack?.id,
                            onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                            onTrackMenu = { trackMenuTarget = it },
                            onClose = { showSearch = false },
                            bottomInset = floatingPlayerInset,
                        )
                    }

                    if (selectionMode) {
                        SelectionActionBar(
                            canAct = selectedTracks.isNotEmpty(),
                            canShare = selectedTracks.isNotEmpty() &&
                                selectedTracks.all { it.audioUri != null },
                            onPlay = {
                                val selection = selectedTracks
                                selectedTrackIds = emptySet()
                                scope.launch { playback.play(selection, 0) }
                            },
                            onAdd = {
                                addToPlaylistSelection = selectedTracks
                            },
                            onShare = {
                                shareTracks(selectedTracks)
                                selectedTrackIds = emptySet()
                            },
                            onRemove = {
                                showSelectionRemoval = true
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    } else currentTrack?.let { current ->
                        MiniPlayerPill(
                            track = current,
                            accent = accent,
                            playback = playback,
                            sharedScope = sharedScope,
                            animatedScope = animatedScope,
                            onTogglePlayPause = { scope.launch { playback.togglePlayPause() } },
                            onPrevious = { scope.launch { playback.previous() } },
                            onNext = { scope.launch { playback.next() } },
                            onOpen = { showNowPlaying = true },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                        }
                    }
                }
            }
        }
            // One host above both AnimatedContent branches: collection/search surfaces and the
            // full player otherwise cover the Scaffold-owned host, making Undo technically exist
            // but impossible to see or tap.
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = if (!showNowPlaying && currentTrack != null) {
                            MINI_PLAYER_HEIGHT + navBottom
                        } else {
                            navBottom
                        },
                    ),
            )
        }

        trackMenuTarget?.let { target ->
            val trackExcluded = target.id in smartExclusionState.trackIds
            val artist = target.artist?.trim()?.takeIf(String::isNotEmpty)
            val artistExcluded = smartExclusionState.excludesArtist(artist)
            TrackActionsSheet(
                track = target,
                onPlay = { scope.launch { playback.play(listOf(target), 0) } },
                onPlayNext = { scope.launch { playback.playNext(target) } },
                onAddToQueue = { scope.launch { playback.addToQueue(target) } },
                onAddToPlaylist = { addToPlaylistSelection = listOf(target) },
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
                        library.hide(target.id)
                        tracks = library.tracks()
                        hasHiddenTracks = true
                        val collectionAfterHide = collectionBeforeHide?.let { selection ->
                            val remaining = selection.tracks.filterNot { it.id == target.id }
                            selection.copy(
                                subtitle = trackCountLabel(remaining.size),
                                tracks = remaining,
                            ).takeIf { remaining.isNotEmpty() }
                        }
                        selectedCollection = collectionAfterHide
                        val result = snackbar.showSnackbar(
                            message = getString(Res.string.snack_removed_from_latentjam),
                            actionLabel = getString(Res.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            library.unhide(target.id)
                            tracks = library.tracks()
                            hasHiddenTracks = library.hasHiddenTracks()
                            // Do not reopen a detail page the listener closed while the snackbar
                            // was visible; restore only the collection state this action changed.
                            if (selectedCollection === collectionAfterHide) {
                                selectedCollection = collectionBeforeHide
                            }
                        }
                    }
                },
                canDelete = target.audioUri != null &&
                    !target.id.value.startsWith("ios-media:"),
                onDelete = { deleteTarget = target },
                onDismiss = { trackMenuTarget = null },
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
                        ids.forEach { library.hide(it) }
                        tracks = library.tracks()
                        hasHiddenTracks = true
                        selectedTrackIds = emptySet()
                        val result = snackbar.showSnackbar(
                            message = getString(Res.string.snack_removed_from_latentjam),
                            actionLabel = getString(Res.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            ids.forEach { library.unhide(it) }
                            tracks = library.tracks()
                            hasHiddenTracks = library.hasHiddenTracks()
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
                        selectedCollection = CollectionSelection(
                            title = world?.title.orEmpty(),
                            subtitle = trackCountLabel(world?.tracks?.size ?: 0),
                            artworkUri = target.track.artworkUri,
                            tracks = world?.tracks.orEmpty(),
                        )
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
                        )
                        val byId = songs.associateBy { it.id }
                        playback.play(listOf(target.track) + queue.mapNotNull(byId::get), 0)
                    }
                },
                onDismiss = { worldTarget = null },
            )
        }

        addToPlaylistSelection?.let { selection ->
            AddToPlaylistSheet(
                tracks = selection,
                playlists = playlists,
                onAddTo = { playlist ->
                    scope.launch {
                        AppGraph.playlists.addTracks(playlist.id, selection.map { it.id })
                        refreshPlaylists()
                        selectedTrackIds = emptySet()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_added_to_playlist, playlist.name),
                        )
                    }
                },
                onCreateNew = {
                    // Remember the selection so the new playlist starts with every chosen track.
                    pendingPlaylistTracks = selection
                    showCreatePlaylist = true
                },
                onDismiss = { addToPlaylistSelection = null },
            )
        }

        if (showCreatePlaylist) {
            val tracksToSeed = pendingPlaylistTracks
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_new),
                confirmLabel = stringResource(Res.string.action_create),
                onConfirm = { name ->
                    showCreatePlaylist = false
                    pendingPlaylistTracks = emptyList()
                    scope.launch {
                        val created = AppGraph.playlists.create(name)
                        if (tracksToSeed.isNotEmpty()) {
                            AppGraph.playlists.addTracks(created.id, tracksToSeed.map { it.id })
                        }
                        refreshPlaylists()
                        selectedTrackIds = emptySet()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_playlist_created, created.name),
                        )
                    }
                },
                onDismiss = {
                    showCreatePlaylist = false
                    pendingPlaylistTracks = emptyList()
                },
            )
        }

        renameTarget?.let { target ->
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_rename_title),
                initialName = target.name,
                confirmLabel = stringResource(Res.string.action_rename),
                onConfirm = { name ->
                    renameTarget = null
                    scope.launch {
                        AppGraph.playlists.rename(target.id, name)
                        refreshPlaylists()
                    }
                },
                onDismiss = { renameTarget = null },
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
                    selectedTrackIds = emptySet()
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
                onSaved = { scope.launch { tracks = library.tracks() } },
                onDismiss = { infoTarget = null },
            )
        }

        if (showSettings) {
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
                                tracks = library.tracks()
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
                    invalidateSmartRecommendationCaches()
                    retryAutomaticIndexing()
                },
                onHideTrack = { target ->
                    library.hide(target.id)
                    tracks = library.tracks()
                    hasHiddenTracks = true
                    selectedCollection = selectedCollection?.let { selection ->
                        val remaining = selection.tracks.filterNot { it.id == target.id }
                        selection.copy(
                            subtitle = trackCountLabel(remaining.size),
                            tracks = remaining,
                        ).takeIf { remaining.isNotEmpty() }
                    }
                    invalidateSmartRecommendationCaches()
                },
                onBackupRestored = {
                    tracks = library.tracks()
                    hasHiddenTracks = library.hasHiddenTracks()
                    selectedCollection = null
                    metadataVectorsReady = false
                    audioVectorsReady = false
                    worlds = emptyList()
                    worldLibraryIds = emptyList()
                    personalizationRevision += 1
                    invalidateSmartRecommendationCaches()
                    refreshPlaylists()
                },
                onClearListeningHistory = {
                    AppGraph.history.clear()
                    forYou = ForYouPage()
                    builtWorlds = null
                    builtForYouLibraryIds = null
                    personalizationRevision += 1
                    refreshPlaylists()
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

/** Stable pager indices for the fixed browse destinations. */
private const val FOR_YOU_TAB = 0
private const val MAP_TAB = 1
private const val PLAYLISTS_TAB = 2
private const val TRACKS_TAB = 3
private const val ALBUMS_TAB = 4
private const val ARTISTS_TAB = 5
private const val GENRES_TAB = 6
private const val FOLDERS_TAB = 7

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
private const val RECENT_EVENTS_FOR_YOU = 500

/** Duration of the mini-player ↔ now-playing morph. */
private const val MORPH_MILLIS = 340

// The full player owns precise seeking; the browse pill updates this glanceable hint less often so
// playback does not invalidate the browse shell every 500 ms.
private const val MINI_PLAYER_PROGRESS_STEP_MS = 5_000L

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

private suspend fun AlbumGroup.toSelection() = CollectionSelection(
    title = title ?: getString(Res.string.track_unknown_album),
    subtitle = artist,
    artworkUri = artworkUri,
    tracks = tracks,
)

private suspend fun ArtistGroup.toSelection() = CollectionSelection(
    title = name ?: getString(Res.string.track_unknown_artist),
    subtitle = getPluralString(Res.plurals.count_tracks, tracks.size, tracks.size) +
        SUBTITLE_SEPARATOR +
        getPluralString(Res.plurals.count_albums, albumCount, albumCount),
    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
    tracks = tracks,
)

private suspend fun GenreGroup.toSelection() = CollectionSelection(
    title = name ?: getString(Res.string.track_unknown_genre),
    subtitle = trackCountLabel(tracks.size),
    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
    tracks = tracks,
)

private suspend fun FolderGroup.toSelection() = CollectionSelection(
    title = name,
    subtitle = path + SUBTITLE_SEPARATOR + trackCountLabel(tracks.size),
    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
    tracks = tracks,
)

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

/** Contextual app bar shown while the Tracks page owns a multi-selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopAppBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(stringResource(Res.string.selection_count, count))
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
            val label = stringResource(
                if (allSelected) Res.string.action_deselect_all else Res.string.action_select_all,
            )
            IconButton(onClick = onToggleAll) {
                Icon(
                    imageVector = if (allSelected) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = label,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** Thumb-zone actions for the current track selection; replaces the mini-player temporarily. */
@Composable
private fun SelectionActionBar(
    canAct: Boolean,
    canShare: Boolean = canAct,
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
            icon = Icons.Rounded.DeleteOutline,
            label = stringResource(Res.string.action_delete),
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
    Box {
        IconButton(
            onClick = { open = true },
            modifier = with(sharedScope) {
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
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = with(sharedScope) {
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
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                SongSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        onClick = {
                            sortMenuOpen = false
                            onSortChange(option)
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
private fun SongSort.label(): String = stringResource(
    when (this) {
        SongSort.TITLE -> Res.string.info_title
        SongSort.ARTIST -> Res.string.info_artist
        SongSort.RECENT -> Res.string.sort_recently_added
    },
)

@Composable
private fun AlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
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
            if (album.artworkUri != null) {
                AsyncImage(
                    model = album.artworkUri,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
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

@Composable
private fun GroupRow(title: String, subtitle: String, artworkUri: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(uri = artworkUri, size = 48.dp, cornerRadius = 24.dp)
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
private fun FolderRow(folder: FolderGroup, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
    playback: PlaybackController,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPlaying by remember(playback) {
        playback.state.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsState(playback.state.value.isPlaying)
    val playerShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    CompositionLocalProvider(LocalContentColor provides accent.onContainer) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    with(sharedScope) {
                        Modifier.sharedBounds(
                            rememberSharedContentState(PLAYER_SURFACE_KEY),
                            animatedScope,
                        )
                    },
                )
                // Material Surface still creates a separate rendered surface at zero elevation. On
                // some Android renderers its boundary is visible as a full-width grey hairline.
                // A shaped background paints the same pill without that extra surface boundary.
                .background(accent.container, playerShape)
                .clickable(onClick = onOpen),
        ) {
            Box(modifier = Modifier.navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Artwork(
                        uri = track.artworkUri,
                        size = 44.dp,
                        cornerRadius = 22.dp,
                        modifier = with(sharedScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(ARTWORK_KEY),
                                animatedScope,
                            )
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title ?: stringResource(Res.string.track_untitled),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist ?: stringResource(Res.string.track_unknown_artist),
                            style = MaterialTheme.typography.bodySmall,
                            color = accent.onContainer.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onPrevious) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(Res.string.action_previous),
                        )
                    }
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) {
                                stringResource(Res.string.action_pause)
                            } else {
                                stringResource(Res.string.action_play)
                            },
                        )
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
    val progress by remember(playback) {
        playback.state.map { now ->
            if (now.durationMs > 0) {
                val coarsePosition =
                    now.positionMs / MINI_PLAYER_PROGRESS_STEP_MS * MINI_PLAYER_PROGRESS_STEP_MS
                (coarsePosition.toFloat() / now.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }.distinctUntilChanged()
    }.collectAsState(
        playback.state.value.let { now ->
            if (now.durationMs > 0) {
                val coarsePosition =
                    now.positionMs / MINI_PLAYER_PROGRESS_STEP_MS * MINI_PLAYER_PROGRESS_STEP_MS
                (coarsePosition.toFloat() / now.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        },
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = accent.onContainer.copy(alpha = 0.9f),
        trackColor = Color.Transparent,
        drawStopIndicator = {},
    )
}

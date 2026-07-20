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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_create
import io.github.nikitasud.latentjam.app.generated.resources.action_next
import io.github.nikitasud.latentjam.app.generated.resources.action_pause
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_play_all
import io.github.nikitasud.latentjam.app.generated.resources.action_previous
import io.github.nikitasud.latentjam.app.generated.resources.action_rename
import io.github.nikitasud.latentjam.app.generated.resources.action_rescan_library
import io.github.nikitasud.latentjam.app.generated.resources.action_restore_hidden_tracks
import io.github.nikitasud.latentjam.app.generated.resources.action_undo
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle_all
import io.github.nikitasud.latentjam.app.generated.resources.cd_more_options
import io.github.nikitasud.latentjam.app.generated.resources.cd_search_library
import io.github.nikitasud.latentjam.app.generated.resources.count_albums
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_engine
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_engine_initialize
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_engine_ready
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_engine_retry
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_engine_uninitialized
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_history_empty
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_history_listens
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_history_top
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_index_all
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_index_batch
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_indexed_report
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_indexing
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_library
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_rescan
import io.github.nikitasud.latentjam.app.generated.resources.diagnostics_title
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_backend
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_model_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.engine_error_not_indexed
import io.github.nikitasud.latentjam.app.generated.resources.foryou_mix_discovery
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
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_created
import io.github.nikitasud.latentjam.app.generated.resources.snack_playlist_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_track_deleted
import io.github.nikitasud.latentjam.app.generated.resources.snack_removed_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.snack_hidden_tracks_restored
import io.github.nikitasud.latentjam.app.generated.resources.sort_recently_added
import io.github.nikitasud.latentjam.app.generated.resources.tab_albums
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.tab_for_you
import io.github.nikitasud.latentjam.app.generated.resources.tab_folders
import io.github.nikitasud.latentjam.app.generated.resources.tab_genres
import io.github.nikitasud.latentjam.app.generated.resources.tab_playlists
import io.github.nikitasud.latentjam.app.generated.resources.tab_tracks
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_album
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_genre
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
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
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorlds
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
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
    val themeMode by AppGraph.settings.themeMode.collectAsState()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = latentJamColorScheme(darkTheme = darkTheme)) {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        // The pager owns the section position; everything else reads it. One source of truth means
        // the strip and the content can never disagree about where a half-finished swipe is.
        val pagerState = rememberPagerState(initialPage = TRACKS_TAB) { BROWSE_TABS.size }
        val selectedTab = pagerState.currentPage
        var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
        var playCounts by remember { mutableStateOf<Map<TrackId, Int>>(emptyMap()) }
        var lastPlayedAt by remember { mutableStateOf<Map<TrackId, Long>>(emptyMap()) }
        var showCreatePlaylist by remember { mutableStateOf(false) }
        var renameTarget by remember { mutableStateOf<Playlist?>(null) }
        var addToPlaylistTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var pendingPlaylistTrack by remember { mutableStateOf<TrackDescriptor?>(null) }
        var songSort by remember { mutableStateOf(SongSort.TITLE) }
        var selectedCollection by remember { mutableStateOf<CollectionSelection?>(null) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var infoTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var showNowPlaying by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var trackMenuTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var deleteTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var hasHiddenTracks by remember { mutableStateOf(false) }
        var indexSummary by remember { mutableStateOf<String?>(null) }
        var indexFailureDetails by remember { mutableStateOf<List<String>>(emptyList()) }
        var historySummary by remember { mutableStateOf<String?>(null) }
        val now by playback.state.collectAsState()
        val accent = rememberTrackAccent(now.track)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The lists run to the bottom edge of an edge-to-edge window, so the navigation bar is
        // theirs to clear — nothing below them does it. The pill adds its own height on top of
        // that when it is up; it sits ON the content rather than beside it, so the two add.
        val listPadding = PaddingValues(
            bottom = navBottom + if (now.track != null) MINI_PLAYER_HEIGHT else 12.dp,
        )
        // Screens that already inset themselves against the navigation bar only need the pill's
        // own height on top.
        val floatingPlayerInset = if (now.track != null) MINI_PLAYER_HEIGHT else 0.dp

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

        // Arm SMART in the background as soon as the library is known: load the models, restore the
        // persisted index, and backfill any missing metadata-text vectors. All idempotent. Doing it
        // here rather than on first press means the SMART button is instant when it is finally
        // tapped, instead of stalling on tens of MB of model loading.
        var forYou by remember { mutableStateOf(ForYouPage()) }
        var worlds by remember { mutableStateOf<List<LibraryWorld>>(emptyList()) }
        var builtWorlds by remember { mutableStateOf<List<LibraryWorld>?>(null) }
        var metadataVectorsReady by remember { mutableStateOf(false) }
        var worldTarget by remember { mutableStateOf<ForYouCard?>(null) }
        val discoveryMixLabel = stringResource(Res.string.foryou_mix_discovery)

        LaunchedEffect(tracks, selectedTab == FOR_YOU_TAB, worlds, discoveryMixLabel) {
            val loaded = tracks ?: return@LaunchedEffect
            if (selectedTab != FOR_YOU_TAB) return@LaunchedEffect
            // Built once, and again only when the worlds arrive — the text index fills in the
            // background, so on a cold library they are simply not ready at first visit. Never
            // rebuilt on a mere return to the tab: a page that regroups itself while being read is
            // indistinguishable from a broken one.
            if (!forYou.isEmpty && builtWorlds == worlds) return@LaunchedEffect
            builtWorlds = worlds
            forYou = ForYouBuilder.build(
                library = loaded,
                stats = AppGraph.history.stats(),
                recentEvents = AppGraph.history.recentEvents(RECENT_EVENTS_FOR_YOU),
                nowMs = epochMillis(),
                excluded = setOfNotNull(now.track?.id),
                playlists = playlists,
                worlds = worlds,
                discoveryMixLabel = discoveryMixLabel,
            )
        }

        LaunchedEffect(tracks) {
            val loaded = tracks ?: return@LaunchedEffect
            // Search, playlists, and collection screens often start playback from a filtered
            // subset. SMART is a library journey, so keep its candidate universe independent of
            // whichever small list happened to contain the track the listener tapped.
            playback.setSmartLibrary(loaded)
            AppGraph.appScope.launch {
                engine.initialize()
                var added = 0
                loaded.chunked(INDEX_CHUNK_SIZE).forEach { chunk ->
                    added += engine.ensureMetadataVectors(chunk)
                }
                println("SMART: ready (${engine.state.value}); encoded $added metadata vectors")
                metadataVectorsReady = true

                // Build the acoustic index progressively on a background dispatcher from the very
                // first launch. Each tiny batch is persisted and releases the engine lock, so a
                // SMART press can use the portion that is ready; playback itself never waits for
                // the whole library and metadata supplies an honest cold-start path.
                val failures = LinkedHashMap<TrackId, EngineError>()
                loaded.chunked(INDEX_CHUNK_SIZE).forEach { chunk ->
                    failures.putAll(engine.indexLibrary(chunk).errors)
                }
                indexFailureDetails = failures.map { (id, error) ->
                    indexFailureLine(loaded.firstOrNull { it.id == id }, id, error)
                }
                println("SMART: progressive local index complete (${engine.state.value})")
            }
        }

        // The regions the library falls into. Clustered over the METADATA-TEXT index rather than
        // the audio one: audio embeddings only exist after the listener goes looking for the
        // button that makes them, while text vectors are encoded for everything at first launch —
        // so clustering the other space would leave this row missing for almost everyone.
        LaunchedEffect(tracks, metadataVectorsReady) {
            val loaded = tracks ?: return@LaunchedEffect
            if (!metadataVectorsReady) return@LaunchedEffect
            val vectors = engine.metadataVectors()
            if (vectors.isEmpty()) return@LaunchedEffect
            worlds = withContext(Dispatchers.Default) {
                val started = TimeSource.Monotonic.markNow()
                LibraryWorlds.discover(loaded, vectors, TextEncoder.TEXT_DIM).also { mixes ->
                    println(
                        "SMART: built ${mixes.size} local mixes in " +
                            "${started.elapsedNow().inWholeMilliseconds} ms",
                    )
                }
            }
        }
        val catalog = remember(tracks) { tracks?.let { LibraryCatalog.build(it) } }
        val tracksById = remember(catalog) { catalog?.songs?.associateBy { it.id }.orEmpty() }

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

        val autoPlaylists = remember(catalog, playCounts, lastPlayedAt) {
            catalog?.let { AutoPlaylists.build(it.songs, playCounts, lastPlayedAt) }.orEmpty()
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

        LaunchedEffect(showDiagnostics) {
            if (showDiagnostics) {
                val stats = AppGraph.history.stats()
                val listens = stats.values.sumOf { it.plays }
                val top = stats.maxByOrNull { it.value.plays }
                val counted = getPluralString(Res.plurals.diagnostics_history_listens, listens, listens)
                historySummary = when {
                    listens == 0 -> getString(Res.string.diagnostics_history_empty)
                    top == null -> counted
                    else -> {
                        val title = catalog?.songs?.firstOrNull { it.id == top.key }?.title
                            ?: top.key.value
                        // Two sentences rather than one interpolated line: the listen count needs a
                        // plural form of its own, and nesting it inside another sentence would pin
                        // the word order of every translation to English's.
                        counted + " " +
                            getString(Res.string.diagnostics_history_top, title, top.value.plays)
                    }
                }
            }
        }

        fun indexTracks(selection: List<TrackDescriptor>) {
            // App-lifetime scope: indexing continues if the dialog closes or
            // the screen recomposes. Chunked so the engine persists (and the
            // Ready(indexedCount) state advances) as it goes — resumable at
            // chunk granularity after process death.
            AppGraph.appScope.launch {
                var indexed = 0
                var skipped = 0
                var failed = 0
                val failures = LinkedHashMap<TrackId, EngineError>()
                selection.chunked(INDEX_CHUNK_SIZE).forEach { chunk ->
                    val report = engine.indexLibrary(chunk)
                    indexed += report.indexed
                    skipped += report.skipped
                    failed += report.failed
                    failures.putAll(report.errors)
                    val done = indexed + skipped + failed
                    indexSummary = getString(
                        Res.string.diagnostics_indexing,
                        done,
                        selection.size,
                        indexed,
                        skipped,
                        failed,
                    )
                }
                indexSummary =
                    getString(Res.string.diagnostics_indexed_report, indexed, skipped, failed)
                indexFailureDetails = failures.map { (id, error) ->
                    indexFailureLine(selection.firstOrNull { it.id == id }, id, error)
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
                    fadeIn(tween(MORPH_MILLIS)) togetherWith fadeOut(tween(MORPH_MILLIS))
                },
                label = "player-morph",
            ) { expanded ->
                if (expanded) {
                    NowPlayingScreen(
                        playback = playback,
                        accent = accent,
                        sharedScope = sharedScope,
                        animatedScope = this@AnimatedContent,
                        onTrackMenu = { track -> trackMenuTarget = track },
                        onClose = { showNowPlaying = false },
                    )
                } else {
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
                                TopAppBar(
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
                                        // Same glyph, same slot as the player's
                                        // overflow, and shared with it — so it
                                        // holds still through the morph.
                                        OverflowButton(
                                            sharedScope = sharedScope,
                                            animatedScope = animatedScope,
                                        ) { dismiss ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(stringResource(Res.string.action_rescan_library))
                                                },
                                                onClick = {
                                                    dismiss()
                                                    scope.launch { tracks = library.tracks() }
                                                },
                                            )
                                            if (audioImportAvailable) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(
                                                                Res.string.library_import_action,
                                                            ),
                                                        )
                                                    },
                                                    onClick = {
                                                        dismiss()
                                                        importAudio()
                                                    },
                                                )
                                            }
                                            if (hasHiddenTracks) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(
                                                                Res.string.action_restore_hidden_tracks,
                                                            ),
                                                        )
                                                    },
                                                    onClick = {
                                                        dismiss()
                                                        scope.launch {
                                                            library.unhideAll()
                                                            tracks = library.tracks()
                                                            hasHiddenTracks = false
                                                            snackbar.showSnackbar(
                                                                getString(
                                                                    Res.string.snack_hidden_tracks_restored,
                                                                ),
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(Res.string.settings_title)) },
                                                onClick = {
                                                    dismiss()
                                                    showSettings = true
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(Res.string.diagnostics_title)) },
                                                onClick = {
                                                    dismiss()
                                                    showDiagnostics = true
                                                },
                                            )
                                        }
                                    },
                                )
                                BrowseCarousel(pagerState) { tab ->
                                    scope.launch { pagerState.animateScrollToPage(tab) }
                                }
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbar) },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) { padding ->
                        // One full-bleed surface: it runs to the bottom edge so the
                        // mini-player floats ON the content rather than sitting on a
                        // separate band of background.
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding()),
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                when {
                                    catalog == null -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }

                                    catalog.songs.isEmpty() -> Column(
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
                                        // Neighbours stay composed, so a swipe reveals a built list
                                        // instead of an empty page filling in mid-gesture.
                                        beyondViewportPageCount = 1,
                                        key = { page -> page },
                                    ) { tab ->
                                        when (tab) {
                                        FOR_YOU_TAB -> ForYouTab(
                                            page = forYou,
                                            contentPadding = listPadding,
                                            onPlay = { list, index ->
                                                scope.launch { playback.play(list, index) }
                                            },
                                            onPlayHero = { hero ->
                                                scope.launch {
                                                    // Personal signal picked the seed; SMART decides
                                                    // what follows it.
                                                    val queue = engine.smartQueue(
                                                        hero.track,
                                                        catalog.songs,
                                                        SMART_HERO_LENGTH,
                                                        smartHistoryFor(AppGraph.history, hero.track),
                                                    )
                                                    val byId = catalog.songs.associateBy { it.id }
                                                    val tail = queue.mapNotNull(byId::get)
                                                    playback.play(listOf(hero.track) + tail, 0)
                                                    hero.resumeAtMs?.let { playback.seekTo(it) }
                                                }
                                            },
                                            onTrackMenu = { trackMenuTarget = it },
                                            onOpenWorld = { worldTarget = it },
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
                                                onSortChange = { songSort = it },
                                                onShuffleAll = {
                                                    scope.launch {
                                                        playback.play(catalog.songs.shuffled(), 0)
                                                    }
                                                },
                                                onPlayAll = {
                                                    scope.launch {
                                                        playback.play(
                                                            SongSorting.sort(catalog.songs, songSort),
                                                            0,
                                                        )
                                                    }
                                                },
                                            )
                                            SectionedSongsList(
                                                songs = catalog.songs,
                                                sort = songSort,
                                                currentTrackId = now.track?.id,
                                                contentPadding = listPadding,
                                                onPlay = { queue, index ->
                                                    scope.launch { playback.play(queue, index) }
                                                },
                                                onTrackMenu = { trackMenuTarget = it },
                                            )
                                        }

                                        3 -> LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(
                                                start = 8.dp,
                                                end = 8.dp,
                                                top = 8.dp,
                                                bottom = listPadding.calculateBottomPadding(),
                                            ),
                                        ) {
                                            items(catalog.albums, key = { it.key }) { album ->
                                                AlbumCard(album) {
                                                    scope.launch {
                                                        selectedCollection = album.toSelection()
                                                    }
                                                }
                                            }
                                        }

                                        4 -> LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listPadding,
                                        ) {
                                            items(catalog.artists, key = { it.name ?: "?" }) { artist ->
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
                                            items(catalog.genres, key = { it.name ?: "?" }) { genre ->
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
                                            items(catalog.folders, key = { it.path }) { folder ->
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
                            currentTrackId = now.track?.id,
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
                            currentTrackId = now.track?.id,
                            onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                            onTrackMenu = { trackMenuTarget = it },
                            onClose = { showSearch = false },
                            bottomInset = floatingPlayerInset,
                        )
                    }

                    now.track?.let { current ->
                        MiniPlayerPill(
                            track = current,
                            accent = accent,
                            isPlaying = now.isPlaying,
                            progress = if (now.durationMs > 0) {
                                (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
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

        trackMenuTarget?.let { target ->
            TrackActionsSheet(
                track = target,
                onPlay = { scope.launch { playback.play(listOf(target), 0) } },
                onPlayNext = { scope.launch { playback.playNext(target) } },
                onAddToQueue = { scope.launch { playback.addToQueue(target) } },
                onAddToPlaylist = { addToPlaylistTarget = target },
                onGoToAlbum = { showAlbumOf(target) },
                onGoToArtist = { showArtistOf(target) },
                onInfo = { infoTarget = target },
                onHide = {
                    scope.launch {
                        val collectionBeforeHide = selectedCollection
                        library.hide(target.id)
                        tracks = library.tracks()
                        hasHiddenTracks = true
                        val collectionAfterHide = collectionBeforeHide?.let { selection ->
                            val remaining = selection.tracks.filterNot { it.id == target.id }
                            selection.copy(tracks = remaining).takeIf { remaining.isNotEmpty() }
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
                            songs,
                            SMART_HERO_LENGTH,
                            smartHistoryFor(AppGraph.history, target.track),
                        )
                        val byId = songs.associateBy { it.id }
                        playback.play(listOf(target.track) + queue.mapNotNull(byId::get), 0)
                    }
                },
                onDismiss = { worldTarget = null },
            )
        }

        addToPlaylistTarget?.let { target ->
            AddToPlaylistSheet(
                track = target,
                playlists = playlists,
                onAddTo = { playlist ->
                    scope.launch {
                        AppGraph.playlists.addTracks(playlist.id, listOf(target.id))
                        refreshPlaylists()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_added_to_playlist, playlist.name),
                        )
                    }
                },
                onCreateNew = {
                    // Remember the track so the new playlist starts with it.
                    pendingPlaylistTrack = target
                    showCreatePlaylist = true
                },
                onDismiss = { addToPlaylistTarget = null },
            )
        }

        if (showCreatePlaylist) {
            val trackToSeed = pendingPlaylistTrack
            PlaylistNameDialog(
                title = stringResource(Res.string.playlist_new),
                confirmLabel = stringResource(Res.string.action_create),
                onConfirm = { name ->
                    showCreatePlaylist = false
                    pendingPlaylistTrack = null
                    scope.launch {
                        val created = AppGraph.playlists.create(name)
                        if (trackToSeed != null) {
                            AppGraph.playlists.addTracks(created.id, listOf(trackToSeed.id))
                        }
                        refreshPlaylists()
                        snackbar.showSnackbar(
                            getString(Res.string.snack_playlist_created, created.name),
                        )
                    }
                },
                onDismiss = {
                    showCreatePlaylist = false
                    pendingPlaylistTrack = null
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
                    deleteTrack(target)
                },
                onDismiss = { deleteTarget = null },
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
                settings = AppGraph.settings,
                equalizer = AppGraph.equalizer,
                engine = engine,
                tracks = tracks.orEmpty(),
                onClose = { showSettings = false },
            )
        }

        if (showDiagnostics) {
            DiagnosticsDialog(
                engine = engine,
                trackCount = tracks?.size,
                indexSummary = indexSummary,
                indexFailureDetails = indexFailureDetails,
                historySummary = historySummary,
                onRescan = { scope.launch { tracks = library.tracks() } },
                onIndexSample = {
                    tracks?.let { loaded -> indexTracks(loaded.take(DIAGNOSTICS_SAMPLE)) }
                },
                onIndexAll = { tracks?.let(::indexTracks) },
                onDismiss = { showDiagnostics = false },
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
    Res.string.tab_playlists,
    Res.string.tab_tracks,
    Res.string.tab_albums,
    Res.string.tab_artists,
    Res.string.tab_genres,
    Res.string.tab_folders,
)

/** Playlists lead the carousel, but the app opens on Tracks. */
private const val FOR_YOU_TAB = 0
private const val PLAYLISTS_TAB = 1
private const val TRACKS_TAB = 2
private const val GENRES_TAB = 5
private const val FOLDERS_TAB = 6

/** Persist-and-report granularity for library indexing. */
private const val RECENT_EVENTS_FOR_YOU = 500

/** Long enough to be a sitting, short enough to still reflect the seed. */
private const val SMART_HERO_LENGTH = 20

private const val INDEX_CHUNK_SIZE = 8

/** How many tracks the diagnostics dialog indexes as a sample. */
private const val DIAGNOSTICS_SAMPLE = 24

/** Keep the technical failure list readable inside the compact diagnostics dialog. */
private const val DIAGNOSTICS_FAILURE_LIMIT = 5

/** Duration of the mini-player ↔ now-playing morph. */
private const val MORPH_MILLIS = 340

/**
 * The pill's own height, above whatever navigation-bar inset it is sitting on.
 *
 * Lists add this to the inset so their last row clears the pill instead of hiding under it. A
 * constant rather than a measurement: the pill is a fixed piece of furniture, and measuring it
 * would make every list's padding depend on a layout pass it does not otherwise wait for.
 */
private val MINI_PLAYER_HEIGHT = 76.dp

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
private fun BrowseCarousel(pagerState: PagerState, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val sidePadding = (maxWidth / 2 - 56.dp).coerceAtLeast(0.dp)
        val falloffPx = with(density) { 140.dp.toPx() }

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

        // Track the pager CONTINUOUSLY rather than jumping once a swipe settles: the strip is the
        // same gesture seen from a different angle, so it should move with the finger. Between two
        // labels the centre is interpolated, because labels are only as wide as their text and the
        // step between them is uneven. The pager is the sole scroll owner; this avoids two
        // independent horizontal gestures racing to select different pages.
        LaunchedEffect(pagerState, sidePadding) {
            snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
                .collect { position ->
                    val lower = floor(position).toInt()
                    val upper = ceil(position).toInt()
                    val lowerOffset = offsetFromCentre(lower)
                    val upperOffset = if (upper == lower) lowerOffset else offsetFromCentre(upper)
                    val delta = when {
                        lowerOffset != null && upperOffset != null ->
                            lowerOffset + (upperOffset - lowerOffset) * (position - lower)
                        lowerOffset != null -> lowerOffset
                        upperOffset != null -> upperOffset
                        else -> {
                            centerTab(position.roundToInt().coerceIn(BROWSE_TABS.indices))
                            null
                        }
                    }
                    // Driven, not animated: the pager's own motion is the animation.
                    if (delta != null && abs(delta) > 0.5f) listState.scrollBy(delta)
                }
        }

        // Continuous pager updates can be conflated during a fling. SettledPage is an independent
        // final-state signal, so this makes exact centring an invariant even after interrupted,
        // reversed, or multi-page gestures.
        LaunchedEffect(pagerState.settledPage, sidePadding) {
            centerTab(pagerState.settledPage)
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            itemsIndexed(BROWSE_TABS) { index, title ->
                val distance = (offsetFromCentre(index)?.let { abs(it) / falloffPx } ?: 1f)
                    .coerceIn(0f, 1f)
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .selectable(
                            selected = index == pagerState.settledPage,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(horizontal = 14.dp)
                        .graphicsLayer {
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

/** Sort selector plus shuffle-all / play-all, above the songs list. */
@Composable
private fun SongsHeader(
    sort: SongSort,
    onSortChange: (SongSort) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TextButton(onClick = { sortMenuOpen = true }) {
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
        FilledIconButton(onClick = onPlayAll, modifier = Modifier.padding(start = 8.dp)) {
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
    isPlaying: Boolean,
    progress: Float,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
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
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = accent.container,
        contentColor = accent.onContainer,
        shadowElevation = 8.dp,
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
                        modifier = Modifier.basicMarquee(),
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
            // Hairline progress along the pill's inner edge — status without
            // giving the bar a band of its own.
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape),
                color = accent.onContainer.copy(alpha = 0.9f),
                trackColor = accent.onContainer.copy(alpha = 0.2f),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    engine: SimilarityEngine,
    trackCount: Int?,
    indexSummary: String?,
    indexFailureDetails: List<String>,
    historySummary: String?,
    onRescan: () -> Unit,
    onIndexSample: () -> Unit,
    onIndexAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.diagnostics_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EngineCard(engine)
                // An ellipsis stands in until the scan reports back; once it does, the count is a
                // plural of its own and is formatted as one before being placed in the line.
                val counted = trackCount
                    ?.let { pluralStringResource(Res.plurals.count_tracks, it, it) }
                    ?: "…"
                Text(stringResource(Res.string.diagnostics_library, counted))
                historySummary?.let { Text(it) }
                indexSummary?.let { Text(it) }
                if (indexFailureDetails.isNotEmpty()) {
                    Text(
                        indexFailureDetails.take(DIAGNOSTICS_FAILURE_LIMIT).joinToString("\n") +
                            if (indexFailureDetails.size > DIAGNOSTICS_FAILURE_LIMIT) {
                                "\n+${indexFailureDetails.size - DIAGNOSTICS_FAILURE_LIMIT}"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onIndexSample) {
                        Text(stringResource(Res.string.diagnostics_index_batch, DIAGNOSTICS_SAMPLE))
                    }
                    TextButton(onClick = onIndexAll) {
                        Text(stringResource(Res.string.diagnostics_index_all))
                    }
                    TextButton(onClick = onRescan) {
                        Text(stringResource(Res.string.diagnostics_rescan))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
        },
    )
}

private fun indexFailureLine(
    track: TrackDescriptor?,
    id: TrackId,
    error: EngineError,
): String {
    val label = track?.title?.takeIf(String::isNotBlank) ?: id.value
    val reason = when (error) {
        is EngineError.BackendFailure -> error.message
        EngineError.ModelUnavailable -> "model unavailable"
        EngineError.NotIndexed -> "not indexed"
    }
    return "$label — $reason"
}

@Composable
private fun EngineCard(engine: SimilarityEngine) {
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.diagnostics_engine),
                style = MaterialTheme.typography.titleMedium,
            )
            when (val current = state) {
                EngineState.Uninitialized ->
                    Text(stringResource(Res.string.diagnostics_engine_uninitialized))
                EngineState.Initializing -> CircularProgressIndicator()
                is EngineState.Ready -> Text(
                    pluralStringResource(
                        Res.plurals.diagnostics_engine_ready,
                        current.indexedCount,
                        current.indexedCount,
                    ),
                )
                is EngineState.Failed -> Text(current.error.toUserMessage())
            }
            Button(
                onClick = { scope.launch { engine.initialize() } },
                enabled = state !is EngineState.Initializing,
            ) {
                Text(
                    stringResource(
                        if (state is EngineState.Failed) {
                            Res.string.diagnostics_engine_retry
                        } else {
                            Res.string.diagnostics_engine_initialize
                        },
                    ),
                )
            }
        }
    }
}

/** Friendly, non-technical wording for the typed engine errors. */
@Composable
private fun EngineError.toUserMessage(): String = when (this) {
    EngineError.ModelUnavailable ->
        stringResource(Res.string.engine_error_model_unavailable)
    EngineError.NotIndexed ->
        stringResource(Res.string.engine_error_not_indexed)
    is EngineError.BackendFailure ->
        stringResource(Res.string.engine_error_backend, message)
}

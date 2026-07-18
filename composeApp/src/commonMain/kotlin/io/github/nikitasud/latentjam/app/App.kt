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
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.AutoPlaylists
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.ceil
import kotlinx.coroutines.launch

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
        var indexSummary by remember { mutableStateOf<String?>(null) }
        var historySummary by remember { mutableStateOf<String?>(null) }
        val now by playback.state.collectAsState()
        val accent = rememberTrackAccent(now.track)

        LaunchedEffect(Unit) { tracks = library.tracks() }

        // Arm SMART in the background as soon as the library is known: load the models, restore the
        // persisted index, and backfill any missing metadata-text vectors. All idempotent. Doing it
        // here rather than on first press means the SMART button is instant when it is finally
        // tapped, instead of stalling on tens of MB of model loading.
        var forYou by remember { mutableStateOf(ForYouPage()) }
        LaunchedEffect(tracks, selectedTab == FOR_YOU_TAB) {
            val loaded = tracks ?: return@LaunchedEffect
            if (selectedTab != FOR_YOU_TAB || !forYou.isEmpty) return@LaunchedEffect
            forYou = ForYouBuilder.build(
                library = loaded,
                stats = AppGraph.history.stats(),
                recentEvents = AppGraph.history.recentEvents(RECENT_EVENTS_FOR_YOU),
                nowMs = epochMillis(),
                excluded = setOfNotNull(now.track?.id),
            )
        }

        LaunchedEffect(tracks) {
            val loaded = tracks ?: return@LaunchedEffect
            AppGraph.appScope.launch {
                engine.initialize()
                val added = engine.ensureMetadataVectors(loaded)
                println("SMART: ready (${engine.state.value}); encoded $added metadata vectors")
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
                snackbar.showSnackbar("Track deleted")
            }
        }

        LaunchedEffect(showDiagnostics) {
            if (showDiagnostics) {
                val stats = AppGraph.history.stats()
                val listens = stats.values.sumOf { it.plays }
                val top = stats.maxByOrNull { it.value.plays }
                val topTitle = top?.let { entry ->
                    catalog?.songs?.firstOrNull { it.id == entry.key }?.title ?: entry.key.value
                }
                historySummary = when {
                    listens == 0 -> "History: no listens recorded yet."
                    top == null -> "History: $listens listens."
                    else -> "History: $listens listens; top: $topTitle (${top.value.plays}×)."
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
                selection.chunked(INDEX_CHUNK_SIZE).forEach { chunk ->
                    val report = engine.indexLibrary(chunk)
                    indexed += report.indexed
                    skipped += report.skipped
                    failed += report.failed
                    val done = indexed + skipped + failed
                    indexSummary =
                        "Indexing… $done/${selection.size} (ok $indexed, skip $skipped, fail $failed)"
                }
                indexSummary = "Done — indexed $indexed, skipped $skipped, failed $failed."
            }
        }

        fun showAlbumOf(track: TrackDescriptor) {
            catalog?.albums
                ?.firstOrNull { album -> album.tracks.any { it.id == track.id } }
                ?.let { selectedCollection = it.toSelection() }
        }

        fun showArtistOf(track: TrackDescriptor) {
            catalog?.artists
                ?.firstOrNull { artist -> artist.name == track.artist }
                ?.let { selectedCollection = it.toSelection() }
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
                                                    contentDescription = "New playlist",
                                                )
                                            }
                                        }
                                        IconButton(onClick = { showSearch = true }) {
                                            Icon(Icons.Rounded.Search, contentDescription = "Search library")
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
                                                text = { Text("Rescan library") },
                                                onClick = {
                                                    dismiss()
                                                    scope.launch { tracks = library.tracks() }
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Settings") },
                                                onClick = {
                                                    dismiss()
                                                    showSettings = true
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Diagnostics") },
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
                                val listPadding = PaddingValues(
                                    bottom = if (now.track != null) 104.dp else 12.dp,
                                )
                                when {
                                    catalog == null -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }

                                    catalog.songs.isEmpty() -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("No music found on this device.") }

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
                                                    )
                                                    val byId = catalog.songs.associateBy { it.id }
                                                    val tail = queue.mapNotNull(byId::get)
                                                    playback.play(listOf(hero.track) + tail, 0)
                                                    hero.resumeAtMs?.let { playback.seekTo(it) }
                                                }
                                            },
                                            onTrackMenu = { trackMenuTarget = it },
                                        )

                                        PLAYLISTS_TAB -> PlaylistsTabContent(
                                            autoPlaylists = autoPlaylists,
                                            playlists = playlists,
                                            tracksOf = ::tracksOf,
                                            contentPadding = listPadding,
                                            onOpenAuto = { auto ->
                                                selectedCollection = CollectionSelection(
                                                    title = auto.title,
                                                    subtitle = "${auto.tracks.size} tracks",
                                                    artworkUri = auto.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                    tracks = auto.tracks,
                                                )
                                            },
                                            onOpenPlaylist = { playlist ->
                                                selectedCollection = CollectionSelection(
                                                    title = playlist.name,
                                                    subtitle = "${playlist.trackIds.size} tracks",
                                                    artworkUri = tracksOf(playlist)
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                    tracks = tracksOf(playlist),
                                                )
                                            },
                                            onRename = { renameTarget = it },
                                            onDelete = { playlist ->
                                                scope.launch {
                                                    AppGraph.playlists.delete(playlist.id)
                                                    refreshPlaylists()
                                                    snackbar.showSnackbar("Deleted \"${playlist.name}\"")
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
                                                    selectedCollection = album.toSelection()
                                                }
                                            }
                                        }

                                        4 -> LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listPadding,
                                        ) {
                                            items(catalog.artists, key = { it.name ?: "?" }) { artist ->
                                                GroupRow(
                                                    title = artist.name ?: "Unknown artist",
                                                    subtitle = "${artist.tracks.size} tracks • " +
                                                        "${artist.albumCount} albums",
                                                    artworkUri = artist.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                ) { selectedCollection = artist.toSelection() }
                                            }
                                        }

                                        else -> LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = listPadding,
                                        ) {
                                            items(catalog.genres, key = { it.name ?: "?" }) { genre ->
                                                GroupRow(
                                                    title = genre.name ?: "Unknown genre",
                                                    subtitle = "${genre.tracks.size} tracks",
                                                    artworkUri = genre.tracks
                                                        .firstNotNullOfOrNull { it.artworkUri },
                                                ) { selectedCollection = genre.toSelection() }
                                            }
                                        }
                                        }
                                    }
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
                onDelete = { deleteTarget = target },
                onDismiss = { trackMenuTarget = null },
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
                        snackbar.showSnackbar("Added to \"${playlist.name}\"")
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
                title = "New playlist",
                confirmLabel = "Create",
                onConfirm = { name ->
                    showCreatePlaylist = false
                    pendingPlaylistTrack = null
                    scope.launch {
                        val created = AppGraph.playlists.create(name)
                        if (trackToSeed != null) {
                            AppGraph.playlists.addTracks(created.id, listOf(trackToSeed.id))
                        }
                        refreshPlaylists()
                        snackbar.showSnackbar("Created \"${created.name}\"")
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
                title = "Rename playlist",
                initialName = target.name,
                confirmLabel = "Rename",
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

        selectedCollection?.let { selection ->
            CollectionDetailScreen(
                selection = selection,
                currentTrackId = now.track?.id,
                onPlayTrack = { index -> scope.launch { playback.play(selection.tracks, index) } },
                onShuffle = { scope.launch { playback.play(selection.tracks.shuffled(), 0) } },
                onTrackMenu = { trackMenuTarget = it },
                onClose = { selectedCollection = null },
            )
        }

        if (showSearch) {
            SearchScreen(
                songs = catalog?.songs.orEmpty(),
                currentTrackId = now.track?.id,
                onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                onTrackMenu = { trackMenuTarget = it },
                onClose = { showSearch = false },
            )
        }

        infoTarget?.let { target ->
            TrackInfoSheet(track = target, onDismiss = { infoTarget = null })
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
                historySummary = historySummary,
                onRescan = { scope.launch { tracks = library.tracks() } },
                onIndexSample = { tracks?.let { loaded -> indexTracks(loaded.take(24)) } },
                onIndexAll = { tracks?.let(::indexTracks) },
                onDismiss = { showDiagnostics = false },
            )
        }
    }
}

private val BROWSE_TABS = listOf("For You", "Playlists", "Tracks", "Albums", "Artists", "Genres")

/** Playlists lead the carousel, but the app opens on Tracks. */
private const val FOR_YOU_TAB = 0
private const val PLAYLISTS_TAB = 1
private const val TRACKS_TAB = 2

/** Persist-and-report granularity for library indexing. */
private const val RECENT_EVENTS_FOR_YOU = 500

/** Long enough to be a sitting, short enough to still reflect the seed. */
private const val SMART_HERO_LENGTH = 20

private const val INDEX_CHUNK_SIZE = 8

/** Duration of the mini-player ↔ now-playing morph. */
private const val MORPH_MILLIS = 340

private fun AlbumGroup.toSelection() = CollectionSelection(
    title = title ?: "Unknown album",
    subtitle = artist,
    artworkUri = artworkUri,
    tracks = tracks,
)

private fun ArtistGroup.toSelection() = CollectionSelection(
    title = name ?: "Unknown artist",
    subtitle = "${tracks.size} tracks • $albumCount albums",
    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
    tracks = tracks,
)

private fun GenreGroup.toSelection() = CollectionSelection(
    title = name ?: "Unknown genre",
    subtitle = "${tracks.size} tracks",
    artworkUri = tracks.firstNotNullOfOrNull { it.artworkUri },
    tracks = tracks,
)

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
    val selectedTab = pagerState.currentPage

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

        // Track the pager CONTINUOUSLY rather than jumping once a swipe settles: the strip is the
        // same gesture seen from a different angle, so it should move with the finger. Between two
        // labels the centre is interpolated, because labels are only as wide as their text and the
        // step between them is uneven.
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
                        // Neither neighbour is on screen — a jump, not a swipe.
                        else -> {
                            listState.scrollToItem(position.roundToInt())
                            null
                        }
                    }
                    // Driven, not animated: the pager's own motion is the animation.
                    if (delta != null && abs(delta) > 0.5f) listState.scrollBy(delta)
                }
        }

        // Settle on whatever ended up nearest the middle. Only when the strip itself was dragged —
        // while the pager is moving it is the one in charge, and both correcting at once fights.
        LaunchedEffect(listState.isScrollInProgress) {
            if (!listState.isScrollInProgress && !pagerState.isScrollInProgress) {
                val info = listState.layoutInfo
                val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                val nearest = info.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2f) - centre)
                } ?: return@LaunchedEffect
                if (nearest.index != selectedTab) {
                    onSelect(nearest.index)
                } else {
                    val delta = (nearest.offset + nearest.size / 2f) - centre
                    if (abs(delta) > 1f) listState.animateScrollBy(delta)
                }
            }
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            itemsIndexed(BROWSE_TABS) { index, title ->
                val distance = (offsetFromCentre(index)?.let { abs(it) / falloffPx } ?: 1f)
                    .coerceIn(0f, 1f)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) }
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
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
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
            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle all")
        }
        FilledIconButton(onClick = onPlayAll, modifier = Modifier.padding(start = 8.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play all")
        }
    }
}

private fun SongSort.label(): String = when (this) {
    SongSort.TITLE -> "Title"
    SongSort.ARTIST -> "Artist"
    SongSort.RECENT -> "Recently added"
}

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
            text = album.title ?: "Unknown album",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = album.artist ?: "Unknown artist",
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
                        text = track.title ?: "Untitled",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        text = track.artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = accent.onContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track")
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
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
    historySummary: String?,
    onRescan: () -> Unit,
    onIndexSample: () -> Unit,
    onIndexAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EngineCard(engine)
                Text("Library: ${trackCount?.toString() ?: "…"} tracks")
                historySummary?.let { Text(it) }
                indexSummary?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onIndexSample) { Text("Index 24") }
                    TextButton(onClick = onIndexAll) { Text("Index all") }
                    TextButton(onClick = onRescan) { Text("Rescan") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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
                text = "Similarity engine",
                style = MaterialTheme.typography.titleMedium,
            )
            when (val current = state) {
                EngineState.Uninitialized -> Text("Not initialized yet.")
                EngineState.Initializing -> CircularProgressIndicator()
                is EngineState.Ready -> Text("Ready — ${current.indexedCount} tracks indexed.")
                is EngineState.Failed -> Text(current.error.toUserMessage())
            }
            Button(
                onClick = { scope.launch { engine.initialize() } },
                enabled = state !is EngineState.Initializing,
            ) {
                Text(if (state is EngineState.Failed) "Retry initialization" else "Initialize engine")
            }
        }
    }
}

/** Friendly, non-technical wording for the typed engine errors. */
private fun EngineError.toUserMessage(): String = when (this) {
    EngineError.ModelUnavailable ->
        "Similarity model isn't bundled yet — smart shuffle is disabled in this build."
    EngineError.NotIndexed ->
        "Library not indexed yet."
    is EngineError.BackendFailure ->
        "Engine backend failed: $message"
}

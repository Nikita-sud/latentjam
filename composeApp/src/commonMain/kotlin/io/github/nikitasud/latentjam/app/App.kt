/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.GenreGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch

/**
 * Root composable, shared by Android and iOS: the player shell.
 *
 * Browse tabs over the scanned library sit in a rounded content container,
 * with a sort/play header on Songs, drill-in collection details that scope
 * the play queue, and a floating mini-player pill that expands to the
 * now-playing screen. All Material 3, all original expression — the legacy
 * app's look is never consulted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(engine: SimilarityEngine, library: MusicLibrary, playback: PlaybackController) {
    MaterialTheme(colorScheme = latentJamColorScheme(darkTheme = isSystemInDarkTheme())) {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        var selectedTab by remember { mutableStateOf(0) }
        var songSort by remember { mutableStateOf(SongSort.TITLE) }
        var selectedCollection by remember { mutableStateOf<CollectionSelection?>(null) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var showNowPlaying by remember { mutableStateOf(false) }
        var showSearch by remember { mutableStateOf(false) }
        var trackMenuTarget by remember { mutableStateOf<TrackDescriptor?>(null) }
        var indexSummary by remember { mutableStateOf<String?>(null) }
        var historySummary by remember { mutableStateOf<String?>(null) }
        val now by playback.state.collectAsState()

        LaunchedEffect(Unit) { tracks = library.tracks() }
        val catalog = remember(tracks) { tracks?.let { LibraryCatalog.build(it) } }

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

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("LatentJam") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        actions = {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search library")
                            }
                            ShuffleAction(mode = now.shuffleMode) {
                                scope.launch {
                                    val newMode = playback.cycleShuffleMode()
                                    // Persistent label carries the state; explain
                                    // only the novel mode once per activation.
                                    if (newMode == ShuffleMode.SMART) {
                                        snackbar.showSnackbar(
                                            "Smart shuffle: similar tracks picked on-device",
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { showDiagnostics = true }) {
                                Icon(Icons.Filled.Info, contentDescription = "Diagnostics")
                            }
                        },
                    )
                    BrowseTabs(selectedTab) { selectedTab = it }
                }
            },
            bottomBar = {
                // Slide-in pill: presence itself is feedback that playback started.
                AnimatedVisibility(
                    visible = now.track != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    now.track?.let { current ->
                        MiniPlayerPill(
                            track = current,
                            accent = rememberTrackAccent(current),
                            isPlaying = now.isPlaying,
                            progress = if (now.durationMs > 0) {
                                (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                            onTogglePlayPause = { scope.launch { playback.togglePlayPause() } },
                            onPrevious = { scope.launch { playback.previous() } },
                            onNext = { scope.launch { playback.next() } },
                            onOpen = { showNowPlaying = true },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            // Content lives in a raised, top-rounded container so the list
            // reads as a distinct surface under the chrome.
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                when {
                    catalog == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    catalog.songs.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text("No music found on this device.") }

                    else -> when (selectedTab) {
                        0 -> Column {
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
                                            io.github.nikitasud.latentjam.library.SongSorting
                                                .sort(catalog.songs, songSort),
                                            0,
                                        )
                                    }
                                },
                            )
                            SectionedSongsList(
                                songs = catalog.songs,
                                sort = songSort,
                                currentTrackId = now.track?.id,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                                onTrackMenu = { trackMenuTarget = it },
                            )
                        }

                        1 -> LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        ) {
                            items(catalog.albums, key = { it.key }) { album ->
                                AlbumCard(album) { selectedCollection = album.toSelection() }
                            }
                        }

                        2 -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(catalog.artists, key = { it.name ?: "?" }) { artist ->
                                GroupRow(
                                    title = artist.name ?: "Unknown artist",
                                    subtitle = "${artist.tracks.size} tracks • ${artist.albumCount} albums",
                                    artworkUri = artist.tracks.firstNotNullOfOrNull { it.artworkUri },
                                ) { selectedCollection = artist.toSelection() }
                            }
                        }

                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(catalog.genres, key = { it.name ?: "?" }) { genre ->
                                GroupRow(
                                    title = genre.name ?: "Unknown genre",
                                    subtitle = "${genre.tracks.size} tracks",
                                    artworkUri = genre.tracks.firstNotNullOfOrNull { it.artworkUri },
                                ) { selectedCollection = genre.toSelection() }
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
                onGoToAlbum = { showAlbumOf(target) },
                onGoToArtist = { showArtistOf(target) },
                onDismiss = { trackMenuTarget = null },
            )
        }

        selectedCollection?.let { selection ->
            CollectionDetailScreen(
                selection = selection,
                currentTrackId = now.track?.id,
                onPlayTrack = { index -> scope.launch { playback.play(selection.tracks, index) } },
                onClose = { selectedCollection = null },
            )
        }

        if (showSearch) {
            SearchScreen(
                songs = catalog?.songs.orEmpty(),
                currentTrackId = now.track?.id,
                onPlay = { queue, index -> scope.launch { playback.play(queue, index) } },
                onClose = { showSearch = false },
            )
        }

        if (showNowPlaying) {
            NowPlayingScreen(playback = playback, onClose = { showNowPlaying = false })
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

private val BROWSE_TABS = listOf("Tracks", "Albums", "Artists", "Genres")

/** Persist-and-report granularity for library indexing. */
private const val INDEX_CHUNK_SIZE = 8

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

/** Scrollable tabs; the selected one is emphasized in size and weight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = selectedTab,
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        divider = {},
        indicator = { positions ->
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(positions[selectedTab]),
                width = 32.dp,
            )
        },
    ) {
        BROWSE_TABS.forEachIndexed { index, title ->
            val selected = selectedTab == index
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                text = {
                    Text(
                        text = title,
                        style = if (selected) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
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
                    imageVector = Icons.AutoMirrored.Filled.Sort,
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
            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle all")
        }
        FilledIconButton(onClick = onPlayAll, modifier = Modifier.padding(start = 8.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play all")
        }
    }
}

private fun SongSort.label(): String = when (this) {
    SongSort.TITLE -> "Title"
    SongSort.ARTIST -> "Artist"
    SongSort.RECENT -> "Recently added"
}

@Composable
private fun ShuffleAction(mode: ShuffleMode, onClick: () -> Unit) {
    val tint = when (mode) {
        ShuffleMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        ShuffleMode.ON -> MaterialTheme.colorScheme.primary
        ShuffleMode.SMART -> MaterialTheme.colorScheme.tertiary
    }
    val label = when (mode) {
        ShuffleMode.OFF -> "Off"
        ShuffleMode.ON -> "On"
        ShuffleMode.SMART -> "Smart"
    }
    TextButton(onClick = onClick) {
        Icon(
            // SMART wears the app's own mark — the mode is LatentJam's whole
            // point, so it gets its own symbol rather than a tinted shuffle.
            imageVector = if (mode == ShuffleMode.SMART) LatentJamMark else Icons.Filled.Shuffle,
            contentDescription = "Shuffle mode: $label. Tap to change.",
            tint = tint,
        )
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
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
                imageVector = Icons.Filled.MusicNote,
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
 * Floating mini-player: a tinted pill above the navigation bar with its own
 * progress line, marquee title, and prev/play/next. Tapping expands to the
 * now-playing screen.
 */
@Composable
private fun MiniPlayerPill(
    track: TrackDescriptor,
    accent: TrackAccent,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(28.dp),
        // Colour comes from the cover art, or from the track's place in
        // latent space when it has none.
        color = accent.container,
        contentColor = accent.onContainer,
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 44.dp, cornerRadius = 22.dp)
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
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track")
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next track")
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accent.onContainer,
                trackColor = accent.onContainer.copy(alpha = 0.24f),
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

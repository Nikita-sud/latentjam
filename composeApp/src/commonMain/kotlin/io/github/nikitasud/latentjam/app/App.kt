/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.GenreGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.MusicLibrary
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
 * Browse tabs (Songs / Albums / Artists / Genres) over the scanned library,
 * drill-in collection details that scope the play queue, a global mini-player
 * opening the now-playing screen, and diagnostics behind the info action.
 * All Material 3, all original expression — the legacy app's look is never
 * consulted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(engine: SimilarityEngine, library: MusicLibrary, playback: PlaybackController) {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        var selectedTab by remember { mutableStateOf(0) }
        var selectedCollection by remember { mutableStateOf<CollectionSelection?>(null) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var showNowPlaying by remember { mutableStateOf(false) }
        var indexSummary by remember { mutableStateOf<String?>(null) }
        val now by playback.state.collectAsState()

        LaunchedEffect(Unit) { tracks = library.tracks() }
        val catalog = remember(tracks) { tracks?.let { LibraryCatalog.build(it) } }

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

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("LatentJam") },
                        actions = {
                            ShuffleAction(mode = now.shuffleMode) {
                                scope.launch {
                                    val newMode = playback.cycleShuffleMode()
                                    snackbar.showSnackbar(newMode.userLabel())
                                }
                            }
                            IconButton(onClick = { showDiagnostics = true }) {
                                Icon(Icons.Filled.Info, contentDescription = "Diagnostics")
                            }
                        },
                    )
                    TabRow(selectedTabIndex = selectedTab) {
                        BROWSE_TABS.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) },
                            )
                        }
                    }
                }
            },
            bottomBar = {
                now.track?.let { current ->
                    MiniPlayer(
                        track = current,
                        isPlaying = now.isPlaying,
                        onTogglePlayPause = { scope.launch { playback.togglePlayPause() } },
                        onNext = { scope.launch { playback.next() } },
                        onOpen = { showNowPlaying = true },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
                catalog == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                catalog.songs.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("No music found on this device.") }

                else -> when (selectedTab) {
                    0 -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        itemsIndexed(catalog.songs, key = { _, track -> track.id.value }) { index, track ->
                            TrackRow(
                                track = track,
                                isCurrent = track.id == now.track?.id,
                                onClick = { scope.launch { playback.play(catalog.songs, index) } },
                            )
                        }
                    }

                    1 -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding,
                    ) {
                        items(catalog.albums, key = { it.key }) { album ->
                            AlbumCard(album) { selectedCollection = album.toSelection() }
                        }
                    }

                    2 -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        items(catalog.artists, key = { it.name ?: "?" }) { artist ->
                            GroupRow(
                                title = artist.name ?: "Unknown artist",
                                subtitle = "${artist.tracks.size} tracks • ${artist.albumCount} albums",
                            ) { selectedCollection = artist.toSelection() }
                        }
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        items(catalog.genres, key = { it.name ?: "?" }) { genre ->
                            GroupRow(
                                title = genre.name ?: "Unknown genre",
                                subtitle = "${genre.tracks.size} tracks",
                            ) { selectedCollection = genre.toSelection() }
                        }
                    }
                }
            }
        }

        selectedCollection?.let { selection ->
            CollectionDetailScreen(
                selection = selection,
                currentTrackId = now.track?.id,
                onPlayTrack = { index -> scope.launch { playback.play(selection.tracks, index) } },
                onClose = { selectedCollection = null },
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
                onRescan = { scope.launch { tracks = library.tracks() } },
                onIndexSample = { tracks?.let { loaded -> indexTracks(loaded.take(24)) } },
                onIndexAll = { tracks?.let(::indexTracks) },
                onDismiss = { showDiagnostics = false },
            )
        }
    }
}

private val BROWSE_TABS = listOf("Songs", "Albums", "Artists", "Genres")

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

@Composable
private fun ShuffleAction(mode: ShuffleMode, onClick: () -> Unit) {
    val tint = when (mode) {
        ShuffleMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        ShuffleMode.ON -> MaterialTheme.colorScheme.primary
        ShuffleMode.SMART -> MaterialTheme.colorScheme.tertiary
    }
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle mode: $mode", tint = tint)
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
                .clip(RoundedCornerShape(12.dp))
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
            modifier = Modifier.padding(top = 6.dp),
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
private fun GroupRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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

@Composable
private fun MiniPlayer(
    track: TrackDescriptor,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(uri = track.artworkUri, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title ?: "Untitled",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    }
}

@Composable
private fun DiagnosticsDialog(
    engine: SimilarityEngine,
    trackCount: Int?,
    indexSummary: String?,
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

// ------------------------------------------------------------------- helpers

private fun ShuffleMode.userLabel(): String = when (this) {
    ShuffleMode.OFF -> "Shuffle off"
    ShuffleMode.ON -> "Shuffle on"
    ShuffleMode.SMART -> "SMART shuffle — random fallback until the model lands"
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

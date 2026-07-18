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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
 * Songs list (auto-scanned on entry) → tap to play through the
 * [PlaybackController]; mini-player at the bottom; shuffle action in the top
 * bar cycling OFF → ON → SMART; engine/library diagnostics tucked behind the
 * info action. All Material 3, all original expression — the legacy app's
 * look is never consulted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(engine: SimilarityEngine, library: MusicLibrary, playback: PlaybackController) {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
        var showDiagnostics by remember { mutableStateOf(false) }
        var indexSummary by remember { mutableStateOf<String?>(null) }
        val now by playback.state.collectAsState()

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

        LaunchedEffect(Unit) { tracks = library.tracks() }

        Scaffold(
            topBar = {
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
            },
            bottomBar = {
                now.track?.let { current ->
                    MiniPlayer(
                        track = current,
                        isPlaying = now.isPlaying,
                        onTogglePlayPause = { scope.launch { playback.togglePlayPause() } },
                        onNext = { scope.launch { playback.next() } },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when (val loaded = tracks) {
                null -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> if (loaded.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { Text("No music found on this device.") }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = padding,
                    ) {
                        itemsIndexed(loaded, key = { _, track -> track.id.value }) { index, track ->
                            TrackRow(
                                track = track,
                                isCurrent = track.id == now.track?.id,
                                onClick = { scope.launch { playback.play(loaded, index) } },
                            )
                        }
                    }
                }
            }
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

/** Persist-and-report granularity for library indexing. */
private const val INDEX_CHUNK_SIZE = 8

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
private fun TrackRow(track: TrackDescriptor, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(uri = track.artworkUri, size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title ?: "Untitled",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
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
        track.durationMs?.let { duration ->
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Artwork(uri: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    track: TrackDescriptor,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
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

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch

/**
 * Root composable, shared by Android and iOS.
 *
 * Still a deliberately small status screen, now with two live cards: the
 * similarity engine's lifecycle (stub backends → graceful "model not bundled"
 * state) and the device music library (real MediaStore data on Android). Both
 * are driven from UI-scoped coroutines — safe by contract, because engine and
 * library confine their own work to background dispatchers.
 */
@Composable
fun App(engine: SimilarityEngine, library: MusicLibrary) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "LatentJam",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "On-device smart shuffle — cross-platform core",
                    style = MaterialTheme.typography.bodyMedium,
                )

                EngineCard(engine)
                LibraryCard(library)
            }
        }
    }
}

@Composable
private fun EngineCard(engine: SimilarityEngine) {
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()

    StatusCard(title = "Similarity engine") {
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

@Composable
private fun LibraryCard(library: MusicLibrary) {
    var tracks by remember { mutableStateOf<List<TrackDescriptor>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    StatusCard(title = "Music library") {
        when {
            scanning -> CircularProgressIndicator()
            tracks == null -> Text("Not scanned yet.")
            else -> {
                val found = tracks.orEmpty()
                Text("${found.size} tracks found.")
                found.take(3).forEach { track ->
                    Text(
                        text = listOfNotNull(track.artist, track.title ?: "Untitled")
                            .joinToString(" — "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
        Button(
            onClick = {
                scope.launch {
                    scanning = true
                    tracks = library.tracks()
                    scanning = false
                }
            },
            enabled = !scanning,
        ) {
            Text(if (tracks == null) "Scan library" else "Rescan")
        }
    }
}

/** Shared chrome for the status cards: title + centered content column. */
@Composable
private fun StatusCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
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

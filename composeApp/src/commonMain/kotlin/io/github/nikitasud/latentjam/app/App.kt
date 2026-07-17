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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import kotlinx.coroutines.launch

/**
 * Root composable, shared by Android and iOS.
 *
 * For now this is a deliberately small "engine status" screen: it collects
 * [SimilarityEngine.state] and drives [SimilarityEngine.initialize] from a
 * UI-scoped coroutine — which is safe by contract, because the engine
 * confines its own work to a background dispatcher. With the current stub
 * backends, initializing lands in the graceful "model not bundled" state;
 * the same screen will show Ready once the real ONNX/Core ML backends land.
 */
@Composable
fun App(engine: SimilarityEngine) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val state by engine.state.collectAsState()
            val scope = rememberCoroutineScope()

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
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

                EngineStateCard(state)

                Button(
                    onClick = { scope.launch { engine.initialize() } },
                    enabled = state !is EngineState.Initializing,
                ) {
                    Text(if (state is EngineState.Failed) "Retry initialization" else "Initialize engine")
                }
            }
        }
    }
}

@Composable
private fun EngineStateCard(state: EngineState) {
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
            when (state) {
                EngineState.Uninitialized -> Text("Not initialized yet.")
                EngineState.Initializing -> CircularProgressIndicator()
                is EngineState.Ready -> Text("Ready — ${state.indexedCount} tracks indexed.")
                is EngineState.Failed -> Text(state.error.toUserMessage())
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

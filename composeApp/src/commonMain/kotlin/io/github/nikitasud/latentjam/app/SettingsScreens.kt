/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch

/** Which settings surface is showing. Flat rather than a nav graph — there are three of them. */
enum class SettingsPage { ROOT, EQUALIZER, INTELLIGENCE }

/**
 * Settings.
 *
 * Grouped by what the user is trying to change, not by which module implements it: how it looks,
 * how it sounds, and what the recommender is doing. The last of those is unusual for a music player
 * and is the reason this screen exists at all — an on-device model that reorders your library should
 * be inspectable rather than mysterious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    equalizer: EqualizerController,
    engine: SimilarityEngine,
    tracks: List<TrackDescriptor>,
    onClose: () -> Unit,
) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    PlatformBackHandler(enabled = true) {
        if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (page) {
                                SettingsPage.ROOT -> "Settings"
                                SettingsPage.EQUALIZER -> "Equalizer"
                                SettingsPage.INTELLIGENCE -> "Intelligence"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                when (page) {
                    SettingsPage.ROOT -> SettingsRoot(
                        settings = settings,
                        onOpen = { page = it },
                    )
                    SettingsPage.EQUALIZER -> EqualizerSettings(equalizer)
                    SettingsPage.INTELLIGENCE -> IntelligenceSettings(engine, tracks)
                }
            }
        }
    }
}

@Composable
private fun SettingsRoot(settings: AppSettings, onOpen: (SettingsPage) -> Unit) {
    val theme by settings.themeMode.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            SettingsSection("Appearance") {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    items(ThemeMode.entries) { mode ->
                        FilterChip(
                            selected = theme == mode,
                            onClick = { settings.setThemeMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "Follow system"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("Playback") {
                SettingsRow(
                    title = "Equalizer",
                    subtitle = "Tone controls for this device's audio output",
                    onClick = { onOpen(SettingsPage.EQUALIZER) },
                )
            }
        }
        item {
            SettingsSection("Intelligence") {
                SettingsRow(
                    title = "SMART engine",
                    subtitle = "What the on-device model knows about your library",
                    onClick = { onOpen(SettingsPage.INTELLIGENCE) },
                )
            }
        }
        item {
            SettingsSection("About") {
                Text(
                    text = "LatentJam — a local music player with an on-device recommender. " +
                        "Nothing about your listening leaves this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ equalizer

/**
 * Bands, frequencies and presets all come from the device, so this screen draws whatever the
 * platform reports rather than a fixed five-band picture that might not match what is being changed.
 */
@Composable
private fun EqualizerSettings(equalizer: EqualizerController) {
    val state by equalizer.state.collectAsState()
    val scope = rememberCoroutineScope()

    if (!state.available) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.unavailableReason.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { equalizer.setEnabled(!state.enabled) } }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (state.enabled) "On" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { scope.launch { equalizer.setEnabled(it) } },
                )
            }
        }

        if (state.presets.isNotEmpty()) {
            item {
                SettingsSection("Presets") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                    ) {
                        items(state.presets) { preset ->
                            FilterChip(
                                selected = state.activePreset == preset.index,
                                onClick = { scope.launch { equalizer.applyPreset(preset.index) } },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingsSection("Bands") {
                Row(
                    modifier = Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    state.bands.forEach { band ->
                        BandSlider(
                            label = formatFrequency(band.centreFrequencyHz),
                            value = band.levelMillibels.toFloat(),
                            range = state.minLevelMillibels.toFloat()..state.maxLevelMillibels.toFloat(),
                            enabled = state.enabled,
                            onChange = { scope.launch { equalizer.setBandLevel(band.index, it.toInt()) } },
                        )
                    }
                }
            }
        }

        if (state.bassBoostSupported) {
            item {
                SettingsSection("Bass boost") {
                    Slider(
                        value = state.bassBoostStrength.toFloat(),
                        onValueChange = { scope.launch { equalizer.setBassBoost(it.toInt()) } },
                        valueRange = 0f..1000f,
                        enabled = state.enabled,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                OutlinedButton(onClick = { scope.launch { equalizer.reset() } }) { Text("Reset to flat") }
            }
        }
    }
}

/**
 * A vertical band control.
 *
 * Compose's Slider is horizontal, so it is rotated a quarter turn. The rotation happens inside a
 * fixed-size box, because a rotated child still reports its UNROTATED size to the layout and would
 * otherwise claim a slider's width as its height.
 */
@Composable
private fun BandSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Text(
            text = formatDecibels(value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier.weight(1f).width(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                enabled = enabled,
                modifier = Modifier
                    .width(200.dp)
                    .graphicsLayer { rotationZ = -90f },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatFrequency(hz: Int): String =
    if (hz >= 1000) "${hz / 1000}k" else "$hz"

private fun formatDecibels(millibels: Float): String {
    val decibels = (millibels / 100f)
    val rounded = kotlin.math.round(decibels).toInt()
    return if (rounded > 0) "+$rounded" else "$rounded"
}

// --------------------------------------------------------------- intelligence

/**
 * What the recommender actually knows.
 *
 * This is the honest version of an "AI" screen: coverage numbers and the model identifier, not a
 * confidence percentage invented for reassurance. The two indexes are reported separately because
 * they fill at very different times — metadata is encoded for the whole library at first launch,
 * while audio embedding is expensive and runs when asked.
 */
@Composable
private fun IntelligenceSettings(engine: SimilarityEngine, tracks: List<TrackDescriptor>) {
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val indexed = (state as? EngineState.Ready)?.indexedCount ?: 0
    val total = tracks.size

    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            SettingsSection("Status") {
                StatRow("Engine", describeEngineState(state))
                StatRow("Library", "$total tracks")
                StatRow(
                    label = "Sound fingerprints",
                    value = if (total == 0) "—" else "$indexed of $total",
                )
                if (total > 0 && indexed < total) {
                    LinearProgressIndicator(
                        progress = { indexed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "Tracks without a fingerprint can still be played, but SMART will not " +
                            "pick them or use them to judge what fits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item {
            SettingsSection("Analysis") {
                Text(
                    text = "Listening a track for the first time does not analyse it. Fingerprints are " +
                        "computed in batches, and the work resumes where it left off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !busy && total > 0,
                        onClick = {
                            busy = true
                            message = null
                            AppGraph.appScope.launch {
                                val report = engine.indexLibrary(tracks.take(INDEX_BATCH))
                                message = "Analysed ${report.indexed}, skipped ${report.skipped}, " +
                                    "failed ${report.failed}."
                                busy = false
                            }
                        },
                    ) { Text("Analyse $INDEX_BATCH") }
                    OutlinedButton(
                        enabled = !busy && total > 0,
                        onClick = {
                            busy = true
                            message = null
                            AppGraph.appScope.launch {
                                var indexedNow = 0
                                var failed = 0
                                tracks.chunked(INDEX_BATCH).forEach { chunk ->
                                    val report = engine.indexLibrary(chunk)
                                    indexedNow += report.indexed
                                    failed += report.failed
                                }
                                message = "Analysed $indexedNow, failed $failed."
                                busy = false
                            }
                        },
                    ) { Text("Analyse everything") }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
                message?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item {
            SettingsSection("Privacy") {
                Text(
                    text = "Every model runs on this device. Your library, your listening history and " +
                        "everything derived from them stay here — there is no account and no upload.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private const val INDEX_BATCH = 24

private fun describeEngineState(state: EngineState): String = when (state) {
    is EngineState.Uninitialized -> "Not started"
    is EngineState.Initializing -> "Starting…"
    is EngineState.Ready -> "Ready"
    is EngineState.Failed -> "Unavailable — ${state.error}"
}

// -------------------------------------------------------------------- pieces

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}


/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import org.jetbrains.compose.resources.getString
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_title
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_progress_eta
import io.github.nikitasud.latentjam.app.generated.resources.indexing_notification_progress
import io.github.nikitasud.latentjam.library.nowMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_not_started
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_ready
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_starting
import io.github.nikitasud.latentjam.app.generated.resources.engine_state_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_bands
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_bass_boost
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_presets
import io.github.nikitasud.latentjam.app.generated.resources.equalizer_reset
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analyse_all
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analyse_batch
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysed_all_report
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysed_batch_report
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_analysis_note
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_engine
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_fingerprint_note
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_fingerprints
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_indexed_of
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_library
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_privacy_body
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_section_analysis
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_section_privacy
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_section_status
import io.github.nikitasud.latentjam.app.generated.resources.settings_about_body
import io.github.nikitasud.latentjam.app.generated.resources.settings_equalizer
import io.github.nikitasud.latentjam.app.generated.resources.settings_equalizer_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_intelligence
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_about
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_appearance
import io.github.nikitasud.latentjam.app.generated.resources.settings_section_playback
import io.github.nikitasud.latentjam.app.generated.resources.settings_smart_engine
import io.github.nikitasud.latentjam.app.generated.resources.settings_smart_engine_subtitle
import io.github.nikitasud.latentjam.app.generated.resources.settings_theme
import io.github.nikitasud.latentjam.app.generated.resources.settings_title
import io.github.nikitasud.latentjam.app.generated.resources.state_off
import io.github.nikitasud.latentjam.app.generated.resources.state_on
import io.github.nikitasud.latentjam.app.generated.resources.theme_dark
import io.github.nikitasud.latentjam.app.generated.resources.theme_light
import io.github.nikitasud.latentjam.app.generated.resources.theme_system
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
                            stringResource(
                                when (page) {
                                    SettingsPage.ROOT -> Res.string.settings_title
                                    SettingsPage.EQUALIZER -> Res.string.settings_equalizer
                                    SettingsPage.INTELLIGENCE -> Res.string.settings_intelligence
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(Res.string.action_back),
                            )
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
            SettingsSection(stringResource(Res.string.settings_section_appearance)) {
                Text(
                    text = stringResource(Res.string.settings_theme),
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
                                    stringResource(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> Res.string.theme_system
                                            ThemeMode.LIGHT -> Res.string.theme_light
                                            ThemeMode.DARK -> Res.string.theme_dark
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_section_playback)) {
                SettingsRow(
                    title = stringResource(Res.string.settings_equalizer),
                    subtitle = stringResource(Res.string.settings_equalizer_subtitle),
                    onClick = { onOpen(SettingsPage.EQUALIZER) },
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_intelligence)) {
                SettingsRow(
                    title = stringResource(Res.string.settings_smart_engine),
                    subtitle = stringResource(Res.string.settings_smart_engine_subtitle),
                    onClick = { onOpen(SettingsPage.INTELLIGENCE) },
                )
            }
        }
        item {
            SettingsSection(stringResource(Res.string.settings_section_about)) {
                Text(
                    text = stringResource(Res.string.settings_about_body),
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
 * Draws the bands, frequencies, and presets reported by the active audio effect, so every control
 * corresponds to a filter that the platform playback graph actually owns.
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
                    Text(
                        text = stringResource(Res.string.settings_equalizer),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (state.enabled) {
                            stringResource(Res.string.state_on)
                        } else {
                            stringResource(Res.string.state_off)
                        },
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
                SettingsSection(stringResource(Res.string.equalizer_presets)) {
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
            SettingsSection(stringResource(Res.string.equalizer_bands)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    state.bands.forEach { band ->
                        BandSlider(
                            label = formatFrequency(band.centreFrequencyHz),
                            value = band.levelMillibels.toFloat(),
                            range = state.minLevelMillibels.toFloat()..state.maxLevelMillibels.toFloat(),
                            enabled = state.enabled,
                            compact = state.bands.size > 6,
                            onChange = { scope.launch { equalizer.setBandLevel(band.index, it.toInt()) } },
                        )
                    }
                }
            }
        }

        if (state.bassBoostSupported) {
            item {
                SettingsSection(stringResource(Res.string.equalizer_bass_boost)) {
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
                OutlinedButton(onClick = { scope.launch { equalizer.reset() } }) {
                    Text(stringResource(Res.string.equalizer_reset))
                }
            }
        }
    }
}

/**
 * A vertical band fader, drawn rather than rotated.
 *
 * A rotated horizontal Slider was the old approach and it read as broken: Material's track fills
 * from one end, so a band sitting at 0 dB looked half-full from the bottom, and the modern thumb
 * and track-stop indicators became stray dots once turned on their side. An equalizer band is
 * BIPOLAR — it boosts above 0 and cuts below it — so the only honest picture anchors the fill at
 * the centre line and grows it up for a boost, down for a cut. That is what this draws, and the
 * whole column is the touch target so a band is set by tapping or dragging anywhere along it.
 */
@Composable
private fun BandSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    compact: Boolean,
    onChange: (Float) -> Unit,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    // A DrawScope cannot read the theme, so the colours are resolved here. White for the active
    // fill and thumb, per the palette rule that emphasis comes from contrast, not hue (cyan is
    // reserved for SMART). Everything dims together when the effect is switched off.
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = if (enabled) Color.White else Color.White.copy(alpha = 0.25f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val thumbColor = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Text(
            text = formatDecibels(value),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (value != 0f) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled && value != 0f) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .width(if (compact) 28.dp else 40.dp)
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    // Tap-or-drag: the first touch sets the value, and every subsequent move
                    // updates it, so the whole column behaves like one tall fader.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onChange(bandValueForY(down.position.y, size.height.toFloat(), range))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            onChange(bandValueForY(change.position.y, size.height.toFloat(), range))
                            change.consume()
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centreX = size.width / 2f
                val trackWidth = 6.dp.toPx()
                val radius = trackWidth / 2f
                fun yFor(level: Float): Float {
                    val fraction = ((level - range.start) / span).coerceIn(0f, 1f)
                    return size.height * (1f - fraction)
                }
                val zeroY = yFor(0f)
                val thumbY = yFor(value)
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(centreX - radius, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
                val fillTop = minOf(zeroY, thumbY)
                val fillHeight = (maxOf(zeroY, thumbY) - fillTop).coerceAtLeast(radius)
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(centreX - radius, fillTop),
                    size = Size(trackWidth, fillHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
                // The 0 dB reference, so a boost and a cut are visibly measured from the same line.
                drawLine(
                    color = zeroLineColor,
                    start = Offset(centreX - 8.dp.toPx(), zeroY),
                    end = Offset(centreX + 8.dp.toPx(), zeroY),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(color = thumbColor, radius = 9.dp.toPx(), center = Offset(centreX, thumbY))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Maps a vertical touch position to a band level: top of the track is the maximum boost. */
private fun bandValueForY(y: Float, height: Float, range: ClosedFloatingPointRange<Float>): Float {
    if (height <= 0f) return range.start
    val fraction = (1f - (y / height)).coerceIn(0f, 1f)
    return range.start + fraction * (range.endInclusive - range.start)
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
    val automaticIndexing by AppGraph.automaticIndexing.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // The counts, not a finished sentence: formatting happens in the composition
    // below, so the message is in the reader's language and re-localises if the
    // system language changes while this screen is open.
    var outcome by remember { mutableStateOf<AnalysisOutcome?>(null) }

    val indexed = (state as? EngineState.Ready)?.indexedCount ?: 0
    val total = tracks.size

    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            SettingsSection(stringResource(Res.string.intelligence_section_status)) {
                StatRow(stringResource(Res.string.intelligence_engine), describeEngineState(state))
                StatRow(
                    label = stringResource(Res.string.intelligence_library),
                    value = pluralStringResource(Res.plurals.count_tracks, total, total),
                )
                StatRow(
                    label = stringResource(Res.string.intelligence_fingerprints),
                    value = if (total == 0) {
                        "—"
                    } else {
                        stringResource(Res.string.intelligence_indexed_of, indexed, total)
                    },
                )
                if (total > 0 && indexed < total) {
                    LinearProgressIndicator(
                        progress = { indexed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(Res.string.intelligence_fingerprint_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item {
            SettingsSection(stringResource(Res.string.intelligence_section_analysis)) {
                Text(
                    text = stringResource(Res.string.intelligence_analysis_note),
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
                        enabled = !busy && !automaticIndexing.running && total > 0,
                        onClick = {
                            busy = true
                            outcome = null
                            AppGraph.appScope.launch {
                                val batch = tracks.take(INDEX_BATCH)
                                val notifier = AppGraph.indexingNotifier
                                val notificationTitle =
                                    getString(Res.string.indexing_notification_title)
                                try {
                                    notifier.show(
                                        title = notificationTitle,
                                        text = getString(
                                            Res.string.indexing_notification_progress,
                                            0,
                                            batch.size,
                                        ),
                                        done = 0,
                                        total = batch.size,
                                    )
                                    val report = engine.indexLibrary(batch)
                                    notifier.show(
                                        title = notificationTitle,
                                        text = getString(
                                            Res.string.indexing_notification_progress,
                                            batch.size,
                                            batch.size,
                                        ),
                                        done = batch.size,
                                        total = batch.size,
                                    )
                                    outcome = AnalysisOutcome.Batch(
                                        indexed = report.indexed,
                                        skipped = report.skipped,
                                        failed = report.failed,
                                    )
                                } finally {
                                    notifier.finish()
                                    busy = false
                                }
                            }
                        },
                    ) { Text(stringResource(Res.string.intelligence_analyse_batch, INDEX_BATCH)) }
                    OutlinedButton(
                        enabled = !busy && !automaticIndexing.running && total > 0,
                        onClick = {
                            busy = true
                            outcome = null
                            AppGraph.appScope.launch {
                                var indexedNow = 0
                                var failed = 0
                                var seen = 0
                                val notifier = AppGraph.indexingNotifier
                                val eta = IndexingEta(nowMillis())
                                val notificationTitle =
                                    getString(Res.string.indexing_notification_title)
                                try {
                                    notifier.show(
                                        title = notificationTitle,
                                        text = getString(
                                            Res.string.indexing_notification_progress,
                                            0,
                                            tracks.size,
                                        ),
                                        done = 0,
                                        total = tracks.size,
                                    )
                                    tracks.chunked(INDEX_BATCH).forEach { chunk ->
                                        val report = engine.indexLibrary(chunk)
                                        indexedNow += report.indexed
                                        failed += report.failed
                                        seen += chunk.size
                                        val remaining =
                                            eta.remainingMs(seen, tracks.size, nowMillis())
                                        notifier.show(
                                            title = notificationTitle,
                                            text = if (remaining == null) {
                                                getString(
                                                    Res.string.indexing_notification_progress,
                                                    seen,
                                                    tracks.size,
                                                )
                                            } else {
                                                getString(
                                                    Res.string.indexing_notification_progress_eta,
                                                    seen,
                                                    tracks.size,
                                                    IndexingEta.minutesFrom(remaining),
                                                )
                                            },
                                            done = seen,
                                            total = tracks.size,
                                        )
                                    }
                                } finally {
                                    notifier.finish()
                                }
                                outcome = AnalysisOutcome.Everything(indexedNow, failed)
                                busy = false
                            }
                        },
                    ) { Text(stringResource(Res.string.intelligence_analyse_all)) }
                    if (busy || automaticIndexing.running) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
                outcome?.let { done ->
                    Text(
                        text = when (done) {
                            is AnalysisOutcome.Batch -> stringResource(
                                Res.string.intelligence_analysed_batch_report,
                                done.indexed,
                                done.skipped,
                                done.failed,
                            )
                            is AnalysisOutcome.Everything -> stringResource(
                                Res.string.intelligence_analysed_all_report,
                                done.indexed,
                                done.failed,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item {
            SettingsSection(stringResource(Res.string.intelligence_section_privacy)) {
                Text(
                    text = stringResource(Res.string.intelligence_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private const val INDEX_BATCH = 24

/** What an analysis run reported, kept as counts so the sentence can be localised. */
private sealed interface AnalysisOutcome {
    data class Batch(val indexed: Int, val skipped: Int, val failed: Int) : AnalysisOutcome
    data class Everything(val indexed: Int, val failed: Int) : AnalysisOutcome
}

@Composable
private fun describeEngineState(state: EngineState): String = when (state) {
    is EngineState.Uninitialized -> stringResource(Res.string.engine_state_not_started)
    is EngineState.Initializing -> stringResource(Res.string.engine_state_starting)
    is EngineState.Ready -> stringResource(Res.string.engine_state_ready)
    is EngineState.Failed -> stringResource(Res.string.engine_state_unavailable, state.error)
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

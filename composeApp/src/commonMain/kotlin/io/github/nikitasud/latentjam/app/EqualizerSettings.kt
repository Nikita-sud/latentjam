/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.*
import io.github.nikitasud.latentjam.playback.MAX_CROSSFADE_SECONDS
import io.github.nikitasud.latentjam.playback.EqualizerController
import io.github.nikitasud.latentjam.playback.EqualizerPreset
import io.github.nikitasud.latentjam.playback.EqualizerPresetKind
import io.github.nikitasud.latentjam.playback.EqualizerState
import io.github.nikitasud.latentjam.playback.EqualizerFrequencyResponse
import io.github.nikitasud.latentjam.playback.EqualizerProcessingState
import io.github.nikitasud.latentjam.playback.frequencyResponse
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EqualizerSettings(equalizer: EqualizerController, settings: AppSettings) {
    val state by equalizer.state.collectAsState()
    val processing by remember(equalizer) { equalizer.processing }.collectAsState(EqualizerProcessingState())
    val response = remember(state, processing.sampleRateHz) {
        state.frequencyResponse(processing.sampleRateHz ?: 48000)
    }
    val scope = rememberCoroutineScope()
    val normalize by settings.normalizeVolume.collectAsState()
    val normalization by AppGraph.normalizationStatus.collectAsState()
    val crossfade by settings.crossfadeSeconds.collectAsState()

    FadingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(24.dp.corner()),
                color = if (state.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = state.enabled, enabled = state.available, role = Role.Switch,
                            onValueChange = { scope.launch { equalizer.setEnabled(it) } },
                        ), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.settings_equalizer), style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (!state.enabled) stringResource(Res.string.state_off)
                                else state.presets.find { it.index == state.activePreset }?.let { equalizerPresetLabel(it) }
                                    ?: stringResource(Res.string.equalizer_custom),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        AppSwitch(checked = state.enabled, onCheckedChange = null, enabled = state.available)
                    }
                    Text(
                        stringResource(if (state.available) Res.string.equalizer_hint else Res.string.equalizer_unavailable_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (state.available) {
            item {
                ToneCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.equalizer_presets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { equalizer.reset() } }) {
                            Text(stringResource(Res.string.equalizer_reset_short))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.presets.forEach { preset ->
                            AppChoiceChip(
                                selected = state.activePreset == preset.index,
                                onClick = { scope.launch { equalizer.applyPreset(preset.index) } },
                                label = equalizerPresetLabel(preset),
                            )
                        }
                    }
                    response?.let { curve ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.equalizer_response), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            val rate = (curve.sampleRateHz / 1000f).toString().removeSuffix(".0")
                            Text(
                                if (processing.sampleRateHz == null) stringResource(Res.string.equalizer_response_preview, rate) else "$rate kHz",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BandCurve(curve, state.enabled)
                        Text(
                            stringResource(Res.string.equalizer_response_headroom, formatReductionDb(curve.headroomDb)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(200.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        state.bands.forEach { band ->
                            EqualizerBandSlider(
                                label = if (band.centreFrequencyHz >= 1000) "${band.centreFrequencyHz / 1000}k" else "${band.centreFrequencyHz}",
                                description = "${band.centreFrequencyHz} Hz",
                                value = band.levelMillibels.toFloat(),
                                range = state.minLevelMillibels.toFloat()..state.maxLevelMillibels.toFloat(),
                                // Keep the saved curve, but do not offer a control the current
                                // stream cannot render. It becomes editable again at a higher rate.
                                enabled = state.enabled && (processing.sampleRateHz?.let {
                                    band.centreFrequencyHz < it * 0.45
                                } ?: true),
                                onChange = { scope.launch { equalizer.setBandLevel(band.index, it.roundToInt()) } },
                            )
                        }
                    }
                    Text(stringResource(Res.string.equalizer_bands_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.bassBoostSupported) item {
                val bassLabel = stringResource(Res.string.equalizer_bass_boost)
                ToneCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(bassLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(stringResource(Res.string.equalizer_bass_value, state.bassBoostStrength / 10), style = MaterialTheme.typography.labelLarge)
                    }
                    AppSlider(
                        value = state.bassBoostStrength.toFloat(),
                        onValueChange = { scope.launch { equalizer.setBassBoost(it.roundToInt()) } },
                        valueRange = 0f..1000f, enabled = state.enabled,
                        modifier = Modifier.semantics { contentDescription = bassLabel },
                    )
                }
            }
        }
        if (state.available && processing.limiterReductionDb != null) item {
            ToneCard {
                val reduction = if (state.enabled) processing.limiterReductionDb ?: 0f else 0f
                Text(
                    stringResource(Res.string.equalizer_peak_reduction, formatReductionDb(reduction)),
                    style = MaterialTheme.typography.titleSmall,
                )
                AppLinearProgress(progress = { (reduction / 12f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.equalizer_peak_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (playbackGainControlsAvailable) {
            item {
                ToneCard {
                    Row(
                        Modifier.fillMaxWidth().toggleable(normalize, role = Role.Switch, onValueChange = settings::setNormalizeVolume),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Res.string.settings_normalize_volume), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.settings_normalize_volume_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppSwitch(checked = normalize, onCheckedChange = null)
                    }
                    if (normalize) {
                        val message = when (normalization.phase) {
                            NormalizationPhase.OFF, NormalizationPhase.WAITING -> stringResource(Res.string.normalization_waiting)
                            NormalizationPhase.MEASURING -> stringResource(Res.string.normalization_measuring)
                            NormalizationPhase.UNAVAILABLE -> stringResource(Res.string.normalization_unavailable)
                            NormalizationPhase.READY -> if (normalization.reductionDb < 0.05f) stringResource(Res.string.normalization_unchanged)
                                else stringResource(Res.string.normalization_reduced, ((normalization.reductionDb * 10).roundToInt() / 10f).toString())
                        }
                        if (normalization.phase == NormalizationPhase.MEASURING) AppLinearProgress(Modifier.fillMaxWidth())
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (boundaryFadeControlsAvailable) item {
                ToneCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.settings_crossfade), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (crossfade == 0) stringResource(Res.string.state_off) else stringResource(Res.string.settings_crossfade_value, crossfade))
                    }
                    Text(stringResource(Res.string.settings_crossfade_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppSlider(value = crossfade.toFloat(), onValueChange = { settings.setCrossfadeSeconds(it.roundToInt()) }, valueRange = 0f..MAX_CROSSFADE_SECONDS.toFloat(), steps = MAX_CROSSFADE_SECONDS - 1)
                }
            }
        }
    }
}

@Composable
private fun ToneCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp.corner()), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** The actual linear filter response on a logarithmic frequency axis. */
@Composable
private fun BandCurve(response: EqualizerFrequencyResponse, enabled: Boolean) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f)
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val scale = maxOf(12f, ceil(response.points.maxOf { abs(it.gainDb) } / 6f) * 6f)
    val description = stringResource(Res.string.equalizer_response)
    Row(Modifier.fillMaxWidth().height(112.dp)) {
        Column(Modifier.width(40.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text("+${scale.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("0 dB", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("−${scale.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp).semantics { contentDescription = description }) {
            for (fraction in listOf(0f, 0.5f, 1f)) {
                drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
            }
            val path = Path()
            response.points.forEachIndexed { index, point ->
                val x = size.width * index / response.points.lastIndex
                val y = size.height * (0.5f - point.gainDb / (2 * scale))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height / 2)
                lineTo(0f, size.height / 2)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), accent.copy(alpha = 0.02f))))
            drawPath(path, accent, style = Stroke(2.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${response.points.first().frequencyHz.roundToInt()} Hz", style = MaterialTheme.typography.labelSmall, color = labelColor)
        Text("${response.points.last().frequencyHz.roundToInt() / 1000f} kHz".replace(".0 ", " "), style = MaterialTheme.typography.labelSmall, color = labelColor)
    }
}

private fun formatReductionDb(value: Float): String = ((value * 10).roundToInt() / 10f).toString()

@Composable
private fun EqualizerBandSlider(label: String, description: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    var display by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    val currentChange by rememberUpdatedState(onChange)
    val currentValue by rememberUpdatedState(value)
    LaunchedEffect(value, dragging) { if (!dragging) display = value }
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val valueText = formatEqualizerDb(display)
    Column(
        Modifier.width(52.dp).fillMaxHeight().semantics(mergeDescendants = true) {
            contentDescription = description
            stateDescription = valueText
            progressBarRangeInfo = ProgressBarRangeInfo(display, range)
            if (!enabled) disabled() else setProgress { currentChange(it.coerceIn(range.start, range.endInclusive)); true }
        }, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valueText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
        Canvas(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)
                .pointerInput(enabled, range) {
                    if (enabled) detectTapGestures { point ->
                        val raw = range.endInclusive - point.y / size.height * (range.endInclusive - range.start)
                        currentChange(((raw / 50).roundToInt() * 50f).coerceIn(range.start, range.endInclusive))
                    }
                }
                .pointerInput(enabled, range) {
                    if (enabled) detectVerticalDragGestures(
                        onDragStart = { dragging = true; display = currentValue },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        display = (display - amount / size.height * (range.endInclusive - range.start)).coerceIn(range.start, range.endInclusive)
                        currentChange((display / 50).roundToInt() * 50f)
                    }
                },
        ) {
            val x = size.width / 2
            val y = size.height * (1 - (display - range.start) / (range.endInclusive - range.start))
            val mid = size.height * range.endInclusive / (range.endInclusive - range.start)
            drawRoundRect(track, Offset(x - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height), CornerRadius(3.dp.toPx()))
            drawLine(accent, Offset(x, mid), Offset(x, y), 6.dp.toPx())
            drawLine(accent.copy(alpha = 0.5f), Offset(x - 9.dp.toPx(), mid), Offset(x + 9.dp.toPx(), mid), 1.dp.toPx())
            drawCircle(accent, 9.dp.toPx(), Offset(x, y))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatEqualizerDb(value: Float): String {
    val half = (value / 50).roundToInt()
    val text = if (half % 2 == 0) (half / 2).toString() else (half / 2f).toString()
    return (if (half > 0) "+" else "") + text
}

@Composable
private fun equalizerPresetLabel(preset: EqualizerPreset): String = when (preset.kind) {
    EqualizerPresetKind.FLAT -> stringResource(Res.string.equalizer_preset_flat)
    EqualizerPresetKind.BASS -> stringResource(Res.string.equalizer_preset_bass)
    EqualizerPresetKind.TREBLE -> stringResource(Res.string.equalizer_preset_treble)
    EqualizerPresetKind.VOCAL -> stringResource(Res.string.equalizer_preset_vocal)
    EqualizerPresetKind.ELECTRONIC -> stringResource(Res.string.equalizer_preset_electronic)
    null -> preset.name
}

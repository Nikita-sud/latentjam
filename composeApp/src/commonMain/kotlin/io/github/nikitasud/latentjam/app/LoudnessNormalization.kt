/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.IntegratedLoudness
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.module.Module

/**
 * Measures one complete track in integrated LUFS and true peak dBTP, or null when this platform or track cannot
 * be measured. Implementations are main-safe and cancellable.
 */
internal interface TrackLoudnessMeter {
    suspend fun measure(track: TrackDescriptor): IntegratedLoudness?
}

/** Koin binding for this platform's [TrackLoudnessMeter]. */
internal expect fun loudnessMeterModule(): Module

/** Whether the platform can apply normalization to app-owned file playback. */
internal expect val playbackGainControlsAvailable: Boolean
/** Separate capability: iOS normalization does not imply support for boundary fades. */
internal expect val boundaryFadeControlsAvailable: Boolean

/** Versioned derived cache. Legacy sampled RMS rows are discarded, never reinterpreted as LUFS. */
internal data class StoredTrackLoudness(val measurement: IntegratedLoudness, val audioIdentity: String)
private const val LOUDNESS_CACHE_VERSION = "r128-v1"

internal fun encodeTrackLoudness(loudnessById: Map<String, StoredTrackLoudness>): String =
    loudnessById.entries.filter { it.value.measurement.isValid() }.joinToString("\n") { (id, stored) ->
        "$LOUDNESS_CACHE_VERSION|${id.encodeLoudnessHex()}|${stored.audioIdentity.encodeLoudnessHex()}|" +
            "${(stored.measurement.lufs * 100).toInt()}|${(stored.measurement.truePeakDbtp * 100).toInt()}"
    }

internal fun decodeTrackLoudness(payload: String): Map<String, StoredTrackLoudness> {
    val result = LinkedHashMap<String, StoredTrackLoudness>()
    for (line in payload.lineSequence()) {
        val parts = line.split('|')
        if (parts.size != 5 || parts[0] != LOUDNESS_CACHE_VERSION) continue
        val id = parts[1].decodeLoudnessHex() ?: continue
        val identity = parts[2].decodeLoudnessHex() ?: continue
        val lufs = parts[3].toIntOrNull()?.div(100f) ?: continue
        val peak = parts[4].toIntOrNull()?.div(100f) ?: continue
        val measurement = IntegratedLoudness(lufs, peak)
        if (!measurement.isValid()) continue
        result[id] = StoredTrackLoudness(measurement, identity)
    }
    return result
}

/** The complete per-track volume map playback should apply; empty when the feature is off. */
internal fun normalizationVolumes(loudnessById: Map<String, StoredTrackLoudness>, enabled: Boolean): Map<String, Float> =
    if (!enabled) emptyMap() else loudnessById.mapValues { it.value.measurement.normalizationVolume() }

/** Same id is not same audio: use every descriptor field that identifies the decoded bytes. */
internal fun TrackDescriptor.loudnessAudioIdentity(): String = buildString {
    append("loudness-r128-v1-libebur128-1.2.6-hist")
    listOf(audioUri, durationMs?.toString(), sourceRevision).forEach { field ->
        append('|')
        if (field == null) {
            append("-1:")
        } else {
            append(field.length)
            append(':')
            append(field)
        }
    }
}

internal enum class NormalizationPhase { OFF, WAITING, MEASURING, READY, UNAVAILABLE }

internal data class NormalizationStatus(
    val phase: NormalizationPhase = NormalizationPhase.OFF,
    val reductionDb: Float = 0f,
)

/**
 * Keeps playback volumes matched to measured loudness, measuring lazily as tracks play.
 *
 * There is deliberately no library-wide backfill pass: each track is measured once, on the first
 * play after the toggle goes on (bounded streaming background decode), and remembered until its
 * underlying audio identity changes.
 * Measurement failures are retried next session at most — never in a loop while a track plays.
 */
internal class LoudnessNormalizer(
    private val settings: AppSettings,
    private val playback: PlaybackController,
    private val meter: TrackLoudnessMeter,
    private val onStatus: (NormalizationStatus) -> Unit = {},
) {

    private val measured = MutableStateFlow<Map<String, StoredTrackLoudness>>(emptyMap())
    private val failedThisSession = mutableSetOf<String>()

    fun start(scope: CoroutineScope) {
        measured.value = decodeTrackLoudness(settings.readTrackLoudnessPayload().orEmpty())
        scope.launch {
            combine(measured, settings.normalizeVolume, ::normalizationVolumes)
                .distinctUntilChanged()
                .collect { volumes -> playback.setTrackVolumes(volumes) }
        }
        scope.launch {
            combine(
                playback.state.map { it.track }.distinctUntilChanged(),
                settings.normalizeVolume,
            ) { track, enabled -> track to enabled }
                .collectLatest { (track, enabled) ->
                    if (!enabled || track == null) {
                        onStatus(NormalizationStatus(if (enabled) NormalizationPhase.WAITING else NormalizationPhase.OFF))
                        return@collectLatest
                    }
                    val id = track.id.value
                    val audioIdentity = track.loudnessAudioIdentity()
                    val attemptIdentity = "$id|$audioIdentity"
                    val existing = measured.value[id]
                    if (existing?.audioIdentity == audioIdentity) {
                        reportReady(existing.measurement)
                        return@collectLatest
                    }
                    if (existing != null) {
                        // Remove the stale gain before decoding the replacement. Otherwise the old
                        // file's attenuation remains audible during the new file's measurement.
                        publish(measured.value - id)
                    }
                    if (attemptIdentity in failedThisSession) {
                        onStatus(NormalizationStatus(NormalizationPhase.UNAVAILABLE))
                        return@collectLatest
                    }
                    onStatus(NormalizationStatus(NormalizationPhase.MEASURING))
                    val measurement = try {
                        meter.measure(track)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        println("Loudness: measurement failed: $failure")
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    if (measurement == null || !measurement.isValid()) {
                        failedThisSession += attemptIdentity
                        onStatus(NormalizationStatus(NormalizationPhase.UNAVAILABLE))
                        return@collectLatest
                    }
                    publish(measured.value + (id to StoredTrackLoudness(measurement, audioIdentity)))
                    reportReady(measurement)
                }
        }
    }

    private fun reportReady(measurement: IntegratedLoudness) {
        val volume = measurement.normalizationVolume()
        onStatus(NormalizationStatus(NormalizationPhase.READY, if (volume >= 1f) 0f else -20f * kotlin.math.log10(volume)))
    }

    /** A preference failure must not permanently kill the app-lifetime measurement collector. */
    private fun publish(next: Map<String, StoredTrackLoudness>) {
        measured.value = next
        runCatching { settings.writeTrackLoudnessPayload(encodeTrackLoudness(next)) }
            .onFailure { failure -> println("Loudness: persistence failed: $failure") }
    }
}

private fun String.encodeLoudnessHex(): String = encodeToByteArray().joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun String.decodeLoudnessHex(): String? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
}

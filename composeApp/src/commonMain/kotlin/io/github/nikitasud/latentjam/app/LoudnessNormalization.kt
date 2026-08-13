/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.smart.Loudness
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
 * Measures one track's playback loudness in dBFS, or null when this platform or track cannot
 * be measured. Implementations are main-safe and cancellable.
 */
internal interface TrackLoudnessMeter {
    suspend fun measureDb(track: TrackDescriptor): Float?
}

/** Koin binding for this platform's [TrackLoudnessMeter]. */
internal expect fun loudnessMeterModule(): Module

/** Whether the platform playback controller can apply normalization and boundary-fade gains. */
internal expect val playbackGainControlsAvailable: Boolean

/**
 * Persisted loudness codec: one `hex(trackId)|hex(audioIdentity)|centi-dB` line per measured track.
 *
 * Ids are hex-encoded because imported file identities can legally contain any character,
 * including the delimiters. Corrupt lines drop individually — one damaged row must not discard
 * a library's worth of measurements. The audio identity makes a replacement at the same track id
 * remeasure instead of inheriting the previous file's gain.
 */
internal data class StoredTrackLoudness(
    val db: Float,
    val audioIdentity: String,
)

internal fun encodeTrackLoudness(loudnessById: Map<String, StoredTrackLoudness>): String =
    loudnessById.entries
        .filter { (_, measurement) -> measurement.db.isValidMeasuredLoudness() }
        .joinToString("\n") { (id, measurement) ->
            "${id.encodeLoudnessHex()}|${measurement.audioIdentity.encodeLoudnessHex()}|" +
                "${(measurement.db * 100).toInt()}"
        }

internal fun decodeTrackLoudness(payload: String): Map<String, StoredTrackLoudness> {
    val result = LinkedHashMap<String, StoredTrackLoudness>()
    for (line in payload.lineSequence()) {
        if (line.isBlank()) continue
        val parts = line.split('|')
        // The original two-field rows had no source identity. They are a derived cache, so dropping
        // them once is safer than applying stale attenuation to replaced media forever.
        if (parts.size != 3) continue
        val id = parts[0].decodeLoudnessHex() ?: continue
        val audioIdentity = parts[1].decodeLoudnessHex() ?: continue
        val centiDb = parts[2].toIntOrNull() ?: continue
        val db = centiDb / 100f
        if (!db.isValidMeasuredLoudness()) continue
        result[id] = StoredTrackLoudness(db = db, audioIdentity = audioIdentity)
    }
    return result
}

/** The complete per-track volume map playback should apply; empty when the feature is off. */
internal fun normalizationVolumes(
    loudnessById: Map<String, StoredTrackLoudness>,
    enabled: Boolean,
): Map<String, Float> = if (!enabled) {
    emptyMap()
} else {
    loudnessById.mapValues { (_, measurement) -> Loudness.normalizationVolume(measurement.db) }
}

/** Same id is not same audio: use every descriptor field that identifies the decoded bytes/window. */
internal fun TrackDescriptor.loudnessAudioIdentity(): String = buildString {
    append("loudness-v2")
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

/**
 * Keeps playback volumes matched to measured loudness, measuring lazily as tracks play.
 *
 * There is deliberately no library-wide backfill pass: each track is measured once, on the first
 * play after the toggle goes on (a few seconds of background decode), and remembered until its
 * underlying audio identity changes.
 * Measurement failures are retried next session at most — never in a loop while a track plays.
 */
internal class LoudnessNormalizer(
    private val settings: AppSettings,
    private val playback: PlaybackController,
    private val meter: TrackLoudnessMeter,
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
            ) { track, enabled -> track.takeIf { enabled } }
                .collectLatest { track ->
                    if (track == null) return@collectLatest
                    val id = track.id.value
                    val audioIdentity = track.loudnessAudioIdentity()
                    val attemptIdentity = "$id|$audioIdentity"
                    val existing = measured.value[id]
                    if (existing?.audioIdentity == audioIdentity) return@collectLatest
                    if (existing != null) {
                        // Remove the stale gain before decoding the replacement. Otherwise the old
                        // file's attenuation remains audible during the new file's measurement.
                        publish(measured.value - id)
                    }
                    if (attemptIdentity in failedThisSession) return@collectLatest
                    val db = try {
                        meter.measureDb(track)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        println("Loudness: measurement failed: $failure")
                        null
                    }
                    if (db == null || !db.isValidMeasuredLoudness()) {
                        failedThisSession += attemptIdentity
                        return@collectLatest
                    }
                    publish(measured.value + (id to StoredTrackLoudness(db, audioIdentity)))
                }
        }
    }

    /** A preference failure must not permanently kill the app-lifetime measurement collector. */
    private fun publish(next: Map<String, StoredTrackLoudness>) {
        measured.value = next
        runCatching { settings.writeTrackLoudnessPayload(encodeTrackLoudness(next)) }
            .onFailure { failure -> println("Loudness: persistence failed: $failure") }
    }
}

/** Decoder samples are clamped, so a real RMS result is finite and lies between -100 and 0 dBFS. */
private fun Float.isValidMeasuredLoudness(): Boolean = isFinite() && this in -100f..0f

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

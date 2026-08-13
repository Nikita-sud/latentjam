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

/**
 * Persisted loudness codec: one `hex(trackId)|centi-dB` line per measured track.
 *
 * Ids are hex-encoded because imported file identities can legally contain any character,
 * including the delimiters. Corrupt lines drop individually — one damaged row must not discard
 * a library's worth of measurements.
 */
internal fun encodeTrackLoudness(loudnessDbById: Map<String, Float>): String =
    loudnessDbById.entries.joinToString("\n") { (id, db) ->
        "${id.encodeLoudnessHex()}|${(db * 100).toInt()}"
    }

internal fun decodeTrackLoudness(payload: String): Map<String, Float> {
    val result = LinkedHashMap<String, Float>()
    for (line in payload.lineSequence()) {
        if (line.isBlank()) continue
        val parts = line.split('|')
        if (parts.size != 2) continue
        val id = parts[0].decodeLoudnessHex() ?: continue
        val centiDb = parts[1].toIntOrNull() ?: continue
        result[id] = centiDb / 100f
    }
    return result
}

/** The complete per-track volume map playback should apply; empty when the feature is off. */
internal fun normalizationVolumes(
    loudnessDbById: Map<String, Float>,
    enabled: Boolean,
): Map<String, Float> = if (!enabled) {
    emptyMap()
} else {
    loudnessDbById.mapValues { (_, db) -> Loudness.normalizationVolume(db) }
}

/**
 * Keeps playback volumes matched to measured loudness, measuring lazily as tracks play.
 *
 * There is deliberately no library-wide backfill pass: each track is measured once, on the first
 * play after the toggle goes on (a few seconds of background decode), and remembered forever.
 * Measurement failures are retried next session at most — never in a loop while a track plays.
 */
internal class LoudnessNormalizer(
    private val settings: AppSettings,
    private val playback: PlaybackController,
    private val meter: TrackLoudnessMeter,
) {

    private val measured = MutableStateFlow<Map<String, Float>>(emptyMap())
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
                    if (id in measured.value || id in failedThisSession) return@collectLatest
                    val db = try {
                        meter.measureDb(track)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        println("Loudness: measurement failed: $failure")
                        null
                    }
                    if (db == null) {
                        failedThisSession += id
                        return@collectLatest
                    }
                    val next = measured.value + (id to db)
                    measured.value = next
                    settings.writeTrackLoudnessPayload(encodeTrackLoudness(next))
                }
        }
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

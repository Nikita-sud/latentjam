/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.IntegratedLoudness
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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

/** The row after the playing one in the order it will play, or null at the end of the queue. */
internal fun NowPlaying.upcomingTrack(): TrackDescriptor? =
    if (queueIndex < 0) null else queue.getOrNull(queueIndex + 1)

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
 * There is deliberately no library-wide backfill pass: each track is measured once, while the track
 * before it in the queue plays (or on its own first play when nothing came before), with a bounded
 * streaming background decode, and remembered until its underlying audio identity changes.
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

    /**
     * Decodes in flight by attempt identity, in the app scope rather than the per-track block: the
     * next track's scan carries on when that track starts instead of starting over. Only the
     * measuring blocks below touch it, and they never run concurrently.
     */
    private val inFlight = mutableMapOf<String, Deferred<IntegratedLoudness?>>()
    private lateinit var measureScope: CoroutineScope

    fun start(scope: CoroutineScope) {
        measureScope = scope
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
                    keepMeasuring(track.takeIf { enabled })
                    if (!enabled || track == null) {
                        onStatus(NormalizationStatus(if (enabled) NormalizationPhase.WAITING else NormalizationPhase.OFF))
                        return@collectLatest
                    }
                    when (val result = ensureMeasured(track, reportsProgress = true)) {
                        is Measured.Ready -> reportReady(result.measurement)
                        Measured.Unavailable -> onStatus(NormalizationStatus(NormalizationPhase.UNAVAILABLE))
                    }
                    // Then what plays next, while this track plays, so its first second already
                    // sounds at the right level instead of dipping once a mid-song scan finishes.
                    // Its own key: SMART appending the next row must not restart the scan above.
                    playback.state.map { it.upcomingTrack() }.distinctUntilChanged().collectLatest { upcoming ->
                        keepMeasuring(track, upcoming)
                        if (upcoming != null && upcoming.id != track.id) ensureMeasured(upcoming, reportsProgress = false)
                    }
                }
        }
    }

    private sealed interface Measured {
        data class Ready(val measurement: IntegratedLoudness) : Measured
        data object Unavailable : Measured
    }

    /** Cached when the audio identity still matches; otherwise decoded once per session attempt. */
    private suspend fun ensureMeasured(track: TrackDescriptor, reportsProgress: Boolean): Measured {
        val id = track.id.value
        val audioIdentity = track.loudnessAudioIdentity()
        val attemptIdentity = track.loudnessAttempt()
        val existing = measured.value[id]
        if (existing?.audioIdentity == audioIdentity) return Measured.Ready(existing.measurement)
        if (existing != null) {
            // Remove the stale gain before decoding the replacement. Otherwise the old
            // file's attenuation remains audible during the new file's measurement.
            publish(measured.value - id)
        }
        if (attemptIdentity in failedThisSession) return Measured.Unavailable
        if (reportsProgress) onStatus(NormalizationStatus(NormalizationPhase.MEASURING))
        val job = inFlight[attemptIdentity]?.takeUnless { it.isCancelled }
            ?: measureScope.async { measureOrNull(track) }.also { inFlight[attemptIdentity] = it }
        val measurement = try {
            job.await()
        } catch (cancelled: CancellationException) {
            // Our own cancellation rethrows; a scan dropped under us is simply not available now.
            currentCoroutineContext().ensureActive()
            return Measured.Unavailable
        }
        currentCoroutineContext().ensureActive()
        if (measurement == null || !measurement.isValid()) {
            failedThisSession += attemptIdentity
            return Measured.Unavailable
        }
        publish(measured.value + (id to StoredTrackLoudness(measurement, audioIdentity)))
        return Measured.Ready(measurement)
    }

    private suspend fun measureOrNull(track: TrackDescriptor): IntegratedLoudness? = try {
        meter.measure(track)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("Loudness: measurement failed: $failure")
        null
    }

    /** Scans for anything but the playing and the next track are no longer wanted. */
    private fun keepMeasuring(vararg tracks: TrackDescriptor?) {
        val keep = tracks.mapNotNullTo(HashSet()) { it?.loudnessAttempt() }
        val entries = inFlight.entries.iterator()
        while (entries.hasNext()) {
            val (identity, job) = entries.next()
            // A finished scan stays too: its result may not be published yet, and dropping it would
            // decode the track again from the start.
            if (identity in keep && !job.isCancelled) continue
            job.cancel()
            entries.remove()
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

private fun TrackDescriptor.loudnessAttempt(): String = "${id.value}|${loudnessAudioIdentity()}"

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

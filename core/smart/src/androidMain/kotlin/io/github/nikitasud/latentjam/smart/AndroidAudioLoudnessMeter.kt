/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Measures a track's playback loudness from a few short decoded windows.
 *
 * Reuses the embedding pipeline's decoder, so anything it can embed it can also measure. The
 * result feeds [Loudness.normalizationVolume]; a null simply leaves that track at full volume.
 */
public class AndroidAudioLoudnessMeter(context: Context) {

    private val decoder = AndroidAudioDecoder(context)

    /** RMS loudness in dBFS near the start/middle/late sections, or null when unmeasurable. */
    public suspend fun measureDb(audioUri: String, durationMs: Long?): Float? =
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(audioUri) }.getOrNull() ?: return@withContext null
            val positions = windowStartsMs(durationMs)
            val context = currentCoroutineContext()
            val windows = positions.mapNotNull { startMs ->
                val decoded = try {
                    decoder.decodeWindowMono(
                        uri = uri,
                        startMs = startMs,
                        targetSampleRate = SAMPLE_RATE,
                        targetSamples = WINDOW_SAMPLES,
                        downmixMode = AudioDownmixMode.PRESERVE_CHANNEL_POWER,
                        isCancelled = { !context.isActive },
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                (decoded as? AudioDecodeResult.Success)?.let { success ->
                    Loudness.Window(success.waveform, success.validSamples)
                }
            }
            Loudness.measureDb(windows)
        }

    private fun windowStartsMs(durationMs: Long?): List<Long> {
        val duration = durationMs?.takeIf { it > WINDOW_MS * 2 } ?: return listOf(0L)
        return listOf(0.1, 0.4, 0.7).map { share ->
            ((duration * share).toLong()).coerceIn(0L, duration - WINDOW_MS)
        }.distinct()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_MS = 5_000L
        const val WINDOW_SAMPLES = (SAMPLE_RATE * WINDOW_MS / 1000).toInt()
    }
}

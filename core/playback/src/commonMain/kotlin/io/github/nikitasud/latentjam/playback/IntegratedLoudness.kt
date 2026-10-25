/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.pow

/**
 * Full-track EBU R128 measurement. Never construct this from the legacy sampled RMS cache. [sound]
 * is where the track's sound begins and ends, found in the same pass; null when it has none or was
 * measured before sound edges were.
 */
public data class IntegratedLoudness(
    public val lufs: Float,
    public val truePeakDbtp: Float,
    public val sound: SoundSpan? = null,
) {
    public fun isValid(): Boolean = lufs.isFinite() && lufs in -100f..10f &&
        truePeakDbtp.isFinite() && truePeakDbtp in -160f..40f

    /** Attenuation only. Also keep the measured source true peak below -1 dBTP.
     * This headroom describes the source; subsequent EQ has its own sample-peak limiter. */
    public fun normalizationVolume(): Float {
        if (!isValid()) return 1f
        val gainDb = minOf(0f, -14f - lufs, -1f - truePeakDbtp)
        return 10.0.pow(gainDb / 20.0).toFloat().coerceIn(0f, 1f)
    }
}

/** Background-only native meter. Each owner must close it, including cancellation/failure paths. */
internal expect class NativeLoudnessMeter(sampleRate: Int, channels: Int) {
    fun add(samples: FloatArray, frames: Int): Boolean
    fun finish(): IntegratedLoudness?
    fun close()
}

/** Resource caps are refusals, never a license to label a partial scan as integrated LUFS. */
internal object LoudnessScanLimits {
    const val CHUNK_FRAMES = 4096
    const val MAX_DURATION_MS = 6L * 60 * 60 * 1000
    const val WALL_BUDGET_MS = 120_000L
    const val IDLE_BUDGET_MS = 5_000L

    fun complete(frames: Long, rate: Int, expectedDurationMs: Long?): Boolean {
        if (rate !in 8000..192000 || frames < (rate + 5L) / 10 * 4) return false
        if (frames > MAX_DURATION_MS * rate / 1000) return false
        val duration = frames * 1000 / rate
        // Container durations can include encoder delay/padding and rounding. Gross truncation,
        // including an early apparent EOS, must not be cached as a complete measurement.
        val expected = expectedDurationMs?.takeIf { it > 0 } ?: return true
        return kotlin.math.abs(duration - expected) <= maxOf(2_000L, expected / 50)
    }
}

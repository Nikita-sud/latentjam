/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.log10

/** Audio-owned primitive writes; UI snapshots are allocated only by an active collector. */
internal class EqualizerMeter {
    @Volatile private var sampleRate = 0
    @Volatile private var heldGain = 1f
    @Volatile private var lastReport = Long.MIN_VALUE
    private var lastPeak = Long.MIN_VALUE

    fun report(rate: Int, minimumGain: Float, now: Long = System.nanoTime()) {
        sampleRate = rate
        val gain = minimumGain.coerceIn(0.000001f, 1f)
        if (lastPeak == Long.MIN_VALUE || now - lastPeak >= HOLD_NANOS || gain <= heldGain) {
            heldGain = gain
            lastPeak = now
        }
        lastReport = now
    }

    fun snapshot(now: Long = System.nanoTime()): EqualizerProcessingState {
        val recent = lastReport != Long.MIN_VALUE && now - lastReport <= STALE_NANOS
        return EqualizerProcessingState(sampleRate.takeIf { it > 0 },
            if (recent && heldGain < 1f) (-20 * log10(heldGain.toDouble())).toFloat() else 0f)
    }

    companion object {
        private const val HOLD_NANOS = 400_000_000L
        private const val STALE_NANOS = 500_000_000L
    }
}

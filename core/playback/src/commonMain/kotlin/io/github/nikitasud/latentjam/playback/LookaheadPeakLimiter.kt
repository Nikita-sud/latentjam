/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Stereo-linked sample-peak limiter with 5 ms lookahead, 20 ms peak hold and 100 ms release.
 * A sliding maximum gives an instantaneous-attack detector; a moving average smooths its gain.
 * The audio delay matches the smoothing window: every sample's required attenuation is present
 * throughout that window before the sample is emitted, so smoothing cannot miss a sharp peak.
 *
 * Memory is allocated at construction; the monotonic peak deque takes amortized O(1) per frame.
 * This bounds digital sample peaks, not reconstructed inter-sample/analogue peaks.
 */
internal class LookaheadPeakLimiter(sampleRate: Int, private val channels: Int) {
    init { require(sampleRate > 0 && channels > 0) }

    val latencyFrames: Int = max(1, sampleRate / 200)
    private val window = latencyFrames + 1
    private val holdFrames = max(latencyFrames, sampleRate / 50)
    private val delayed = FloatArray(window * channels)
    private val gains = DoubleArray(window) { 1.0 }
    private val peakValues = DoubleArray(holdFrames + 2)
    private val peakFrames = LongArray(peakValues.size)
    private var head = 0
    private var count = 0
    private var frameNumber = 0L
    private var position = 0
    private var gainSum = window.toDouble()
    private var envelope = 1.0
    private val release = exp(-1.0 / (sampleRate * 0.1))

    /** In place. The first [latencyFrames] outputs are zero; feed zeros to drain delayed audio. */
    fun process(frame: FloatArray, ceiling: Float = 1f) {
        require(frame.size == channels && ceiling > 0f && ceiling <= 1f)
        var peak = 0.0
        for (channel in 0 until channels) {
            val sample = if (frame[channel].isFinite()) frame[channel] else 0f
            delayed[position * channels + channel] = sample
            peak = max(peak, abs(sample.toDouble()))
        }
        peak /= ceiling
        while (count > 0 && peakFrames[head] < frameNumber - holdFrames) {
            head = (head + 1) % peakValues.size
            count--
        }
        while (count > 0 && peakValues[(head + count - 1) % peakValues.size] <= peak) count--
        val tail = (head + count) % peakValues.size
        peakValues[tail] = peak
        peakFrames[tail] = frameNumber++
        count++

        val required = 1.0 / max(1.0, peakValues[head])
        envelope = minOf(required, 1.0 - (1.0 - envelope) * release)
        if (envelope > 0.999999 && required == 1.0) envelope = 1.0
        gainSum += envelope - gains[position]
        gains[position] = envelope
        val gain = (gainSum / window).coerceIn(0.0, 1.0)
        val oldest = (position + 1) % window
        for (channel in 0 until channels) {
            // The clamp only guards floating-point rounding at the ceiling.
            frame[channel] = (delayed[oldest * channels + channel] * gain).coerceIn(-1.0, 1.0).toFloat()
        }
        position = oldest
    }
}

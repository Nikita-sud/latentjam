/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

public data class EqualizerResponsePoint(public val frequencyHz: Float, public val gainDb: Float)

/** Linear response, including static headroom; the signal-dependent limiter is metered separately. */
public data class EqualizerFrequencyResponse(
    public val sampleRateHz: Int,
    public val headroomDb: Float,
    public val points: List<EqualizerResponsePoint>,
)

/** Evaluate outside the audio callback. Native effects with an unknown response return null. */
public fun EqualizerState.frequencyResponse(sampleRateHz: Int = 48000): EqualizerFrequencyResponse? {
    if (!supportsFrequencyResponse) return null
    require(sampleRateHz > 0)
    val settings = SoftwareEqualizerSettings(
        enabled = enabled,
        levels = SoftwareEqualizerSettings.FREQUENCIES.map { frequency ->
            bands.firstOrNull { it.centreFrequencyHz == frequency }?.levelMillibels ?: 0
        },
        bass = bassBoostStrength,
    ).sanitized()
    val upper = minOf(20000.0, sampleRateHz * 0.49)
    val lower = minOf(20.0, upper / 10)
    val frequencies = FloatArray(121) { index -> (lower * (upper / lower).pow(index / 120.0)).toFloat() }
    val native = nativeEqualizerResponse(sampleRateHz, settings, frequencies)
    return EqualizerFrequencyResponse(sampleRateHz, native.headroomDb, frequencies.indices.map { index ->
        EqualizerResponsePoint(frequencies[index], native.gainsDb[index])
    })
}

/** Kotlin reference retained for numerical comparison tests; production uses Signalsmith C++. */
internal class EqualizerFilterDesign(private val sampleRate: Int, value: SoftwareEqualizerSettings) {
    val coefficients: List<DoubleArray> = if (!value.enabled) emptyList() else buildList {
        SoftwareEqualizerSettings.FREQUENCIES.forEachIndexed { index, frequency ->
            val gain = value.levels[index] / 100.0
            if (gain != 0.0 && frequency < sampleRate * 0.45) add(peak(frequency.toDouble(), gain))
        }
        if (value.bass > 0) add(lowShelf(minOf(100.0, sampleRate * 0.1), value.bass * 0.009))
    }
    val headroomDb: Double = if (coefficients.isEmpty()) 0.0 else {
        var boostDb = value.bass * 0.009
        for (index in SoftwareEqualizerSettings.FREQUENCIES.indices) {
            if (SoftwareEqualizerSettings.FREQUENCIES[index] < sampleRate * 0.45) {
                boostDb = max(boostDb, value.levels[index] / 100.0)
            }
        }
        boostDb.coerceIn(0.0, 2.0)
    }
    val gain: Double = 10.0.pow(-headroomDb / 20.0)

    fun responseDb(frequency: Double): Double {
        val w = 2 * PI * frequency / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        var result = -headroomDb
        for (c in coefficients) {
            val nr = c[0] + c[1] * c1 + c[2] * c2
            val ni = c[1] * s1 + c[2] * s2
            val dr = 1 + c[3] * c1 + c[4] * c2
            val di = c[3] * s1 + c[4] * s2
            result += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        return result
    }

    private fun peak(frequency: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * frequency / sampleRate
        val alpha = sin(w) / (2 * 1.4)
        return normalized(1 + alpha * a, -2 * cos(w), 1 - alpha * a,
            1 + alpha / a, -2 * cos(w), 1 - alpha / a)
    }

    private fun lowShelf(frequency: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * frequency / sampleRate
        val c = cos(w)
        val t = sqrt(2 * a) * sin(w)
        return normalized(
            a * ((a + 1) - (a - 1) * c + t),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - t),
            (a + 1) + (a - 1) * c + t,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - t,
        )
    }

    private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): DoubleArray =
        doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

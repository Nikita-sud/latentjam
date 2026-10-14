/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable settings shared with the audio thread; never mutate a published band list. */
internal data class SoftwareEqualizerSettings(
    val enabled: Boolean = false,
    val levels: List<Int> = List(10) { 0 },
    val bass: Int = 0,
    val preset: Int? = null,
) {
    fun sanitized(): SoftwareEqualizerSettings = copy(
        levels = List(FREQUENCIES.size) { levels.getOrElse(it) { 0 }.coerceIn(-1200, 1200) },
        bass = bass.coerceIn(0, 1000),
        preset = preset?.takeIf { it in PRESETS.indices },
    )

    fun withPreset(index: Int): SoftwareEqualizerSettings = PRESETS.getOrNull(index)?.let {
        // A selected preset is audible immediately, and replaces the entire tone setting.
        copy(enabled = true, levels = it, bass = 0, preset = index)
    } ?: this

    fun asState(): EqualizerState = EqualizerState(
        available = true,
        enabled = enabled,
        bands = FREQUENCIES.mapIndexed { index, frequency ->
            EqualizerBand(index, frequency, levels[index])
        },
        presets = EqualizerPresetKind.entries.mapIndexed { index, kind ->
            EqualizerPreset(index, kind.name, kind)
        },
        activePreset = preset,
        minLevelMillibels = -1200,
        maxLevelMillibels = 1200,
        bassBoostStrength = bass,
        bassBoostSupported = true,
    )

    companion object {
        val FREQUENCIES = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val PRESETS = listOf(
            List(10) { 0 },
            listOf(700, 600, 450, 250, 0, 0, 0, 0, 0, 0),
            listOf(0, 0, 0, 0, 0, 100, 250, 450, 600, 700),
            listOf(-200, -150, 0, 200, 400, 500, 400, 200, 0, -100),
            listOf(500, 350, 100, 0, -150, 100, 300, 450, 500, 350),
        )
    }
}

/**
 * Ten octave-spaced RBJ peaking filters and a low shelf, before the device's audio output.
 * Coefficients: https://www.w3.org/TR/audio-eq-cookbook/ (peaking EQ and low shelf, S=1).
 * Filter histories belong to each channel. Edits crossfade whole stable filter banks for 20 ms;
 * coefficients are never interpolated through an unstable intermediate filter. A linked-channel
 * lookahead limiter protects actual peaks instead of attenuating the whole track by its EQ maximum.
 */
internal class SoftwareEqualizer(
    private val sampleRate: Int,
    private val channels: Int,
    initial: SoftwareEqualizerSettings,
) {
    init { require(sampleRate > 0 && channels > 0) }

    private var settings = initial.sanitized()
    private var bank = Bank(settings)
    private var next: Bank? = null
    private val transitionFrames = max(1, sampleRate / 50)
    private var transitionPosition = 0
    private val limiter = LookaheadPeakLimiter(sampleRate, channels)

    /** Fixed for the stream, including bypass, so live toggles cannot drop or repeat samples. */
    val latencyFrames: Int get() = limiter.latencyFrames

    fun update(value: SoftwareEqualizerSettings) {
        // Coalesce rapid slider edits; finish the current 20 ms ramp before starting another.
        if (value == settings || next != null) return
        settings = value.sanitized()
        next = Bank(settings)
        transitionPosition = 0
    }

    /** One interleaved frame, in place. No allocations or locks on the sample-processing path. */
    fun process(frame: FloatArray) {
        require(frame.size == channels)
        val pending = next
        val mix = if (pending == null) 0.0 else (transitionPosition + 1).toDouble() / transitionFrames
        for (channel in 0 until channels) {
            val input = if (frame[channel].isFinite()) frame[channel].toDouble() else 0.0
            val old = bank.sample(input, channel)
            val output = if (pending == null) old else old + mix * (pending.sample(input, channel) - old)
            frame[channel] = output.toFloat()
        }
        if (pending != null && ++transitionPosition >= transitionFrames) {
            bank = pending
            next = null
        }
        // Leave 0.5 dB at the sample ceiling while processing tone. This reduces reconstruction
        // overshoots; it is not an oversampled true-peak guarantee. Flat/bypass remains bit-exact.
        val ceiling = if (bank.filters.isEmpty() && next == null) 1f else 0.9440609f
        limiter.process(frame, ceiling)
    }

    private inner class Bank(value: SoftwareEqualizerSettings) {
        val filters: List<Biquad> = if (!value.enabled) emptyList() else buildList {
            SoftwareEqualizerSettings.FREQUENCIES.forEachIndexed { index, frequency ->
                val gain = value.levels[index] / 100.0
                if (gain != 0.0 && frequency < sampleRate * 0.45) {
                    add(Biquad(peak(frequency.toDouble(), gain), channels))
                }
            }
            if (value.bass > 0) add(Biquad(lowShelf(minOf(100.0, sampleRate * 0.1), value.bass * 0.009), channels))
        }
        // Reserve a small amount for boosts, not the worst possible response of the entire
        // curve: that used to turn Bass + 100% boost down by 17.5 dB even on quiet recordings.
        // Actual overloads are handled by the lookahead limiter. Cuts need no fixed attenuation.
        private val gain = if (filters.isEmpty()) 1.0 else {
            var boostDb = value.bass * 0.009
            for (index in SoftwareEqualizerSettings.FREQUENCIES.indices) {
                if (SoftwareEqualizerSettings.FREQUENCIES[index] < sampleRate * 0.45) {
                    boostDb = max(boostDb, value.levels[index] / 100.0)
                }
            }
            10.0.pow(-boostDb.coerceIn(0.0, 2.0) / 20.0)
        }

        fun sample(input: Double, channel: Int): Double {
            var sample = input * gain
            for (index in filters.indices) sample = filters[index].sample(sample, channel)
            return sample
        }
    }

    private fun peak(frequency: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * frequency / sampleRate
        val alpha = sin(w) / (2 * 1.4) // Approximately one octave bandwidth.
        return coefficients(1 + alpha * a, -2 * cos(w), 1 - alpha * a,
            1 + alpha / a, -2 * cos(w), 1 - alpha / a)
    }

    private fun lowShelf(frequency: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * frequency / sampleRate
        val c = cos(w)
        val t = sqrt(2 * a) * sin(w)
        return coefficients(
            a * ((a + 1) - (a - 1) * c + t),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - t),
            (a + 1) + (a - 1) * c + t,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - t,
        )
    }

    private fun coefficients(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): DoubleArray =
        doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private class Biquad(private val c: DoubleArray, channels: Int) {
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)
        fun sample(input: Double, channel: Int): Double {
            val output = c[0] * input + z1[channel]
            z1[channel] = c[1] * input - c[3] * output + z2[channel]
            z2[channel] = c[2] * input - c[4] * output
            return output
        }
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.max

/** Immutable settings shared with the audio thread; never mutate a published band list. */
internal data class SoftwareEqualizerSettings(
    val enabled: Boolean = false,
    val levels: List<Int> = List(10) { 0 },
    val bass: Int = 0,
    val preset: Int? = null,
) {
    fun sanitized(): SoftwareEqualizerSettings = copy(
        levels = List(FREQUENCIES.size) { levels.getOrElse(it) { 0 }.coerceIn(MIN_LEVEL_MILLIBELS, MAX_LEVEL_MILLIBELS) },
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
        minLevelMillibels = MIN_LEVEL_MILLIBELS,
        maxLevelMillibels = MAX_LEVEL_MILLIBELS,
        bassBoostStrength = bass,
        bassBoostSupported = true,
        supportsFrequencyResponse = true,
    )

    companion object {
        const val MIN_LEVEL_MILLIBELS = -1500
        const val MAX_LEVEL_MILLIBELS = 1500
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
    val limiterGain: Float get() = limiter.currentGain

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
        // overshoots; it is not an oversampled true-peak guarantee. Flat/bypass is bit-exact within
        // the nominal ±1 PCM range; over-full-scale decoder values still activate the limiter.
        val ceiling = if (bank.filters.isEmpty() && next == null) 1f else 0.9440609f
        limiter.process(frame, ceiling)
    }

    private inner class Bank(value: SoftwareEqualizerSettings) {
        private val design = EqualizerFilterDesign(sampleRate, value)
        val filters: List<Biquad> = design.coefficients.map { Biquad(it, channels) }
        private val gain = design.gain

        fun sample(input: Double, channel: Int): Double {
            var sample = input * gain
            for (index in filters.indices) sample = filters[index].sample(sample, channel)
            return sample
        }
    }

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

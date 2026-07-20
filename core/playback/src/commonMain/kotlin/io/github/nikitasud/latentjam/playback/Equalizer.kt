/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module

/**
 * One adjustable band of the equalizer.
 *
 * @param centreFrequencyHz the band's centre, for labelling
 * @param levelMillibels current gain; millibels because that is the unit the platform effect speaks,
 *   and converting at the edges would only invite rounding drift
 */
public data class EqualizerBand(
    public val index: Int,
    public val centreFrequencyHz: Int,
    public val levelMillibels: Int,
)

/**
 * A named curve offered by the active platform audio effect.
 */
public data class EqualizerPreset(public val index: Int, public val name: String)

/**
 * @param available false when the device has no usable equalizer effect, or the platform refused to
 *   attach one. The UI shows an explanation rather than dead sliders.
 * @param minLevelMillibels lower bound of a band's gain, from the platform
 * @param activePreset index of the preset currently applied, or null once a band has been moved by
 *   hand (the curve is then no longer any named preset)
 */
public data class EqualizerState(
    public val available: Boolean = false,
    public val enabled: Boolean = false,
    public val bands: List<EqualizerBand> = emptyList(),
    public val presets: List<EqualizerPreset> = emptyList(),
    public val activePreset: Int? = null,
    public val minLevelMillibels: Int = -1500,
    public val maxLevelMillibels: Int = 1500,
    public val bassBoostStrength: Int = 0,
    public val bassBoostSupported: Boolean = false,
) {
    public val unavailableReason: String?
        get() = if (available) null else "This device does not offer a system equalizer."
}

/**
 * The equalizer attached to this app's audio output.
 *
 * The state describes the actual effect graph: Android reports the device effect's bands and iOS
 * reports the bands in LatentJam's AVAudioEngine graph. The UI therefore controls exactly the
 * filters through which app-owned playback is routed.
 *
 * Settings persist and are re-applied whenever the audio session is rebuilt, so an equalizer set
 * once survives the service being restarted.
 */
public interface EqualizerController {

    public val state: StateFlow<EqualizerState>

    /** Turns the effect on or off without discarding the curve. */
    public suspend fun setEnabled(enabled: Boolean)

    /** @param levelMillibels clamped to the platform's range. */
    public suspend fun setBandLevel(bandIndex: Int, levelMillibels: Int)

    public suspend fun applyPreset(presetIndex: Int)

    /** @param strength 0..1000, the platform's scale. */
    public suspend fun setBassBoost(strength: Int)

    /** Returns every band to flat and clears the active preset. */
    public suspend fun reset()
}

public expect fun equalizerModule(): Module

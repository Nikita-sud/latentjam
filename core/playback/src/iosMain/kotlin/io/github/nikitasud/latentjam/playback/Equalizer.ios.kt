/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [EqualizerController] — not implemented.
 *
 * iOS has no shared system equalizer to attach to; the equivalent is an `AVAudioUnitEQ` inserted
 * into the app's own audio graph, which only becomes possible once playback here is built on
 * AVAudioEngine. Reports itself unavailable so the settings screen explains the absence instead of
 * showing sliders that do nothing.
 */
internal class UnavailableEqualizerController : EqualizerController {

    private val mutableState = MutableStateFlow(EqualizerState(available = false))
    override val state: StateFlow<EqualizerState> = mutableState.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setBandLevel(bandIndex: Int, levelMillibels: Int) = Unit
    override suspend fun applyPreset(presetIndex: Int) = Unit
    override suspend fun setBassBoost(strength: Int) = Unit
    override suspend fun reset() = Unit
}

public actual fun equalizerModule(): Module = module {
    single<EqualizerController> { UnavailableEqualizerController() }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module

/** How the app picks between its light and dark palettes. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User preferences that belong to the app shell rather than to any one feature.
 *
 * Deliberately small. Anything a feature owns (equalizer curves, the SMART index) is stored by that
 * feature, so a preference screen never becomes the place where unrelated state accumulates.
 */
interface AppSettings {
    val themeMode: StateFlow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)
}

expect fun appSettingsModule(): Module

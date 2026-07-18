/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

internal class IosAppSettings : AppSettings {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val mutableTheme = MutableStateFlow(
        defaults.stringForKey(KEY_THEME)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
    )
    override val themeMode: StateFlow<ThemeMode> = mutableTheme.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        defaults.setObject(mode.name, KEY_THEME)
        mutableTheme.value = mode
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}

actual fun appSettingsModule(): Module = module {
    single<AppSettings> { IosAppSettings() }
}

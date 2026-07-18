/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

internal class AndroidAppSettings(context: Context) : AppSettings {

    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(readTheme())
    override val themeMode: StateFlow<ThemeMode> = mutableTheme.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
        mutableTheme.value = mode
    }

    /** An unknown stored value (a downgrade, a corrupt write) falls back rather than throwing. */
    private fun readTheme(): ThemeMode =
        preferences.getString(KEY_THEME, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}

actual fun appSettingsModule(): Module = module {
    single<AppSettings> { AndroidAppSettings(get()) }
}

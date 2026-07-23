/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

internal class IosAppSettings : AppSettings {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val mutableTheme = MutableStateFlow(
        defaults.stringForKey(KEY_THEME)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
    )
    override val themeMode: StateFlow<ThemeMode> = mutableTheme.asStateFlow()
    private val mutableStartPage = MutableStateFlow(
        startPageFromPersisted(defaults.stringForKey(KEY_START_PAGE)),
    )
    override val startPage: StateFlow<StartPage> = mutableStartPage.asStateFlow()
    private val mutableTrackColorMode = MutableStateFlow(
        trackColorModeFromPersisted(defaults.stringForKey(KEY_TRACK_COLOR_MODE)),
    )
    override val trackColorMode: StateFlow<TrackColorMode> = mutableTrackColorMode.asStateFlow()
    private val mutableSmartQueueLength = MutableStateFlow(readSmartQueueLength())
    override val smartQueueLength: StateFlow<Int> = mutableSmartQueueLength.asStateFlow()
    private val mutableIncludeNoveltyMixes = MutableStateFlow(readNoveltyMixPreference())
    override val includeNoveltyMixes: StateFlow<Boolean> = mutableIncludeNoveltyMixes.asStateFlow()
    private val mutableSaveListeningHistory = MutableStateFlow(readRecordingPreference(KEY_SAVE_HISTORY))
    override val saveListeningHistory: StateFlow<Boolean> = mutableSaveListeningHistory.asStateFlow()
    private val mutableRememberSearches = MutableStateFlow(readRecordingPreference(KEY_REMEMBER_SEARCHES))
    override val rememberSearches: StateFlow<Boolean> = mutableRememberSearches.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        defaults.setObject(mode.name, KEY_THEME)
        mutableTheme.value = mode
    }

    override fun setStartPage(page: StartPage) {
        defaults.setObject(page.persistedValue, KEY_START_PAGE)
        mutableStartPage.value = page
    }

    override fun setTrackColorMode(mode: TrackColorMode) {
        defaults.setObject(mode.persistedValue, KEY_TRACK_COLOR_MODE)
        mutableTrackColorMode.value = mode
    }

    override fun setSmartQueueLength(length: Int) {
        val sanitized = sanitizeSmartQueueLength(length)
        defaults.setInteger(sanitized.toLong(), KEY_SMART_QUEUE_LENGTH)
        mutableSmartQueueLength.value = sanitized
    }

    override fun setIncludeNoveltyMixes(enabled: Boolean) {
        defaults.setBool(enabled, KEY_INCLUDE_NOVELTY_MIXES)
        mutableIncludeNoveltyMixes.value = enabled
    }

    override suspend fun setSaveListeningHistory(enabled: Boolean): Result<Unit> =
        persistRecordingPreference(KEY_SAVE_HISTORY, enabled, mutableSaveListeningHistory)

    override suspend fun setRememberSearches(enabled: Boolean): Result<Unit> =
        persistRecordingPreference(KEY_REMEMBER_SEARCHES, enabled, mutableRememberSearches)

    /** Missing or type-corrupt values use the default; valid integers migrate to the nearest size. */
    private fun readSmartQueueLength(): Int {
        val stored = (defaults.objectForKey(KEY_SMART_QUEUE_LENGTH) as? NSNumber)
            ?.longLongValue
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
        return smartQueueLengthFromPersisted(stored)
    }

    private fun readNoveltyMixPreference(): Boolean = noveltyMixPreferenceFromPersisted(
        (defaults.objectForKey(KEY_INCLUDE_NOVELTY_MIXES) as? NSNumber)?.boolValue,
    )

    /** Missing or type-corrupt values keep the migration-safe historical default. */
    private fun readRecordingPreference(key: String): Boolean = recordingPreferenceFromPersisted(
        (defaults.objectForKey(key) as? NSNumber)?.boolValue,
    )

    private suspend fun persistRecordingPreference(
        key: String,
        enabled: Boolean,
        state: MutableStateFlow<Boolean>,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val previous = state.value
        runCatching {
            defaults.setBool(enabled, key)
            check(defaults.synchronize()) { "Could not persist privacy preference" }
            state.value = enabled
        }.onFailure {
            // Keep the durable value and the observable flow aligned if the flush was rejected.
            defaults.setBool(previous, key)
            defaults.synchronize()
        }
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_START_PAGE = "start_page"
        const val KEY_TRACK_COLOR_MODE = "track_color_mode"
        const val KEY_SMART_QUEUE_LENGTH = "smart_queue_length"
        const val KEY_INCLUDE_NOVELTY_MIXES = "include_novelty_mixes"
        const val KEY_SAVE_HISTORY = "save_listening_history"
        const val KEY_REMEMBER_SEARCHES = "remember_searches"
    }
}

actual fun appSettingsModule(): Module = module {
    single<AppSettings> { IosAppSettings() }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal const val APP_SETTINGS_FILE = "app_settings"
internal const val APP_THEME_KEY = "theme_mode"

internal class AndroidAppSettings(context: Context) : AppSettings {

    private val preferences = context.getSharedPreferences(APP_SETTINGS_FILE, Context.MODE_PRIVATE)
    private val mutableTheme = MutableStateFlow(readTheme())
    override val themeMode: StateFlow<ThemeMode> = mutableTheme.asStateFlow()
    private val mutableStartPage = MutableStateFlow(readStartPage())
    override val startPage: StateFlow<StartPage> = mutableStartPage.asStateFlow()
    private val mutableTrackColorMode = MutableStateFlow(readTrackColorMode())
    override val trackColorMode: StateFlow<TrackColorMode> = mutableTrackColorMode.asStateFlow()
    private val mutableSmartQueueLength = MutableStateFlow(readSmartQueueLength())
    override val smartQueueLength: StateFlow<Int> = mutableSmartQueueLength.asStateFlow()
    private val mutableIncludeNoveltyMixes = MutableStateFlow(readNoveltyMixPreference())
    override val includeNoveltyMixes: StateFlow<Boolean> = mutableIncludeNoveltyMixes.asStateFlow()
    private val mutableSaveListeningHistory = MutableStateFlow(readRecordingPreference(KEY_SAVE_HISTORY))
    override val saveListeningHistory: StateFlow<Boolean> = mutableSaveListeningHistory.asStateFlow()
    private val mutableRememberSearches = MutableStateFlow(readRecordingPreference(KEY_REMEMBER_SEARCHES))
    override val rememberSearches: StateFlow<Boolean> = mutableRememberSearches.asStateFlow()
    private val mutableResumePlayback = MutableStateFlow(readResumePlayback())
    override val resumePlayback: StateFlow<ResumePlayback?> = mutableResumePlayback.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
        mutableTheme.value = mode
    }

    override fun setStartPage(page: StartPage) {
        preferences.edit().putString(KEY_START_PAGE, page.persistedValue).apply()
        mutableStartPage.value = page
    }

    override fun setTrackColorMode(mode: TrackColorMode) {
        preferences.edit().putString(KEY_TRACK_COLOR_MODE, mode.persistedValue).apply()
        mutableTrackColorMode.value = mode
    }

    override fun setSmartQueueLength(length: Int) {
        val sanitized = sanitizeSmartQueueLength(length)
        preferences.edit().putInt(KEY_SMART_QUEUE_LENGTH, sanitized).apply()
        mutableSmartQueueLength.value = sanitized
    }

    override fun setIncludeNoveltyMixes(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_INCLUDE_NOVELTY_MIXES, enabled).apply()
        mutableIncludeNoveltyMixes.value = enabled
    }

    override suspend fun setSaveListeningHistory(enabled: Boolean): Result<Unit> =
        persistRecordingPreference(KEY_SAVE_HISTORY, enabled, mutableSaveListeningHistory)

    override suspend fun setRememberSearches(enabled: Boolean): Result<Unit> =
        persistRecordingPreference(KEY_REMEMBER_SEARCHES, enabled, mutableRememberSearches)

    /** An unknown stored value (a downgrade, a corrupt write) falls back rather than throwing. */
    private fun readTheme(): ThemeMode =
        readString(KEY_THEME)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM

    private fun readStartPage(): StartPage = startPageFromPersisted(readString(KEY_START_PAGE))

    private fun readTrackColorMode(): TrackColorMode =
        trackColorModeFromPersisted(readString(KEY_TRACK_COLOR_MODE))

    private fun readSmartQueueLength(): Int = smartQueueLengthFromPersisted(
        runCatching {
            preferences.getInt(KEY_SMART_QUEUE_LENGTH, DEFAULT_SMART_QUEUE_LENGTH)
        }.getOrNull(),
    )

    private fun readNoveltyMixPreference(): Boolean = noveltyMixPreferenceFromPersisted(
        runCatching { preferences.getBoolean(KEY_INCLUDE_NOVELTY_MIXES, false) }.getOrNull(),
    )

    /** SharedPreferences throws ClassCastException when a key was previously written as another type. */
    private fun readString(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    /** A value written under another type is treated exactly like a missing migration key. */
    private fun readRecordingPreference(key: String): Boolean = recordingPreferenceFromPersisted(
        runCatching { preferences.getBoolean(key, true) }.getOrNull(),
    )

    private fun readResumePlayback(): ResumePlayback? {
        val trackId = readString(KEY_RESUME_TRACK) ?: return null
        val mode = readString(KEY_RESUME_MODE) ?: return null
        val position = runCatching { preferences.getLong(KEY_RESUME_POSITION, 0L) }.getOrNull() ?: 0L
        return ResumePlayback(trackId = trackId, shuffleMode = mode, positionMs = position)
    }

    override fun setResumePlayback(state: ResumePlayback?) {
        preferences.edit().apply {
            if (state == null) {
                remove(KEY_RESUME_TRACK).remove(KEY_RESUME_MODE).remove(KEY_RESUME_POSITION)
            } else {
                putString(KEY_RESUME_TRACK, state.trackId)
                putString(KEY_RESUME_MODE, state.shuffleMode)
                putLong(KEY_RESUME_POSITION, state.positionMs)
            }
        }.apply()
        mutableResumePlayback.value = state
    }

    private suspend fun persistRecordingPreference(
        key: String,
        enabled: Boolean,
        state: MutableStateFlow<Boolean>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(preferences.edit().putBoolean(key, enabled).commit()) {
                "Could not persist privacy preference"
            }
            state.value = enabled
        }
    }

    private companion object {
        const val KEY_THEME = APP_THEME_KEY
        const val KEY_START_PAGE = "start_page"
        const val KEY_TRACK_COLOR_MODE = "track_color_mode"
        const val KEY_SMART_QUEUE_LENGTH = "smart_queue_length"
        const val KEY_INCLUDE_NOVELTY_MIXES = "include_novelty_mixes"
        const val KEY_SAVE_HISTORY = "save_listening_history"
        const val KEY_REMEMBER_SEARCHES = "remember_searches"
        const val KEY_RESUME_TRACK = "resume_track_id"
        const val KEY_RESUME_MODE = "resume_shuffle_mode"
        const val KEY_RESUME_POSITION = "resume_position_ms"
    }
}

actual fun appSettingsModule(): Module = module {
    single<AppSettings> { AndroidAppSettings(get()) }
}

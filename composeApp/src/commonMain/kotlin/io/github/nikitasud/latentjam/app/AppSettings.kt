/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import kotlin.math.abs

/** How the app picks between its light and dark palettes. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The browse destination shown when LatentJam is opened. */
enum class StartPage(internal val persistedValue: String) {
    FOR_YOU("for_you"),
    PLAYLISTS("playlists"),
    TRACKS("tracks"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    GENRES("genres"),
    FOLDERS("folders"),
}

/** Where the player surface gets its accent colour. */
enum class TrackColorMode(internal val persistedValue: String) {
    /** Prefer artwork and fall back to the SMART embedding when artwork has no useful colour. */
    DYNAMIC("dynamic"),

    /** Derive the colour from the track's local SMART embedding. */
    SMART("smart"),

    /** Keep the current app theme colour for every track. */
    THEME("theme"),
}

const val DEFAULT_SMART_QUEUE_LENGTH: Int = 20
val SMART_QUEUE_LENGTH_OPTIONS: List<Int> = listOf(10, DEFAULT_SMART_QUEUE_LENGTH, 40)

internal fun startPageFromPersisted(value: String?): StartPage =
    StartPage.entries.firstOrNull { it.persistedValue == value } ?: StartPage.TRACKS

internal fun trackColorModeFromPersisted(value: String?): TrackColorMode =
    TrackColorMode.entries.firstOrNull { it.persistedValue == value } ?: TrackColorMode.DYNAMIC

/**
 * Keeps callers and migrated persisted values on one of the supported queue sizes.
 *
 * A value between two options prefers the default, then the smaller option. Using [Long] for the
 * distance keeps even [Int.MIN_VALUE] and [Int.MAX_VALUE] safe.
 */
internal fun sanitizeSmartQueueLength(value: Int): Int =
    SMART_QUEUE_LENGTH_OPTIONS.minWithOrNull(
        compareBy<Int> { option -> abs(value.toLong() - option.toLong()) }
            .thenBy { option -> abs(option - DEFAULT_SMART_QUEUE_LENGTH) }
            .thenBy { it },
    ) ?: DEFAULT_SMART_QUEUE_LENGTH

internal fun smartQueueLengthFromPersisted(value: Int?): Int =
    value?.let(::sanitizeSmartQueueLength) ?: DEFAULT_SMART_QUEUE_LENGTH

/** Missing or type-corrupt privacy preferences preserve the historical opt-in default. */
internal fun recordingPreferenceFromPersisted(value: Boolean?): Boolean = value ?: true

/** Novelty/effects mixes are an explicit opt-in; a missing or corrupt value stays off. */
internal fun noveltyMixPreferenceFromPersisted(value: Boolean?): Boolean = value ?: false

/**
 * User preferences that belong to the app shell rather than to any one feature.
 *
 * Deliberately small. Anything a feature owns (equalizer curves, the SMART index) is stored by that
 * feature, so a preference screen never becomes the place where unrelated state accumulates.
 */
interface AppSettings {
    val themeMode: StateFlow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)

    val startPage: StateFlow<StartPage>
    fun setStartPage(page: StartPage)

    val trackColorMode: StateFlow<TrackColorMode>
    fun setTrackColorMode(mode: TrackColorMode)

    val smartQueueLength: StateFlow<Int>
    fun setSmartQueueLength(length: Int)

    /**
     * Whether For You may surface separately routed meme/viral and sound-effect clusters.
     *
     * These tracks are never folded into music mixes. This preference only controls whether their
     * own clearly named mixes are visible.
     */
    val includeNoveltyMixes: StateFlow<Boolean>
    fun setIncludeNoveltyMixes(enabled: Boolean)

    /** Whether new playback sessions are written to the private on-device listening log. */
    val saveListeningHistory: StateFlow<Boolean>

    /**
     * Persists [enabled] before changing the observable value.
     *
     * Unlike purely visual preferences, privacy controls must not claim to be off when the durable
     * write failed and the next launch would silently turn them back on.
     */
    suspend fun setSaveListeningHistory(enabled: Boolean): Result<Unit>

    /** Whether searches submitted from the library are added to the private recent-query list. */
    val rememberSearches: StateFlow<Boolean>

    /** See [setSaveListeningHistory]; the same persistence guarantee applies to search history. */
    suspend fun setRememberSearches(enabled: Boolean): Result<Unit>
}

expect fun appSettingsModule(): Module

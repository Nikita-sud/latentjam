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
    MAP("map"),
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

/** Live SMART queue plus its independent canonical source, persisted as one atomic value. */
internal data class ResumeQueueState(
    val queueTrackIds: List<String>,
    val sourceQueueTrackIds: List<String>,
    val queueIndex: Int,
    val sourceQueuePersisted: Boolean = true,
)

/**
 * Collision-safe queue codec.
 *
 * Track ids are opaque (imported file identities can legally contain commas), so each id is
 * length-prefixed instead of delimiter-escaped. Both lists and the current index share one encoded
 * value, preventing a crash between separate writes from pairing a new SMART queue with an old
 * source playlist.
 */
internal fun encodeResumeQueueState(state: ResumeQueueState): String = buildString {
    val sourceIds = state.sourceQueueTrackIds.takeIf { state.sourceQueuePersisted }.orEmpty()
    append(RESUME_QUEUE_STATE_PREFIX)
    append(state.queueIndex)
    append('|')
    append(state.queueTrackIds.size)
    append('|')
    state.queueTrackIds.forEach { id ->
        append(id.length)
        append(':')
        append(id)
    }
    append('|')
    append(if (state.sourceQueuePersisted) 1 else 0)
    append('|')
    append(sourceIds.size)
    append('|')
    sourceIds.forEach { id ->
        append(id.length)
        append(':')
        append(id)
    }
}

/** Returns null for a different version or any truncated/type-corrupt payload. */
internal fun decodeResumeQueueState(value: String): ResumeQueueState? {
    if (!value.startsWith(RESUME_QUEUE_STATE_PREFIX)) return null
    var offset = RESUME_QUEUE_STATE_PREFIX.length

    fun readIntToken(): Int? {
        val delimiter = value.indexOf('|', startIndex = offset)
        if (delimiter < 0) return null
        val parsed = value.substring(offset, delimiter).toIntOrNull()
        offset = delimiter + 1
        return parsed
    }

    fun readIds(count: Int): List<String>? {
        if (count !in 0..MAX_DECODED_RESUME_QUEUE_IDS) return null
        val ids = ArrayList<String>(count)
        repeat(count) {
            val colon = value.indexOf(':', startIndex = offset)
            if (colon < 0) return null
            val length = value.substring(offset, colon).toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: return null
            val start = colon + 1
            if (length > value.length - start) return null
            val end = start + length
            ids += value.substring(start, end)
            offset = end
        }
        return ids
    }

    val queueIndex = readIntToken() ?: return null
    val queueCount = readIntToken() ?: return null
    val queueIds = readIds(queueCount) ?: return null
    if (value.getOrNull(offset) != '|') return null
    offset++
    val sourcePersisted = when (readIntToken()) {
        0 -> false
        1 -> true
        else -> return null
    }
    val sourceCount = readIntToken() ?: return null
    val sourceIds = readIds(sourceCount) ?: return null
    if (!sourcePersisted && sourceIds.isNotEmpty()) return null
    if (offset != value.length) return null
    return ResumeQueueState(
        queueTrackIds = queueIds,
        sourceQueueTrackIds = sourceIds,
        queueIndex = queueIndex,
        sourceQueuePersisted = sourcePersisted,
    )
}

private const val RESUME_QUEUE_STATE_PREFIX = "LJQ2|"
private const val MAX_DECODED_RESUME_QUEUE_IDS = 10_000

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

    /** The playback session to restore at launch, or null when nothing was saved. */
    val resumePlayback: StateFlow<ResumePlayback?>

    /** Fire-and-forget: written on every track/mode change, so best-effort like the visual prefs. */
    fun setResumePlayback(state: ResumePlayback?)
}

/**
 * Where listening stood when the app last ran: enough to put the same track back in the player,
 * paused, with the same shuffle mode — so SMART stays on across launches without being re-armed.
 *
 * [shuffleMode] is the mode's name rather than the enum so a persisted value from a build with
 * different modes degrades to "no restore" instead of crashing launch.
 */
data class ResumePlayback(
    val trackId: String,
    val shuffleMode: String,
    val positionMs: Long,
    /**
     * [QueueSourceKind] name of what the queue was started from, or null when unknown — including
     * every session saved before sources existed. Stored as a name for the same reason as
     * [shuffleMode].
     */
    val sourceKind: String? = null,
    /** Display name for name-bearing sources (collection title, search query). */
    val sourceName: String? = null,
    /** Stable source identity used to reconstruct an omitted oversized queue when available. */
    val sourceReference: String? = null,
    /**
     * The queue's track ids in play order, so a restart restores the queue that was actually
     * playing — a playlist stays that playlist — instead of wrapping the track in the whole
     * library. Empty for sessions saved before this existed, or when the queue was too large
     * to be worth persisting; restore falls back to the whole-library wrap then.
     */
    val queueTrackIds: List<String> = emptyList(),
    /** Index of the saved current row in [queueTrackIds], or -1 for legacy sessions. */
    val queueIndex: Int = -1,
    /**
     * Canonical natural-order source retained independently from a generated SMART queue. Empty
     * for legacy sessions or when an oversized source is reconstructed from [sourceReference].
     */
    val sourceQueueTrackIds: List<String> = emptyList(),
    /** Whether [sourceQueueTrackIds] was explicitly saved; distinguishes known empty from absent. */
    val sourceQueuePersisted: Boolean = false,
)

expect fun appSettingsModule(): Module

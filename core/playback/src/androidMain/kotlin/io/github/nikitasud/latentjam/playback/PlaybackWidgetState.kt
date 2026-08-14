/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings

/**
 * Small, process-independent view of the media session for home-screen widgets.
 *
 * The snapshot deliberately contains URIs rather than decoded artwork and a pair of monotonic
 * timing anchors rather than periodically persisted progress. A widget can project [positionMs]
 * while playback remains live in the same boot, then wait for the next player event to persist a
 * new anchor.
 */
public data class PlaybackWidgetSnapshot(
    public val revision: Long = 0,
    public val mediaId: String = "",
    public val title: String = "",
    public val artist: String = "",
    public val artworkUri: String? = null,
    public val isPlaying: Boolean = false,
    public val positionMs: Long = 0,
    public val durationMs: Long = 0,
    public val hasPrevious: Boolean = false,
    public val hasNext: Boolean = false,
    public val repeatMode: RepeatMode = RepeatMode.OFF,
    public val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    public val nextTitle: String? = null,
    public val capturedElapsedRealtimeMs: Long = 0,
    public val capturedBootCount: Int = UNKNOWN_BOOT_COUNT,
) {
    /** True only while the persisted play anchor is valid for this device boot. */
    public fun isLivePlaying(
        currentElapsedRealtimeMs: Long,
        currentBootCount: Int,
    ): Boolean {
        if (!hasValidPlayingAnchor(currentElapsedRealtimeMs, currentBootCount)) return false
        return durationMs <= 0 || unboundedProjectedPositionMs(currentElapsedRealtimeMs) < durationMs
    }

    /**
     * Projects the playhead from the last player event without a service/widget update ticker.
     *
     * A reboot (or otherwise invalid monotonic clock) freezes the last stored position. Known
     * durations cap the result, so a stale-but-same-boot snapshot cannot run past the track end.
     */
    public fun projectedPositionMs(
        currentElapsedRealtimeMs: Long,
        currentBootCount: Int,
    ): Long {
        val storedPosition = positionMs.coerceAtLeast(0)
        val projected = if (hasValidPlayingAnchor(currentElapsedRealtimeMs, currentBootCount)) {
            unboundedProjectedPositionMs(currentElapsedRealtimeMs)
        } else {
            storedPosition
        }
        return if (durationMs > 0) projected.coerceAtMost(durationMs) else projected
    }

    private fun hasValidPlayingAnchor(
        currentElapsedRealtimeMs: Long,
        currentBootCount: Int,
    ): Boolean = isPlaying &&
        capturedBootCount != UNKNOWN_BOOT_COUNT &&
        currentBootCount != UNKNOWN_BOOT_COUNT &&
        capturedBootCount == currentBootCount &&
        capturedElapsedRealtimeMs >= 0 &&
        currentElapsedRealtimeMs >= capturedElapsedRealtimeMs

    private fun unboundedProjectedPositionMs(currentElapsedRealtimeMs: Long): Long {
        val storedPosition = positionMs.coerceAtLeast(0)
        val elapsed = currentElapsedRealtimeMs - capturedElapsedRealtimeMs
        return if (storedPosition > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else storedPosition + elapsed
    }

    public companion object {
        /** Sentinel used when Android cannot expose the current boot generation. */
        public const val UNKNOWN_BOOT_COUNT: Int = -1
    }
}

/** Chooses controller modes before a widget command reaches a newly created, empty service. */
internal fun initialPlaybackModes(
    mediaItemCount: Int,
    nativeRepeatMode: RepeatMode,
    registryShuffleMode: ShuffleMode,
    nativeShuffleEnabled: Boolean,
    widgetSnapshot: PlaybackWidgetSnapshot,
): Pair<RepeatMode, ShuffleMode> {
    val coldWidgetState = widgetSnapshot.takeIf {
        mediaItemCount == 0 && it.mediaId.isNotBlank()
    }
    if (coldWidgetState != null) {
        return coldWidgetState.repeatMode to coldWidgetState.shuffleMode
    }
    val shuffleMode = if (
        registryShuffleMode == ShuffleMode.OFF && nativeShuffleEnabled
    ) {
        ShuffleMode.ON
    } else {
        registryShuffleMode
    }
    return nativeRepeatMode to shuffleMode
}

/** Persistent bridge between the Media3 service and app-widget receivers. */
public object PlaybackWidgetStateStore {
    /** Reads the last complete snapshot, returning an empty paused value before first playback. */
    public fun read(context: Context): PlaybackWidgetSnapshot {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_FILE,
            Context.MODE_PRIVATE,
        )
        return PlaybackWidgetSnapshot(
            revision = preferences.safeLong(KEY_REVISION, 0),
            mediaId = preferences.safeString(KEY_MEDIA_ID).orEmpty(),
            title = preferences.safeString(KEY_TITLE).orEmpty(),
            artist = preferences.safeString(KEY_ARTIST).orEmpty(),
            artworkUri = preferences.safeString(KEY_ARTWORK_URI),
            isPlaying = preferences.safeBoolean(KEY_IS_PLAYING, false),
            positionMs = preferences.safeLong(KEY_POSITION_MS, 0).coerceAtLeast(0),
            durationMs = preferences.safeLong(KEY_DURATION_MS, 0).coerceAtLeast(0),
            hasPrevious = preferences.safeBoolean(KEY_HAS_PREVIOUS, false),
            hasNext = preferences.safeBoolean(KEY_HAS_NEXT, false),
            repeatMode = preferences.safeEnum(KEY_REPEAT_MODE, RepeatMode.OFF),
            shuffleMode = preferences.safeEnum(KEY_SHUFFLE_MODE, ShuffleMode.OFF),
            nextTitle = preferences.safeString(KEY_NEXT_TITLE),
            capturedElapsedRealtimeMs = preferences.safeLong(KEY_CAPTURED_ELAPSED_MS, 0)
                .coerceAtLeast(0),
            capturedBootCount = preferences.safeInt(
                KEY_CAPTURED_BOOT_COUNT,
                PlaybackWidgetSnapshot.UNKNOWN_BOOT_COUNT,
            ),
        )
    }

    /** Action emitted after the complete preference transaction has become visible in-process. */
    public fun stateChangedAction(context: Context): String =
        "${context.applicationContext.packageName}.action.PLAYBACK_WIDGET_STATE_CHANGED"

    /** Persists one event-driven player snapshot and wakes only receivers in this app package. */
    internal fun publish(
        context: Context,
        snapshot: PlaybackWidgetSnapshot,
    ): PlaybackWidgetSnapshot = synchronized(writeLock) {
        val appContext = context.applicationContext
        val nextRevision = read(appContext).revision
            .let { revision -> if (revision == Long.MAX_VALUE) Long.MAX_VALUE else revision + 1 }
        val persisted = snapshot.copy(revision = nextRevision)
        appContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_REVISION, persisted.revision)
            .putString(KEY_MEDIA_ID, persisted.mediaId)
            .putString(KEY_TITLE, persisted.title)
            .putString(KEY_ARTIST, persisted.artist)
            .putString(KEY_ARTWORK_URI, persisted.artworkUri)
            .putBoolean(KEY_IS_PLAYING, persisted.isPlaying)
            .putLong(KEY_POSITION_MS, persisted.positionMs.coerceAtLeast(0))
            .putLong(KEY_DURATION_MS, persisted.durationMs.coerceAtLeast(0))
            .putBoolean(KEY_HAS_PREVIOUS, persisted.hasPrevious)
            .putBoolean(KEY_HAS_NEXT, persisted.hasNext)
            .putString(KEY_REPEAT_MODE, persisted.repeatMode.name)
            .putString(KEY_SHUFFLE_MODE, persisted.shuffleMode.name)
            .putString(KEY_NEXT_TITLE, persisted.nextTitle)
            .putLong(
                KEY_CAPTURED_ELAPSED_MS,
                persisted.capturedElapsedRealtimeMs.coerceAtLeast(0),
            )
            .putInt(KEY_CAPTURED_BOOT_COUNT, persisted.capturedBootCount)
            .apply()
        appContext.sendBroadcast(
            Intent(stateChangedAction(appContext)).setPackage(appContext.packageName),
        )
        persisted
    }

    /** Freezes projected progress and records a paused state before the service disappears. */
    internal fun markPaused(context: Context): PlaybackWidgetSnapshot {
        val appContext = context.applicationContext
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val bootCount = currentBootCount(appContext)
        val current = read(appContext)
        return publish(
            appContext,
            current.copy(
                isPlaying = false,
                positionMs = current.projectedPositionMs(elapsedRealtimeMs, bootCount),
                capturedElapsedRealtimeMs = elapsedRealtimeMs,
                capturedBootCount = bootCount,
            ),
        )
    }

    internal fun currentBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(
            context.applicationContext.contentResolver,
            Settings.Global.BOOT_COUNT,
        )
    }.getOrDefault(PlaybackWidgetSnapshot.UNKNOWN_BOOT_COUNT)

    private const val PREFERENCES_FILE = "playback_widget_state"
    private const val KEY_REVISION = "revision"
    private const val KEY_MEDIA_ID = "media_id"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ARTWORK_URI = "artwork_uri"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_DURATION_MS = "duration_ms"
    private const val KEY_HAS_PREVIOUS = "has_previous"
    private const val KEY_HAS_NEXT = "has_next"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_SHUFFLE_MODE = "shuffle_mode"
    private const val KEY_NEXT_TITLE = "next_title"
    private const val KEY_CAPTURED_ELAPSED_MS = "captured_elapsed_realtime_ms"
    private const val KEY_CAPTURED_BOOT_COUNT = "captured_boot_count"

    private val writeLock: Any = Any()
}

private fun android.content.SharedPreferences.safeString(key: String): String? =
    runCatching { getString(key, null) }.getOrNull()

private fun android.content.SharedPreferences.safeLong(key: String, default: Long): Long =
    runCatching { getLong(key, default) }.getOrDefault(default)

private fun android.content.SharedPreferences.safeInt(key: String, default: Int): Int =
    runCatching { getInt(key, default) }.getOrDefault(default)

private fun android.content.SharedPreferences.safeBoolean(key: String, default: Boolean): Boolean =
    runCatching { getBoolean(key, default) }.getOrDefault(default)

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.safeEnum(
    key: String,
    default: T,
): T = safeString(key)?.let { persisted ->
    enumValues<T>().firstOrNull { candidate -> candidate.name == persisted }
} ?: default

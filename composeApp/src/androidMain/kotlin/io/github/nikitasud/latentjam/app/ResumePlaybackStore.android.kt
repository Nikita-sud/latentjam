/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.SharedPreferences

/** The preferences file of the saved session: its track, mode, source and queue. */
internal const val RESUME_SESSION_FILE = "resume_session"

/** The preferences file of the saved session's position alone. */
internal const val RESUME_POSITION_FILE = "resume_position"

/**
 * Where the saved playback session lives on Android: two small preferences files of its own.
 *
 * A preferences file is written whole on every change. The session was kept in the app's settings
 * file, and its position is written every ten seconds of playback: each of those writes put the
 * whole settings file on storage again, with the saved queue (two lists of up to 10,000 ids), the
 * duplicate finder's decisions and everything else in it. Here the position has a file to itself
 * ([position]), so a position write is a few bytes, and the queue ([session]) is written only when
 * the track or the queue changes.
 *
 * The two files are written one after the other, so a process that ends between them can leave a
 * new track beside the position of the one before. Both carry the serial of the write that made
 * them, and a position whose serial is not the session's reads as the start of the track.
 *
 * A session saved by 0.9.5 or older is still in [legacy] (the settings file). It is read from
 * there until the first write, which also takes it out of that file.
 */
internal class ResumePlaybackStore(
    private val legacy: SharedPreferences,
    private val session: SharedPreferences,
    private val position: SharedPreferences,
) {
    private var legacyPending = LEGACY_KEYS.any { key -> runCatching { legacy.contains(key) }.getOrDefault(false) }
    private var serial = session.long(KEY_SERIAL) ?: 0L

    fun read(): ResumePlayback? {
        val current = readSession(session)
        if (current != null) {
            val positionMs = position.long(KEY_RESUME_POSITION)
                ?.takeIf { position.long(KEY_SERIAL) == session.long(KEY_SERIAL) }
            return current.copy(positionMs = positionMs ?: 0L)
        }
        if (!legacyPending) return null
        return readSession(legacy)?.copy(positionMs = legacy.long(KEY_RESUME_POSITION) ?: 0L)
    }

    /** Stores [state] in place of [previous], the session last read or written here. */
    fun write(previous: ResumePlayback?, state: ResumePlayback?) {
        if (state != null && !legacyPending && previous?.sameSessionExceptPosition(state) == true) {
            // Position advances every 10 seconds while the queue usually stays unchanged for many
            // minutes: only the small position file is written. With the session's serial each time: a
            // position file left one write behind the session (or lost) reads as the start of the track, and
            // a write of the position alone would otherwise never make it the session's again.
            position.edit().putLong(KEY_SERIAL, serial).putLong(KEY_RESUME_POSITION, state.positionMs).apply()
            return
        }
        if (state == null) {
            session.edit().clear().apply()
            position.edit().clear().apply()
        } else {
            serial++
            session.edit().apply {
                clear()
                putLong(KEY_SERIAL, serial)
                putString(KEY_RESUME_TRACK, state.trackId)
                putString(KEY_RESUME_MODE, state.shuffleMode)
                state.sourceKind?.let { putString(KEY_RESUME_SOURCE_KIND, it) }
                state.sourceName?.let { putString(KEY_RESUME_SOURCE_NAME, it) }
                state.sourceReference?.let { putString(KEY_RESUME_SOURCE_REFERENCE, it) }
                // Live and source queues are one length-prefixed value: opaque ids cannot collide
                // with separators and the pair is committed in this editor update.
                if (state.queueTrackIds.isNotEmpty()) {
                    putString(
                        KEY_RESUME_QUEUE_STATE,
                        encodeResumeQueueState(
                            ResumeQueueState(
                                queueTrackIds = state.queueTrackIds,
                                sourceQueueTrackIds = state.sourceQueueTrackIds,
                                queueIndex = state.queueIndex,
                                sourceQueuePersisted = state.sourceQueuePersisted,
                                smartContinuationIds = state.smartContinuationIds,
                                smartPlanPositions = state.smartPlanPositions,
                            ),
                        ),
                    )
                }
            }.apply()
            position.edit().putLong(KEY_SERIAL, serial).putLong(KEY_RESUME_POSITION, state.positionMs).apply()
        }
        if (legacyPending) {
            legacyPending = false
            legacy.edit().apply { LEGACY_KEYS.forEach(::remove) }.apply()
        }
    }

    private fun readSession(preferences: SharedPreferences): ResumePlayback? {
        val trackId = preferences.string(KEY_RESUME_TRACK) ?: return null
        val mode = preferences.string(KEY_RESUME_MODE) ?: return null
        val encodedQueueState = preferences.string(KEY_RESUME_QUEUE_STATE)
        val queueState = encodedQueueState?.let(::decodeResumeQueueState)
        // The former comma codec is read only when no versioned value exists. A malformed payload must
        // not silently pair with a stale legacy queue left by an interrupted migration.
        val legacyQueueIds = if (encodedQueueState == null) {
            preferences.string(KEY_RESUME_QUEUE)
                ?.split(',')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        } else {
            emptyList()
        }
        return ResumePlayback(
            trackId = trackId,
            shuffleMode = mode,
            positionMs = 0L,
            // Absent on sessions saved before queue sources existed; null simply hides the label.
            sourceKind = preferences.string(KEY_RESUME_SOURCE_KIND),
            sourceName = preferences.string(KEY_RESUME_SOURCE_NAME),
            sourceReference = preferences.string(KEY_RESUME_SOURCE_REFERENCE),
            queueTrackIds = queueState?.queueTrackIds ?: legacyQueueIds,
            queueIndex = queueState?.queueIndex ?: -1,
            sourceQueueTrackIds = queueState?.sourceQueueTrackIds.orEmpty(),
            sourceQueuePersisted = queueState?.sourceQueuePersisted ?: false,
            smartContinuationIds = queueState?.smartContinuationIds.orEmpty(),
            smartPlanPositions = queueState?.smartPlanPositions.orEmpty(),
        )
    }

    /** SharedPreferences throws ClassCastException when a key was previously written as another type. */
    private fun SharedPreferences.string(key: String): String? = runCatching { getString(key, null) }.getOrNull()

    private fun SharedPreferences.long(key: String): Long? =
        runCatching { if (contains(key)) getLong(key, 0L) else null }.getOrNull()

    private companion object {
        const val KEY_SERIAL = "resume_serial"
        const val KEY_RESUME_TRACK = "resume_track_id"
        const val KEY_RESUME_MODE = "resume_shuffle_mode"
        const val KEY_RESUME_POSITION = "resume_position_ms"
        const val KEY_RESUME_SOURCE_KIND = "resume_source_kind"
        const val KEY_RESUME_SOURCE_NAME = "resume_source_name"
        const val KEY_RESUME_SOURCE_REFERENCE = "resume_source_reference"
        const val KEY_RESUME_QUEUE_STATE = "resume_queue_state_v2"

        /** Read-only migration key used by builds before collision-safe queue state. */
        const val KEY_RESUME_QUEUE = "resume_queue_track_ids"

        /** Every key a session had in the settings file. */
        val LEGACY_KEYS = listOf(
            KEY_RESUME_TRACK, KEY_RESUME_MODE, KEY_RESUME_POSITION, KEY_RESUME_SOURCE_KIND, KEY_RESUME_SOURCE_NAME,
            KEY_RESUME_SOURCE_REFERENCE, KEY_RESUME_QUEUE_STATE, KEY_RESUME_QUEUE,
        )
    }
}

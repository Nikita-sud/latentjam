/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Hands each coordinator checkpoint to every live owner of saved state. On Android that is every
 * Activity's `SavedStateHandle`: whichever one the system restores after process death holds the
 * latest batch. An owner that attaches late gets the latest checkpoint at once.
 */
internal class CheckpointFanOut {
    private val sinks = LinkedHashMap<Any, (List<String>) -> Unit>()
    private var latest: List<String>? = null

    fun save(state: List<String>) {
        latest = state
        sinks.values.forEach { it(state) }
    }

    fun attach(owner: Any, sink: (List<String>) -> Unit) {
        sinks[owner] = sink
        latest?.let(sink)
    }

    fun detach(owner: Any) {
        sinks.remove(owner)
    }
}

/**
 * What common code needs from a platform's tag saving: the one coordinator, the key a track is
 * written under, and what a READ_ONLY file means here. On iOS that is a Music-library item, which
 * no app may write. On Android it is a read-only volume.
 */
internal class TagWriteAccess(
    val coordinator: TagWriteCoordinator<*>,
    val readOnlyIsMusicLibrary: Boolean,
    private val key: (TrackDescriptor) -> String?,
) {
    /** Null when this platform has no way to write the track at all. */
    fun keyOf(track: TrackDescriptor): String? = key(track)
}

/** The process's tag-save access, or null where saving is impossible (no Activity, a malformed sandbox). */
@Composable
internal expect fun rememberTagWriteAccess(): TagWriteAccess?

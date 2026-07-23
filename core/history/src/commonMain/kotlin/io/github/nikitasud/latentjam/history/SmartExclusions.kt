/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tracks and artists the listener keeps in the library but does not want SMART to suggest. */
public data class SmartExclusionState(
    public val trackIds: Set<TrackId> = emptySet(),
    public val artists: Set<String> = emptySet(),
)

/** True when [track] must stay out of every SMART-generated recommendation surface. */
public fun SmartExclusionState.excludes(track: TrackDescriptor): Boolean =
    track.id in trackIds || excludesArtist(track.artist)

/** Artist exclusions match metadata robustly without changing the artist shown to the listener. */
public fun SmartExclusionState.excludesArtist(artist: String?): Boolean {
    val normalized = artist?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return artists.any { it.equals(normalized, ignoreCase = true) }
}

public interface SmartExclusionStore {
    public suspend fun read(): List<String>
    public suspend fun write(lines: List<String>)
}

public class SmartExclusions(
    private val store: SmartExclusionStore,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableState = MutableStateFlow(SmartExclusionState())

    public val state: StateFlow<SmartExclusionState> = mutableState

    public suspend fun load(): SmartExclusionState = mutex.withLock {
        ensureLoaded()
        mutableState.value
    }

    public suspend fun excludeTrack(id: TrackId) {
        update { it.copy(trackIds = it.trackIds + id) }
    }

    public suspend fun includeTrack(id: TrackId) {
        update { it.copy(trackIds = it.trackIds - id) }
    }

    public suspend fun excludeArtist(artist: String) {
        val normalized = artist.trim().takeIf(String::isNotEmpty) ?: return
        update { state ->
            if (state.artists.any { it.equals(normalized, ignoreCase = true) }) {
                state
            } else {
                state.copy(artists = state.artists + normalized)
            }
        }
    }

    public suspend fun includeArtist(artist: String) {
        val normalized = artist.trim().takeIf(String::isNotEmpty) ?: return
        update { state ->
            state.copy(
                artists = state.artists
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                    .toSet(),
            )
        }
    }

    public suspend fun clear() {
        update { SmartExclusionState() }
    }

    /** Replaces both exclusion sets in one durable write, for local backup restore. */
    public suspend fun replace(state: SmartExclusionState) {
        update { state.normalized() }
    }

    private suspend fun update(transform: (SmartExclusionState) -> SmartExclusionState) = mutex.withLock {
        ensureLoaded()
        val previous = mutableState.value
        val next = transform(previous)
        if (next == previous) return@withLock
        try {
            store.write(serialize(next))
            mutableState.value = next
        } catch (failure: Throwable) {
            mutableState.value = previous
            throw failure
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutableState.value = parse(store.read())
        loaded = true
    }

    private fun serialize(state: SmartExclusionState): List<String> = buildList {
        state.trackIds.sortedBy { it.value }.forEach { add("T:${it.value.encodeHex()}") }
        state.artists.sortedBy { it.lowercase() }.forEach { add("A:${it.encodeHex()}") }
    }

    private fun parse(lines: List<String>): SmartExclusionState {
        val tracks = LinkedHashSet<TrackId>()
        val artists = LinkedHashSet<String>()
        lines.forEach { line ->
            val value = line.substringAfter(':', "").decodeHex() ?: return@forEach
            when (line.substringBefore(':')) {
                "T" -> tracks += TrackId(value)
                "A" -> value.takeIf(String::isNotBlank)?.let(artists::add)
            }
        }
        return SmartExclusionState(tracks, artists)
    }

    private fun SmartExclusionState.normalized(): SmartExclusionState = copy(
        trackIds = trackIds.filterTo(LinkedHashSet()) { it.value.isNotBlank() },
        artists = artists.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) },
    )
}

private fun String.encodeHex(): String = encodeToByteArray().joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun String.decodeHex(): String? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.decodeToString()
    }.getOrNull()
}

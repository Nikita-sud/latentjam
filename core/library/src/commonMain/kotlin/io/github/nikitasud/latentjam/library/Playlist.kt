/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A user-made playlist: a name and an ordered list of track ids. */
public data class Playlist(
    public val id: String,
    public val name: String,
    public val trackIds: List<String> = emptyList(),
    public val createdAtMs: Long = 0,
)

/**
 * The device's playlists. Ids rather than descriptors are stored, so a
 * playlist survives a rescan and never holds stale metadata.
 */
public interface Playlists {
    public suspend fun all(): List<Playlist>
    public suspend fun create(name: String): Playlist
    public suspend fun rename(id: String, name: String)
    public suspend fun delete(id: String)
    public suspend fun addTracks(id: String, trackIds: List<TrackId>)
    public suspend fun removeTrack(id: String, trackId: TrackId)

    /** Replaces all user playlists in one durable write, primarily for local backup restore. */
    public suspend fun replaceAll(playlists: List<Playlist>)
}

/** Whole-list storage; playlists are few and small. */
public interface PlaylistStore {
    public suspend fun read(): List<String>
    public suspend fun write(lines: List<String>)
}

/** Wall-clock milliseconds, for playlist ids and creation stamps. */
public expect fun nowMillis(): Long

/**
 * Default [Playlists]: an in-memory list written through to [PlaylistStore]
 * on every change. Duplicate tracks are ignored on add, so dragging the same
 * song in twice is a no-op rather than a surprise.
 */
public class DefaultPlaylists(
    private val store: PlaylistStore,
) : Playlists {

    private val mutex = Mutex()
    private var loaded = false
    private val playlists = mutableListOf<Playlist>()

    override suspend fun all(): List<Playlist> = mutex.withLock {
        ensureLoaded()
        playlists.toList()
    }

    override suspend fun create(name: String): Playlist = mutex.withLock {
        ensureLoaded()
        val created = Playlist(
            id = "pl-${nowMillis()}-${playlists.size}",
            name = name.trim().ifEmpty { "Untitled playlist" },
            createdAtMs = nowMillis(),
        )
        playlists.add(0, created)
        persist()
        created
    }

    override suspend fun rename(id: String, name: String): Unit = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index >= 0) {
            playlists[index] = playlists[index].copy(name = name.trim().ifEmpty { "Untitled playlist" })
            persist()
        }
    }

    override suspend fun delete(id: String): Unit = mutex.withLock {
        ensureLoaded()
        if (playlists.removeAll { it.id == id }) persist()
    }

    override suspend fun addTracks(id: String, trackIds: List<TrackId>): Unit = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock
        val existing = playlists[index].trackIds
        val additions = trackIds.map { it.value }.filter { it !in existing }
        if (additions.isNotEmpty()) {
            playlists[index] = playlists[index].copy(trackIds = existing + additions)
            persist()
        }
    }

    override suspend fun removeTrack(id: String, trackId: TrackId): Unit = mutex.withLock {
        ensureLoaded()
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return@withLock
        val remaining = playlists[index].trackIds.filterNot { it == trackId.value }
        if (remaining.size != playlists[index].trackIds.size) {
            playlists[index] = playlists[index].copy(trackIds = remaining)
            persist()
        }
    }

    override suspend fun replaceAll(playlists: List<Playlist>): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = playlists.map { it.normalizedForRestore() }
        require(replacement.map(Playlist::id).toSet().size == replacement.size) {
            "Playlist ids must be unique"
        }
        store.write(replacement.map(PlaylistSerializer::serialize))
        this.playlists.clear()
        this.playlists += replacement
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        runCatching { store.read() }
            .getOrDefault(emptyList())
            .mapNotNull(PlaylistSerializer::parse)
            .forEach(playlists::add)
        loaded = true
    }

    private suspend fun persist() {
        runCatching { store.write(playlists.map(PlaylistSerializer::serialize)) }
    }

    private fun Playlist.normalizedForRestore(): Playlist {
        require(id.isNotBlank()) { "Playlist id cannot be blank" }
        return copy(
            name = name.trim().ifEmpty { "Untitled playlist" },
            trackIds = trackIds.filter(String::isNotBlank).distinct(),
        )
    }
}

/**
 * One playlist per line. Fields are separated by a unit separator rather than
 * a printable character, so a playlist called "Rock | Metal" survives.
 */
internal object PlaylistSerializer {

    private const val FIELD = ''
    private const val TRACK = ','

    fun serialize(playlist: Playlist): String = listOf(
        playlist.id,
        playlist.name,
        playlist.createdAtMs.toString(),
        playlist.trackIds.joinToString(TRACK.toString()),
    ).joinToString(FIELD.toString())

    fun parse(line: String): Playlist? {
        val parts = line.split(FIELD)
        if (parts.size != 4) return null
        return Playlist(
            id = parts[0].ifEmpty { return null },
            name = parts[1],
            createdAtMs = parts[2].toLongOrNull() ?: return null,
            trackIds = parts[3].split(TRACK).filter { it.isNotEmpty() },
        )
    }
}

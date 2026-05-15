/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.data

import io.github.nikitasud.latentjam.music.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

/**
 * Single source of truth for a song's effective metadata. Layers user-supplied overrides
 * (from the in-app edit dialog) on top of the file-tag values read by musikr. Callers
 * that care about metadata (e.g. [io.github.nikitasud.latentjam.ml.predictor.MetadataRerank])
 * should consult this rather than reading Song fields directly.
 *
 * Override cache is loaded eagerly on first access and kept in memory; writes update both
 * the DAO and the cache, and bump `revision` so reactive consumers (the recommendation
 * engine) can invalidate their per-library snapshots.
 */
@Singleton
class TrackMetadataResolver
@Inject
constructor(
    private val dao: TrackMetadataOverrideDao,
    private val musicRepository: MusicRepository,
) {
    @Volatile private var loaded = false
    private val overrideByUid = HashMap<Music.UID, TrackMetadataOverrideEntity>()
    private val _revision = MutableStateFlow(0L)

    /**
     * Monotonic version counter. Increments on every override write. Recommendation
     * engine watches this to know when to rebuild its cached embedding snapshot's
     * derived data.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        // First call: hydrate the cache from the DAO. Synchronous on whichever thread
        // happens to ask first — there's no good way to await an async load when called
        // from a non-suspending context (the rerank path runs in a coroutine but the
        // overhead of a one-shot synchronous query is trivial).
        runBlocking {
            for (row in dao.all()) overrideByUid[row.songUid] = row
        }
        loaded = true
    }

    fun effectiveGenre(song: Song): String? {
        ensureLoaded()
        val override = overrideByUid[song.uid]?.genre?.takeIf { it.isNotBlank() }
        if (override != null) return override
        return song.genres.firstOrNull()?.name?.let { it as? Name.Known }?.raw
    }

    fun effectiveArtist(song: Song): String? {
        ensureLoaded()
        val override = overrideByUid[song.uid]?.artist?.takeIf { it.isNotBlank() }
        if (override != null) return override
        return song.artists.firstOrNull()?.name?.let { it as? Name.Known }?.raw
    }

    fun effectiveYear(song: Song): Int? {
        ensureLoaded()
        return overrideByUid[song.uid]?.year ?: song.date?.year
    }

    fun overrideFor(uid: Music.UID): TrackMetadataOverrideEntity? {
        ensureLoaded()
        return overrideByUid[uid]
    }

    /**
     * Distinct genre values present in the library — used to populate the edit dialog's
     * autocomplete dropdown. Combines override genres + file-tag genres.
     */
    fun knownGenres(): List<String> {
        ensureLoaded()
        val out = HashSet<String>()
        for (row in overrideByUid.values) {
            row.genre?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        val lib = musicRepository.library
        if (lib != null) {
            for (genre in lib.genres) {
                (genre.name as? Name.Known)?.raw?.let(out::add)
            }
        }
        return out.sorted()
    }

    suspend fun setOverride(uid: Music.UID, genre: String?, artist: String?, year: Int?) {
        val normalized = TrackMetadataOverrideEntity(
            songUid = uid,
            genre = genre?.trim()?.takeIf { it.isNotBlank() },
            artist = artist?.trim()?.takeIf { it.isNotBlank() },
            year = year,
        )
        // If all fields are null, prefer deleting the row so we don't keep empty stubs.
        if (normalized.genre == null && normalized.artist == null && normalized.year == null) {
            dao.delete(uid)
            synchronized(this) { overrideByUid.remove(uid) }
        } else {
            dao.upsert(normalized)
            synchronized(this) { overrideByUid[uid] = normalized }
        }
        _revision.value = _revision.value + 1
    }
}

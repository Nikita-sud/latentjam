/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Upgrades single-genre descriptors to the full genre list embedded in each file's own tags.
 *
 * Android's media scanner keeps one genre per track, so a correctly tagged FLAC — five separate
 * `GENRE` fields — reaches the library as just its first value. This pass reads the real list
 * once per file revision, remembers it durably, and rewrites `TrackDescriptor.genre` to the
 * canonical joined form. Every consumer downstream — the genre tab, the mixes' naming, the
 * chain's genre terms, and crucially the SMART metadata-text embedding whose vector identity
 * includes the genre string — sees the richer value and reacts on its own.
 *
 * A cache entry is keyed by the track's [TrackDescriptor.sourceRevision]: retagging a file
 * changes the revision, which makes the entry stale and the file re-read. "Read fine, found
 * none" is remembered too — the file is not worth re-opening every launch — while "could not
 * read" stores nothing and stays eligible for retry.
 */
internal class GenreEnrichment(
    private val settings: AppSettings,
) {
    private data class Stored(val revision: String, val joined: String)

    private val mutex = Mutex()
    private var cache: MutableMap<String, Stored>? = null

    /** Applies remembered genres; pure and cheap, safe on every library load. */
    suspend fun apply(library: List<TrackDescriptor>): List<TrackDescriptor> {
        val loaded = mutex.withLock { ensureLoaded() }
        return library.map { track ->
            val stored = loaded[track.id.value] ?: return@map track
            if (stored.revision != track.revisionKey()) return@map track
            val joined = stored.joined.takeIf { it.isNotEmpty() } ?: return@map track
            if (joined == track.genre) track else track.copy(genre = joined)
        }
    }

    /**
     * Reads embedded genres for tracks with no fresh cache entry. Returns true when anything
     * newly learned changes a descriptor, so the caller knows a reload is worth it. Bounded
     * politeness: yields between files so a first launch over a big library never owns a core.
     */
    suspend fun backfill(library: List<TrackDescriptor>): Boolean {
        val known = mutex.withLock { ensureLoaded().toMap() }
        var learnedSomething = false
        val updates = HashMap<String, Stored>()
        for (track in library) {
            val revision = track.revisionKey()
            val existing = known[track.id.value]
            if (existing != null && existing.revision == revision) continue
            val genres = readEmbeddedGenres(track) ?: continue
            val joined = GenreTags.canonical(genres).orEmpty()
            updates[track.id.value] = Stored(revision, joined)
            if (joined.isNotEmpty() && joined != track.genre) learnedSomething = true
            yield()
        }
        if (updates.isNotEmpty()) {
            mutex.withLock {
                val target = ensureLoaded()
                target.putAll(updates)
                settings.writeTrackGenresPayload(encode(target))
            }
        }
        return learnedSomething
    }

    private fun ensureLoaded(): MutableMap<String, Stored> {
        cache?.let { return it }
        val loaded = decode(settings.readTrackGenresPayload())
        cache = loaded
        return loaded
    }

    private companion object {
        const val FORMAT = "v1"

        /**
         * The identity a cache entry is valid for. [TrackDescriptor.sourceRevision] carries
         * size/mtime/generation on Android; the duration stands in where a platform leaves it
         * null, and a constant otherwise — worst case is one extra read after an app update.
         */
        fun TrackDescriptor.revisionKey(): String =
            sourceRevision ?: durationMs?.toString() ?: "-"

        fun encode(entries: Map<String, Stored>): String =
            entries.entries.joinToString("\n") { (id, stored) ->
                listOf(
                    FORMAT,
                    id.hex(),
                    stored.revision.hex(),
                    stored.joined.hex(),
                ).joinToString("|")
            }

        fun decode(payload: String?): MutableMap<String, Stored> {
            val result = HashMap<String, Stored>()
            payload?.lineSequence()?.forEach { line ->
                val parts = line.split('|')
                if (parts.size != 4 || parts[0] != FORMAT) return@forEach
                val id = parts[1].unhex() ?: return@forEach
                val revision = parts[2].unhex() ?: return@forEach
                val joined = parts[3].unhex() ?: return@forEach
                result[id] = Stored(revision, joined)
            }
            return result
        }

        fun String.hex(): String = encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

        fun String.unhex(): String? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { index ->
                    substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }
}

/** Splits a (possibly joined) genre string for consumers that group by individual genre. */
internal fun TrackDescriptor.genreList(): List<String> = GenreTags.split(genre)

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Upgrades descriptors with the tag facts the system scanner loses: the FULL genre list, the
 * credited-artists list, and the original release year.
 *
 * Android's media scanner keeps one genre and one display-artist string per track, and reports
 * the edition year. The files themselves know more — five separate `GENRE` fields, a Picard
 * `ARTISTS` list, `ORIGINALDATE`. This pass reads each file once per revision, remembers the
 * result durably, and rewrites descriptors. Every consumer downstream reacts on its own: the
 * genre tab lists a track under each genre, the artists tab under each credit, the chain's
 * artist spacing recognises collaborations, its era term keeps a remaster in its real decade,
 * and the SMART metadata-text embedding re-encodes because the genre string is part of its
 * vector identity.
 *
 * A cache entry is keyed by [TrackDescriptor.sourceRevision]: retagging changes the revision,
 * which makes the entry stale and the file re-read. "Read fine, found nothing" is remembered
 * too — the file is not worth re-opening every launch — while "could not read" stores nothing
 * and stays eligible for retry.
 */
internal class GenreEnrichment(
    private val settings: AppSettings,
) {
    private data class Stored(
        val revision: String,
        val joinedGenres: String,
        val artists: List<String>,
        val originalYear: Int?,
    )

    private val mutex = Mutex()
    private var cache: MutableMap<String, Stored>? = null

    /** Applies remembered facts; pure and cheap, safe on every library load. */
    suspend fun apply(library: List<TrackDescriptor>): List<TrackDescriptor> {
        val loaded = mutex.withLock { ensureLoaded() }
        return library.map { track ->
            val stored = loaded[track.id.value] ?: return@map track
            if (stored.revision != track.revisionKey()) return@map track
            val genre = stored.joinedGenres.takeIf { it.isNotEmpty() } ?: track.genre
            val artists = stored.artists.ifEmpty { track.artists }
            val originalYear = stored.originalYear ?: track.originalYear
            if (genre == track.genre && artists == track.artists &&
                originalYear == track.originalYear
            ) {
                track
            } else {
                track.copy(genre = genre, artists = artists, originalYear = originalYear)
            }
        }
    }

    /**
     * Reads embedded facts for tracks with no fresh cache entry. Returns true when anything
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
            val facts = readEmbeddedFacts(track) ?: continue
            val stored = Stored(
                revision = revision,
                joinedGenres = GenreTags.canonical(facts.genres).orEmpty(),
                artists = facts.artists,
                originalYear = facts.originalYear,
            )
            updates[track.id.value] = stored
            if (stored.changes(track)) learnedSomething = true
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

    private fun Stored.changes(track: TrackDescriptor): Boolean =
        (joinedGenres.isNotEmpty() && joinedGenres != track.genre) ||
            (artists.isNotEmpty() && artists != track.artists) ||
            (originalYear != null && originalYear != track.originalYear)

    private fun ensureLoaded(): MutableMap<String, Stored> {
        cache?.let { return it }
        val loaded = decode(settings.readTrackGenresPayload())
        cache = loaded
        return loaded
    }

    private companion object {
        /**
         * v2 added artists and the original year. v1 lines (genres only) are deliberately
         * dropped on decode: those files must be re-read once anyway to learn the new facts.
         */
        const val FORMAT = "v2"

        /** Joins the artist list inside one hex field; NUL never appears in a real name. */
        const val ARTIST_JOIN = "\u0000"

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
                    stored.joinedGenres.hex(),
                    stored.artists.joinToString(ARTIST_JOIN).hex(),
                    stored.originalYear?.toString() ?: "",
                ).joinToString("|")
            }

        fun decode(payload: String?): MutableMap<String, Stored> {
            val result = HashMap<String, Stored>()
            payload?.lineSequence()?.forEach { line ->
                val parts = line.split('|')
                if (parts.size != 6 || parts[0] != FORMAT) return@forEach
                val id = parts[1].unhex() ?: return@forEach
                val revision = parts[2].unhex() ?: return@forEach
                val genres = parts[3].unhex() ?: return@forEach
                val artistsJoined = parts[4].unhex() ?: return@forEach
                result[id] = Stored(
                    revision = revision,
                    joinedGenres = genres,
                    artists = artistsJoined.split(ARTIST_JOIN).filter { it.isNotEmpty() },
                    originalYear = parts[5].toIntOrNull(),
                )
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

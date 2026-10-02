/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.library.tags.TextRepair
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Upgrades descriptors with the tag facts the system scanner loses: the FULL genre list, the
 * credited-artists list, the original release year, the language, the album artist, and the
 * file's own year when the scanner reported none.
 *
 * Android's media scanner keeps one genre and one display-artist string per track, and reports
 * the edition year. The files themselves know more — five separate `GENRE` fields, a Picard
 * `ARTISTS` list, `ORIGINALDATE`. Android 16's scanner also leaves the year empty for every
 * MP3, FLAC and Opus file (only M4A keeps one), so decade search, album years and SMART's era
 * term would see no year at all; the file's `TDRC`/`TYER`/`DATE` fills in, while a year the
 * scanner did report always wins. This pass reads each file once per revision, remembers the
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
    private val readFacts: suspend (TrackDescriptor) -> EmbeddedTagFacts? = ::readEmbeddedFacts,
) {
    private data class Stored(
        val revision: String,
        val joinedGenres: String,
        val artists: List<String>,
        val originalYear: Int?,
        val language: String?,
        val albumArtist: String?,
        /** The file's own year; applied only where the scanner reported none. */
        val year: Int?,
    )

    private val mutex = Mutex()
    private var cache: MutableMap<String, Stored>? = null
    private var cacheDirty = false

    /** Applies remembered facts; pure and cheap, safe on every library load. */
    suspend fun apply(library: List<TrackDescriptor>): List<TrackDescriptor> = mutex.withLock {
        val loaded = ensureLoaded()
        library.map { track ->
            val stored = loaded[track.id.value] ?: return@map track
            if (stored.revision != track.revisionKey()) return@map track
            stored.mergedInto(track)
        }
    }

    /**
     * [track] with these facts. A file's text was repaired as MediaStore's is when it was read, but
     * a short mojibake repair cannot tell from a real word stays, and it never replaces the clean
     * value MediaStore holds for the same field: MediaStore's own reading wins over its mangling.
     * [track] itself when nothing changes.
     */
    private fun Stored.mergedInto(track: TrackDescriptor): TrackDescriptor {
        val genre = joinedGenres.takeIf { it.isNotEmpty() }
            ?.split(GenreTags.SEPARATOR)
            ?.joinToString(GenreTags.SEPARATOR) { it.unlessMangling(track.genre) }
            ?: track.genre
        val artists = artists.map { it.unlessMangling(track.artist) }.ifEmpty { track.artists }
        val originalYear = originalYear ?: track.originalYear
        val language = language ?: track.language
        val albumArtist = albumArtist?.unlessMangling(track.albumArtist) ?: track.albumArtist
        val year = track.year ?: year
        return if (genre == track.genre && artists == track.artists &&
            originalYear == track.originalYear && language == track.language &&
            albumArtist == track.albumArtist && year == track.year
        ) {
            track
        } else {
            track.copy(
                genre = genre,
                artists = artists,
                originalYear = originalYear,
                language = language,
                albumArtist = albumArtist,
                year = year,
            )
        }
    }

    /** [clean] (MediaStore's reading of the same field) where this is only its mojibake, else this. */
    private fun String.unlessMangling(clean: String?): String =
        if (clean != null && TextRepair.isMangling(this, of = clean)) clean else this

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
            val facts = readFacts(track) ?: continue
            // The same repair MediaStore's text gets (issue #7): an older tagger's UTF-8 read as a
            // Windows codepage is in the file itself, and must not reach the library as "BeyoncÃ©".
            val stored = Stored(
                revision = revision,
                joinedGenres = GenreTags.canonical(facts.genres.map(TextRepair::repair)).orEmpty(),
                artists = facts.artists.map(TextRepair::repair),
                originalYear = facts.originalYear,
                language = facts.language?.let(TextRepair::repair),
                albumArtist = facts.albumArtist?.let(TextRepair::repair),
                year = facts.year,
            )
            updates[track.id.value] = stored
            if (stored.changes(track)) learnedSomething = true
            yield()
        }
        mutex.withLock {
            val target = ensureLoaded()
            if (updates.isNotEmpty()) {
                target.putAll(updates)
                cacheDirty = true
            }
            // Facts remain usable in memory if storage is unavailable. A warm pass must also
            // retry a failed save: its files no longer need reading, so updates will be empty.
            if (cacheDirty) {
                try {
                    settings.writeTrackGenresPayload(encode(target))
                    cacheDirty = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // This is a rebuildable cache, not a reason to terminate the library screen.
                }
            }
        }
        return learnedSomething
    }

    private fun Stored.changes(track: TrackDescriptor): Boolean = mergedInto(track) != track

    private fun ensureLoaded(): MutableMap<String, Stored> {
        cache?.let { return it }
        val loaded = decode(settings.readTrackGenresPayload())
        cache = loaded
        return loaded
    }

    private companion object {
        /**
         * v2 added artists and the original year, v3 the language tag, v4 the album artist, v5 the
         * file's own year. Older lines are deliberately dropped on decode: those files must be
         * re-read once anyway to learn the new facts. That is one tag read per track, the same
         * pass every earlier bump cost; SMART's audio analysis is keyed on the audio's identity
         * (URI, duration, revision) and is untouched — only the cheap text vector re-encodes
         * where a year appears.
         */
        const val FORMAT = "v5"

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
                    stored.language.orEmpty().hex(),
                    stored.albumArtist.orEmpty().hex(),
                    stored.year?.toString() ?: "",
                ).joinToString("|")
            }

        fun decode(payload: String?): MutableMap<String, Stored> {
            val result = HashMap<String, Stored>()
            payload?.lineSequence()?.forEach { line ->
                val parts = line.split('|')
                if (parts.size != 9 || parts[0] != FORMAT) return@forEach
                val id = parts[1].unhex() ?: return@forEach
                val revision = parts[2].unhex() ?: return@forEach
                val genres = parts[3].unhex() ?: return@forEach
                val artistsJoined = parts[4].unhex() ?: return@forEach
                val language = parts[6].unhex() ?: return@forEach
                val albumArtist = parts[7].unhex() ?: return@forEach
                // Entries written before the ID3 reader learned to read UTF-8 in Latin-1 frames
                // hold "HÃ¶rspiel" for an unchanged file. TextRepair recovers the names a re-read
                // would find without opening the file, and leaves a sound name as it is.
                result[id] = Stored(
                    revision = revision,
                    joinedGenres = genres.split(GenreTags.SEPARATOR)
                        .joinToString(GenreTags.SEPARATOR, transform = TextRepair::repair),
                    artists = artistsJoined.split(ARTIST_JOIN).filter { it.isNotEmpty() }.map(TextRepair::repair),
                    originalYear = parts[5].toIntOrNull(),
                    language = language.takeIf { it.isNotEmpty() }?.let(TextRepair::repair),
                    albumArtist = albumArtist.takeIf { it.isNotEmpty() }?.let(TextRepair::repair),
                    year = parts[8].toIntOrNull(),
                )
            }
            return result
        }
    }
}

/** Splits a (possibly joined) genre string for consumers that group by individual genre. */
internal fun TrackDescriptor.genreList(): List<String> = GenreTags.split(genre)

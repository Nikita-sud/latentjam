/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.library.tags.TextRepair
import io.github.nikitasud.latentjam.smart.PayloadHex
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
 * Whether the file names an album at all is one more such fact. The scanner files a song without
 * one under its folder's name, and that pseudo-album's cover belongs to another song in the folder
 * (issue #12), so such a song keeps only a cover of its own ([ownCoverOnly]). Its album stays as
 * the scanner reported it: the Albums tab still lists the folder.
 *
 * A cache entry is keyed by [TrackDescriptor.sourceRevision]: retagging changes the revision,
 * which makes the entry stale and the file re-read. "Read fine, found nothing" is remembered
 * too — the file is not worth re-opening every launch — while "could not read" stores nothing
 * and stays eligible for retry.
 */
internal class GenreEnrichment(
    private val settings: AppSettings,
    private val ownCover: (String) -> String = ::ownCoverOnly,
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
        /** Whether the file names an album; without one it borrows no album's cover. */
        val namesAlbum: Boolean,
    )

    private val mutex = Mutex()

    /**
     * Facts learned but not yet saved (storage refused the write). Normally empty: the cache
     * itself stays on storage and is streamed by [apply] and [backfill], never held whole. At
     * 10,000 songs the resident map and its decoded strings were 2.8 MB of a 48 MB heap.
     */
    private val unsaved = HashMap<String, Stored>()

    /** Applies remembered facts; one streamed pass over the cache, safe on every library load. */
    suspend fun apply(library: List<TrackDescriptor>): List<TrackDescriptor> = mutex.withLock {
        if (library.isEmpty()) return@withLock library
        val positions = HashMap<String, Int>(library.size * 2)
        library.forEachIndexed { index, track -> positions[track.id.value] = index }
        var result: MutableList<TrackDescriptor>? = null
        // An album's songs share one genre, artist list and album artist: one instance per value.
        val pool = HashMap<String, String>()
        fun merge(id: String, stored: Stored) {
            val index = positions[id] ?: return
            val track = library[index]
            if (stored.revision != track.revisionKey()) return
            val merged = stored.mergedInto(track, pool)
            if (merged !== track || result != null) {
                val target = result ?: library.toMutableList().also { result = it }
                target[index] = merged
            }
        }
        settings.readTrackGenreLines { lines ->
            for (line in lines) {
                val id = idOf(line) ?: continue
                if (id !in positions || id in unsaved) continue
                decode(line)?.let { merge(id, it) }
            }
        }
        for ((id, stored) in unsaved) merge(id, stored)
        result ?: library
    }

    /**
     * [track] with these facts. A file's text was repaired as MediaStore's is when it was read, but
     * a short mojibake repair cannot tell from a real word stays, and it never replaces the clean
     * value MediaStore holds for the same field: MediaStore's own reading wins over its mangling.
     * [track] itself when nothing changes.
     */
    private fun Stored.mergedInto(track: TrackDescriptor, pool: MutableMap<String, String>? = null): TrackDescriptor {
        fun String.shared(): String = pool?.getOrPut(this) { this } ?: this
        val genre = joinedGenres.takeIf { it.isNotEmpty() }
            ?.split(GenreTags.SEPARATOR)
            ?.joinToString(GenreTags.SEPARATOR) { it.unlessMangling(track.genre) }
            ?.let { if (it == track.genre) track.genre else it.shared() }
            ?: track.genre
        val artists = artists.map { it.unlessMangling(track.artist).shared() }.ifEmpty { track.artists }
        val originalYear = originalYear ?: track.originalYear
        val language = language?.shared() ?: track.language
        val albumArtist = albumArtist?.unlessMangling(track.albumArtist)?.shared() ?: track.albumArtist
        val year = track.year ?: year
        val artworkUri = if (namesAlbum) track.artworkUri else track.artworkUri?.let(ownCover)
        return if (genre == track.genre && artists == track.artists &&
            originalYear == track.originalYear && language == track.language &&
            albumArtist == track.albumArtist && year == track.year && artworkUri == track.artworkUri
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
                artworkUri = artworkUri,
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
        // Only the revision each song was read at: what decides whether its file is read again.
        val known = mutex.withLock {
            val revisions = HashMap<String, String>()
            settings.readTrackGenreLines { lines ->
                for (line in lines) {
                    val id = idOf(line) ?: continue
                    // A line that does not decode is no entry at all: its file is read again.
                    decode(line)?.let { revisions[id] = it.revision }
                }
            }
            for ((id, stored) in unsaved) revisions[id] = stored.revision
            revisions
        }
        var learnedSomething = false
        val updates = HashMap<String, Stored>()
        for (track in library) {
            val revision = track.revisionKey()
            if (known[track.id.value] == revision) continue
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
                namesAlbum = facts.album != null,
            )
            updates[track.id.value] = stored
            if (stored.changes(track)) learnedSomething = true
            yield()
        }
        mutex.withLock {
            unsaved.putAll(updates)
            // Facts remain usable in memory if storage is unavailable. A warm pass must also
            // retry a failed save: its files no longer need reading, so updates will be empty.
            if (unsaved.isNotEmpty()) {
                try {
                    persist(unsaved)
                    unsaved.clear()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // This is a rebuildable cache, not a reason to terminate the library screen.
                }
            }
        }
        return learnedSomething
    }

    /** Rewrites the cache: every current-format line not superseded by [changes], then [changes]. */
    private fun persist(changes: Map<String, Stored>) {
        settings.writeTrackGenreLines { emit ->
            settings.readTrackGenreLines { lines ->
                for (line in lines) {
                    val id = idOf(line) ?: continue
                    if (id in changes || decode(line) == null) continue
                    emit(line)
                }
            }
            for ((id, stored) in changes) emit(encode(id, stored))
        }
    }

    private fun Stored.changes(track: TrackDescriptor): Boolean = mergedInto(track) != track

    private companion object {
        /**
         * v2 added artists and the original year, v3 the language tag, v4 the album artist, v5 the
         * file's own year, v6 whether the file names an album. Older lines are deliberately dropped on decode: those files must be
         * re-read once anyway to learn the new facts. That is one tag read per track, the same
         * pass every earlier bump cost; SMART's audio analysis is keyed on the audio's identity
         * (URI, duration, revision) and is untouched — only the cheap text vector re-encodes
         * where a year appears.
         */
        const val FORMAT = "v6"
        const val FORMAT_PREFIX = "$FORMAT|"

        /** Joins the artist list inside one hex field; NUL never appears in a real name. */
        const val ARTIST_JOIN = "\u0000"

        /**
         * The identity a cache entry is valid for. [TrackDescriptor.sourceRevision] carries
         * size/mtime/generation on Android; the duration stands in where a platform leaves it
         * null, and a constant otherwise — worst case is one extra read after an app update.
         */
        fun TrackDescriptor.revisionKey(): String =
            sourceRevision ?: durationMs?.toString() ?: "-"

        fun encode(id: String, stored: Stored): String =
            listOf(
                FORMAT,
                PayloadHex.encode(id),
                PayloadHex.encode(stored.revision),
                PayloadHex.encode(stored.joinedGenres),
                PayloadHex.encode(stored.artists.joinToString(ARTIST_JOIN)),
                stored.originalYear?.toString() ?: "",
                PayloadHex.encode(stored.language.orEmpty()),
                PayloadHex.encode(stored.albumArtist.orEmpty()),
                stored.year?.toString() ?: "",
                if (stored.namesAlbum) "1" else "0",
            ).joinToString("|")

        /** Where field [index] of a current-format [line] starts and ends, or null. */
        fun fieldRange(line: String, index: Int): IntRange? {
            if (!line.startsWith(FORMAT_PREFIX)) return null
            var start = 0
            for (field in 0 until index) {
                start = line.indexOf('|', start) + 1
                if (start == 0) return null
            }
            val end = line.indexOf('|', start).let { if (it < 0) line.length else it }
            return start until end
        }

        /** The song a current-format line is about, decoding nothing else. */
        fun idOf(line: String): String? =
            fieldRange(line, 1)?.let { PayloadHex.decode(line, it.first, it.last + 1) }

        fun decode(line: String): Stored? {
            val parts = line.split('|')
            if (parts.size != 10 || parts[0] != FORMAT) return null
            parts[1].unhexOrNull() ?: return null
            val revision = parts[2].unhexOrNull() ?: return null
            val genres = parts[3].unhexOrNull() ?: return null
            val artistsJoined = parts[4].unhexOrNull() ?: return null
            val language = parts[6].unhexOrNull() ?: return null
            val albumArtist = parts[7].unhexOrNull() ?: return null
            // Entries written before the ID3 reader learned to read UTF-8 in Latin-1 frames
            // hold "HÃ¶rspiel" for an unchanged file. TextRepair recovers the names a re-read
            // would find without opening the file, and leaves a sound name as it is.
            return Stored(
                revision = revision,
                joinedGenres = genres.split(GenreTags.SEPARATOR)
                    .joinToString(GenreTags.SEPARATOR, transform = TextRepair::repair),
                artists = artistsJoined.split(ARTIST_JOIN).filter { it.isNotEmpty() }.map(TextRepair::repair),
                originalYear = parts[5].toIntOrNull(),
                language = language.takeIf { it.isNotEmpty() }?.let(TextRepair::repair),
                albumArtist = albumArtist.takeIf { it.isNotEmpty() }?.let(TextRepair::repair),
                year = parts[8].toIntOrNull(),
                namesAlbum = parts[9] != "0",
            )
        }

        private fun String.unhexOrNull(): String? = PayloadHex.decode(this)
    }
}

/** Splits a (possibly joined) genre string for consumers that group by individual genre. */
internal fun TrackDescriptor.genreList(): List<String> = GenreTags.split(genre)

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.idIndex
import io.github.nikitasud.latentjam.library.FolderTree
import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.library.tags.TextRepair
import io.github.nikitasud.latentjam.smart.PayloadHex
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Whether the file names an album at all is one more such fact. Android's scanner files a song
 * without one under its folder's name, and that pseudo-album's cover belongs to another song in the
 * folder (issue #12), so such a song keeps only a cover of its own ([ownCoverOnly]). A folder's name
 * is no album either (issue #39): where the scanner's album is the folder's name and the file was
 * read whole and names no album, the song has none, for everything the listener sees. SMART's
 * ranking keeps reading what the scanner said ([TrackDescriptor.rankingAlbum]). A song whose
 * file cannot tell (a tag read in part or refused, a container no reader judges), or has not been
 * read yet, keeps what the scanner said, exactly as before. A file that changed since it was read
 * keeps its "no album" only while the scanner still gives the folder's name.
 *
 * A cache entry is keyed by [TrackDescriptor.sourceRevision]: retagging changes the revision,
 * which makes the entry stale and the file re-read. "Read fine, found nothing" is remembered
 * too — the file is not worth re-opening every launch — while "could not read" stores nothing
 * and stays eligible for retry.
 */
internal class GenreEnrichment(
    private val settings: AppSettings,
    private val ownCover: (artworkUri: String, revision: String?) -> String = ::ownCoverOnly,
    /** Whether the scanner names a song's album after its folder when the file names none (Android). */
    private val folderAlbums: Boolean = scannerNamesAlbumsAfterFolders,
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
        /** What the file says of its album; one that names none borrows no album's cover. */
        val album: AlbumFact,
        /**
         * The file's length from its own header, for an MP4 file the scanner reported none for (Apple Lossless on
         * Android, which the system cannot decode). Null: not asked (the scanner had a length, another container, or
         * the line is older); 0: asked, and the file gives none.
         */
        val durationMs: Long? = null,
        /**
         * Read by the previous format, which did not record [album]: applied as it is (the
         * album fallback kept), but its file is read again.
         */
        val stale: Boolean = false,
    )

    private val mutex = Mutex()

    /**
     * Facts learned but not yet saved (storage refused the write). Normally empty: the cache
     * itself stays on storage and is streamed by [apply] and [backfill], never held whole (on
     * Android; iOS keeps it as one stored string). At
     * 10,000 songs the resident map and its decoded strings were 2.8 MB of a 48 MB heap.
     */
    private val unsaved = HashMap<String, Stored>()

    /** Applies remembered facts; one streamed pass over the cache, safe on every library load. */
    suspend fun apply(library: List<TrackDescriptor>): List<TrackDescriptor> = mutex.withLock {
        if (library.isEmpty()) return@withLock library
        // The cache is a file now: streamed, decoded and merged off the caller's (main) thread.
        withContext(Dispatchers.Default) { applyLocked(library) }
    }

    private fun applyLocked(library: List<TrackDescriptor>): List<TrackDescriptor> {
        val positions = HashMap<String, Int>(library.size * 2)
        library.forEachIndexed { index, track -> positions[track.id.value] = index }
        var result: MutableList<TrackDescriptor>? = null
        // An album's songs share one genre, artist list and album artist: one instance per value.
        val pool = HashMap<String, String>()
        fun merge(id: String, stored: Stored) {
            val index = positions[id] ?: return
            val track = library[index]
            if (stored.revision != track.revisionKey()) {
                // The file changed and waits to be read again. Its remembered length still stands in where the scanner
                // gives none: the engine keys a track's analysis by its length, and a track that lost it for the
                // minutes until the re-read would be analysed anew, and lose what a tag edit carries over.
                val length = stored.durationMs?.takeIf { it > 0 && track.durationMs == null }
                // So does its "names no album", while the scanner still gives the folder's name. A tag saved into
                // such a song (its title, say) otherwise brought the folder back as its album, with the folder's
                // cover, until the re-read took both away again: a tile that came and went, the catalog built
                // twice. An album typed meanwhile makes the scanner's album another name, and that one is shown;
                // one typed that is the folder's name comes back with the re-read ([mergedInto]).
                val albumless = stored.album == AlbumFact.NONE && folderAlbums &&
                    albumIsFolderName(track.rankingAlbum, track.folderPath, track.storageVolume)
                if (length == null && !albumless) return
                val held = if (albumless) {
                    track.copy(
                        durationMs = length ?: track.durationMs,
                        album = null,
                        scannerAlbum = track.rankingAlbum,
                        artworkUri = track.artworkUri?.let { ownCover(it, track.sourceRevision) },
                    )
                } else {
                    track.copy(durationMs = length)
                }
                // A library that is already held so (the list published before the re-read) stays as it is.
                if (held != track) {
                    val target = result ?: library.toMutableList().also { result = it }
                    target[index] = held
                }
                return
            }
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
        return result ?: library
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
        // The scanner's album is the folder's name and the file, read whole, names none: it has no album. A file
        // that cannot tell keeps the scanner's: it may well have read a real album that is the folder's name.
        // What the scanner said stays on the descriptor ([TrackDescriptor.scannerAlbum]) for what SMART ranks
        // with, and is the album again as soon as a line says the file names one.
        val scanned = track.rankingAlbum
        val folderNamed = album == AlbumFact.NONE && folderAlbums &&
            albumIsFolderName(scanned, track.folderPath, track.storageVolume)
        val trackAlbum = if (folderNamed) null else scanned
        val scannerAlbum = if (folderNamed) scanned else null
        val artworkUri = if (album == AlbumFact.NONE) {
            track.artworkUri?.let { ownCover(it, track.sourceRevision) }
        } else {
            track.artworkUri
        }
        // Only where the scanner reported no length.
        val durationMs = track.durationMs ?: durationMs?.takeIf { it > 0 }
        return if (genre == track.genre && artists == track.artists &&
            originalYear == track.originalYear && language == track.language &&
            albumArtist == track.albumArtist && year == track.year && artworkUri == track.artworkUri &&
            durationMs == track.durationMs && trackAlbum == track.album && scannerAlbum == track.scannerAlbum
        ) {
            track
        } else {
            track.copy(
                album = trackAlbum,
                scannerAlbum = scannerAlbum,
                genre = genre,
                artists = artists,
                originalYear = originalYear,
                language = language,
                albumArtist = albumArtist,
                year = year,
                artworkUri = artworkUri,
                durationMs = durationMs,
            )
        }
    }

    /**
     * Whether [this] still answers for [track]: read at the track's revision, and, for a song the scanner gave no
     * length, already asked the file for one (lines written before the file's length was read were not).
     */
    private fun Stored.currentFor(track: TrackDescriptor): Boolean =
        revision == track.revisionKey() && (!track.lengthAskedOfFile() || durationMs != null)

    /**
     * A song the scanner reported no length for, in a container the facts reader can time (MP4: `.m4a` and its kin).
     * Only these are asked, so no other song's line or reading changes.
     */
    private fun TrackDescriptor.lengthAskedOfFile(): Boolean =
        durationMs == null && fileName?.substringAfterLast('.', "")?.lowercase() in MP4_EXTENSIONS

    /** [clean] (MediaStore's reading of the same field) where this is only its mojibake, else this. */
    private fun String.unlessMangling(clean: String?): String =
        if (clean != null && TextRepair.isMangling(this, of = clean)) clean else this

    /**
     * Reads embedded facts for tracks with no fresh cache entry. Returns true when anything
     * newly learned changes a descriptor, so the caller knows a reload is worth it. Bounded
     * politeness: yields between files so a first launch over a big library never owns a core.
     */
    suspend fun backfill(library: List<TrackDescriptor>): Boolean {
        // Which songs were read at their current revision: a flag per song, not a map of every id and
        // revision string (~150 bytes a song at 100,000 songs).
        val positions = library.idIndex()
        val upToDate = mutex.withLock {
            withContext(Dispatchers.Default) {
                val current = BooleanArray(library.size)
                settings.readTrackGenreLines { lines ->
                    for (line in lines) {
                        val id = idOf(line) ?: continue
                        val position = positions.positionOf(id)
                        if (position < 0) continue
                        // A line that does not decode, or is stale, is no entry: its file is read again.
                        decode(line)?.takeIf { !it.stale }?.let {
                            current[position] = it.currentFor(library[position])
                        }
                    }
                }
                for ((id, stored) in unsaved) {
                    val position = positions.positionOf(id)
                    if (position >= 0) current[position] = stored.currentFor(library[position])
                }
                current
            }
        }
        var learnedSomething = false
        val updates = HashMap<String, Stored>()
        for (track in library) {
            // The last song of a repeated id is the one the flags describe, as the map they replace did.
            if (upToDate[positions.positionOf(track.id.value)]) continue
            val revision = track.revisionKey()
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
                // A tag read only in part may name an album that was not reached: it cannot be told.
                album = when {
                    facts.album != null -> AlbumFact.NAMED
                    facts.albumKnown -> AlbumFact.NONE
                    else -> AlbumFact.UNKNOWN
                },
                // Asked only where the scanner reported no length; 0 records that the file gives none either.
                durationMs = if (track.lengthAskedOfFile()) facts.durationMs ?: 0L else null,
            )
            updates[track.id.value] = stored
            if (stored.changes(track)) learnedSomething = true
            // Saved as it goes: a pass over a whole library takes minutes, and an app closed
            // midway must not have to read the same files again.
            if (updates.size >= SAVE_EVERY) {
                save(updates)
                updates.clear()
            }
            yield()
        }
        // A warm pass must also retry a failed save: its files no longer need reading.
        save(updates)
        return learnedSomething
    }

    private suspend fun save(updates: Map<String, Stored>) = mutex.withLock {
        unsaved.putAll(updates)
        // Facts remain usable in memory if storage is unavailable.
        if (unsaved.isNotEmpty()) {
            try {
                withContext(Dispatchers.Default) { persist(unsaved) }
                unsaved.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // This is a rebuildable cache, not a reason to terminate the library screen.
            }
        }
    }

    /**
     * Rewrites the cache: every current- or previous-format line not superseded by [changes], then
     * [changes].
     */
    private fun persist(changes: Map<String, Stored>) {
        settings.writeTrackGenreLines { emit ->
            settings.readTrackGenreLines { lines ->
                for (line in lines) {
                    // Only lines of the formats [decode] reads are carried over, checked by their shape:
                    // their fields are not decoded here, as a pass over a whole library rewrites the
                    // file many times. A line that then fails to decode is read again by [backfill].
                    if (!isReadableFormat(line)) continue
                    val id = idOf(line) ?: continue
                    if (id in changes) continue
                    emit(line)
                }
            }
            for ((id, stored) in changes) emit(encode(id, stored))
        }
    }

    private fun Stored.changes(track: TrackDescriptor): Boolean = mergedInto(track) != track

    /** What a song's file says of its album, as a cache line keeps it. */
    private enum class AlbumFact(val code: String) {
        /** The file names an album. */
        NAMED("1"),

        /** The file was read whole and names none. */
        NONE("0"),

        /**
         * The file was read, but whether it names an album cannot be told: a tag read in part, or a container no
         * reader judges. Treated as [NAMED] (the scanner's album and its cover fallback stand); kept apart so the
         * file is read once. A build that knows only "0" and "1" reads this as "names one" too.
         */
        UNKNOWN("2"),
    }

    private companion object {
        /**
         * v2 added artists and the original year, v3 the language tag, v4 the album artist, v5 the
         * file's own year, v6 whether the file names an album. Lines older than v5 are dropped on
         * decode: those files must be read again anyway to learn facts the library shows. v5 lines
         * still apply while their files are read again ([Stored.stale]), so the library keeps its
         * genres and years through that pass instead of falling back to the scanner's for minutes.
         */
        // A v6 line may end in one more field, the file's own length ([Stored.durationMs]): written only for an MP4
        // file the scanner gave no length, so every other line is byte for byte what it was.
        const val FORMAT = "v6"
        const val FORMAT_PREFIX = "$FORMAT|"
        const val PREVIOUS_FORMAT = "v5"

        /** File name extensions of the MP4 container, the one whose own length the facts reader reads. */
        val MP4_EXTENSIONS = setOf("m4a", "m4b", "m4r", "mp4")

        /** Files read between two saves of a backfill: each save rewrites the whole cache. */
        const val SAVE_EVERY = 1024

        /** A line of the current format, or of the previous one, by its field count alone. */
        fun isReadableFormat(line: String): Boolean {
            val fields = line.count { it == '|' } + 1
            return (line.startsWith(FORMAT_PREFIX) && (fields == 10 || fields == 11)) ||
                (line.startsWith("$PREVIOUS_FORMAT|") && fields == 9)
        }

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
                stored.album.code,
                // A trailing field only for a song whose length was asked of its file: every other line is as before.
            ).plus(listOfNotNull(stored.durationMs?.toString())).joinToString("|")

        /** Where field [index] of a current- or previous-format [line] starts and ends, or null. */
        fun fieldRange(line: String, index: Int): IntRange? {
            if (!line.startsWith(FORMAT_PREFIX) && !line.startsWith("$PREVIOUS_FORMAT|")) return null
            var start = 0
            for (field in 0 until index) {
                start = line.indexOf('|', start) + 1
                if (start == 0) return null
            }
            val end = line.indexOf('|', start).let { if (it < 0) line.length else it }
            return start until end
        }

        /** The song a current- or previous-format line is about, decoding nothing else. */
        fun idOf(line: String): String? =
            fieldRange(line, 1)?.let { PayloadHex.decode(line, it.first, it.last + 1) }

        fun decode(line: String): Stored? {
            val parts = line.split('|')
            val current = (parts.size == 10 || parts.size == 11) && parts[0] == FORMAT
            val previous = parts.size == 9 && parts[0] == PREVIOUS_FORMAT
            if (!current && !previous) return null
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
                album = if (!current) AlbumFact.NAMED else AlbumFact.entries.firstOrNull { it.code == parts[9] } ?: AlbumFact.NAMED,
                durationMs = if (current) parts.getOrNull(10)?.toLongOrNull() else null,
                stale = previous,
            )
        }

        private fun String.unhexOrNull(): String? = PayloadHex.decode(this)
    }
}

/** Splits a (possibly joined) genre string for consumers that group by individual genre. */
internal fun TrackDescriptor.genreList(): List<String> = GenreTags.split(genre)

/**
 * Whether [album], as Android's scanner reported it, is only the name of the folder the file is in: the scanner files
 * a song whose file names no album under its parent folder's name. For a file in a volume's own root that folder is
 * the mount point: the user's number ("0") on the device's storage, the volume's id on a card.
 *
 * The scanner's text went through the mojibake repair and [folderPath] did not, so both spellings count.
 */
internal fun albumIsFolderName(album: String?, folderPath: String?, storageVolume: String?): Boolean {
    val name = album?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val folder = folderPath?.replace('\\', '/')?.trim('/')?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
    if (folder == null) {
        return if (storageVolume == null || storageVolume == FolderTree.PRIMARY_VOLUME) {
            name.length <= 3 && name.all { it in '0'..'9' }
        } else {
            name.equals(storageVolume, ignoreCase = true)
        }
    }
    return name == folder || name == TextRepair.repair(folder).trim()
}

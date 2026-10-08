/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.CoverPicture
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What a file's own tags say its cover is, for re-checking a [TrackCoverOverrides] entry. */
internal sealed interface FileCover {
    /** The cover's CRC-32, or null when the file has none. */
    data class Read(val crc32: Long?) : FileCover

    /** Not a file the tag codecs recognise any more: nothing about its cover can be trusted. */
    data object Unrecognised : FileCover

    /** Could not be opened now (a permission, a busy volume): asked again at the next scan. */
    data object Unreadable : FileCover
}

/**
 * Covers of the songs whose cover LatentJam saved, which MediaStore's own covers may not show.
 *
 * Below Android 10 MediaStore gives every song its album's art (see [MediaStoreArtwork]), so a new
 * cover saved into one song of an album never shows. After such a save the new image is kept in
 * [directory] as `<crc32>.<ext>`, and the song's entry in a small index beside it names that file;
 * a removed cover is an entry with no file. Every scan swaps those songs' album art for their own cover (null for a removed one) and
 * keeps the album's in [TrackDescriptor.albumArtworkUri], which grouping compares. Every other
 * song is left exactly as the scan made it, so no other file is read.
 *
 * A new entry is trusted at whatever revision the first scan after its save sees: the save itself
 * verified the file holds the cover, so a cover saved into thousands of files costs no reads. From
 * then on an entry is trusted at that revision. At any other, another app may have retagged the
 * file, so [readCover] reads its embedded cover: the same one keeps the entry at the new revision,
 * any other drops it. A later LatentJam save that keeps the cover trusts the entry again ([kept]).
 *
 * MediaProvider (Android 11+) also regenerates an album's art from a file whose cover changed, so
 * after a cover saved into some songs of an album every other song of it would show the new one.
 * [record] therefore pins each such sibling that has no entry yet to the picture its own file holds:
 * an entry like a saved one, trusted at the first scan's revision and re-checked by CRC after that.
 * A sibling whose file holds no picture (or one that cannot be read) keeps the album's art, and may
 * show whatever MediaProvider regenerated it from.
 *
 * A song a scan does not hold keeps its entry and image: a library on a card that is not mounted
 * yet, or a revoked permission, makes a scan look empty, and the songs come back. Entries are tiny,
 * so absent ones are pruned only past [MAX_ABSENT], those absent longest first; an entry whose image
 * is gone is dropped at once. An image no entry names, a temporary file a crash left, and
 * everything under an index this store did not write are deleted. An index storage cannot read for
 * the moment is left alone, and so is everything it names.
 *
 * Not thread-safe across processes; within one, every call holds the store's lock.
 */
internal class TrackCoverOverrides(
    private val directory: File,
    private val readCover: (TrackDescriptor) -> FileCover,
    /** Reads the index file; a seam so tests can make it fail the way storage does. */
    private val readIndex: (File) -> String = { it.readText() },
    /** The clock that dates when a song went missing from the scans; a seam for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What reading the index found. Only [Corrupt] may cost the store its entries. */
    private sealed interface IndexRead {
        data class Ok(val entries: Map<String, Entry>) : IndexRead

        /** Content this store did not write: nothing in it can be trusted. */
        data object Corrupt : IndexRead

        /** Storage refused the read for now (I/O error, too many open files, a vanished file). */
        data object Unavailable : IndexRead
    }

    private data class Entry(
        /** The image file in [directory], named by its CRC-32; null for a removed cover. */
        val fileName: String?,
        /** The revision the song's file was last checked at; null until the first scan after its save. */
        val revision: String?,
        /** When the scans stopped holding the song ([now]); null while they hold it. */
        val absentSince: Long? = null,
    ) {
        /** The CRC-32 of the cover the song's file must still hold; null for a removed cover. */
        val crc32: Long? get() = fileName?.substringBefore('.')?.toLong(16)
    }

    private val index = File(directory, INDEX_NAME)
    private val lock = Any()

    /**
     * Remembers that [cover] was just saved into the files of [trackIds]. [CoverEdit.Keep] changes
     * nothing. Each of [siblings] (the other songs of their albums) that is not one of them and has
     * no entry yet is pinned to the picture [siblingCover] reads from its own file; one without a
     * picture is left alone. True when what a scan shows for one of them changes: a new entry, or
     * another cover than its entry named. False when every one already had this cover, or nothing
     * could be kept.
     */
    fun record(
        trackIds: Collection<TrackId>,
        cover: CoverEdit,
        siblings: Collection<TrackId> = emptyList(),
        siblingCover: (TrackId) -> CoverPicture? = { null },
    ): Boolean {
        if (cover == CoverEdit.Keep || trackIds.isEmpty()) return false
        return synchronized(lock) {
            // An index that cannot be read now must not be overwritten with this save's entries
            // alone: the save then shows album art, as a cover that could not be kept does.
            val entries = when (val found = read()) {
                is IndexRead.Ok -> found.entries.toMutableMap()
                IndexRead.Corrupt -> HashMap()
                IndexRead.Unavailable -> return false
            }
            val entry = when (cover) {
                is CoverEdit.Replace -> Entry(fileName = store(cover.bytes, cover.mime), revision = null)
                else -> Entry(fileName = null, revision = null)
            }
            var shown = false
            for (id in trackIds) {
                val before = entries.put(id.value, entry)
                if (before == null || before.fileName != entry.fileName || before.absentSince != null) shown = true
            }
            for (sibling in siblings) {
                if (sibling.value in entries) continue
                val own = siblingCover(sibling) ?: continue
                entries[sibling.value] = Entry(fileName = store(own.bytes, own.mime), revision = null)
                shown = true
            }
            write(entries)
            shown
        }
    }

    /** Keeps [bytes] as `<crc32>.<ext>` unless that file is already there; its name. */
    private fun store(bytes: ByteArray, mime: String): String {
        val name = Crc32.of(bytes).toString(16).padStart(8, '0') + "." + extensionOf(mime)
        val file = File(directory, name)
        if (!file.isFile || file.length() != bytes.size.toLong()) writeAtomically(file, bytes)
        return name
    }

    /**
     * A LatentJam save that kept the cover just changed the files of [saved] (as the library held
     * them when the save began). Verification pinned every picture byte for byte, so an entry still
     * checked at that revision holds at the file's new one too: it is trusted again, and the next
     * scan stamps it without reading the file. An entry checked at another revision may have been
     * retagged since, and is left to be read.
     */
    fun kept(saved: Collection<TrackDescriptor>) {
        if (saved.isEmpty()) return
        synchronized(lock) {
            val entries = (read() as? IndexRead.Ok)?.entries ?: return
            if (entries.isEmpty()) return
            val trusted = entries.toMutableMap()
            for (track in saved) {
                val entry = entries[track.id.value] ?: continue
                if (entry.absentSince == null && entry.revision != null && entry.revision == track.sourceRevision) {
                    trusted[track.id.value] = entry.copy(revision = null)
                }
            }
            if (trusted != entries) write(trusted)
        }
    }

    /**
     * [tracks], the complete result of a scan, with each overridden song's own cover. A song missing
     * from it keeps its entry, dated, until it comes back or is pruned. Never throws: the scan it is
     * part of must not fail over a cover.
     */
    fun apply(tracks: List<TrackDescriptor>): List<TrackDescriptor> = synchronized(lock) {
        val entries = when (val found = read()) {
            is IndexRead.Ok -> found.entries
            IndexRead.Corrupt -> {
                // Nothing in it can be trusted, and nothing names its images any more.
                runCatching { write(emptyMap()) }
                return tracks
            }
            // Asked again at the next scan; nothing is dropped or deleted meanwhile.
            IndexRead.Unavailable -> return tracks
        }
        if (entries.isEmpty()) return tracks
        val kept = HashMap<String, Entry>(entries.size)
        val byId = tracks.associateBy { it.id.value }
        val scannedAt = now()
        for ((id, entry) in entries) {
            if (entry.fileName != null && !File(directory, entry.fileName).isFile) continue
            val track = byId[id]
            if (track == null) {
                kept[id] = if (entry.absentSince != null) entry else entry.copy(absentSince = scannedAt)
                continue
            }
            val present = entry.copy(absentSince = null)
            if (entry.revision == null || entry.revision == track.sourceRevision) {
                kept[id] = present.copy(revision = track.sourceRevision)
                continue
            }
            when (val found = readCover(track)) {
                FileCover.Unreadable -> kept[id] = present
                FileCover.Unrecognised -> Unit
                is FileCover.Read -> if (found.crc32 == entry.crc32) kept[id] = present.copy(revision = track.sourceRevision)
            }
        }
        // Past the cap, the songs missing longest are taken to be gone for good.
        val absent = kept.entries.filter { it.value.absentSince != null }
        if (absent.size > MAX_ABSENT) {
            absent.sortedWith(compareBy({ it.value.absentSince }, { it.key }))
                .take(absent.size - MAX_ABSENT)
                .forEach { kept.remove(it.key) }
        }
        // A store that cannot be written now still shows what it checked, and checks again next scan.
        if (kept != entries) runCatching { write(kept) }
        if (kept.isEmpty()) return tracks
        tracks.map { track ->
            val entry = kept[track.id.value]
            // A song without album art has no album identity to keep beside its own cover: its
            // grouping would change, so it keeps what the scan gave it.
            if (entry == null || track.artworkUri == null) {
                track
            } else {
                track.copy(
                    artworkUri = entry.fileName?.let { "file://" + File(directory, it).absolutePath },
                    albumArtworkUri = track.albumArtworkUri ?: track.artworkUri,
                )
            }
        }
    }

    /** Every entry; none when the index is missing. */
    private fun read(): IndexRead {
        if (!index.exists()) return IndexRead.Ok(emptyMap())
        val text = try {
            readIndex(index)
        } catch (_: IOException) {
            return IndexRead.Unavailable
        } catch (_: SecurityException) {
            return IndexRead.Unavailable
        }
        return try {
            val lines = text.lines().filter(String::isNotEmpty)
            // Version 1 had no absence date: every entry in it was held by the scan that wrote it.
            val fieldCount = when (lines.firstOrNull()) {
                VERSION -> 4
                VERSION_1 -> 3
                else -> error("not an index")
            }
            lines.drop(1).associate { line ->
                val fields = line.split('\t')
                check(fields.size == fieldCount && fields[0].isNotEmpty())
                val fileName = fields[1].takeIf { it.isNotEmpty() }
                check(fileName == null || fileName.matches(FILE_NAME))
                val absentSince = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: error("not a date") }
                fields[0] to Entry(fileName = fileName, revision = fields[2].takeIf { it.isNotEmpty() }, absentSince = absentSince)
            }.let(IndexRead::Ok)
        } catch (_: IllegalStateException) {
            IndexRead.Corrupt
        } catch (_: IllegalArgumentException) {
            IndexRead.Corrupt
        }
    }

    /** Writes [entries], then deletes every image none of them names and every leftover temporary file. */
    private fun write(entries: Map<String, Entry>) {
        if (entries.isEmpty()) {
            index.delete()
        } else {
            index.atomicReplaceText(
                buildString {
                    append(VERSION).append('\n')
                    for ((id, entry) in entries.toSortedMap()) {
                        append(id).append('\t')
                            .append(entry.fileName.orEmpty()).append('\t')
                            .append(entry.revision.orEmpty().replace('\t', ' ').replace('\n', ' ')).append('\t')
                            .append(entry.absentSince?.toString().orEmpty())
                            .append('\n')
                    }
                },
            )
        }
        val named = entries.values.mapNotNullTo(HashSet()) { it.fileName }
        directory.listFiles()?.forEach { file ->
            val name = file.name
            val stray = name.matches(FILE_NAME) && name !in named
            // Only this store writes here, and only under its lock: no temporary file is in use now.
            val leftover = name.startsWith(".") && name.endsWith(".tmp")
            if (stray || leftover) file.delete()
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        directory.mkdirs()
        val temporary = File(directory, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not store ${file.name}" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val INDEX_NAME = "overrides.txt"
        const val VERSION = "track-covers v2"
        const val VERSION_1 = "track-covers v1"

        /** Entries of songs no scan holds that are kept before the oldest are pruned. */
        const val MAX_ABSENT = 2_000
        val FILE_NAME = Regex("[0-9a-f]{8}\\.(jpg|png|img)")

        fun extensionOf(mime: String): String = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            else -> "img"
        }
    }
}

/**
 * The songs of [rows] (id, MediaStore album id) that share an album with one of [saved] without
 * being saved themselves, in [rows]' order: those whose album art MediaProvider may regenerate from
 * a saved file. Album id 0 is no album, so it has no shared art.
 */
internal fun albumSiblings(saved: Collection<TrackId>, rows: List<Pair<TrackId, Long>>): List<TrackId> {
    val savedIds = saved.toHashSet()
    val albums = rows.mapNotNullTo(HashSet()) { (id, album) -> album.takeIf { id in savedIds && it > 0 } }
    if (albums.isEmpty()) return emptyList()
    return rows.mapNotNull { (id, album) -> id.takeIf { album in albums && it !in savedIds } }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.io.FileOutputStream

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
 * Covers of the songs whose cover LatentJam saved, on a platform that knows only album covers.
 *
 * MediaStore gives every song its album's art, so a new cover saved into one song of an album
 * never shows. After such a save the new image is kept in [directory] as `<crc32>.<ext>`, and the
 * song's entry in a small index beside it names that file; a removed cover is an entry with no
 * file. Every scan swaps those songs' album art for their own cover (null for a removed one) and
 * keeps the album's in [TrackDescriptor.albumArtworkUri], which grouping compares. Every other
 * song is left exactly as the scan made it, so no other file is read.
 *
 * A new entry is trusted at whatever revision the first scan after its save sees: the save itself
 * verified the file holds the cover, so a cover saved into thousands of files costs no reads. From
 * then on an entry is trusted at that revision. At any other, another app may have retagged the
 * file, so [readCover] reads its embedded cover: the same one keeps the entry at the new revision,
 * any other drops it. A song that is gone drops its entry. An image no entry names, a temporary
 * file a crash left, and everything under an index this store cannot read are deleted.
 *
 * Not thread-safe across processes; within one, every call holds the store's lock.
 */
internal class TrackCoverOverrides(
    private val directory: File,
    private val readCover: (TrackDescriptor) -> FileCover,
) {
    private data class Entry(
        /** The image file in [directory], named by its CRC-32; null for a removed cover. */
        val fileName: String?,
        /** The revision the song's file was last checked at; null until the first scan after its save. */
        val revision: String?,
    ) {
        /** The CRC-32 of the cover the song's file must still hold; null for a removed cover. */
        val crc32: Long? get() = fileName?.substringBefore('.')?.toLong(16)
    }

    private val index = File(directory, INDEX_NAME)
    private val lock = Any()

    /** Remembers that [cover] was just saved into the files of [trackIds]. [CoverEdit.Keep] changes nothing. */
    fun record(trackIds: Collection<TrackId>, cover: CoverEdit) {
        if (cover == CoverEdit.Keep || trackIds.isEmpty()) return
        synchronized(lock) {
            val entry = when (cover) {
                is CoverEdit.Replace -> {
                    val crc = Crc32.of(cover.bytes)
                    val name = crc.toString(16).padStart(8, '0') + "." + extensionOf(cover.mime)
                    val file = File(directory, name)
                    if (!file.isFile || file.length() != cover.bytes.size.toLong()) writeAtomically(file, cover.bytes)
                    Entry(fileName = name, revision = null)
                }
                else -> Entry(fileName = null, revision = null)
            }
            val entries = read().orEmpty().toMutableMap()
            for (id in trackIds) entries[id.value] = entry
            write(entries)
        }
    }

    /**
     * [tracks], the complete result of a scan, with each overridden song's own cover. Only a
     * complete scan may be passed: a song missing from it loses its entry. Never throws: the scan
     * it is part of must not fail over a cover.
     */
    fun apply(tracks: List<TrackDescriptor>): List<TrackDescriptor> = synchronized(lock) {
        val entries = read()
        if (entries == null) {
            // Nothing in it can be trusted, and nothing names its images any more.
            runCatching { write(emptyMap()) }
            return tracks
        }
        if (entries.isEmpty()) return tracks
        val kept = HashMap<String, Entry>(entries.size)
        val byId = tracks.associateBy { it.id.value }
        for ((id, entry) in entries) {
            val track = byId[id] ?: continue
            if (entry.fileName != null && !File(directory, entry.fileName).isFile) continue
            if (entry.revision == null || entry.revision == track.sourceRevision) {
                kept[id] = entry.copy(revision = track.sourceRevision)
                continue
            }
            when (val found = readCover(track)) {
                FileCover.Unreadable -> kept[id] = entry
                FileCover.Unrecognised -> Unit
                is FileCover.Read -> if (found.crc32 == entry.crc32) kept[id] = entry.copy(revision = track.sourceRevision)
            }
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
                    albumArtworkUri = track.artworkUri,
                )
            }
        }
    }

    /** Every entry: none when the index is missing, null when it is not one this store wrote. */
    private fun read(): Map<String, Entry>? {
        if (!index.isFile) return emptyMap()
        return try {
            val lines = index.readLines().filter(String::isNotEmpty)
            check(lines.firstOrNull() == VERSION)
            lines.drop(1).associate { line ->
                val fields = line.split('\t')
                check(fields.size == 3 && fields[0].isNotEmpty())
                val fileName = fields[1].takeIf { it.isNotEmpty() }
                check(fileName == null || fileName.matches(FILE_NAME))
                fields[0] to Entry(fileName = fileName, revision = fields[2].takeIf { it.isNotEmpty() })
            }
        } catch (_: Exception) {
            null
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
                            .append(entry.revision.orEmpty().replace('\t', ' ').replace('\n', ' '))
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
        const val VERSION = "track-covers v1"
        val FILE_NAME = Regex("[0-9a-f]{8}\\.(jpg|png|img)")

        fun extensionOf(mime: String): String = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            else -> "img"
        }
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.Crc32

/**
 * What a scan read out of one imported file. The rest of the file's descriptor (its id, its locator, its folder)
 * follows from where the file is, so none of it is kept here.
 */
internal data class ImportedFileTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val durationMs: Long?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val albumArtist: String?,
    /**
     * The cover's file NAME in the cache folder, never its path: the app's container moves between installs and
     * updates, and a stored path would point into the old one.
     */
    val coverFile: String?,
)

/** One imported file as a scan left it: the path under Documents, the size and dates it had, and what was read. */
internal data class ImportScanEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val addedAtMs: Long?,
    val tags: ImportedFileTags,
)

/**
 * The stored form of the iOS import scan, so that a launch does not read every unchanged file again.
 *
 * The file is regenerable and all or nothing: [decode] answers null for anything that is not a whole file written by
 * the same app build (cut short, damaged, another build, another layout), and the scan then reads the files as if
 * there were no cache.
 */
internal object ImportScanCache {
    const val FILE_NAME: String = "ios_import_scan_v1"

    private const val MAGIC = 0x4C4A4953 // "LJIS"
    private const val LAYOUT = 1

    /** The smallest entry: an empty path, two longs, and a flag or length for each optional field. */
    private const val MIN_ENTRY_BYTES = 4 + 8 + 8 + 1 + 4 * 4 + 1 + 3 * 1 + 4 + 4

    fun encode(build: String, entries: Collection<ImportScanEntry>): ByteArray {
        val out = Writer()
        out.int(MAGIC)
        out.int(LAYOUT)
        out.string(build)
        out.int(entries.size)
        for (entry in entries) {
            out.string(entry.relativePath)
            out.long(entry.sizeBytes)
            out.long(entry.modifiedAtMs)
            out.optionalLong(entry.addedAtMs)
            val tags = entry.tags
            out.string(tags.title)
            out.string(tags.artist)
            out.string(tags.album)
            out.string(tags.genre)
            out.optionalLong(tags.durationMs)
            out.optionalInt(tags.year)
            out.optionalInt(tags.trackNumber)
            out.optionalInt(tags.discNumber)
            out.string(tags.albumArtist)
            out.string(tags.coverFile)
        }
        val body = out.bytes()
        return body + intBytes(Crc32.of(body).toInt())
    }

    fun decode(build: String, bytes: ByteArray): List<ImportScanEntry>? {
        if (bytes.size < 4) return null
        val bodySize = bytes.size - 4
        val stored = Reader(bytes, bodySize, bytes.size).int()
        if (stored != Crc32.of(bytes, 0, bodySize).toInt()) return null
        return try {
            val input = Reader(bytes, 0, bodySize)
            if (input.int() != MAGIC || input.int() != LAYOUT) return null
            if (input.string() != build) return null
            val count = input.int()
            if (count < 0 || count.toLong() * MIN_ENTRY_BYTES > input.remaining) return null
            val entries = ArrayList<ImportScanEntry>(count)
            repeat(count) {
                val relativePath = input.string() ?: return null
                val sizeBytes = input.long()
                val modifiedAtMs = input.long()
                val addedAtMs = input.optionalLong()
                entries += ImportScanEntry(
                    relativePath = relativePath,
                    sizeBytes = sizeBytes,
                    modifiedAtMs = modifiedAtMs,
                    addedAtMs = addedAtMs,
                    tags = ImportedFileTags(
                        title = input.string(),
                        artist = input.string(),
                        album = input.string(),
                        genre = input.string(),
                        durationMs = input.optionalLong(),
                        year = input.optionalInt(),
                        trackNumber = input.optionalInt(),
                        discNumber = input.optionalInt(),
                        albumArtist = input.string(),
                        coverFile = input.string(),
                    ),
                )
            }
            entries.takeIf { input.remaining == 0 }
        } catch (_: Truncated) {
            null
        }
    }

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private class Truncated : Exception()

    private class Writer {
        private var buffer = ByteArray(4096)
        private var size = 0

        private fun room(count: Int) {
            if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        }

        fun byte(value: Int) {
            room(1)
            buffer[size++] = value.toByte()
        }

        fun int(value: Int) {
            room(4)
            for (shift in 24 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
        }

        fun long(value: Long) {
            room(8)
            for (shift in 56 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
        }

        /** A length of -1 is null: an empty string and a missing one are different tags. */
        fun string(value: String?) {
            if (value == null) return int(-1)
            val encoded = value.encodeToByteArray()
            int(encoded.size)
            room(encoded.size)
            encoded.copyInto(buffer, size)
            size += encoded.size
        }

        fun optionalLong(value: Long?) {
            byte(if (value == null) 0 else 1)
            if (value != null) long(value)
        }

        fun optionalInt(value: Int?) {
            byte(if (value == null) 0 else 1)
            if (value != null) int(value)
        }

        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    private class Reader(private val bytes: ByteArray, private var at: Int, private val end: Int) {
        val remaining: Int get() = end - at

        private fun need(count: Int) {
            if (count < 0 || count > remaining) throw Truncated()
        }

        fun byte(): Int {
            need(1)
            return bytes[at++].toInt() and 0xFF
        }

        fun int(): Int {
            need(4)
            var value = 0
            repeat(4) { value = (value shl 8) or (bytes[at++].toInt() and 0xFF) }
            return value
        }

        fun long(): Long {
            need(8)
            var value = 0L
            repeat(8) { value = (value shl 8) or (bytes[at++].toLong() and 0xFF) }
            return value
        }

        fun string(): String? {
            val length = int()
            if (length == -1) return null
            need(length)
            return bytes.decodeToString(at, at + length).also { at += length }
        }

        fun optionalLong(): Long? = when (byte()) {
            0 -> null
            1 -> long()
            else -> throw Truncated()
        }

        fun optionalInt(): Int? = when (byte()) {
            0 -> null
            1 -> int()
            else -> throw Truncated()
        }
    }
}

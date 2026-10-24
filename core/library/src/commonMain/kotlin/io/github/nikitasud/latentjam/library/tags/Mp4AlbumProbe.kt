/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Whether an MP4 file names an album (`©alb`), read with positional reads of box headers only.
 *
 * MediaStore files a song without an album under its folder's name (issue #12), and the one-pass
 * facts reader has no MP4 path, so this answers that one question for M4A files. It walks
 * `moov` → (`udta` →) `meta` → `ilst` → `©alb` → `data` and skips every other box by its size: the
 * cover (`covr`) and the sample tables are never read, whatever their size.
 */
internal object Mp4AlbumProbe {
    /** Boxes looked at per level; a real file has a handful, a corrupt one must not loop. */
    private const val MAX_BOXES = 4_096
    private const val MAX_ALBUM_BYTES = 4 * 1024
    private const val DATA_UTF16 = 2L

    /**
     * [EmbeddedTagFacts] with only [EmbeddedTagFacts.album] set; its album is null when the file is
     * well-formed and names none. Null when [source] is not MP4 or its boxes cannot be followed:
     * not knowing must not read as "no album".
     */
    fun read(source: RandomAccessSource): EmbeddedTagFacts? = try {
        album(source)
    } catch (_: Malformed) {
        null
    }

    private fun album(source: RandomAccessSource): EmbeddedTagFacts? {
        val head = source.read(0, 8) ?: return null
        if (head.ascii(4) != "ftyp") return null
        val moov = child(source, 0, source.length, "moov") ?: return null
        val metas = listOfNotNull(
            child(source, moov.body, moov.end, "udta")?.let { udta -> child(source, udta.body, udta.end, "meta") },
            child(source, moov.body, moov.end, "meta"),
        )
        for (meta in metas) {
            val ilst = child(source, metaBody(source, meta), meta.end, "ilst") ?: continue
            val item = child(source, ilst.body, ilst.end, ALBUM) ?: return EmbeddedTagFacts()
            val data = child(source, item.body, item.end, "data") ?: return EmbeddedTagFacts()
            // A data box's payload starts with a type indicator and a locale, four bytes each.
            val textStart = data.body + 8
            val count = (data.end - textStart).coerceIn(0, MAX_ALBUM_BYTES.toLong()).toInt()
            val bytes = source.read(textStart, count) ?: throw Malformed
            // The well-known type: the low 24 bits of the indicator; 1 is UTF-8, 2 UTF-16BE.
            val type = (source.read(data.body, 4) ?: throw Malformed).u32(0) and 0xFFFFFF
            val text = if (type == DATA_UTF16) utf16(bytes) else bytes.decodeToString()
            return EmbeddedTagFacts(album = text.trim().takeIf { it.isNotEmpty() })
        }
        // Well-formed, and no `©alb` anywhere MediaStore would look: the file names no album.
        return EmbeddedTagFacts()
    }

    /** A box that runs past its parent or the file: the structure cannot be followed. */
    private object Malformed : Exception()

    private class Box(val body: Long, val end: Long)

    /**
     * The first box of [type] directly inside [start] until [end]; null when there is none. A box
     * that does not fit its parent throws [Malformed].
     */
    private fun child(source: RandomAccessSource, start: Long, end: Long, type: String): Box? {
        var position = start
        repeat(MAX_BOXES) {
            if (position == end) return null
            if (position + 8 > end) throw Malformed
            val header = source.read(position, 8) ?: throw Malformed
            val size32 = header.u32(0)
            val (headerSize, size) = when (size32) {
                0L -> 8L to end - position
                1L -> {
                    val large = source.read(position + 8, 8) ?: throw Malformed
                    16L to ((large.u32(0) shl 32) or large.u32(4))
                }
                else -> 8L to size32
            }
            if (size < headerSize || size > end - position) throw Malformed
            if (header.ascii(4) == type) return Box(position + headerSize, position + size)
            position += size
        }
        throw Malformed
    }

    /**
     * Where a `meta` box's children start: ISO `meta` is a full box with four bytes of version and
     * flags first, while QuickTime's has its `hdlr` right away.
     */
    private fun metaBody(source: RandomAccessSource, meta: Box): Long {
        val peek = source.read(meta.body, 8) ?: throw Malformed
        return if (peek.ascii(4) == "hdlr") meta.body else meta.body + 4
    }

    private fun ByteArray.u32(at: Int): Long =
        ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or
            ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)

    private fun ByteArray.ascii(at: Int): String = CharArray(4) { (this[at + it].toInt() and 0xFF).toChar() }.concatToString()

    private fun utf16(bytes: ByteArray): String =
        CharArray(bytes.size / 2) { (((bytes[2 * it].toInt() and 0xFF) shl 8) or (bytes[2 * it + 1].toInt() and 0xFF)).toChar() }
            .concatToString()

    private const val ALBUM = "©alb"
}

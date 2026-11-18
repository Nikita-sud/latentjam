/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Whether a file with an ID3v2.2 tag names an album (`TAL`), read with positional reads of frame
 * headers only.
 *
 * The facts reader takes ID3v2.3 and 2.4 ([Id3FactsPrefix]); older taggers (iTunes for years) wrote
 * 2.2, with three-letter frame names and three-byte sizes. The system scanner reads those, so
 * without this answer a 2.2 file that names its album could not be told from one MediaStore filed
 * under its folder's name (issue #39). Every other frame is skipped by its size: a picture (`PIC`)
 * is never read.
 */
internal object Id3v22AlbumProbe {
    private const val HEADER = Id3Codec.HEADER_SIZE
    private const val FRAME_HEADER = 6
    private const val VERSION_2_2 = 2

    /** Frames looked at; a real tag has a few dozen, a corrupt one must not loop. */
    private const val MAX_FRAMES = 4_096
    private const val MAX_ALBUM_BYTES = 4 * 1024

    /**
     * [EmbeddedTagFacts] with only [EmbeddedTagFacts.album] set; its album is null when the tag is
     * well-formed and names none. Null when [source] has no ID3v2.2 tag or its frames cannot be
     * followed (an unsynchronised or compressed tag, a damaged frame): not knowing must not read as
     * "no album".
     */
    fun read(source: RandomAccessSource): EmbeddedTagFacts? {
        if (source.length < HEADER) return null
        val header = source.read(0, HEADER) ?: return null
        if (!Id3Codec.looksLikeTag(header) || (header[3].toInt() and 0xFF) != VERSION_2_2) return null
        // Bit 7 is unsynchronisation, bit 6 the compression nobody defined; the rest must be clear.
        if ((header[4].toInt() and 0xFF) == 0xFF || (header[5].toInt() and 0xFF) != 0) return null
        val size = Id3Codec.readSyncsafe(header, 6) ?: return null
        val end = HEADER.toLong() + size
        if (size <= 0 || end > source.length) return null
        var position = HEADER.toLong()
        var album: String? = null
        repeat(MAX_FRAMES) {
            // What is left cannot hold a frame, or padding begins: the tag was walked whole.
            if (end - position < FRAME_HEADER) return EmbeddedTagFacts(album = album)
            val frame = source.read(position, FRAME_HEADER) ?: return null
            if (frame[0] == 0.toByte()) return EmbeddedTagFacts(album = album)
            for (index in 0 until 3) {
                val letter = (frame[index].toInt() and 0xFF).toChar()
                if (letter !in 'A'..'Z' && letter !in '0'..'9') return null
            }
            val length = ((frame[3].toInt() and 0xFF) shl 16) or
                ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            val body = position + FRAME_HEADER
            if (length > end - body) return null
            if (album == null && frame[0] == 'T'.code.toByte() && frame[1] == 'A'.code.toByte() &&
                frame[2] == 'L'.code.toByte() && length > 1
            ) {
                if (length > MAX_ALBUM_BYTES) return null
                val text = source.read(body, length) ?: return null
                // An encoding byte, then the text; a name that cannot be decoded is still a name.
                val decoded = Id3Text.decode(text[0].toInt() and 0xFF, text, 1, text.size) ?: return null
                album = decoded.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
            }
            position = body + length
        }
        return null
    }
}

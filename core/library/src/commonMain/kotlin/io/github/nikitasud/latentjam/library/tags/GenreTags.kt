/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Reads EVERY genre a file's own tags carry, across the containers the library actually holds.
 *
 * Android's media scanner keeps exactly one genre per track, so a FLAC tagged the correct way —
 * five separate `GENRE` Vorbis fields — reaches the app as just "Electronic". These parsers read
 * the file's own metadata instead: multiple Vorbis `GENRE` fields, ID3v2.4 NUL-separated `TCON`
 * values, ID3v2.3 `(nn)` numeric references, and the semicolon/slash-joined strings sloppy
 * taggers write. Everything funnels into one canonical joined form (see [SEPARATOR]) so a single
 * `genre` string can carry the full list through the rest of the app unchanged.
 */
public object GenreTags {

    /** Canonical list separator inside a joined genre string. */
    public const val SEPARATOR: String = "; "

    /** More values than this is tag spam, not curation; keep the informative head. */
    private const val MAX_GENRES = 8

    /** A single value longer than this is prose (or an essay-length tag), not a genre. */
    private const val MAX_GENRE_LENGTH = 64

    /**
     * Splits a raw genre string into its individual genres: separators first, then ID3v1
     * numeric references — both the bare `(17)` form and v1 index strings — mapped to names.
     * Returns an empty list for blank/unknown-only input.
     */
    public fun split(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        // Expand ID3v2.3 "(17)(4)Custom" runs into ';'-separated names before the general
        // split. NEVER a space separator here — "Trip Hop" is one genre, not two.
        val expanded = buildString {
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                if (char == '(') {
                    val close = raw.indexOf(')', index)
                    val inner = if (close > index) raw.substring(index + 1, close) else null
                    val number = inner?.toIntOrNull()
                    if (number != null) {
                        append('\u0000')
                        append(ID3V1_GENRES.getOrNull(number) ?: "")
                        append('\u0000')
                        index = close + 1
                        continue
                    }
                }
                append(char)
                index++
            }
        }
        return expanded
            .split(';', '/', ',', '|', '\u0000')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { value -> value.toIntOrNull()?.let { ID3V1_GENRES.getOrNull(it) } ?: value }
            .filter { it.isNotEmpty() && it.length <= MAX_GENRE_LENGTH && !it.isUnknown() }
            .distinctByLowercase()
            .take(MAX_GENRES)
    }

    /** Joins split genres back into the canonical single-string form, or null when empty. */
    public fun canonical(genres: List<String>): String? =
        genres.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    /**
     * All `GENRE` fields of a FLAC file's Vorbis comment block, in file order, already split
     * and deduplicated. Null when [source] is not a FLAC stream or carries no comment block.
     *
     * Walks the metadata blocks from the stream head: 4-byte magic, then per block a 1-byte
     * last/type header and a 24-bit big-endian length. The comment body is Vorbis binary:
     * little-endian u32 lengths framing UTF-8 `KEY=value` entries.
     */
    public fun flacGenres(source: ByteSource): List<String>? {
        val magic = source.read(4) ?: return null
        if (!magic.contentEquals(FLAC_MAGIC)) return null
        return flacGenresAfterMagic(source)
    }

    /**
     * Container-sniffing entry point: reads the first bytes itself and dispatches to the FLAC
     * walk, the ID3 prefix parser, or the Ogg scan. This is the one function platform readers
     * call — they only adapt their stream to [ByteSource].
     */
    public fun embeddedGenres(source: ByteSource): List<String>? {
        val magic = source.read(4) ?: return null
        return when {
            magic.contentEquals(FLAC_MAGIC) -> flacGenresAfterMagic(source)
            magic.contentEquals(OGG_MAGIC) ->
                oggGenres(magic + source.readUpTo(OGG_PREFIX_BYTES))
            magic.size == 4 && magic[0] == 'I'.code.toByte() &&
                magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() -> {
                val headerRest = source.read(Id3Tags.HEADER_SIZE - 4) ?: return null
                val header = magic + headerRest
                val tagLength = Id3Tags.tagLength(header) ?: return null
                if (tagLength <= header.size || tagLength > MAX_ID3_PREFIX) return null
                val body = source.read(tagLength - header.size) ?: return null
                id3Genres(header + body)
            }
            else -> null
        }
    }

    private fun flacGenresAfterMagic(source: ByteSource): List<String>? {
        while (true) {
            val header = source.read(4) ?: return null
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (type == FLAC_VORBIS_COMMENT) {
                if (length > MAX_COMMENT_BLOCK) return null
                val body = source.read(length) ?: return null
                return vorbisCommentGenres(body, 0)
            }
            if (last) return null
            if (!source.skip(length.toLong())) return null
        }
    }

    /**
     * Genres from an Ogg (Vorbis/Opus) prefix: finds the comment header marker in the first
     * pages and parses the same Vorbis binary layout. A pragmatic scan rather than a full Ogg
     * page walk — the comment header sits within the first few kilobytes of every real file.
     */
    public fun oggGenres(prefix: ByteArray): List<String>? {
        if (prefix.size < 8 || !prefix.startsWith(OGG_MAGIC)) return null
        val opus = prefix.indexOfSequence(OPUS_TAGS)
        if (opus >= 0) return vorbisCommentGenres(prefix, opus + OPUS_TAGS.size)
        val vorbis = prefix.indexOfSequence(VORBIS_COMMENT_HEADER)
        if (vorbis >= 0) return vorbisCommentGenres(prefix, vorbis + VORBIS_COMMENT_HEADER.size)
        return null
    }

    /** Genres from an ID3v2 tag prefix, split from the flattened `TCON` text. */
    public fun id3Genres(prefix: ByteArray): List<String>? {
        val genre = Id3Tags.read(prefix)?.genre ?: return null
        return split(genre).takeIf { it.isNotEmpty() }
    }

    /** Streaming byte access, so a FLAC walk can skip megabyte cover-art blocks unread. */
    public interface ByteSource {
        /** Exactly [count] bytes, or null at a short read. */
        public fun read(count: Int): ByteArray?

        /** Up to [count] bytes — shorter (possibly empty) at end of stream, never null. */
        public fun readUpTo(count: Int): ByteArray

        /** Advances past [count] bytes; false when the stream ends first. */
        public fun skip(count: Long): Boolean
    }

    private fun vorbisCommentGenres(body: ByteArray, start: Int): List<String>? {
        var offset = start
        val vendorLength = body.readLeU32(offset) ?: return null
        offset += 4 + vendorLength
        val count = body.readLeU32(offset) ?: return null
        offset += 4
        if (count > MAX_COMMENT_COUNT) return null
        val values = ArrayList<String>()
        repeat(count) {
            val length = body.readLeU32(offset) ?: return values.finish()
            offset += 4
            if (length > MAX_COMMENT_BLOCK || offset + length > body.size) return values.finish()
            val entry = body.decodeToString(offset, offset + length)
            offset += length
            val equals = entry.indexOf('=')
            if (equals > 0 && entry.substring(0, equals).equals("GENRE", ignoreCase = true)) {
                values.add(entry.substring(equals + 1))
            }
        }
        return values.finish()
    }

    private fun List<String>.finish(): List<String>? =
        flatMap { split(it) }.distinctByLowercase().take(MAX_GENRES).takeIf { it.isNotEmpty() }

    private fun ByteArray.readLeU32(offset: Int): Int? {
        if (offset < 0 || offset + 4 > size) return null
        val value = (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
        return value.takeIf { it in 0..MAX_SANE_U32 }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun String.isUnknown(): Boolean =
        equals("<unknown>", ignoreCase = true) ||
            equals("unknown", ignoreCase = true) ||
            equals("undefined", ignoreCase = true) ||
            equals("genre", ignoreCase = true)

    private fun List<String>.distinctByLowercase(): List<String> {
        val seen = HashSet<String>()
        return filter { seen.add(it.lowercase()) }
    }

    private val FLAC_MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"
    private const val FLAC_VORBIS_COMMENT = 4
    private val OGG_MAGIC = byteArrayOf(0x4F, 0x67, 0x67, 0x53) // "OggS"
    private val OPUS_TAGS = "OpusTags".encodeToByteArray()
    private val VORBIS_COMMENT_HEADER = byteArrayOf(0x03) + "vorbis".encodeToByteArray()
    private const val MAX_COMMENT_BLOCK = 1 shl 20
    private const val OGG_PREFIX_BYTES = 64 * 1024
    private const val MAX_ID3_PREFIX = 8 * 1024 * 1024
    private const val MAX_COMMENT_COUNT = 512
    private const val MAX_SANE_U32 = Int.MAX_VALUE - 8

    /** The ID3v1 genre table (0–79) plus the universally adopted Winamp extensions (80–125). */
    private val ID3V1_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
        "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion",
        "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel",
        "Noise", "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave",
        "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock",
        "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer",
        "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical",
        "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion",
        "Bebop", "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock",
        "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band",
        "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera",
        "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire",
        "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad",
        "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A cappella",
        "Euro-House", "Dance Hall",
    )
}

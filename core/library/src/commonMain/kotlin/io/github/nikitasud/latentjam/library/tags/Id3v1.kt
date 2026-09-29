/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * The ID3v1 block at the very end of a file — and why a rewrite deletes it.
 *
 * ID3v1 is 128 fixed-width bytes of ISO-8859-1: thirty characters each for
 * title, artist and album, four for the year, thirty for a comment, and one
 * byte naming a genre from a closed numeric list. It has no extension
 * mechanism, and it predates the idea that music might not be written in a
 * Western European alphabet.
 *
 * ### Why removal rather than maintenance
 *
 * The alternative is to rewrite the trailer alongside the ID3v2 tag so the two
 * agree. That is only possible when the new text fits thirty ISO-8859-1
 * characters — and in the library this writer was built for, it usually does
 * not. Measured over 504 files: 478 carry a trailer, and 84 of those already
 * contradict their own ID3v2 tag before anything is edited. Cyrillic and
 * Japanese titles are stored as runs of `?`, and even plain English loses a
 * typographic apostrophe, so `Livin’ on a Prayer` is on disk as `Livin? on a
 * Prayer`. Thirty characters also truncates: `Hard to Say I’m Sorry (single
 * version)` stops at `(single`.
 *
 * A trailer that cannot represent the tag is not a fallback. It is a second,
 * wrong answer sitting in the same file, and players that prefer ID3v1 show it
 * in place of the correct one. Rewriting the ID3v2 tag while leaving the
 * trailer alone would make that worse: the two copies would then disagree by
 * exactly the edit the user just made.
 *
 * A rewrite first migrates fields absent from ID3v2 (including comments and track
 * numbers), then drops the standard trailer. Enhanced TAG+ trailers are refused until
 * their extra fields can be migrated faithfully. Supported files keep their active
 * metadata in the tag that can hold every script the library actually contains. The cost
 * is honest and worth naming: a player that reads only ID3v1 will show nothing
 * for these files instead of showing mojibake.
 *
 * ### What counts as the trailer
 *
 * Three things, all at the very end of the file:
 *
 * 1. The 128-byte `TAG` block.
 * 2. Any further `TAG` blocks stacked immediately behind it. Taggers that
 *    append a new one without removing the old leave the file with several,
 *    and only the last is ever read. One file in the 504 carries a stacked
 *    pair whose two halves disagree about both the artist and the album — so
 *    removing just the final block would promote a still older lie.
 * 3. ID3v1.2's 227-byte `TAG+` extension, when it sits immediately in front of
 *    the earliest of those blocks. `TAG+` is only ever located by counting
 *    backwards from a `TAG` block, so removing the latter while keeping the
 *    former would strand 227 bytes that no reader can reach again.
 */
public object Id3v1 {

    /** The standard trailer: `TAG` plus 125 bytes of fixed-width fields. */
    public const val TRAILER_SIZE: Int = 128

    /** ID3v1.2's `TAG+` block, which extends the fields in front of the standard one. */
    public const val EXTENDED_SIZE: Int = 227

    /**
     * How many stacked `TAG` blocks [trailerLength] will consume.
     *
     * Stacking is unbounded in principle and two is the most ever observed. The
     * cap exists so [MAX_TRAILER_SIZE] can be a real number: a caller holding
     * only the tail of a file must get the same answer as one holding all of
     * it, and that is only true if there is a limit to how far back this looks.
     */
    public const val MAX_STACKED_TRAILERS: Int = 4

    /**
     * The most trailing bytes [trailerLength] can ever claim, and therefore how
     * much of a file's tail a streaming caller needs to hand it.
     */
    public const val MAX_TRAILER_SIZE: Int = MAX_STACKED_TRAILERS * TRAILER_SIZE + EXTENDED_SIZE

    /**
     * How many trailing bytes of [data] are ID3v1, between 0 and
     * [MAX_TRAILER_SIZE].
     *
     * [data] may be the whole file, or just its last [MAX_TRAILER_SIZE] bytes —
     * a caller streaming a large file does not need to hold it in memory to ask.
     * A shorter tail than that is answered from what it has, so passing too
     * little can only ever under-report, never claim bytes that are not there.
     */
    public fun trailerLength(data: ByteArray): Int {
        var start = data.size
        var blocks = 0
        while (blocks < MAX_STACKED_TRAILERS && startsWith(data, start - TRAILER_SIZE, "TAG")) {
            start -= TRAILER_SIZE
            blocks++
        }
        if (blocks == 0) return 0
        // `TAG+` is 227 bytes and `TAG` is 128, so the loop above can never have
        // mistaken one for the other — their starts do not coincide.
        if (startsWith(data, start - EXTENDED_SIZE, "TAG+")) start -= EXTENDED_SIZE
        return data.size - start
    }

    /**
     * False when the trailer at the end of [tail] cannot be carried over whole before a rewrite
     * drops it: a `TAG+` block, whose longer fields and timing an ID3v2 tag has no place for, or
     * more stacked `TAG` blocks than [trailerLength] removes, which would leave the oldest one
     * standing as the file's ID3v1. True when there is no trailer.
     */
    internal fun canMigrate(tail: ByteArray): Boolean {
        val length = trailerLength(tail)
        if (length == 0) return true
        return !startsWith(tail, tail.size - length, "TAG+") &&
            !startsWith(tail, tail.size - length - TRAILER_SIZE, "TAG")
    }

    /**
     * [frames] plus a frame for every field of the active (last) `TAG` block that [frames] lack;
     * existing ID3v2 frames always win. Null when [canMigrate] is false.
     */
    internal fun migrate(tail: ByteArray, version: Id3Version, frames: List<Id3RawFrame>): List<Id3RawFrame>? {
        if (!canMigrate(tail)) return null
        if (trailerLength(tail) == 0) return frames
        val start = tail.size - TRAILER_SIZE
        fun field(offset: Int, size: Int): String = buildString {
            for (i in start + offset until start + offset + size) {
                if (tail[i] == 0.toByte()) break
                append((tail[i].toInt() and 0xff).toChar())
            }
        }.trimEnd()
        val result = frames.toMutableList()
        fun text(id: String, value: String, aliases: Set<String> = setOf(id)) {
            if (value.isNotEmpty() && frames.none { it.id in aliases }) {
                result += Id3RawFrame(id, byteArrayOf(0, 0), Id3Text.encodeTextFrameBody(value, version))
            }
        }
        text("TIT2", field(3, 30))
        text("TPE1", field(33, 30))
        text("TALB", field(63, 30))
        text(if (version == Id3Version.V2_3) "TYER" else "TDRC", field(93, 4), setOf("TYER", "TDRC"))
        val genre = tail[start + 127].toInt() and 0xff
        if (genre != 255) text("TCON", GenreTags.split("($genre)").joinToString("; ").ifEmpty { "($genre)" })
        val hasTrack = tail[start + 125] == 0.toByte() && tail[start + 126] != 0.toByte()
        if (hasTrack) text("TRCK", (tail[start + 126].toInt() and 0xff).toString())
        val comment = field(97, if (hasTrack) 28 else 30)
        if (comment.isNotEmpty()) {
            // All v1 bytes are Latin-1. A separate description preserves a legacy comment
            // alongside any existing v2 comment without replacing it.
            val description = if (frames.any { it.id == "COMM" }) "ID3v1" else ""
            val body = byteArrayOf(0) + "und".encodeToByteArray() + description.encodeToByteArray() + byteArrayOf(0) +
                ByteArray(comment.length) { comment[it].code.toByte() }
            result += Id3RawFrame("COMM", byteArrayOf(0, 0), body)
        }
        return result
    }

    private fun startsWith(data: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > data.size) return false
        for (i in text.indices) if (data[at + i] != text[i].code.toByte()) return false
        return true
    }
}

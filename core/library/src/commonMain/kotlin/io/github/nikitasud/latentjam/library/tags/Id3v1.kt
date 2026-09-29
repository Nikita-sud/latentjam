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
     * existing ID3v2 frames always win. The comment is the one field [frames] may hold more than
     * once, so it is added unless some `COMM` frame already starts with it. Null when
     * [canMigrate] is false.
     */
    internal fun migrate(tail: ByteArray, version: Id3Version, frames: List<Id3RawFrame>): List<Id3RawFrame>? {
        if (!canMigrate(tail)) return null
        if (trailerLength(tail) == 0) return frames
        val start = tail.size - TRAILER_SIZE
        fun field(offset: Int, size: Int): String {
            val from = start + offset
            var end = from
            while (end < from + size && tail[end] != 0.toByte()) end++
            return decodeField(tail, from, end, filled = end == from + size).trimEnd()
        }
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
        val commentWidth = if (hasTrack) 28 else 30
        val comment = field(97, commentWidth)
        // Taggers that write both tags copy the comment into each, and the v1 copy is the v2 one
        // cut to 28 or 30 characters. Measured on a real library, every trailer comment a rewrite
        // would have carried over was such a copy, so adding it made a second, shorter comment
        // appear beside the one the file already had. [field] has already trimmed the trailing
        // spaces and NULs that pad the fixed-width field.
        //
        // Only a comment that reaches the field's width can have been cut, though. A shorter one
        // that merely begins the v2 comment ("Great" beside "Great album") is a comment of its
        // own, and the trailer holding it is about to be removed: it must be carried over.
        val cut = reachesWidth(tail, start + 97, commentWidth)
        val alreadyHeld = comment.isNotEmpty() && Id3Tags.commentTexts(version, frames).any {
            val held = it.trimEnd(' ', '\u0000')
            held == comment || (cut && held.startsWith(comment))
        }
        if (comment.isNotEmpty() && !alreadyHeld) {
            // A separate description preserves a legacy comment alongside any existing v2 comment
            // without replacing it.
            val description = if (frames.any { it.id == "COMM" }) "ID3v1" else ""
            result += Id3Tags.commentFrame(version, "und", description, comment)
        }
        return result
    }

    /**
     * True when the field `bytes[from, from + width)` holds text up to its last byte — or up to
     * the one before it, since a cut that lands on a space leaves a space that trimming removes.
     * The text ends at the first NUL, as [migrate] reads it, and trailing spaces are padding.
     */
    private fun reachesWidth(bytes: ByteArray, from: Int, width: Int): Boolean {
        var end = from
        while (end < from + width && bytes[end] != 0.toByte()) end++
        while (end > from && bytes[end - 1] == ' '.code.toByte()) end--
        return end - from >= width - 1
    }

    /**
     * One fixed-width field, `bytes[from, to)` with its NUL padding already cut off.
     *
     * The format says ISO-8859-1, but taggers that wrote UTF-8 into the v2 tag's encoding-0
     * frames wrote it here too, so a field is read by the same rule as those frames (see
     * [Id3Text.decode]): strict UTF-8 when its high bytes form it, else Latin-1. Reading
     * "Grüße" stored as UTF-8 as Latin-1 would carry the mojibake "GrÃ¼ÃŸe" into the v2 tag,
     * and since that string does not fit Latin-1 cleanly, it would be written out as Unicode:
     * permanent, and no longer repairable by the encoding-0 reader.
     *
     * One case differs from a v2 frame, which has no width limit. UTF-8 spends two to four
     * bytes per non-ASCII letter, so a field [filled] to its width is often cut inside the
     * last letter, and strict decoding rejects the whole field for that one broken sequence.
     * When dropping just that incomplete sequence leaves well-formed UTF-8 with a high byte,
     * the letters before the cut are kept; the field was UTF-8, and the cut letter is lost
     * either way.
     */
    private fun decodeField(bytes: ByteArray, from: Int, to: Int, filled: Boolean): String {
        val whole = Id3Text.decode(Id3Text.ISO_8859_1, bytes, from, to) ?: ""
        if (!filled) return whole
        // A field ending inside a sequence cannot have decoded as UTF-8 whole, so [whole] is its
        // Latin-1 reading here. Pure ASCII before the cut is no evidence of UTF-8: keep that.
        val cut = incompleteUtf8Tail(bytes, from, to)
        if (cut == to || (from until cut).none { bytes[it].toInt() and 0xFF >= 0x80 }) return whole
        return TextRepair.decodeUtf8Strict(bytes, from, cut) ?: whole
    }

    /**
     * Where a UTF-8 sequence that `bytes[from, to)` ends in the middle of begins, or [to] when
     * the range does not end inside one.
     */
    private fun incompleteUtf8Tail(bytes: ByteArray, from: Int, to: Int): Int {
        var lead = to - 1
        // A sequence is at most four bytes, so at most three continuation bytes precede the cut.
        while (lead >= from && lead > to - 4 && bytes[lead].toInt() and 0xC0 == 0x80) lead--
        if (lead < from) return to
        val b = bytes[lead].toInt() and 0xFF
        val length = when (b) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return to
        }
        return if (to - lead < length) lead else to
    }

    private fun startsWith(data: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > data.size) return false
        for (i in text.indices) if (data[at + i] != text[i].code.toByte()) return false
        return true
    }
}

/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Reads and rewrites the ID3v2 tag at the head of a file.
 *
 * Everything here operates on [ByteArray] and performs no IO, so the format
 * logic is testable on the host JVM and identical on every target.
 *
 * ### Preservation
 *
 * Frames this codec does not understand — album art, ReplayGain, comments,
 * lyrics, anything — are copied to the output byte for byte, in their original
 * order. Only the five managed text frames are ever rewritten. A tag that
 * cannot be fully accounted for is refused rather than partially rewritten,
 * because a partial rewrite is indistinguishable from deleting the frames that
 * were not understood.
 *
 * ### How much of the file to pass
 *
 * - [updateTag] and the top-level [updateId3Tag] take the **whole file** and
 *   return the whole new file. Simplest to use and the natural input for an
 *   atomic write-temp-then-rename.
 * - [buildUpdate] needs only the leading bytes: at least [HEADER_SIZE] bytes to
 *   read the header, and then as many as [tagLength] reports. It hands back the
 *   new tag plus how many leading bytes it replaces, so a caller can stream the
 *   untouched audio instead of holding a whole file in memory. Such a caller
 *   must also ask [droppedTrailerLength] how many bytes to stop short of at the
 *   end, which [updateTag] does on its behalf.
 *
 * Nothing here writes files, so atomicity is the caller's to arrange: write to
 * a temporary file in the same directory, fsync, then rename over the original.
 */
public object Id3Tags {

    /** Frame IDs this writer manages. Everything else is opaque and preserved. */
    private const val FRAME_TITLE = "TIT2"
    private const val FRAME_ARTIST = "TPE1"
    private const val FRAME_ALBUM = "TALB"
    private const val FRAME_GENRE = "TCON"

    /** Unsynchronised lyrics. Read-only here: never rewritten, always preserved. */
    private const val FRAME_LYRICS = "USLT"

    private const val FRAME_USER_TEXT = "TXXX"

    /** Original release time (v2.4) / original release year (v2.3). */
    private const val FRAME_ORIGINAL_V24 = "TDOR"
    private const val FRAME_ORIGINAL_V23 = "TORY"

    /** ID3v2.3's year frame — exactly four characters. */
    private const val FRAME_YEAR_V23 = "TYER"

    /** ID3v2.4's recording-time frame, which supersedes TYER. */
    private const val FRAME_YEAR_V24 = "TDRC"

    /**
     * Slack left at the end of a tag this codec creates or grows, so the next
     * few edits can reuse the same footprint instead of moving the audio.
     */
    private const val PADDING = 1024

    /** Bytes a caller must read before [tagLength] can answer. */
    public const val HEADER_SIZE: Int = Id3Codec.HEADER_SIZE

    /**
     * Total byte length of the tag at the head of [prefix], or 0 when there is
     * none. Null when [prefix] is shorter than a header or the header is
     * unusable. Needs only the first [HEADER_SIZE] bytes.
     */
    public fun tagLength(prefix: ByteArray): Int? = Id3Codec.tagLength(prefix)

    /**
     * How many trailing bytes of ID3v1 a rewrite with [edits] would drop from a
     * file whose tail is [tail] — see [Id3v1] for why it is dropped at all.
     *
     * Always zero for an empty [edits]. Nothing about the metadata changed, so
     * nothing the trailer says became any less true than it already was, and a
     * no-op rewrite stays byte-for-byte identical to its input — which is what
     * makes it safe to use as a probe for "would this file survive an edit".
     *
     * [tail] may be the whole file or just its last [Id3v1.MAX_TRAILER_SIZE]
     * bytes. [updateTag] applies this itself; the streaming path through
     * [buildUpdate], which never sees the end of the file, has to ask.
     */
    public fun droppedTrailerLength(tail: ByteArray, edits: TagEdits): Int =
        if (edits.isEmpty) 0 else Id3v1.trailerLength(tail)

    /**
     * Reads the tag at the head of [prefix], or null when there is none or it
     * is one this codec refuses. Use [refusalOf] to tell those two apart.
     */
    public fun read(prefix: ByteArray): Id3TagInfo? {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return null
        val year = when (tag.version) {
            Id3Version.V2_4 -> text(tag, FRAME_YEAR_V24) ?: text(tag, FRAME_YEAR_V23)
            Id3Version.V2_3 -> text(tag, FRAME_YEAR_V23) ?: text(tag, FRAME_YEAR_V24)
        }
        return Id3TagInfo(
            version = tag.version,
            totalLength = tag.totalLength,
            title = text(tag, FRAME_TITLE),
            artist = text(tag, FRAME_ARTIST),
            album = text(tag, FRAME_ALBUM),
            genre = text(tag, FRAME_GENRE),
            year = year,
            frameIds = tag.frames.map { it.id },
        )
    }

    /**
     * Embedded unsynchronised lyrics (the `USLT` frame): the first non-empty one, or null.
     *
     * Frame layout after the shared prefixes: one encoding byte, a 3-byte language code, an
     * encoding-terminated content descriptor, then the lyrics text itself.
     */
    public fun lyrics(prefix: ByteArray): String? {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return null
        for (frame in tag.frames) {
            if (frame.id != FRAME_LYRICS) continue
            val body = frameTextBody(frame, tag.version) ?: continue
            if (body.size < 5) continue
            val encoding = body[0].toInt() and 0xFF
            var offset = 4
            // UTF-16 descriptors terminate on an aligned 00 00 pair; single-byte ones on 00.
            if (encoding == 1 || encoding == 2) {
                while (offset + 1 < body.size &&
                    !(body[offset] == 0.toByte() && body[offset + 1] == 0.toByte())
                ) {
                    offset += 2
                }
                offset += 2
            } else {
                while (offset < body.size && body[offset] != 0.toByte()) offset++
                offset += 1
            }
            if (offset >= body.size) continue
            val text = Id3Text.decode(encoding, body, offset, body.size)
                ?.trim('\u0000')
                ?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    /**
     * Why [prefix] cannot be rewritten, or null when it can be (including when
     * it simply has no tag yet).
     */
    public fun refusalOf(prefix: ByteArray): Id3Refusal? =
        (Id3Codec.parse(prefix) as? Id3Parse.Refused)?.reason

    /**
     * Builds a replacement tag from the leading bytes of a file.
     *
     * [prefix] must contain at least the whole existing tag — ask [tagLength]
     * how long that is. Returns null when the tag cannot be rewritten safely;
     * [refusalOf] says why.
     *
     * When there is no tag yet, one is created at [newTagVersion] only when the
     * data starts with a valid MPEG-audio or ADTS frame header. An allowlist is
     * intentional: an unknown container is not safe merely because its magic
     * has not been added to a denylist yet.
     */
    public fun buildUpdate(
        prefix: ByteArray,
        edits: TagEdits,
        newTagVersion: Id3Version = Id3Version.V2_3,
    ): Id3TagUpdate? = when (val parsed = Id3Codec.parse(prefix)) {
        is Id3Parse.Refused -> null

        is Id3Parse.Parsed -> {
            val tag = parsed.tag
            if (edits.isEmpty) {
                // Besides being cheaper, this preserves optional extended headers,
                // footers, non-canonical frame-size encodings, and every padding byte.
                Id3TagUpdate(prefix.copyOfRange(0, tag.totalLength), tag.totalLength)
            } else {
                val frames = applyEdits(tag.version, tag.frames, edits)
                val needed = Id3Codec.HEADER_SIZE + Id3Codec.frameBytesLength(frames)
                // Keep the original footprint whenever the new frames still fit:
                // the surplus becomes padding, the audio never moves, and the caller
                // may patch just the head of the file instead of rewriting it.
                val total = if (needed <= tag.totalLength) tag.totalLength else needed + PADDING
                Id3Codec.serialize(tag.version, tag.isExperimental, frames, total)
                    ?.let { Id3TagUpdate(it, tag.totalLength) }
            }
        }

        Id3Parse.Absent -> when {
            !canPrependTag(prefix) -> null
            edits.isEmpty -> Id3TagUpdate(prefix.copyOf(), prefix.size)
            else -> {
                val frames = applyEdits(newTagVersion, emptyList(), edits)
                val total = Id3Codec.HEADER_SIZE + Id3Codec.frameBytesLength(frames) + PADDING
                Id3Codec.serialize(newTagVersion, experimental = false, frames = frames, totalLength = total)
                    ?.let { Id3TagUpdate(it, 0) }
            }
        }
    }

    /**
     * Whole file in, whole file out. Returns null when the tag cannot be
     * rewritten safely, in which case the original must be left untouched.
     *
     * An edit that changes anything also drops the ID3v1 trailer, so the file
     * is left with a single account of itself — [droppedTrailerLength].
     */
    public fun updateTag(
        original: ByteArray,
        edits: TagEdits,
        newTagVersion: Id3Version = Id3Version.V2_3,
    ): ByteArray? {
        val update = buildUpdate(original, edits, newTagVersion) ?: return null
        // A trailer that would reach back into the tag just rebuilt is a false
        // positive — `TAG` happening to fall 128 bytes from the end of a file
        // that is almost entirely tag. Keep every byte rather than cut audio on
        // the strength of a three-byte coincidence.
        val keepUntil = (original.size - droppedTrailerLength(original, edits))
            .takeIf { it >= update.replacedLength } ?: original.size
        val out = ByteArray(update.tag.size + (keepUntil - update.replacedLength))
        update.tag.copyInto(out)
        original.copyInto(
            destination = out,
            destinationOffset = update.tag.size,
            startIndex = update.replacedLength,
            endIndex = keepUntil,
        )
        return out
    }

    /**
     * Applies [edits] to [frames], preserving everything else in place.
     *
     * A replaced frame keeps its original position, so player-visible frame
     * order is stable; a frame that did not exist is appended.
     */
    private fun applyEdits(
        version: Id3Version,
        frames: List<Id3RawFrame>,
        edits: TagEdits,
    ): List<Id3RawFrame> {
        var result = frames
        result = setText(version, result, FRAME_TITLE, edits.title)
        result = setText(version, result, FRAME_ARTIST, edits.artist)
        result = setText(version, result, FRAME_ALBUM, edits.album)
        result = setText(version, result, FRAME_GENRE, edits.genre)

        val yearFrame = if (version == Id3Version.V2_4) FRAME_YEAR_V24 else FRAME_YEAR_V23
        val staleYear = if (version == Id3Version.V2_4) FRAME_YEAR_V23 else FRAME_YEAR_V24
        result = setText(
            version = version,
            frames = result,
            id = yearFrame,
            value = edits.year?.let { normaliseYear(it, version) },
            // Drop the other version's year frame so the file cannot end up
            // carrying two years that disagree.
            alsoRemove = listOf(staleYear),
        )
        return result
    }

    /**
     * Sets, replaces or removes a text frame.
     *
     * A null [value] leaves the frame alone. An empty [value] removes it. Any
     * duplicate frames with the same ID are collapsed into the single new one,
     * and every ID in [alsoRemove] is dropped regardless.
     */
    private fun setText(
        version: Id3Version,
        frames: List<Id3RawFrame>,
        id: String,
        value: String?,
        alsoRemove: List<String> = emptyList(),
    ): List<Id3RawFrame> {
        if (value == null) return frames
        val doomed = alsoRemove + id
        val out = ArrayList<Id3RawFrame>(frames.size + 1)
        var placed = false
        for (frame in frames) {
            if (frame.id !in doomed) {
                out.add(frame)
                continue
            }
            if (frame.id == id && !placed && value.isNotEmpty()) {
                out.add(newTextFrame(version, id, value))
                placed = true
            }
            // Every other match is dropped: duplicates of the frame we just
            // wrote, and the other version's stale year frame.
        }
        if (!placed && value.isNotEmpty()) out.add(newTextFrame(version, id, value))
        return out
    }

    /** New frames get clean flags — no grouping, compression or encryption. */
    private fun newTextFrame(version: Id3Version, id: String, value: String): Id3RawFrame =
        Id3RawFrame(id, byteArrayOf(0, 0), Id3Text.encodeTextFrameBody(value, version))

    /**
     * TYER is defined as exactly four characters, so on ID3v2.3 a fuller
     * timestamp is narrowed to its leading year. TDRC on 2.4 takes it whole.
     */
    private fun normaliseYear(value: String, version: Id3Version): String {
        if (version == Id3Version.V2_4 || value.length <= 4) return value
        val head = value.take(4)
        return if (head.all { it in '0'..'9' }) head else value
    }

    /** Decoded text of the first frame with [id], or null when absent/opaque. */
    /**
     * The individual values of a text frame: ID3v2.4 permits several NUL-separated strings in
     * one frame, and that structure — not a display-string guess — is what multi-credit fields
     * legitimately look like.
     */
    internal fun textValues(prefix: ByteArray, id: String): List<String> {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return emptyList()
        val frame = tag.frames.firstOrNull { it.id == id } ?: return emptyList()
        val body = frameTextBody(frame, tag.version) ?: return emptyList()
        if (body.isEmpty()) return emptyList()
        val raw = Id3Text.decode(body[0].toInt() and 0xFF, body, 1, body.size) ?: return emptyList()
        return raw.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Every `TXXX` user-text frame as (description, value). Frame layout: one encoding byte, an
     * encoding-terminated description, then the value (possibly NUL-joined multi-values).
     */
    internal fun userTexts(prefix: ByteArray): List<Pair<String, String>> {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return emptyList()
        val result = ArrayList<Pair<String, String>>()
        for (frame in tag.frames) {
            if (frame.id != FRAME_USER_TEXT) continue
            val body = frameTextBody(frame, tag.version) ?: continue
            if (body.size < 2) continue
            val encoding = body[0].toInt() and 0xFF
            var offset = 1
            val descriptionStart = offset
            if (encoding == 1 || encoding == 2) {
                while (offset + 1 < body.size &&
                    !(body[offset] == 0.toByte() && body[offset + 1] == 0.toByte())
                ) {
                    offset += 2
                }
                val description =
                    Id3Text.decode(encoding, body, descriptionStart, offset)
                offset += 2
                if (offset > body.size) continue
                val value = Id3Text.decode(encoding, body, offset, body.size)
                if (description != null && value != null) {
                    result.add(description.trim('\u0000').trim() to value.trim('\u0000'))
                }
            } else {
                while (offset < body.size && body[offset] != 0.toByte()) offset++
                val description = Id3Text.decode(encoding, body, descriptionStart, offset)
                offset += 1
                if (offset > body.size) continue
                val value = Id3Text.decode(encoding, body, offset, body.size)
                if (description != null && value != null) {
                    result.add(description.trim() to value.trim('\u0000'))
                }
            }
        }
        return result
    }

    /** The original-release year frame's text, honouring the version's frame id. */
    internal fun originalYearText(prefix: ByteArray): String? {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return null
        return text(tag, FRAME_ORIGINAL_V24) ?: text(tag, FRAME_ORIGINAL_V23)
    }

    /** Artist and genre frame texts for the comment mapping; null-joined values flattened. */
    internal fun artistValues(prefix: ByteArray): List<String> = textValues(prefix, FRAME_ARTIST)

    private fun text(tag: Id3RawTag, id: String): String? {
        val frame = tag.frames.firstOrNull { it.id == id } ?: return null
        val body = frameTextBody(frame, tag.version) ?: return null
        if (body.isEmpty()) return null
        val raw = Id3Text.decode(body[0].toInt() and 0xFF, body, 1, body.size) ?: return null
        // ID3v2.4 allows several NUL-separated values in one text frame. They
        // are flattened for display; the frame is only ever rewritten wholesale.
        return raw.split('\u0000')
            .filter { it.isNotEmpty() }
            .joinToString("; ")
            .ifEmpty { null }
    }

    /**
     * Strips the optional per-frame prefixes so the encoding byte is really the
     * first byte, and undoes frame-level unsynchronisation. Returns null for
     * compressed or encrypted frames, whose contents are not readable here —
     * they are still preserved verbatim on write.
     */
    private fun frameTextBody(frame: Id3RawFrame, version: Id3Version): ByteArray? {
        val format = frame.flags[1].toInt() and 0xFF
        var start = 0
        var unsynchronised = false
        when (version) {
            Id3Version.V2_3 -> {
                if (format and 0x80 != 0 || format and 0x40 != 0) return null // compressed/encrypted
                if (format and 0x20 != 0) start += 1 // grouping identity
            }
            Id3Version.V2_4 -> {
                if (format and 0x08 != 0 || format and 0x04 != 0) return null // compressed/encrypted
                if (format and 0x40 != 0) start += 1 // grouping identity
                if (format and 0x01 != 0) start += 4 // data length indicator
                unsynchronised = format and 0x02 != 0
            }
        }
        if (start > frame.body.size) return null
        return if (unsynchronised) {
            Id3Text.deunsynchronise(frame.body, start, frame.body.size)
        } else {
            frame.body.copyOfRange(start, frame.body.size)
        }
    }

    /**
     * True only for raw stream formats for which a leading ID3v2 tag is defined
     * and routinely supported. A filename or a non-match against a list of known
     * containers is not evidence that prepending bytes is safe.
     */
    private fun canPrependTag(data: ByteArray): Boolean =
        looksLikeMpegAudioFrame(data) || looksLikeAdtsFrame(data)

    /** Validates the fixed fields of an MPEG-1/2/2.5 audio frame header. */
    private fun looksLikeMpegAudioFrame(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val first = data[0].toInt() and 0xFF
        val second = data[1].toInt() and 0xFF
        val third = data[2].toInt() and 0xFF
        if (first != 0xFF || second and 0xE0 != 0xE0) return false

        val version = second ushr 3 and 0x03
        val layer = second ushr 1 and 0x03
        val bitrate = third ushr 4 and 0x0F
        val sampleRate = third ushr 2 and 0x03
        return version != 0x01 &&
            layer != 0x00 &&
            bitrate != 0x00 &&
            bitrate != 0x0F &&
            sampleRate != 0x03
    }

    /** Validates enough of ADTS's seven-byte fixed header to reject accidental sync words. */
    private fun looksLikeAdtsFrame(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val first = data[0].toInt() and 0xFF
        val second = data[1].toInt() and 0xFF
        val third = data[2].toInt() and 0xFF
        val fourth = data[3].toInt() and 0xFF
        val fifth = data[4].toInt() and 0xFF
        val sixth = data[5].toInt() and 0xFF
        if (first != 0xFF || second and 0xF6 != 0xF0) return false
        if (third ushr 2 and 0x0F == 0x0F) return false // reserved sampling-frequency index

        val frameLength = ((fourth and 0x03) shl 11) or (fifth shl 3) or (sixth ushr 5)
        return frameLength >= 7
    }
}

/**
 * Rewrites the ID3v2 tag at the head of [original], returning the new file
 * bytes, or null when the tag cannot be rewritten without risking data loss.
 *
 * An edit that changes anything also removes the ID3v1 trailer, if there is
 * one, so the file is not left holding two disagreeing accounts of itself. See
 * [Id3v1] for the measurements behind that choice.
 *
 * [original] should be the whole file: the result is the whole new file, ready
 * to be written to a temporary file and renamed over the original. For a
 * streaming caller that would rather not hold the file in memory, use
 * [Id3Tags.buildUpdate], which needs only the leading bytes.
 *
 * A null return is a normal outcome, not an error to work around — see
 * [Id3Refusal], and [Id3Tags.refusalOf] for the specific reason. The original
 * file must be left exactly as it is.
 */
public fun updateId3Tag(
    original: ByteArray,
    edits: TagEdits,
    newTagVersion: Id3Version = Id3Version.V2_3,
): ByteArray? = Id3Tags.updateTag(original, edits, newTagVersion)

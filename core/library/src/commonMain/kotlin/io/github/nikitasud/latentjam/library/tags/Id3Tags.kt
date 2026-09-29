/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.TrackNumbers

/**
 * Reads and rewrites the ID3v2 tag at the head of a file.
 *
 * Everything here operates on [ByteArray] and performs no IO, so the format
 * logic is testable on the host JVM and identical on every target.
 *
 * ### Preservation
 *
 * Frames this codec does not manage — ReplayGain, comments, credits, anything
 * an edit does not name — are copied to the output byte for byte, in their
 * original order. Only the fields [TagEdits] exposes are ever rewritten. A tag
 * that cannot be fully accounted for is refused rather than partially
 * rewritten, because a partial rewrite is indistinguishable from deleting the
 * frames that were not understood.
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

    /** Unsynchronised lyrics — the frame [setLyrics] rewrites. */
    private const val FRAME_LYRICS = "USLT"

    private const val FRAME_USER_TEXT = "TXXX"

    /** Original release time (v2.4) / original release year (v2.3). */
    private const val FRAME_ORIGINAL_V24 = "TDOR"
    private const val FRAME_ORIGINAL_V23 = "TORY"

    /** ID3v2.3's year frame — exactly four characters. */
    private const val FRAME_YEAR_V23 = "TYER"

    /** ID3v2.4's recording-time frame, which supersedes TYER. */
    private const val FRAME_YEAR_V24 = "TDRC"

    private const val FRAME_ALBUM_ARTIST = "TPE2"
    private const val FRAME_TRACK = "TRCK"
    private const val FRAME_DISC = "TPOS"
    private const val FRAME_PICTURE = "APIC"
    private const val ARTISTS_DESCRIPTION = "ARTISTS"

    /**
     * Slack left at the end of a tag this codec creates or grows, so the next
     * few edits can reuse the same footprint instead of moving the audio.
     */
    private const val PADDING = TagSpace.SPARE_BYTES

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

    /** Every field the editor shows, plus what verification needs, from one parse. */
    internal class Id3Fields(
        val version: Id3Version,
        val totalLength: Int,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val genre: String?,
        val year: String?,
        /** Raw TRCK text, "3/12" style. */
        val track: String?,
        /** Raw TPOS text. */
        val disc: String?,
        val lyrics: String?,
        val cover: CoverInfo?,
        val otherPictures: Int,
        /** The picture that would become the cover if [cover] were removed. */
        val nextCover: CoverInfo?,
        /** CRC-32 of every APIC's image data (its whole body when unreadable), sorted. */
        val pictures: List<Long>,
        val artists: List<String>,
        /** "ID:crc32(body)" of every frame the editor does not own, in file order (pictures excluded). */
        val unmanaged: List<String>,
    )

    internal fun readFields(prefix: ByteArray): Id3Fields? {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return null
        val version = tag.version
        val frames = tag.frames
        val cover = coverTarget(version, frames)
        val lyricsTarget = frames.withIndex()
            .filter { it.value.id == FRAME_LYRICS }
            .let { list -> list.firstOrNull { lyricsParts(version, it.value)?.text?.isNotBlank() == true } ?: list.firstOrNull() }
        val managedIds = setOf(
            FRAME_TITLE, FRAME_ARTIST, FRAME_ALBUM, FRAME_ALBUM_ARTIST, FRAME_GENRE,
            FRAME_YEAR_V23, FRAME_YEAR_V24, FRAME_TRACK, FRAME_DISC,
        )
        val unmanaged = frames.withIndex()
            .filterNot { (index, frame) ->
                frame.id in managedIds || frame.id == FRAME_PICTURE || index == lyricsTarget?.index ||
                    isArtistsFrame(version, frame)
            }
            // Lyrics frames besides the one the editor shows go when the lyrics are removed.
            .map { (_, frame) ->
                val entry = "${frame.id}:${Crc32.of(frame.body)}"
                if (frame.id == FRAME_LYRICS) TagVerification.LYRICS_ENTRY + entry else entry
            }
        val year = when (version) {
            Id3Version.V2_4 -> textIn(version, frames, FRAME_YEAR_V24) ?: textIn(version, frames, FRAME_YEAR_V23)
            Id3Version.V2_3 -> textIn(version, frames, FRAME_YEAR_V23) ?: textIn(version, frames, FRAME_YEAR_V24)
        }
        return Id3Fields(
            version = version,
            totalLength = tag.totalLength,
            title = textIn(version, frames, FRAME_TITLE),
            artist = textIn(version, frames, FRAME_ARTIST),
            album = textIn(version, frames, FRAME_ALBUM),
            albumArtist = textIn(version, frames, FRAME_ALBUM_ARTIST),
            genre = textIn(version, frames, FRAME_GENRE),
            year = year,
            track = textIn(version, frames, FRAME_TRACK),
            disc = textIn(version, frames, FRAME_DISC),
            lyrics = lyricsTarget?.let { lyricsParts(version, it.value) }?.text?.trim()?.ifEmpty { null },
            cover = cover?.let { CoverInfo.of(it.data, it.mime) },
            otherPictures = frames.count { it.id == FRAME_PICTURE } - (if (cover != null) 1 else 0),
            nextCover = cover
                ?.let { target -> coverTarget(version, frames.filterIndexed { index, _ -> index != target.index }) }
                ?.let { CoverInfo.of(it.data, it.mime) },
            pictures = frames.withIndex()
                .filter { it.value.id == FRAME_PICTURE }
                .map { (index, frame) -> parsePicture(version, index, frame)?.let { Crc32.of(it.data) } ?: Crc32.of(frame.body) }
                .sorted(),
            artists = frames.filter { isArtistsFrame(version, it) }
                .flatMap { frame -> userTextParts(version, frame)?.second?.let(TagFacts::splitArtists).orEmpty() },
            unmanaged = unmanaged,
        )
    }

    /** False when [edits] would leave every frame exactly as it is (a "same values" save). */
    internal fun wouldChange(prefix: ByteArray, edits: TagEdits): Boolean = when (val parsed = Id3Codec.parse(prefix)) {
        is Id3Parse.Refused -> true
        Id3Parse.Absent -> applyEdits(Id3Version.V2_3, emptyList(), edits).isNotEmpty()
        is Id3Parse.Parsed -> {
            val before = parsed.tag.frames
            val after = applyEdits(parsed.tag.version, before, edits)
            before.size != after.size || before.indices.any { i ->
                before[i].id != after[i].id ||
                    !before[i].flags.contentEquals(after[i].flags) ||
                    !before[i].body.contentEquals(after[i].body)
            }
        }
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
            } else if (erasesUnreadableNumber(tag.version, tag.frames, edits)) {
                null
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
        if (edits.artist != null) result = setCreditedArtists(version, result, edits.artist)
        result = setText(version, result, FRAME_ALBUM, edits.album)
        result = setText(version, result, FRAME_ALBUM_ARTIST, edits.albumArtist)
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
        result = setText(
            version,
            result,
            FRAME_TRACK,
            composeNumber(textIn(version, result, FRAME_TRACK), edits.trackNumber, edits.trackTotal),
        )
        result = setText(
            version,
            result,
            FRAME_DISC,
            composeNumber(textIn(version, result, FRAME_DISC), edits.discNumber, edits.discTotal),
        )
        edits.lyrics?.let { result = setLyrics(version, result, it.trim()) }
        result = setCover(version, result, edits.cover)
        return result
    }

    /**
     * The "n/total" text for TRCK/TPOS: null leaves the frame alone, "" removes it. Each half can
     * change alone; without a number there is nothing for a total to belong to, so the frame goes.
     * A pair that already reads as the requested numbers ("03/09" for 3 and 9) is kept as written.
     * Callers refuse first when a total alone would erase an unreadable number (see
     * [erasesUnreadableNumber]).
     */
    internal fun composeNumber(current: String?, number: String?, total: String?): String? {
        if (number == null && total == null) return null
        val currentNumber = TrackNumbers.parse(current)
        val currentTotal = current?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }?.let(TrackNumbers::parse)
        val n = if (number == null) currentNumber else TagNumbers.strict(number)
        val t = if (total == null) currentTotal else TagNumbers.strict(total)
        return when {
            current != null && n != null && n == currentNumber && t == currentTotal -> current
            n == null -> ""
            t == null -> "$n"
            else -> "$n/$t"
        }
    }

    /**
     * True when [edits] change a total but not its number, and the number in the same TRCK/TPOS frame
     * is text no reader takes as one ("A1", or past 999): [composeNumber] would erase it.
     */
    private fun erasesUnreadableNumber(version: Id3Version, frames: List<Id3RawFrame>, edits: TagEdits): Boolean =
        erasesUnreadableNumber(textIn(version, frames, FRAME_TRACK), edits.trackNumber, edits.trackTotal) ||
            erasesUnreadableNumber(textIn(version, frames, FRAME_DISC), edits.discNumber, edits.discTotal)

    private fun erasesUnreadableNumber(current: String?, number: String?, total: String?): Boolean {
        if (number != null || total == null || current == null) return false
        val written = current.substringBefore('/').trim()
        // Blank and zero are no number at all; there is nothing to lose.
        return written.any { it != '0' } && TrackNumbers.parse(current) == null
    }

    /** Why [edits] cannot be applied to the tag in [prefix] although the tag itself is fine; null when they can. */
    internal fun editRefusal(prefix: ByteArray, edits: TagEdits): TagRefusal? {
        val tag = (Id3Codec.parse(prefix) as? Id3Parse.Parsed)?.tag ?: return null
        return TagRefusal.UNREADABLE_NUMBER.takeIf { erasesUnreadableNumber(tag.version, tag.frames, edits) }
    }

    /** Rewrites `TXXX:ARTISTS` from a new display credit — only when the file already has one. */
    private fun setCreditedArtists(version: Id3Version, frames: List<Id3RawFrame>, artist: String): List<Id3RawFrame> {
        val first = frames.indexOfFirst { isArtistsFrame(version, it) }
        if (first < 0) return frames
        val names = CreditedArtists.fromDisplay(artist)
        val out = ArrayList<Id3RawFrame>(frames.size)
        for ((index, frame) in frames.withIndex()) {
            when {
                index == first && names.isNotEmpty() -> out += userTextFrame(
                    version,
                    ARTISTS_DESCRIPTION,
                    names.joinToString(if (version == Id3Version.V2_4) "\u0000" else "; "),
                )
                isArtistsFrame(version, frame) -> Unit
                else -> out += frame
            }
        }
        return out
    }

    private fun isArtistsFrame(version: Id3Version, frame: Id3RawFrame): Boolean =
        frame.id == FRAME_USER_TEXT &&
            userTextParts(version, frame)?.first?.equals(ARTISTS_DESCRIPTION, ignoreCase = true) == true

    /** (description, value) of a TXXX frame, or null when its body is unreadable. */
    private fun userTextParts(version: Id3Version, frame: Id3RawFrame): Pair<String, String>? {
        val body = frameTextBody(frame, version) ?: return null
        if (body.size < 2) return null
        val encoding = body[0].toInt() and 0xFF
        val end = terminatorIndex(body, 1, encoding)
        val description = Id3Text.decode(encoding, body, 1, end)?.trim('\u0000')?.trim() ?: return null
        val valueStart = minOf(body.size, end + terminatorLength(encoding))
        val value = Id3Text.decode(encoding, body, valueStart, body.size)?.trim('\u0000') ?: return null
        return description to value
    }

    private fun userTextFrame(version: Id3Version, description: String, value: String): Id3RawFrame {
        // A NUL in the description would be read back as its own terminator, splitting the frame
        // on the next parse. The value is left alone: TXXX:ARTISTS deliberately NUL-joins its
        // names on ID3v2.4, and that separator must survive.
        val cleanDescription = description.replace("\u0000", "")
        val encoding = chooseEncoding(version, cleanDescription, value)
        val body = byteArrayOf(encoding.toByte()) + encodeText(encoding, cleanDescription) + terminator(encoding) +
            encodeText(encoding, value)
        return Id3RawFrame(FRAME_USER_TEXT, byteArrayOf(0, 0), body)
    }

    private class LyricsParts(val language: String, val descriptor: String, val text: String)

    private fun lyricsParts(version: Id3Version, frame: Id3RawFrame): LyricsParts? {
        val body = frameTextBody(frame, version) ?: return null
        if (body.size < 4) return null
        val encoding = body[0].toInt() and 0xFF
        val language = CharArray(3) { (body[1 + it].toInt() and 0xFF).toChar() }.concatToString()
        val end = terminatorIndex(body, 4, encoding)
        val descriptor = Id3Text.decode(encoding, body, 4, end)?.trim('\u0000') ?: return null
        val textStart = minOf(body.size, end + terminatorLength(encoding))
        val text = Id3Text.decode(encoding, body, textStart, body.size)?.trim('\u0000') ?: return null
        return LyricsParts(language, descriptor, text)
    }

    /**
     * Replaces the lyrics frame [lyrics] reads (the first non-blank USLT, else the first USLT),
     * keeping its language and descriptor. Other-language frames stay. "" removes every USLT: "no
     * lyrics" means none, not another language's frame taking the removed one's place.
     *
     * A target that already holds exactly [lyrics] is left byte for byte as it is — a UTF-16
     * frame is not rewritten as Latin-1 just because the user saved without changing the words.
     */
    private fun setLyrics(version: Id3Version, frames: List<Id3RawFrame>, lyrics: String): List<Id3RawFrame> {
        val candidates = frames.withIndex().filter { it.value.id == FRAME_LYRICS }
        val target = candidates.firstOrNull { lyricsParts(version, it.value)?.text?.isNotBlank() == true }
            ?: candidates.firstOrNull()
        if (lyrics.isEmpty()) return frames.filter { it.id != FRAME_LYRICS }
        val parts = target?.let { lyricsParts(version, it.value) }
        if (parts != null && parts.text.trim() == lyrics) return frames
        val language = parts?.language ?: "XXX"
        // An embedded NUL in either string would be read back as its own terminator, splitting
        // the frame on the next parse.
        val descriptor = (parts?.descriptor ?: "").replace("\u0000", "")
        val cleanLyrics = lyrics.replace("\u0000", "")
        val encoding = chooseEncoding(version, descriptor, cleanLyrics)
        val body = byteArrayOf(encoding.toByte()) + ByteArray(3) { language[it].code.toByte() } +
            encodeText(encoding, descriptor) + terminator(encoding) + encodeText(encoding, cleanLyrics)
        val frame = Id3RawFrame(FRAME_LYRICS, byteArrayOf(0, 0), body)
        return if (target == null) frames + frame else frames.toMutableList().also { it[target.index] = frame }
    }

    private class PictureFrame(
        val index: Int,
        val type: Int,
        val mime: String,
        /** Encoding byte, description and its terminator, exactly as stored. */
        val descriptionBytes: ByteArray,
        val data: ByteArray,
    )

    private fun pictures(version: Id3Version, frames: List<Id3RawFrame>): List<PictureFrame> =
        frames.withIndex()
            .filter { it.value.id == FRAME_PICTURE }
            .mapNotNull { (index, frame) -> parsePicture(version, index, frame) }

    private fun parsePicture(version: Id3Version, index: Int, frame: Id3RawFrame): PictureFrame? {
        val body = frameTextBody(frame, version) ?: return null
        if (body.isEmpty()) return null
        val encoding = body[0].toInt() and 0xFF
        var p = 1
        while (p < body.size && body[p] != 0.toByte()) p++
        if (p + 1 >= body.size) return null
        val mime = CharArray(p - 1) { (body[1 + it].toInt() and 0xFF).toChar() }.concatToString()
        p += 1
        val type = body[p].toInt() and 0xFF
        p += 1
        val descriptionEnd = terminatorIndex(body, p, encoding)
        val dataStart = descriptionEnd + terminatorLength(encoding)
        if (dataStart > body.size) return null
        return PictureFrame(
            index = index,
            type = type,
            mime = mime,
            descriptionBytes = byteArrayOf(encoding.toByte()) + body.copyOfRange(p, dataStart),
            data = body.copyOfRange(dataStart, body.size),
        )
    }

    private fun coverTarget(version: Id3Version, frames: List<Id3RawFrame>): PictureFrame? {
        val pictures = pictures(version, frames)
        return CoverTarget.index(pictures.map { it.type })?.let { pictures[it] }
    }

    private fun setCover(version: Id3Version, frames: List<Id3RawFrame>, edit: CoverEdit): List<Id3RawFrame> {
        if (edit == CoverEdit.Keep) return frames
        val target = coverTarget(version, frames)
        return when (edit) {
            CoverEdit.Keep -> frames
            CoverEdit.Remove ->
                if (target == null) frames else frames.filterIndexed { index, _ -> index != target.index }
            is CoverEdit.Replace -> {
                val description = target?.descriptionBytes ?: byteArrayOf(0, 0)
                val body = byteArrayOf(description[0]) +
                    ByteArray(edit.mime.length) { edit.mime[it].code.toByte() } +
                    byteArrayOf(0, FlacPicture.FRONT_COVER.toByte()) +
                    description.copyOfRange(1, description.size) +
                    edit.bytes
                val frame = Id3RawFrame(FRAME_PICTURE, byteArrayOf(0, 0), body)
                if (target == null) frames + frame else frames.toMutableList().also { it[target.index] = frame }
            }
        }
    }

    /** Latin-1 when every character fits; otherwise UTF-8 on 2.4 and UTF-16 with BOM on 2.3. */
    private fun chooseEncoding(version: Id3Version, vararg texts: String): Int = when {
        texts.all(Id3Text::isLatin1) -> 0
        version == Id3Version.V2_4 -> 3
        else -> 1
    }

    private fun encodeText(encoding: Int, text: String): ByteArray = when (encoding) {
        0 -> ByteArray(text.length) { text[it].code.toByte() }
        3 -> text.encodeToByteArray()
        1 -> {
            val out = ByteArray(2 + text.length * 2)
            out[0] = 0xFF.toByte()
            out[1] = 0xFE.toByte()
            for ((i, c) in text.withIndex()) {
                out[2 + i * 2] = (c.code and 0xFF).toByte()
                out[3 + i * 2] = (c.code shr 8).toByte()
            }
            out
        }
        else -> error("encoding $encoding is never chosen for writing")
    }

    private fun terminator(encoding: Int): ByteArray =
        if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)

    private fun terminatorLength(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    /** Index of the string terminator starting the search at [from]; [body].size when there is none. */
    private fun terminatorIndex(body: ByteArray, from: Int, encoding: Int): Int {
        var i = from
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < body.size && !(body[i] == 0.toByte() && body[i + 1] == 0.toByte())) i += 2
            return minOf(i, body.size)
        }
        while (i < body.size && body[i] != 0.toByte()) i++
        return i
    }

    /** Decoded text of the first frame with [id] in [frames], NUL-separated values joined by "; ". */
    private fun textIn(version: Id3Version, frames: List<Id3RawFrame>, id: String): String? {
        val frame = frames.firstOrNull { it.id == id } ?: return null
        val body = frameTextBody(frame, version) ?: return null
        if (body.isEmpty()) return null
        val raw = Id3Text.decode(body[0].toInt() and 0xFF, body, 1, body.size) ?: return null
        return raw.split('\u0000').filter { it.isNotEmpty() }.joinToString("; ").ifEmpty { null }
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
        // A frame that already says exactly this stays as it is — a UTF-16 "Song" is not rewritten
        // as Latin-1 "Song" just because the user saved without changing it.
        val same = frames.firstOrNull { it.id == id }
            ?.takeIf { value.isNotEmpty() && textIn(version, listOf(it), id) == value }
        val out = ArrayList<Id3RawFrame>(frames.size + 1)
        var placed = false
        for (frame in frames) {
            if (frame.id !in doomed) {
                out.add(frame)
                continue
            }
            if (frame.id == id && !placed && value.isNotEmpty()) {
                out.add(same ?: newTextFrame(version, id, value))
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

    private fun text(tag: Id3RawTag, id: String): String? = textIn(tag.version, tag.frames, id)

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
    internal fun canPrependTag(data: ByteArray): Boolean =
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

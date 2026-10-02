/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** What to do with a file's cover picture — see [TagSnapshot.cover] for which picture that is. */
public sealed interface CoverEdit {
    /** Leave every picture exactly as it is. */
    public data object Keep : CoverEdit

    /** Remove the cover picture; every other picture stays. */
    public data object Remove : CoverEdit

    /** Put [bytes] — a JPEG or PNG, named by [mime] — in place of the cover, or add one. */
    public class Replace(public val bytes: ByteArray, public val mime: String) : CoverEdit
}

/**
 * The fields the tag writers understand, and what to do with each.
 *
 * `null` leaves the field exactly as it is. A non-null value replaces it, and the **empty string
 * removes it** — that distinction is what lets a UI tell "user did not touch this box" apart from
 * "user cleared it". Number fields take plain digits (see [numbersAreValid]).
 */
public data class TagEdits(
    public val title: String? = null,
    public val artist: String? = null,
    public val album: String? = null,
    public val genre: String? = null,
    /**
     * TDRC on ID3v2.4, TYER on ID3v2.3 (narrowed to its leading year there, the frame is defined as
     * four characters), DATE in Vorbis comments, ©day in MP4.
     *
     * An original-release date the file already has (ID3 `TDOR`/`TORY`/`TXXX:ORIGINALYEAR`/
     * `TXXX:ORIGINALDATE`, Vorbis `ORIGINALDATE`/`ORIGINALYEAR`, MP4 `----:com.apple.iTunes:`
     * `ORIGINALYEAR`/`ORIGINALDATE`) that said the same year as the old value follows the new one,
     * unless [originalFollowsYear] is false — see [OriginalDates].
     */
    public val year: String? = null,
    public val albumArtist: String? = null,
    public val trackNumber: String? = null,
    public val trackTotal: String? = null,
    public val discNumber: String? = null,
    public val discTotal: String? = null,
    public val lyrics: String? = null,
    public val cover: CoverEdit = CoverEdit.Keep,
    /**
     * Whether a [year] edit moves the original-release dates that said the same year as the old
     * one ([OriginalDates]). False leaves every original date exactly as it is: a year stamped over
     * files whose years differed (a self-made compilation) is a release year for the collection,
     * and each song's own original year is real information. Changes nothing on its own.
     */
    public val originalFollowsYear: Boolean = true,
) {
    /** True when applying these edits would change nothing. */
    public val isEmpty: Boolean
        get() = title == null && artist == null && album == null && genre == null && year == null &&
            albumArtist == null && trackNumber == null && trackTotal == null &&
            discNumber == null && discTotal == null && lyrics == null && cover == CoverEdit.Keep

    /** Every number field is absent, empty (remove), or plain digits from 1 to 999. */
    public val numbersAreValid: Boolean
        get() = listOf(trackNumber, trackTotal, discNumber, discTotal)
            .all { it == null || it.isEmpty() || TagNumbers.strict(it) != null }

    /**
     * The edits every codec writes and [TagSnapshot.expectedAfter] predicts: NUL removed from every
     * text field (ID3 cannot store one inside a value — it is the separator there), and lyrics
     * without surrounding whitespace, which is how every format stores them.
     */
    internal fun normalized(): TagEdits = copy(
        title = title?.withoutNul(),
        artist = artist?.withoutNul(),
        album = album?.withoutNul(),
        genre = genre?.withoutNul(),
        year = year?.withoutNul(),
        albumArtist = albumArtist?.withoutNul(),
        lyrics = lyrics?.withoutNul()?.trim(),
    )

    private fun String.withoutNul(): String = if (indexOf('\u0000') >= 0) replace("\u0000", "") else this
}

/** The containers the codecs handle. Opus and Vorbis share Ogg but are told apart for display. */
public enum class TagFormat { MP3, FLAC, OPUS, VORBIS, MP4 }

/**
 * Why a file's tags were left alone. Every value is a case where writing risked destroying data
 * the codec cannot faithfully reproduce — refusing is the correct outcome, never "write anyway".
 */
public enum class TagRefusal {
    UNSUPPORTED_FORMAT,
    TRUNCATED,

    ID3_UNSUPPORTED_VERSION,
    ID3_UNSYNCHRONISED,
    ID3_BAD_EXTENDED_HEADER,
    ID3_BAD_FOOTER,
    ID3_UNKNOWN_HEADER_FLAGS,
    ID3_MALFORMED_FRAMES,
    ID3_NOT_TAGGABLE,
    ID3_TAG_TOO_LARGE,

    /** TAG+ fields or an oversized trailer stack cannot yet be migrated without data loss. */
    ID3_UNSUPPORTED_LEGACY_TAG,

    /** An ID3v2 tag in front of FLAC, Ogg or MP4: two disagreeing tags would result. */
    ID3_BEFORE_OTHER_CONTAINER,

    FLAC_STREAMINFO_NOT_FIRST,
    FLAC_MALFORMED_METADATA,
    FLAC_BLOCK_TOO_LARGE,

    OGG_MULTIPLE_STREAMS,
    OGG_BAD_PAGE_CRC,
    OGG_UNKNOWN_CODEC,
    OGG_MALFORMED_PAGES,

    MP4_FRAGMENTED,
    MP4_DRM_PROTECTED,
    MP4_UNKNOWN_OFFSET_BOX,
    MP4_OFFSET_INSIDE_REWRITE,
    MP4_OFFSET_OVERFLOW,
    MP4_MALFORMED_ATOMS,
    MP4_TAGS_TOO_LARGE,

    /** A number field was not plain digits 1–999. */
    INVALID_NUMBER,

    /**
     * Only a total was edited, but the file's number beside it is one no reader takes as a number
     * (a vinyl side such as "A1", or one past 999). ID3 keeps both in one frame, so writing the
     * total would erase that position.
     */
    UNREADABLE_NUMBER,

    /**
     * What follows the ID3v2 tag is not recognisably MPEG audio or ADTS, and the edit would move
     * it: a container behind the tag may hold absolute offsets that moving would break.
     */
    ID3_UNKNOWN_AUDIO,

    /**
     * A write plan broke its own invariants (a transform changed its segment's length). A codec
     * bug, caught before the output is trusted; nothing is written over.
     */
    PLAN_INCONSISTENT,

    /** A new cover was not a readable JPEG or PNG. */
    UNSUPPORTED_IMAGE,
    ;

    public companion object {
        public fun of(reason: Id3Refusal): TagRefusal = when (reason) {
            Id3Refusal.TRUNCATED -> TRUNCATED
            Id3Refusal.UNSUPPORTED_VERSION -> ID3_UNSUPPORTED_VERSION
            Id3Refusal.UNSYNCHRONISED -> ID3_UNSYNCHRONISED
            Id3Refusal.BAD_EXTENDED_HEADER -> ID3_BAD_EXTENDED_HEADER
            Id3Refusal.BAD_FOOTER -> ID3_BAD_FOOTER
            Id3Refusal.UNKNOWN_HEADER_FLAGS -> ID3_UNKNOWN_HEADER_FLAGS
            Id3Refusal.MALFORMED_FRAMES -> ID3_MALFORMED_FRAMES
            Id3Refusal.NOT_TAGGABLE -> ID3_NOT_TAGGABLE
            Id3Refusal.TAG_TOO_LARGE -> ID3_TAG_TOO_LARGE
        }
    }
}

/** The cover as far as verification needs to know it: type, size and a checksum of its bytes. */
public data class CoverInfo(val mime: String, val size: Int, val crc32: Long) {
    public companion object {
        public fun of(bytes: ByteArray, mime: String): CoverInfo = CoverInfo(mime, bytes.size, Crc32.of(bytes))
    }
}

/**
 * One original-release date field as stored. [yearOnly] fields are defined as a four-digit year
 * (ID3 `TORY`, `ORIGINALYEAR` in every format); the others (ID3 `TDOR`, `ORIGINALDATE`) are dates.
 */
public data class OriginalDate(val value: String, val yearOnly: Boolean)

/**
 * Everything the editor shows and the verifier compares, read from the file itself.
 *
 * Text fields are exactly as stored (several Vorbis GENRE values joined with "; "). Numbers are the
 * leading number of their field; 0 and garbage read as null.
 */
public data class TagSnapshot(
    val format: TagFormat,
    /** "ID3v2.3", "ID3v2.4", "none" (an untagged MP3), "FLAC", "Opus", "Vorbis" or "MP4". */
    val version: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val lyrics: String? = null,
    /** The picture a cover edit acts on, or null when the file has none. */
    val cover: CoverInfo? = null,
    /** Pictures other than [cover]; every edit preserves them. */
    val otherPictures: Int = 0,
    /**
     * The picture that becomes the cover when [cover] is removed (the next front cover, or the first
     * "other" picture, or MP4's second `covr` image); null when none would. Not predicted by
     * [expectedAfter]: verifiers compare snapshots with this field cleared.
     */
    val nextCover: CoverInfo? = null,
    /**
     * CRC-32 of every picture's image data (the cover included), sorted. With [cover] this pins every
     * picture byte for byte: [expectedAfter] knows exactly which one an edit replaces or removes.
     */
    val pictures: List<Long> = emptyList(),
    /** The credited-artists list (`ARTISTS`), empty when the file carries none. */
    val artists: List<String> = emptyList(),
    /**
     * Every readable original-release date field, as stored, in file order: ID3 `TDOR`/`TORY` and
     * `TXXX:ORIGINALYEAR`/`TXXX:ORIGINALDATE`, Vorbis `ORIGINALDATE`/`ORIGINALYEAR`, MP4's
     * `----:com.apple.iTunes:ORIGINALYEAR`/`ORIGINALDATE` text values. A year edit moves the ones
     * that said the same year ([OriginalDates]).
     */
    val originalDates: List<OriginalDate> = emptyList(),
    /** Why this file cannot be edited; null when it can. */
    val refusal: TagRefusal? = null,
) {
    val editable: Boolean get() = refusal == null

    /**
     * What a correct write of [edits] reads back as — the verifier's expectation. The edits are
     * normalized here exactly as the codecs normalize them before writing. Number edits must be
     * valid ([TagEdits.numbersAreValid]); every codec refuses the write otherwise.
     */
    public fun expectedAfter(edits: TagEdits): TagSnapshot = expect(edits.normalized())

    private fun expect(edits: TagEdits): TagSnapshot {
        var newTrackTotal = number(trackTotal, edits.trackTotal)
        var newDiscTotal = number(discTotal, edits.discTotal)
        val newTrackNumber = number(trackNumber, edits.trackNumber)
        val newDiscNumber = number(discNumber, edits.discNumber)
        // ID3 stores "3/12" in one frame: without the number there is no frame to hold a total.
        if (format == TagFormat.MP3) {
            if (newTrackNumber == null) newTrackTotal = null
            if (newDiscNumber == null) newDiscTotal = null
        }
        // Removing the cover promotes the next picture; every other picture stays where it was.
        val (newCover, newOtherPictures) = when (val edit = edits.cover) {
            CoverEdit.Keep -> cover to otherPictures
            CoverEdit.Remove ->
                if (cover == null) null to otherPictures
                else nextCover to (otherPictures - (if (nextCover != null) 1 else 0))
            is CoverEdit.Replace -> CoverInfo.of(edit.bytes, edit.mime) to otherPictures
        }
        val newPictures = when (edits.cover) {
            CoverEdit.Keep -> pictures
            CoverEdit.Remove -> if (cover == null) pictures else pictures.minusOne(cover.crc32)
            is CoverEdit.Replace ->
                ((if (cover == null) pictures else pictures.minusOne(cover.crc32)) + newCover!!.crc32).sorted()
        }
        return copy(
            title = text(title, edits.title),
            artist = text(artist, edits.artist),
            album = text(album, edits.album),
            albumArtist = text(albumArtist, edits.albumArtist),
            genre = text(genre, edits.genre),
            year = expectedYear(edits.year),
            trackNumber = newTrackNumber,
            trackTotal = newTrackTotal,
            discNumber = newDiscNumber,
            discTotal = newDiscTotal,
            // Readers return lyrics trimmed in every format, and writers store them trimmed.
            lyrics = text(lyrics, edits.lyrics),
            cover = newCover,
            otherPictures = newOtherPictures,
            // Which picture comes next after a removal is not predicted; after other edits it stays.
            nextCover = if (edits.cover == CoverEdit.Remove) null else nextCover,
            pictures = newPictures,
            artists = when {
                edits.artist == null || artists.isEmpty() -> artists
                else -> CreditedArtists.fromDisplay(edits.artist)
            },
            originalDates = if (!edits.originalFollowsYear) {
                originalDates
            } else {
                originalDates.map { date -> OriginalDates.moved(date, year, expectedYear(edits.year))?.let { date.copy(value = it) } ?: date }
            },
        )
    }

    private fun List<Long>.minusOne(value: Long): List<Long> {
        val index = indexOf(value)
        return if (index < 0) this else filterIndexed { i, _ -> i != index }
    }

    private fun expectedYear(edit: String?): String? = when {
        edit == null -> year
        edit.isEmpty() -> null
        // An untagged MP3 ("none") gets its first tag as ID3v2.3, so it narrows the same way.
        format == TagFormat.MP3 && (version == "ID3v2.3" || version == "none") && edit.length > 4 &&
            edit.take(4).all { it in '0'..'9' } -> edit.take(4)
        else -> edit
    }

    private fun text(current: String?, edit: String?): String? = when {
        edit == null -> current
        edit.isEmpty() -> null
        else -> edit
    }

    private fun number(current: Int?, edit: String?): Int? = when {
        edit == null -> current
        edit.isEmpty() -> null
        else -> TagNumbers.strict(edit)
    }
}

/**
 * A Year edit and the file's original-release date. When the original said the same year as the
 * year being replaced, it was a copy of that year rather than an earlier first release, and it
 * follows the edit: otherwise the library, which prefers the original year, would keep showing
 * the old one. A remaster (the years differ) keeps its original. Fields are only ever rewritten,
 * never added, and clearing Year leaves them alone. An edit with [TagEdits.originalFollowsYear]
 * false moves none.
 */
internal object OriginalDates {
    /** `yyyy`, `yyyy-MM` or `yyyy-MM-dd`, the last optionally with an ID3v2.4 time (`THH[:mm[:ss]]`). */
    private val DATE = Regex("""\d{4}(-(0[1-9]|1[0-2])(-(0[1-9]|[12]\d|3[01])(T([01]\d|2[0-3])(:[0-5]\d(:[0-5]\d)?)?)?)?)?""")

    /**
     * What [original] becomes when the year [current] is replaced by [written] (the value the year
     * field gets); null leaves the field as it is.
     *
     * Years compare by their leading four digits, parsed as [TagFacts] parses them. Nothing moves
     * when [written] is no year at all, or the same year as [current]: restating "1999" must not
     * flatten an original "1999-05-01". A year-only field gets just the four-digit year; a date
     * field gets [written] when it is a well-formed date, and the four-digit year otherwise.
     */
    fun moved(original: OriginalDate, current: String?, written: String?): String? {
        if (written.isNullOrEmpty() || current == null) return null
        val year = TagFacts.parseYear(current) ?: return null
        val wanted = TagFacts.parseYear(written) ?: return null
        if (wanted == year || TagFacts.parseYear(original.value) != year) return null
        return if (!original.yearOnly && DATE.matches(written)) written else wanted.toString()
    }
}

internal object TagNumbers {
    /**
     * Plain digits from 1 to 999; anything else is not a number a tag should hold. The cap is the
     * readers' ([io.github.nikitasud.latentjam.library.TrackNumbers]) and MediaStore's disc×1000+track:
     * a larger number would be written and then read back as none.
     */
    fun strict(value: String): Int? =
        value.takeIf { it.length in 1..3 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it > 0 }
}

internal object CreditedArtists {
    /**
     * The credited-artists list a display credit implies: its ";"-separated names when there are
     * several, otherwise none — a single name needs no list, and "feat."-guessing is never done.
     */
    fun fromDisplay(value: String): List<String> {
        val names = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return if (names.size > 1) names else emptyList()
    }
}

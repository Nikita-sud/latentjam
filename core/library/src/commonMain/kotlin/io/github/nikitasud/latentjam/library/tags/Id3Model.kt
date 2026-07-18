/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * The ID3v2 minor versions this codec will read and write.
 *
 * ID3v2.2 (three-character frame IDs) is deliberately absent: it is a different
 * frame layout, and quietly reinterpreting it as 2.3 destroys tags. See
 * [Id3Refusal.UNSUPPORTED_VERSION].
 */
public enum class Id3Version(public val major: Int) {
    /** ID3v2.3 — plain big-endian frame sizes, ISO-8859-1 or UTF-16 text only. */
    V2_3(3),

    /** ID3v2.4 — syncsafe frame sizes, UTF-8 permitted. */
    V2_4(4),
    ;

    internal companion object {
        fun of(major: Int): Id3Version? = when (major) {
            3 -> V2_3
            4 -> V2_4
            else -> null
        }
    }
}

/**
 * Why a tag was left alone.
 *
 * Every one of these is a case where rewriting the tag risked destroying data
 * the codec cannot faithfully reproduce. Refusing is the correct outcome —
 * the caller should surface "could not edit this file", never write anyway.
 */
public enum class Id3Refusal {
    /** The header promises more bytes than the caller supplied. */
    TRUNCATED,

    /** ID3v2.2 or a major version newer than 2.4. */
    UNSUPPORTED_VERSION,

    /**
     * The whole-tag unsynchronisation flag is set. Implementations disagree on
     * whether frame sizes are pre- or post-unsynchronisation, so any rewrite is
     * a coin flip on other people's files.
     */
    UNSYNCHRONISED,

    /** An extended header whose declared size does not fit the spec or the tag. */
    BAD_EXTENDED_HEADER,

    /** Header flags are set that this codec does not know how to preserve. */
    UNKNOWN_HEADER_FLAGS,

    /**
     * The frame walk lost its place. Continuing would silently drop everything
     * after the bad frame — album art included — so the whole tag is refused.
     */
    MALFORMED_FRAMES,

    /** There is no ID3v2 tag and the data is a container that must not get one. */
    NOT_TAGGABLE,

    /** The resulting tag would exceed the 256 MiB the size field can express. */
    TAG_TOO_LARGE,
}

/**
 * The fields this writer understands, and what to do with each.
 *
 * `null` leaves the corresponding frame exactly as it is. A non-null value
 * replaces it, and the **empty string removes it** — that distinction is what
 * lets a UI tell "user did not touch this box" apart from "user cleared it".
 */
public data class TagEdits(
    /** TIT2. */
    public val title: String? = null,
    /** TPE1. */
    public val artist: String? = null,
    /** TALB. */
    public val album: String? = null,
    /** TCON, written as free text — no ID3v1 numeric genre references. */
    public val genre: String? = null,
    /**
     * TDRC on ID3v2.4, TYER on ID3v2.3. Whichever of the two is not the target
     * version's frame is removed, so a file never carries two disagreeing years.
     *
     * TYER is defined as exactly four characters, so on 2.3 a longer timestamp
     * such as `2001-05-03` is narrowed to its leading `2001`. On 2.4 it is
     * written whole.
     */
    public val year: String? = null,
) {
    /** True when applying these edits would change nothing. */
    public val isEmpty: Boolean
        get() = title == null && artist == null && album == null && genre == null && year == null
}

/** A read-only view of the tag at the head of a file. */
public class Id3TagInfo internal constructor(
    public val version: Id3Version,
    /** Bytes this tag occupies at the head of the file, header and footer included. */
    public val totalLength: Int,
    public val title: String?,
    public val artist: String?,
    public val album: String?,
    public val genre: String?,
    public val year: String?,
    /** Every frame ID present, in file order — duplicates included. */
    public val frameIds: List<String>,
) {
    override fun toString(): String =
        "Id3TagInfo(version=$version, totalLength=$totalLength, frames=$frameIds)"
}

/**
 * A replacement tag, plus how much of the original file it stands in for.
 *
 * The caller writes [tag] followed by everything in the original file from byte
 * offset [replacedLength] onward. When [isSameLength] is true the audio has not
 * moved and only the first [replacedLength] bytes need to be overwritten.
 */
public class Id3TagUpdate internal constructor(
    /** The complete new ID3v2 tag, ready to be written at offset 0. */
    public val tag: ByteArray,
    /** Bytes at the head of the original file that [tag] replaces; 0 when there was no tag. */
    public val replacedLength: Int,
) {
    /**
     * True when the new tag occupies exactly the footprint of the old one, so
     * the audio payload does not move and an in-place patch of the file head is
     * equivalent to a full rewrite.
     */
    public val isSameLength: Boolean
        get() = tag.size == replacedLength
}

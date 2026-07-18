/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * One frame, kept exactly as it was found.
 *
 * [flags] and [body] are opaque byte runs. Compression, encryption, grouping
 * bytes, data-length indicators and frame-level unsynchronisation all live
 * inside [body] and are round-tripped untouched, which is what lets the writer
 * preserve album art and other frames it has no idea how to interpret.
 */
internal class Id3RawFrame(
    val id: String,
    /** Exactly two bytes. */
    val flags: ByteArray,
    val body: ByteArray,
) {
    /** Bytes this frame occupies once serialized. */
    val encodedSize: Int get() = Id3Codec.FRAME_HEADER_SIZE + body.size
}

/** A parsed tag, reduced to the things a rewrite needs to reproduce. */
internal class Id3RawTag(
    val version: Id3Version,
    val headerFlags: Int,
    /** Bytes the tag occupies at the head of the file, header and footer included. */
    val totalLength: Int,
    val frames: List<Id3RawFrame>,
) {
    val isExperimental: Boolean get() = headerFlags and Id3Codec.FLAG_EXPERIMENTAL != 0
}

/** Outcome of looking at the head of a file. */
internal sealed interface Id3Parse {
    /** No ID3v2 tag here. */
    data object Absent : Id3Parse

    /** A tag is present, and this codec will not touch it. */
    class Refused(val reason: Id3Refusal) : Id3Parse

    class Parsed(val tag: Id3RawTag) : Id3Parse
}

/**
 * The ID3v2 binary format: header, frame walk, and serialization.
 *
 * Two rules drive the whole design.
 *
 * **Parsing is all-or-nothing.** A lenient parser that stops at the first
 * damaged frame and keeps what it has looks robust and is in fact a data
 * shredder — everything after the bad frame, typically the album art, is
 * dropped on the next write. If any part of the tag cannot be accounted for,
 * the whole tag is refused and the file is left alone.
 *
 * **Sizes are the hard part.** Tag sizes are syncsafe (7 bits per byte) in every
 * version. Frame sizes are syncsafe in 2.4 but plain big-endian in 2.3, and the
 * two agree only for values below 128 — so any frame longer than 127 bytes is
 * where mixing them up starts corrupting files.
 */
internal object Id3Codec {

    const val HEADER_SIZE: Int = 10
    const val FOOTER_SIZE: Int = 10
    const val FRAME_HEADER_SIZE: Int = 10

    /** Largest value a syncsafe 32-bit field can carry: 28 usable bits. */
    const val MAX_SYNCSAFE: Int = 0x0FFF_FFFF

    const val FLAG_UNSYNCHRONISED: Int = 0x80
    const val FLAG_EXTENDED_HEADER: Int = 0x40
    const val FLAG_EXPERIMENTAL: Int = 0x20
    const val FLAG_FOOTER: Int = 0x10

    /** True when [data] begins with the three-byte ID3v2 identifier. */
    fun looksLikeTag(data: ByteArray): Boolean =
        data.size >= 3 &&
            data[0] == 'I'.code.toByte() &&
            data[1] == 'D'.code.toByte() &&
            data[2] == '3'.code.toByte()

    /**
     * Total tag length read from the 10-byte header alone, so a caller can find
     * out how many bytes of the file it needs before reading the rest.
     *
     * Returns 0 when there is no tag, and null when [data] is too short or the
     * header is unusable.
     */
    fun tagLength(data: ByteArray): Int? {
        if (data.size < 3) return null
        if (!looksLikeTag(data)) return 0
        if (data.size < HEADER_SIZE) return null
        val major = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val size = readSyncsafe(data, 6) ?: return null
        // The footer flag only means anything from 2.4 onwards.
        val footer = if (major >= 4 && flags and FLAG_FOOTER != 0) FOOTER_SIZE else 0
        return HEADER_SIZE + size + footer
    }

    fun parse(data: ByteArray): Id3Parse {
        if (!looksLikeTag(data)) return Id3Parse.Absent
        if (data.size < HEADER_SIZE) return Id3Parse.Refused(Id3Refusal.TRUNCATED)

        val major = data[3].toInt() and 0xFF
        val revision = data[4].toInt() and 0xFF
        // $FF is never a valid version byte; it means we are looking at a false
        // synchronisation rather than a real header.
        if (major == 0xFF || revision == 0xFF) return Id3Parse.Refused(Id3Refusal.UNSUPPORTED_VERSION)
        val version = Id3Version.of(major) ?: return Id3Parse.Refused(Id3Refusal.UNSUPPORTED_VERSION)

        val flags = data[5].toInt() and 0xFF
        val size = readSyncsafe(data, 6) ?: return Id3Parse.Refused(Id3Refusal.TRUNCATED)

        // Whole-tag unsynchronisation: the spec and the installed base disagree
        // about whether frame sizes count pre- or post-unsynchronisation bytes,
        // so there is no rewrite that is safe across real files. Refuse.
        if (flags and FLAG_UNSYNCHRONISED != 0) return Id3Parse.Refused(Id3Refusal.UNSYNCHRONISED)

        val known = when (version) {
            Id3Version.V2_3 -> FLAG_UNSYNCHRONISED or FLAG_EXTENDED_HEADER or FLAG_EXPERIMENTAL
            Id3Version.V2_4 ->
                FLAG_UNSYNCHRONISED or FLAG_EXTENDED_HEADER or FLAG_EXPERIMENTAL or FLAG_FOOTER
        }
        if (flags and known.inv() and 0xFF != 0) return Id3Parse.Refused(Id3Refusal.UNKNOWN_HEADER_FLAGS)

        val bodyEnd = HEADER_SIZE + size
        val footerSize = if (version == Id3Version.V2_4 && flags and FLAG_FOOTER != 0) FOOTER_SIZE else 0
        val totalLength = bodyEnd + footerSize
        if (data.size < totalLength) return Id3Parse.Refused(Id3Refusal.TRUNCATED)

        var pos = HEADER_SIZE
        if (flags and FLAG_EXTENDED_HEADER != 0) {
            pos = skipExtendedHeader(data, pos, bodyEnd, version)
                ?: return Id3Parse.Refused(Id3Refusal.BAD_EXTENDED_HEADER)
        }

        val frames = parseFrames(data, pos, bodyEnd, version)
            ?: return Id3Parse.Refused(Id3Refusal.MALFORMED_FRAMES)
        return Id3Parse.Parsed(Id3RawTag(version, flags, totalLength, frames))
    }

    /** Total bytes [frames] occupy once serialized, excluding the tag header. */
    fun frameBytesLength(frames: List<Id3RawFrame>): Int = frames.sumOf { it.encodedSize }

    /**
     * Writes a complete tag of exactly [totalLength] bytes, padding whatever is
     * left after the frames with zeroes.
     *
     * The extended header and footer are not reproduced: both are optional, and
     * everything they carry (a CRC, a padding size, tag restrictions) either
     * describes data this rewrite has just changed or is recomputable. The
     * frames — the actual user data — are what must survive.
     *
     * Returns null when [totalLength] cannot hold the frames or overflows the
     * size field.
     */
    fun serialize(
        version: Id3Version,
        experimental: Boolean,
        frames: List<Id3RawFrame>,
        totalLength: Int,
    ): ByteArray? {
        val declaredSize = totalLength - HEADER_SIZE
        if (declaredSize < frameBytesLength(frames)) return null
        if (declaredSize > MAX_SYNCSAFE) return null

        val out = ByteArray(totalLength)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = version.major.toByte()
        out[4] = 0
        out[5] = if (experimental) FLAG_EXPERIMENTAL.toByte() else 0
        writeSyncsafe(declaredSize, out, 6)

        var pos = HEADER_SIZE
        for (frame in frames) {
            for (i in 0 until 4) out[pos + i] = frame.id[i].code.toByte()
            when (version) {
                // Always emit the version's correct encoding, even when the
                // frame was read out of a tag that used the wrong one.
                Id3Version.V2_4 -> {
                    if (frame.body.size > MAX_SYNCSAFE) return null
                    writeSyncsafe(frame.body.size, out, pos + 4)
                }
                Id3Version.V2_3 -> writeUInt32BE(frame.body.size, out, pos + 4)
            }
            out[pos + 8] = frame.flags[0]
            out[pos + 9] = frame.flags[1]
            frame.body.copyInto(out, destinationOffset = pos + FRAME_HEADER_SIZE)
            pos += frame.encodedSize
        }
        return out
    }

    /**
     * Returns the offset just past the extended header.
     *
     * The two versions disagree in both directions, which is exactly why this is
     * done explicitly rather than by a shared "read a size" helper: 2.3 uses a
     * plain big-endian size that **excludes** its own four bytes and is defined
     * to be 6 or 10; 2.4 uses a syncsafe size that **includes** them.
     */
    private fun skipExtendedHeader(data: ByteArray, start: Int, end: Int, version: Id3Version): Int? {
        if (end - start < 4) return null
        val next = when (version) {
            Id3Version.V2_3 -> {
                val extSize = readUInt32BE(data, start)
                if (extSize != 6L && extSize != 10L) return null
                start + 4 + extSize.toInt()
            }
            Id3Version.V2_4 -> {
                val extSize = readSyncsafe(data, start) ?: return null
                if (extSize < 6) return null
                start + extSize
            }
        }
        return if (next in start..end) next else null
    }

    private fun parseFrames(
        data: ByteArray,
        start: Int,
        end: Int,
        version: Id3Version,
    ): List<Id3RawFrame>? {
        val frames = ArrayList<Id3RawFrame>()
        var pos = start
        while (pos < end) {
            // Padding begins at the first NUL and must run clean to the end of
            // the tag. Anything else means the frame boundary was lost, and
            // guessing past it is how unknown frames get silently dropped.
            if (end - pos < FRAME_HEADER_SIZE || data[pos] == 0.toByte()) {
                return if (isAllZero(data, pos, end)) frames else null
            }
            val id = readFrameId(data, pos) ?: return null
            val size = resolveFrameSize(data, pos, end, version) ?: return null
            val bodyStart = pos + FRAME_HEADER_SIZE
            frames.add(
                Id3RawFrame(
                    id = id,
                    flags = byteArrayOf(data[pos + 8], data[pos + 9]),
                    body = data.copyOfRange(bodyStart, bodyStart + size),
                ),
            )
            pos = bodyStart + size
        }
        return frames
    }

    /**
     * Reads a frame size, preferring the encoding the version mandates and
     * falling back to the other only when the correct one does not land on a
     * plausible next frame.
     *
     * The fallback is not pedantry: a large family of taggers writes plain
     * big-endian sizes into ID3v2.4 tags. Parsing those strictly means refusing
     * to edit a good share of a real library. The boundary check is what makes
     * guessing safe — a wrong size almost never lands on a valid frame ID or on
     * clean padding, and sizes under 128 are identical either way, so the
     * fallback can only ever engage on frames longer than 127 bytes.
     */
    private fun resolveFrameSize(data: ByteArray, framePos: Int, end: Int, version: Id3Version): Int? {
        val sizeOffset = framePos + 4
        val syncsafe = readSyncsafe(data, sizeOffset)
        val plain = readUInt32BE(data, sizeOffset).let { if (it <= Int.MAX_VALUE) it.toInt() else null }
        val candidates = when (version) {
            Id3Version.V2_4 -> listOf(syncsafe, plain)
            Id3Version.V2_3 -> listOf(plain, syncsafe)
        }
        val maxSize = end - (framePos + FRAME_HEADER_SIZE)
        for (candidate in candidates) {
            val size = candidate ?: continue
            if (size < 0 || size > maxSize) continue
            if (isPlausibleBoundary(data, framePos + FRAME_HEADER_SIZE + size, end)) return size
        }
        return null
    }

    /** True when [next] is the end of the tag, the start of padding, or a frame. */
    private fun isPlausibleBoundary(data: ByteArray, next: Int, end: Int): Boolean = when {
        next > end -> false
        next == end -> true
        end - next < FRAME_HEADER_SIZE -> isAllZero(data, next, end)
        data[next] == 0.toByte() -> isAllZero(data, next, end)
        else -> readFrameId(data, next) != null
    }

    /** Frame IDs are four characters of `A-Z0-9`; anything else is not a frame. */
    private fun readFrameId(data: ByteArray, at: Int): String? {
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val c = (data[at + i].toInt() and 0xFF).toChar()
            if (c !in 'A'..'Z' && c !in '0'..'9') return null
            sb.append(c)
        }
        return sb.toString()
    }

    private fun isAllZero(data: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (data[i] != 0.toByte()) return false
        return true
    }

    /**
     * Reads a syncsafe 32-bit integer: four bytes of seven bits each, with the
     * high bit always clear so the value can never look like an MPEG sync word.
     * Returns null if any high bit is set, which means the field is not syncsafe.
     */
    fun readSyncsafe(data: ByteArray, at: Int): Int? {
        var value = 0
        for (i in 0 until 4) {
            val b = data[at + i].toInt() and 0xFF
            if (b and 0x80 != 0) return null
            value = (value shl 7) or b
        }
        return value
    }

    fun writeSyncsafe(value: Int, out: ByteArray, at: Int) {
        out[at] = ((value shr 21) and 0x7F).toByte()
        out[at + 1] = ((value shr 14) and 0x7F).toByte()
        out[at + 2] = ((value shr 7) and 0x7F).toByte()
        out[at + 3] = (value and 0x7F).toByte()
    }

    /** Reads a plain big-endian 32-bit integer as a Long, so it is never negative. */
    fun readUInt32BE(data: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    fun writeUInt32BE(value: Int, out: ByteArray, at: Int) {
        out[at] = ((value shr 24) and 0xFF).toByte()
        out[at + 1] = ((value shr 16) and 0xFF).toByte()
        out[at + 2] = ((value shr 8) and 0xFF).toByte()
        out[at + 3] = (value and 0xFF).toByte()
    }
}

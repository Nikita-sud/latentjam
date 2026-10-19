/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * The ID3v2 tag at the head of a file, reduced to the text frames the tag facts read, without
 * loading the rest. Library enrichment reads every song once; reading whole tags meant loading
 * each embedded cover (often 1-5 MB, up to the 8 MB cap) and copying it again per parse, which
 * a 48 MB heap cannot afford next to a 10,000-song library.
 *
 * Frames are walked with positional reads under exactly [Id3Codec.parse]'s rules (header checks,
 * extended header, footer, padding, the plain/syncsafe size fallback with its look-ahead), so a
 * tag this accepts is one that parse accepts, and one it refuses parse refuses too. The kept
 * frames are re-serialized unchanged, in file order, into a small tag the existing readers parse.
 */
internal object Id3FactsPrefix {
    /** Every frame [GenreTags]'s ID3 mapping reads; nothing else is loaded. */
    private val KEPT = setOf("TALB", "TCON", "TPE1", "TPE2", "TDOR", "TORY", "TDRC", "TYER", "TLAN", "TXXX")

    private const val HEADER = Id3Codec.HEADER_SIZE
    private const val FRAME_HEADER = Id3Codec.FRAME_HEADER_SIZE
    private const val ZERO_CHUNK = 64 * 1024

    /**
     * The reduced tag, or null when there is no tag the parser would accept (refused, truncated,
     * longer than [maxTagLength]): the same cases in which reading it whole yields nothing.
     */
    fun read(source: RandomAccessSource, maxTagLength: Int): ByteArray? {
        val header = source.read(0, HEADER) ?: return null
        if (!Id3Codec.looksLikeTag(header)) return null
        val tagLength = Id3Codec.tagLength(header) ?: return null
        if (tagLength <= HEADER || tagLength > maxTagLength || source.length < tagLength) return null
        val major = header[3].toInt() and 0xFF
        val revision = header[4].toInt() and 0xFF
        if (major == 0xFF || revision == 0xFF) return null
        val version = Id3Version.of(major) ?: return null
        val flags = header[5].toInt() and 0xFF
        if (flags and Id3Codec.FLAG_UNSYNCHRONISED != 0) return null
        val known = when (version) {
            Id3Version.V2_3 -> Id3Codec.FLAG_UNSYNCHRONISED or Id3Codec.FLAG_EXTENDED_HEADER or Id3Codec.FLAG_EXPERIMENTAL
            Id3Version.V2_4 -> Id3Codec.FLAG_UNSYNCHRONISED or Id3Codec.FLAG_EXTENDED_HEADER or
                Id3Codec.FLAG_EXPERIMENTAL or Id3Codec.FLAG_FOOTER
        }
        if (flags and known.inv() and 0xFF != 0) return null
        val size = Id3Codec.readSyncsafe(header, 6) ?: return null
        val end = HEADER + size
        if (version == Id3Version.V2_4 && flags and Id3Codec.FLAG_FOOTER != 0) {
            val footer = source.read(end.toLong(), Id3Codec.FOOTER_SIZE) ?: return null
            if (footer[0] != '3'.code.toByte() || footer[1] != 'D'.code.toByte() || footer[2] != 'I'.code.toByte()) return null
            for (i in 3 until Id3Codec.FOOTER_SIZE) if (footer[i] != header[i]) return null
        }
        var pos = HEADER
        if (flags and Id3Codec.FLAG_EXTENDED_HEADER != 0) {
            if (end - pos < 4) return null
            val field = source.read(pos.toLong(), 4) ?: return null
            val next = when (version) {
                Id3Version.V2_3 -> {
                    val extSize = Id3Codec.readUInt32BE(field, 0)
                    if (extSize != 6L && extSize != 10L) return null
                    pos + 4 + extSize.toInt()
                }
                Id3Version.V2_4 -> {
                    val extSize = Id3Codec.readSyncsafe(field, 0) ?: return null
                    if (extSize < 6) return null
                    pos + extSize
                }
            }
            if (next !in pos..end) return null
            pos = next
        }
        val kept = ByteArraySink()
        while (pos < end) {
            if (end - pos < FRAME_HEADER) {
                if (!allZero(source, pos, end)) return null
                break
            }
            val frame = source.read(pos.toLong(), FRAME_HEADER) ?: return null
            if (frame[0] == 0.toByte()) {
                if (!allZero(source, pos, end)) return null
                break
            }
            val id = frameId(frame, 0) ?: return null
            val frameSize = resolveFrameSize(source, frame, pos, end, version) ?: return null
            if (id in KEPT) {
                val body = source.read((pos + FRAME_HEADER).toLong(), frameSize) ?: return null
                val out = ByteArray(FRAME_HEADER)
                frame.copyInto(out, 0, 0, 4)
                when (version) {
                    Id3Version.V2_4 -> Id3Codec.writeSyncsafe(frameSize, out, 4)
                    Id3Version.V2_3 -> Id3Codec.writeUInt32BE(frameSize, out, 4)
                }
                out[8] = frame[8]
                out[9] = frame[9]
                kept.write(out)
                kept.write(body)
            }
            pos += FRAME_HEADER + frameSize
        }
        val reduced = ByteArray(HEADER + kept.size)
        header.copyInto(reduced, 0, 0, 6)
        // No extended header and no footer: the reduced tag is header and frames alone.
        reduced[5] = (flags and (Id3Codec.FLAG_EXTENDED_HEADER or Id3Codec.FLAG_FOOTER).inv()).toByte()
        Id3Codec.writeSyncsafe(kept.size, reduced, 6)
        kept.toByteArray().copyInto(reduced, HEADER)
        return reduced
    }

    /** [Id3Codec]'s size resolution: the version's encoding first, the other only if it lands. */
    private fun resolveFrameSize(
        source: RandomAccessSource,
        frame: ByteArray,
        framePos: Int,
        end: Int,
        version: Id3Version,
    ): Int? {
        val syncsafe = Id3Codec.readSyncsafe(frame, 4)
        val plain = Id3Codec.readUInt32BE(frame, 4).let { if (it <= Int.MAX_VALUE) it.toInt() else null }
        val candidates = when (version) {
            Id3Version.V2_4 -> listOf(syncsafe, plain)
            Id3Version.V2_3 -> listOf(plain, syncsafe)
        }
        val maxSize = end - (framePos + FRAME_HEADER)
        for (candidate in candidates) {
            val size = candidate ?: continue
            if (size < 0 || size > maxSize) continue
            if (isPlausibleBoundary(source, framePos + FRAME_HEADER + size, end)) return size
        }
        return null
    }

    private fun isPlausibleBoundary(source: RandomAccessSource, next: Int, end: Int): Boolean {
        if (next > end) return false
        if (next == end) return true
        if (end - next < FRAME_HEADER) return allZero(source, next, end)
        val head = source.read(next.toLong(), FRAME_HEADER) ?: return false
        if (head[0] == 0.toByte()) return allZero(source, next, end)
        return frameId(head, 0) != null
    }

    private fun frameId(data: ByteArray, at: Int): String? {
        val chars = CharArray(4)
        for (i in 0 until 4) {
            val c = (data[at + i].toInt() and 0xFF).toChar()
            if (c !in 'A'..'Z' && c !in '0'..'9') return null
            chars[i] = c
        }
        return chars.concatToString()
    }

    /** Padding check over `[from, to)`, a bounded chunk at a time. */
    private fun allZero(source: RandomAccessSource, from: Int, to: Int): Boolean {
        var at = from
        while (at < to) {
            val count = minOf(ZERO_CHUNK, to - at)
            val chunk = source.read(at.toLong(), count) ?: return false
            for (byte in chunk) if (byte != 0.toByte()) return false
            at += count
        }
        return true
    }
}

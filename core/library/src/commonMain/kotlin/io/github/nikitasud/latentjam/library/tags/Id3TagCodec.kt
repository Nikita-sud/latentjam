/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.TrackNumbers

/** MP3 (and ADTS AAC): an ID3v2 tag at the head of a raw audio stream. */
internal object Id3TagCodec : TagCodec {

    /** Bytes read past the tag, enough to see which container it fronts. */
    private const val AFTER_TAG = 16

    /** How far past the tag the first audio frame may start; zero padding past a tag's end is common. */
    private const val AUDIO_WINDOW = 4096

    /** An ADTS header's fixed part, the longest header [Id3Tags.canPrependTag] looks at. */
    private const val FRAME_HEADER = 7

    private class Head(val prefix: ByteArray, val tagLength: Int)

    override fun recognizes(head: ByteArray): Boolean =
        Id3Codec.looksLikeTag(head) || Id3Tags.canPrependTag(head)

    private fun head(source: RandomAccessSource): Head? {
        val header = source.read(0, minOf(source.length, Id3Tags.HEADER_SIZE.toLong()).toInt()) ?: return null
        val tagLength = Id3Tags.tagLength(header) ?: return Head(header, -1)
        val wanted = minOf(source.length, tagLength.toLong() + AFTER_TAG).toInt()
        return Head(source.read(0, wanted) ?: return null, tagLength)
    }

    private fun startsWith(data: ByteArray, at: Int, text: String): Boolean =
        at >= 0 && at + text.length <= data.size && text.indices.all { data[at + it] == text[it].code.toByte() }

    private fun refusal(head: Head): TagRefusal? {
        if (head.tagLength < 0) {
            return Id3Tags.refusalOf(head.prefix)?.let { TagRefusal.of(it) } ?: TagRefusal.TRUNCATED
        }
        val t = head.tagLength
        if (t > 0 && (startsWith(head.prefix, t, "fLaC") || startsWith(head.prefix, t, "OggS") ||
                startsWith(head.prefix, t + 4, "ftyp"))
        ) {
            return TagRefusal.ID3_BEFORE_OTHER_CONTAINER
        }
        Id3Tags.refusalOf(head.prefix)?.let { return TagRefusal.of(it) }
        if (t == 0 && !Id3Tags.canPrependTag(head.prefix)) return TagRefusal.ID3_NOT_TAGGABLE
        return null
    }

    override fun read(source: RandomAccessSource): TagSnapshot {
        val head = head(source) ?: return TagSnapshot(TagFormat.MP3, "none", refusal = TagRefusal.TRUNCATED)
        refusal(head)?.let { return TagSnapshot(TagFormat.MP3, "ID3", refusal = it) }
        if (head.tagLength == 0) return TagSnapshot(TagFormat.MP3, "none")
        val fields = Id3Tags.readFields(head.prefix)
            ?: return TagSnapshot(TagFormat.MP3, "ID3", refusal = TagRefusal.ID3_MALFORMED_FRAMES)
        return TagSnapshot(
            format = TagFormat.MP3,
            version = if (fields.version == Id3Version.V2_4) "ID3v2.4" else "ID3v2.3",
            title = fields.title,
            artist = fields.artist,
            album = fields.album,
            albumArtist = fields.albumArtist,
            genre = fields.genre,
            year = fields.year,
            trackNumber = TrackNumbers.parse(fields.track),
            trackTotal = total(fields.track),
            discNumber = TrackNumbers.parse(fields.disc),
            discTotal = total(fields.disc),
            lyrics = fields.lyrics,
            cover = fields.cover,
            otherPictures = fields.otherPictures,
            nextCover = fields.nextCover,
            pictures = fields.pictures,
            artists = fields.artists,
        )
    }

    private fun total(pair: String?): Int? =
        pair?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }?.let(TrackNumbers::parse)

    override fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan {
        val normalized = edits.normalized()
        if (normalized.isEmpty) return WritePlan.NoChange
        EditChecks.refusal(normalized)?.let { return WritePlan.Refused(it) }
        val head = head(source) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
        refusal(head)?.let { return WritePlan.Refused(it) }
        val prefix = if (head.tagLength > 0) head.prefix.copyOf(head.tagLength) else head.prefix
        if (!Id3Tags.wouldChange(prefix, normalized)) return WritePlan.NoChange
        Id3Tags.editRefusal(prefix, normalized)?.let { return WritePlan.Refused(it) }
        val update = Id3Tags.buildUpdate(prefix, normalized)
            ?: return WritePlan.Refused(Id3Tags.refusalOf(prefix)?.let { TagRefusal.of(it) } ?: TagRefusal.ID3_TAG_TOO_LARGE)

        val length = source.length
        val tailSize = minOf(length, Id3v1.MAX_TRAILER_SIZE.toLong()).toInt()
        val tail = source.read(length - tailSize, tailSize) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
        var trailer = Id3Tags.droppedTrailerLength(tail, normalized).toLong()
        // "TAG" 128 bytes from the end of a file that is almost all tag is a coincidence, not a trailer.
        if (length - trailer < update.replacedLength) trailer = 0
        val audioLength = length - trailer - update.replacedLength
        return if (update.isSameLength) {
            val old = source.read(0, update.replacedLength) ?: return WritePlan.Refused(TagRefusal.TRUNCATED)
            ByteDiff.patchOrNoChange(0, old, update.tag, update.tag.size + audioLength, length)
        } else {
            // Moving what follows the tag is only safe for a raw MPEG or ADTS stream, which holds no
            // absolute offsets; a container behind the tag (QuickTime, say) might.
            if (!audioFollows(source, update.replacedLength.toLong())) return WritePlan.Refused(TagRefusal.ID3_UNKNOWN_AUDIO)
            WritePlan.StreamingRewrite(
                listOf(
                    OutputSegment.Bytes(update.tag),
                    OutputSegment.Copy(update.replacedLength.toLong(), audioLength),
                ),
            )
        }
    }

    /** An MPEG audio or ADTS frame header at the first non-zero byte within [AUDIO_WINDOW] of [offset]. */
    private fun audioFollows(source: RandomAccessSource, offset: Long): Boolean {
        val count = minOf(source.length - offset, (AUDIO_WINDOW + FRAME_HEADER).toLong()).toInt()
        if (count <= 0) return false
        val window = source.read(offset, count) ?: return false
        val first = window.indexOfFirst { it != 0.toByte() }
        return first in 0 until AUDIO_WINDOW && Id3Tags.canPrependTag(window.copyOfRange(first, window.size))
    }

    override fun audioDigest(source: RandomAccessSource): Long? {
        val head = head(source) ?: return null
        val start = maxOf(head.tagLength, 0).toLong()
        val length = source.length
        val tailSize = minOf(length, Id3v1.MAX_TRAILER_SIZE.toLong()).toInt()
        val tail = source.read(length - tailSize, tailSize) ?: return null
        val end = (length - Id3v1.trailerLength(tail)).takeIf { it >= start } ?: length
        return Digests.crc32(source, start, end - start)
    }

    override fun inventory(source: RandomAccessSource): List<String> {
        val head = head(source) ?: return emptyList()
        if (head.tagLength <= 0) return emptyList()
        return Id3Tags.readFields(head.prefix)?.unmanaged.orEmpty()
    }
}

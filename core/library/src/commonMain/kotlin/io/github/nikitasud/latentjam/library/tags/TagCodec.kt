/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * One container format's tags: read them, and plan the smallest safe write of an edit.
 *
 * A codec never writes. It reads through [RandomAccessSource] and returns a [WritePlan] that a
 * platform executes — which is what lets the same code run, and be tested, everywhere.
 */
public interface TagCodec {
    /** True when [head] — the first [HEAD_BYTES] bytes, or fewer for a tiny file — is this container. */
    public fun recognizes(head: ByteArray): Boolean

    /** What the file says; a snapshot with [TagSnapshot.refusal] set when it cannot be edited. */
    public fun read(source: RandomAccessSource): TagSnapshot

    public fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan

    /** CRC-32 over exactly the bytes no edit may change (the audio); null when unreadable. */
    public fun audioDigest(source: RandomAccessSource): Long?

    /** An identity for everything the editor does not own, in file order; equal before and after any edit. */
    public fun inventory(source: RandomAccessSource): List<String>

    public companion object {
        public const val HEAD_BYTES: Int = 64
    }
}

/** Checks that do not depend on the format, run before any format work. */
internal object EditChecks {
    fun refusal(edits: TagEdits): TagRefusal? {
        val cover = edits.cover
        return when {
            !edits.numbersAreValid -> TagRefusal.INVALID_NUMBER
            cover is CoverEdit.Replace && CoverTarget.replacement(cover) == null -> TagRefusal.UNSUPPORTED_IMAGE
            else -> null
        }
    }
}

internal object Digests {
    /** CRC-32 of [length] bytes at [offset], read in chunks; null when the range is not readable. */
    fun crc32(source: RandomAccessSource, offset: Long, length: Long): Long? {
        if (offset < 0 || length < 0 || offset > source.length || length > source.length - offset) return null
        val crc = Crc32()
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = minOf(WritePlans.COPY_CHUNK.toLong(), end - position).toInt()
            val chunk = source.read(position, count) ?: return null
            crc.update(chunk)
            position += count
        }
        return crc.value
    }
}

/** Picks a file's codec from its first bytes. The single entry point for everything outside `tags`. */
public object TagCodecs {
    // ID3 last: an ID3v2 tag in front of FLAC, Ogg or MP4 must reach the ID3 codec, which refuses it.
    private val ALL: List<TagCodec> = listOf(FlacTagCodec, OggTagCodec, Mp4TagCodec, Id3TagCodec)

    public fun forSource(source: RandomAccessSource): TagCodec? {
        if (source.length <= 0) return null
        val head = source.read(0, minOf(source.length, TagCodec.HEAD_BYTES.toLong()).toInt()) ?: return null
        return ALL.firstOrNull { it.recognizes(head) }
    }

    public fun read(source: RandomAccessSource): TagSnapshot? = forSource(source)?.read(source)

    public fun plan(source: RandomAccessSource, edits: TagEdits): WritePlan =
        forSource(source)?.plan(source, edits) ?: WritePlan.Refused(TagRefusal.UNSUPPORTED_FORMAT)
}

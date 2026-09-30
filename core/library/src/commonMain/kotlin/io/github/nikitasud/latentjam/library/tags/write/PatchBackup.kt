/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteArraySink
import io.github.nikitasud.latentjam.library.tags.ByteWrite
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.WritePlan
import io.github.nikitasud.latentjam.library.tags.WritePlans

/**
 * Everything needed to undo an in-place patch (§5.2), and to tell "our half-finished patch" from
 * "a file someone else has since changed" — a recovery must put back the first and leave the second.
 */
internal class PatchBackup(
    val originalLength: Long,
    val newLength: Long,
    /** The original bytes each write covers, clipped to [originalLength]. */
    val ranges: List<ByteWrite>,
    /** What the patch writes. */
    val writes: List<ByteWrite>,
    /** The original bytes from [newLength] to [originalLength] when the patch shrinks the file. */
    val tail: ByteWrite?,
    val restCrc: Long,
) {
    fun restore(target: TargetFile) {
        for (range in ranges) target.write(range.offset, range.bytes)
        tail?.let { target.write(it.offset, it.bytes) }
        target.setLength(originalLength)
        target.force()
    }

    fun matches(target: RandomAccessSource): Boolean =
        target.length == originalLength &&
            ranges.all { target.read(it.offset, it.bytes.size)?.contentEquals(it.bytes) == true } &&
            (tail == null || target.read(tail.offset, tail.bytes.size)?.contentEquals(tail.bytes) == true) &&
            restCrc(target, writes, minOf(originalLength, newLength)) == restCrc

    fun explains(target: RandomAccessSource): Boolean {
        val low = minOf(originalLength, newLength)
        if (target.length !in low..maxOf(originalLength, newLength)) return false
        for (write in writes) {
            val old = ranges.firstOrNull { it.offset == write.offset }?.bytes ?: ByteArray(0)
            val count = minOf(write.bytes.size.toLong(), maxOf(0L, target.length - write.offset)).toInt()
            // A write starting past the current end has not landed at all (an untouched original, or a
            // growth whose length change was lost): nothing to compare, and read() refuses such offsets.
            if (count == 0) continue
            val now = target.read(write.offset, count) ?: return false
            for (i in 0 until count) {
                // Past the original end a torn extension reads as zeros.
                val before = if (i < old.size) old[i] else 0
                if (now[i] != before && now[i] != write.bytes[i]) return false
            }
        }
        tail?.let { t ->
            val count = minOf(t.bytes.size.toLong(), maxOf(0L, target.length - t.offset)).toInt()
            if (count == 0) return@let
            val now = target.read(t.offset, count) ?: return false
            for (i in 0 until count) if (now[i] != t.bytes[i] && now[i] != 0.toByte()) return false
        }
        return restCrc(target, writes, low) == restCrc
    }

    fun encode(): ByteArray {
        val out = Writer()
        out.bytes(MAGIC)
        out.long(originalLength)
        out.long(newLength)
        out.long(restCrc)
        out.ranges(ranges)
        out.ranges(writes)
        out.int(if (tail == null) 0 else 1)
        tail?.let { out.range(it) }
        val body = out.toByteArray()
        return body + Writer().apply { long(Crc32.of(body)) }.toByteArray()
    }

    companion object {
        private val MAGIC = "LJPB1".encodeToByteArray()
        private const val HEAD_WINDOW = 256L * 1024
        private const val TAIL_WINDOW = 64L * 1024

        fun capture(target: RandomAccessSource, plan: WritePlan.InPlacePatch): PatchBackup? {
            val length = target.length
            val ranges = plan.writes.mapNotNull { write ->
                val end = minOf(write.offset + write.bytes.size, length)
                if (write.offset >= end) null
                else ByteWrite(write.offset, target.read(write.offset, (end - write.offset).toInt()) ?: return null)
            }
            // A tail that cannot be held in one array is a file this backup does not cover.
            if (length - plan.newLength > Int.MAX_VALUE) return null
            val tail = if (plan.newLength < length) {
                ByteWrite(plan.newLength, target.read(plan.newLength, (length - plan.newLength).toInt()) ?: return null)
            } else null
            val rest = restCrc(target, plan.writes, minOf(length, plan.newLength)) ?: return null
            return PatchBackup(length, plan.newLength, ranges, plan.writes, tail, rest)
        }

        /**
         * CRC of the bytes below [limit] that no write covers, within the first [HEAD_WINDOW] and
         * the last [TAIL_WINDOW] bytes. Every format keeps its tags there (ID3, FLAC metadata and Ogg
         * headers at the head; an MP4 `moov` at either end), so another tagger's edit shows up —
         * without an in-place save ever reading the audio in between. A change in the middle of a
         * larger file is not seen, by design: that is the audio, which this check never reads.
         */
        private fun restCrc(source: RandomAccessSource, writes: List<ByteWrite>, limit: Long): Long? {
            val headEnd = minOf(limit, HEAD_WINDOW)
            val windows = listOf(0L until headEnd, maxOf(headEnd, limit - TAIL_WINDOW) until limit)
            val covered = writes.map { it.offset until it.offset + it.bytes.size }
            val crc = Crc32()
            for (window in windows) {
                var position = window.first
                while (position <= window.last) {
                    val block = covered.firstOrNull { position in it }
                    if (block != null) {
                        position = block.last + 1
                        continue
                    }
                    val nextCovered = covered.filter { it.first > position }.minOfOrNull { it.first } ?: Long.MAX_VALUE
                    val end = minOf(window.last + 1, nextCovered)
                    val count = minOf(WritePlans.COPY_CHUNK.toLong(), end - position).toInt()
                    crc.update(source.read(position, count) ?: return null)
                    position += count
                }
            }
            return crc.value
        }

        fun decode(bytes: ByteArray): PatchBackup? = try {
            check(bytes.size > 8)
            val body = bytes.copyOf(bytes.size - 8)
            check(Reader(bytes.copyOfRange(body.size, bytes.size)).long() == Crc32.of(body))
            val r = Reader(body)
            check(r.bytes(MAGIC.size).contentEquals(MAGIC))
            val originalLength = r.long()
            val newLength = r.long()
            val restCrc = r.long()
            val ranges = r.ranges()
            val writes = r.ranges()
            val tail = when (r.int()) {
                0 -> null
                1 -> r.range()
                else -> error("bad tail flag")
            }
            check(r.atEnd)
            PatchBackup(originalLength, newLength, ranges, writes, tail, restCrc)
        } catch (_: Exception) {
            null
        }
    }

    private class Writer {
        private val sink = ByteArraySink()
        fun bytes(value: ByteArray) = sink.write(value)
        fun int(value: Int) = sink.write(ByteArray(4) { (value ushr (24 - 8 * it)).toByte() })
        fun long(value: Long) = sink.write(ByteArray(8) { (value ushr (56 - 8 * it)).toByte() })
        fun range(range: ByteWrite) {
            long(range.offset)
            int(range.bytes.size)
            bytes(range.bytes)
        }
        fun ranges(list: List<ByteWrite>) {
            int(list.size)
            list.forEach(::range)
        }
        fun toByteArray() = sink.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var at = 0
        val atEnd: Boolean get() = at == data.size
        fun bytes(n: Int): ByteArray {
            check(n >= 0 && n <= data.size - at)
            return data.copyOfRange(at, at + n).also { at += n }
        }
        fun int(): Int = bytes(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        fun long(): Long = bytes(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        fun range(): ByteWrite {
            val offset = long()
            return ByteWrite(offset, bytes(int()))
        }
        fun ranges(): List<ByteWrite> = List(int()) { range() }
    }
}

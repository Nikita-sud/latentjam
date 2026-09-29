/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** Executes [WritePlan]s against byte sources and sinks; platforms reuse [stream] for staging. */
public object WritePlans {
    /** Copy granularity: large enough to amortise calls, small enough to never hold a file. */
    public const val COPY_CHUNK: Int = 1 shl 20

    /** Writes the output of [plan] to [sink], reading from [source]. */
    public fun stream(source: RandomAccessSource, plan: WritePlan.StreamingRewrite, sink: ByteSink) {
        for (segment in plan.segments) {
            when (segment) {
                is OutputSegment.Bytes -> sink.write(segment.bytes)
                is OutputSegment.Copy -> copy(source, segment.sourceOffset, segment.length) { sink.write(it) }
                is OutputSegment.Transformed -> {
                    val pass = segment.transform.start()
                    copy(source, segment.sourceOffset, segment.length) { pass.process(it, sink) }
                    pass.finish(sink)
                }
            }
        }
    }

    /** The complete resulting file, or null for [WritePlan.Refused]. For tests and small files. */
    public fun applyInMemory(original: ByteArray, plan: WritePlan): ByteArray? = when (plan) {
        WritePlan.NoChange -> original.copyOf()
        is WritePlan.Refused -> null
        is WritePlan.InPlacePatch -> {
            val out = original.copyOf(plan.newLength.toInt())
            for (write in plan.writes) write.bytes.copyInto(out, write.offset.toInt())
            out
        }
        is WritePlan.StreamingRewrite -> {
            val sink = ByteArraySink()
            stream(ByteArraySource(original), plan, sink)
            sink.toByteArray()
        }
    }

    private inline fun copy(source: RandomAccessSource, offset: Long, length: Long, emit: (ByteArray) -> Unit) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = minOf(COPY_CHUNK.toLong(), end - position).toInt()
            val chunk = source.read(position, count) ?: throw StreamRefusedException(TagRefusal.TRUNCATED)
            emit(chunk)
            position += count
        }
    }
}

/** The smallest set of overwrites that turns a region into its new content. */
internal object ByteDiff {
    /** Differing runs closer than this merge: a few larger writes beat many tiny ones. */
    const val MERGE_GAP = 64

    /** Overwrites turning [old] into [new], both starting at file offset [base]. */
    fun writes(base: Long, old: ByteArray, new: ByteArray): List<ByteWrite> {
        val out = ArrayList<ByteWrite>()
        val common = minOf(old.size, new.size)
        var start = -1
        var last = -1
        for (i in 0 until common) {
            if (old[i] == new[i]) continue
            if (start >= 0 && i - last > MERGE_GAP) {
                out += ByteWrite(base + start, new.copyOfRange(start, last + 1))
                start = -1
            }
            if (start < 0) start = i
            last = i
        }
        if (new.size > common) {
            // Bytes past the old end are always written; a pending run close to them joins in.
            if (start >= 0 && common - last <= MERGE_GAP) {
                out += ByteWrite(base + start, new.copyOfRange(start, new.size))
            } else {
                if (start >= 0) out += ByteWrite(base + start, new.copyOfRange(start, last + 1))
                out += ByteWrite(base + common, new.copyOfRange(common, new.size))
            }
        } else if (start >= 0) {
            out += ByteWrite(base + start, new.copyOfRange(start, last + 1))
        }
        return out
    }

    /** [writes] as a plan: [WritePlan.NoChange] when nothing differs and the length stays. */
    fun patchOrNoChange(base: Long, old: ByteArray, new: ByteArray, newLength: Long, oldLength: Long): WritePlan {
        val writes = writes(base, old, new)
        return if (writes.isEmpty() && newLength == oldLength) {
            WritePlan.NoChange
        } else {
            WritePlan.InPlacePatch(writes, newLength)
        }
    }
}

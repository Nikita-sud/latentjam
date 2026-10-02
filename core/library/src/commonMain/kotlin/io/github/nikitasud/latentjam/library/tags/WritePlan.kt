/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** Space a codec leaves for later edits whenever it has to lay a structure out anew. */
public object TagSpace {
    public const val SPARE_BYTES: Int = 16 * 1024
}

/** Overwrite the file at [offset] with [bytes]. */
public class ByteWrite(public val offset: Long, public val bytes: ByteArray)

/** One piece of a rewritten file, in output order. */
public sealed interface OutputSegment {
    /** Bytes this segment contributes to the output. */
    public val length: Long

    public class Bytes(public val bytes: ByteArray) : OutputSegment {
        override val length: Long get() = bytes.size.toLong()
    }

    /** [length] bytes of the original, starting at [sourceOffset], unchanged. */
    public class Copy(public val sourceOffset: Long, override val length: Long) : OutputSegment

    /**
     * [length] bytes of the original passed through [transform], which emits exactly as many bytes
     * as it receives (e.g. Ogg pages with shifted sequence numbers and recomputed checksums).
     */
    public class Transformed(
        public val sourceOffset: Long,
        override val length: Long,
        public val transform: StreamTransform,
    ) : OutputSegment
}

/** A length-preserving, streaming rewrite of a run of source bytes. */
public interface StreamTransform {
    /** Fresh state for one pass. */
    public fun start(): Pass

    public interface Pass {
        /** The next chunk of input; may hold bytes back until a whole unit (a page) has arrived. */
        public fun process(chunk: ByteArray, sink: ByteSink)

        /** Emits what is held back; throws [StreamRefusedException] when input ended mid-unit. */
        public fun finish(sink: ByteSink)
    }
}

/** A stream turned out, mid-copy, to be something the plan cannot honour. Nothing is written over. */
public class StreamRefusedException(public val reason: TagRefusal) : Exception(reason.name)

/** What a codec decided to do with a file. */
public sealed interface WritePlan {
    /** The edit changes nothing; do not open the file for writing. */
    public data object NoChange : WritePlan

    /** Overwrite [writes] and set the length to [newLength]. The audio is never read or written. */
    public class InPlacePatch(public val writes: List<ByteWrite>, public val newLength: Long) : WritePlan {
        init {
            // A write past the end the file is cut to, or before its start, is a codec bug: fail it here.
            require(newLength >= 0) { "negative length $newLength" }
            for (write in writes) {
                require(write.offset >= 0 && write.bytes.size <= newLength - write.offset) {
                    "write of ${write.bytes.size} bytes at ${write.offset} outside a $newLength-byte file"
                }
            }
        }
    }

    /** Build a new file from [segments]; used only when spare space ran out. */
    public class StreamingRewrite(public val segments: List<OutputSegment>) : WritePlan {
        public val newLength: Long get() = segments.sumOf { it.length }
    }

    public class Refused(public val reason: TagRefusal) : WritePlan
}

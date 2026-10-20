/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * A [RowStore] in a memory-mapped scratch file instead of the Java heap: a 10,000-song library's audio index is
 * 38 MB of floats, most of a 48 MB heap, and here it costs page cache the system can drop and refill instead.
 *
 * The floats are the ones handed in, written in native order and read back unchanged, so every query, queue and
 * checkpoint is the heap store's bit for bit. The file is a cache of the index, which is restored from its
 * checkpoint (FileIndexStore) at every start: it is unlinked as soon as it is opened, so nothing is left on storage,
 * whatever way the process ends.
 *
 * The file grows in segments of [SEGMENT_ROWS] rows, each mapped once, so growing never moves a row and a mapping
 * never passes 2 GB. Written rows are never written again; [compact] and [clear] move to a fresh file and leave the
 * old mappings to the rows still handed out (a snapshot), which the system unmaps once those are collected.
 *
 * Not thread-safe, like the index it serves: appends come from the engine's serialized callers. Reading rows handed
 * out earlier from other threads is safe, since those bytes are not written again.
 */
internal class MappedRowStore(
    override val dim: Int,
    private val directory: File,
    private val name: String,
) : RowStore {

    init {
        require(dim > 0) { "Row dimension must be positive, got $dim" }
    }

    private val segmentFloats = SEGMENT_ROWS * dim
    private val segmentBytes = segmentFloats.toLong() * Float.SIZE_BYTES

    private var file: RandomAccessFile = openScratch()
    private val segments = ArrayList<FloatBuffer>()
    private var rows = 0
    private var sealed = false

    override val rowCount: Int get() = rows

    override fun append(values: FloatArray): Int {
        check(!sealed) { "Store is sealed" }
        require(values.size == dim) { "Row has dimension ${values.size}, expected $dim" }
        val row = rows
        val segment = row / SEGMENT_ROWS
        if (segment == segments.size) segments += mapSegment(segment)
        val buffer = segments[segment].duplicate()
        buffer.position((row % SEGMENT_ROWS) * dim)
        buffer.put(values)
        rows++
        return row
    }

    override fun row(row: Int): StoredVectorRow {
        require(row in 0 until rows) { "Row $row is not stored (rows: $rows)" }
        return MappedRow(segments[row / SEGMENT_ROWS], (row % SEGMENT_ROWS) * dim, dim)
    }

    override fun compact(rows: IntArray) {
        val kept = rows.map { row(it) }
        restart()
        val scratch = FloatArray(dim)
        for (row in kept) {
            row.copyInto(scratch)
            append(scratch)
        }
    }

    override fun clear() {
        restart()
    }

    override fun seal() {
        sealed = true
        runCatching { file.close() }
    }

    /** Moves to a fresh file; the old one's mappings stay valid for the rows already handed out. */
    private fun restart() {
        val old = file
        file = openScratch()
        segments.clear()
        rows = 0
        sealed = false
        runCatching { old.close() }
    }

    /**
     * Maps the next segment, its blocks allocated first: a write to a mapped page the file system cannot back (a
     * full disk) kills the process, so a segment that cannot be allocated is kept on the heap instead.
     */
    private fun mapSegment(segment: Int): FloatBuffer {
        val start = segment * segmentBytes
        val mapped = runCatching {
            Os.posix_fallocate(file.fd, start, segmentBytes)
            file.channel.map(FileChannel.MapMode.READ_WRITE, start, segmentBytes)
        }.getOrNull()
        return mapped?.order(ByteOrder.nativeOrder())?.asFloatBuffer() ?: FloatBuffer.allocate(segmentFloats)
    }

    private fun openScratch(): RandomAccessFile {
        directory.mkdirs()
        val path = File.createTempFile("$name-", ".rows", directory)
        val access = RandomAccessFile(path, "rw")
        // The mappings and the descriptor keep the bytes; the name is not needed (and would outlive a crash).
        path.delete()
        return access
    }

    private class MappedRow(
        private val segment: FloatBuffer,
        private val offset: Int,
        override val size: Int,
    ) : StoredVectorRow {
        override fun get(d: Int): Float = segment.get(offset + d)

        override fun copyInto(out: FloatArray, offset: Int) {
            val buffer = segment.duplicate()
            buffer.position(this.offset)
            buffer.get(out, offset, size)
        }
    }

    companion object {
        /** Rows mapped at a time: 2,048 × 960 floats is a 7.9 MB segment of the audio index. */
        const val SEGMENT_ROWS = 2_048

        /**
         * The store for one index: mapped on 64-bit processes, the heap store elsewhere (a 32-bit process has too
         * little address space to spend on it) and when the scratch file cannot be made.
         */
        fun forIndex(context: Context, dim: Int, name: String): RowStore {
            if (!Process.is64Bit()) return HeapRowStore(dim)
            return runCatching {
                MappedRowStore(dim, File(context.noBackupFilesDir, "smart_rows"), name)
            }.getOrElse { HeapRowStore(dim) }
        }
    }
}

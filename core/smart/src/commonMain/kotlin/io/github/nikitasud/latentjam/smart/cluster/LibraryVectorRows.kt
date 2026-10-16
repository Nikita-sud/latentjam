/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.VectorIndex

/** Writes one fused row for a track; see [LibraryVectorFusion.rowWriter]. */
internal class FusedRowWriter(
    val dim: Int,
    private val write: (
        id: TrackId,
        audio: VectorIndex,
        metadata: VectorIndex?,
        out: FloatArray,
        offset: Int,
    ) -> Unit,
) {
    /** Overwrites `out[offset until offset + dim]`; an absent block stays zero. */
    fun write(id: TrackId, audio: VectorIndex, metadata: VectorIndex?, out: FloatArray, offset: Int) {
        out.fill(0f, offset, offset + dim)
        write.invoke(id, audio, metadata, out, offset)
    }
}

/**
 * The rows of a fused [LibraryVectorSpace], read on demand instead of held.
 *
 * Each row is exactly what `LibraryVectorFusion` would write into the space's matrix for the same
 * track. Rows are read in chunks of [CHUNK_ROWS], each under the engine lock for that chunk only,
 * so a pass over 10,000 tracks never blocks SMART for longer than one chunk. Because the index
 * may change between chunks, a caller checks [isCurrent] once it has read every row it needs and
 * discards its result on `false`.
 *
 * Not thread-safe: one reader, sequential use.
 */
public class LibraryVectorRows internal constructor(
    /** The same ids, in the same order, a [LibraryVectorSpace] for this coverage would hold. */
    public val trackIds: List<TrackId>,
    public val dim: Int,
    public val source: LibraryVectorSource,
    /** The [LibraryVectorCoverage.fingerprint] the rows were planned against. */
    public val fingerprint: Long,
    /** Fills `out[0 until count * dim]` with rows `start until start + count`. */
    private val readChunk: suspend (start: Int, count: Int, out: FloatArray) -> Unit,
    /** Whether the content behind [fingerprint] is still what the indexes hold. */
    private val verify: suspend () -> Boolean,
) {
    public val size: Int get() = trackIds.size

    private val chunk = FloatArray(minOf(CHUNK_ROWS, trackIds.size).coerceAtLeast(1) * dim)
    private var chunkStart = 0
    private var chunkCount = 0

    /** Copies row [row] into `out[0 until dim]`. Sequential reads cost one lock per chunk. */
    internal suspend fun read(row: Int, out: FloatArray) {
        if (row < chunkStart || row >= chunkStart + chunkCount) {
            require(row in trackIds.indices) { "Row $row outside 0 until $size" }
            val count = minOf(CHUNK_ROWS, size - row)
            readChunk(row, count, chunk)
            chunkStart = row
            chunkCount = count
        }
        chunk.copyInto(out, 0, (row - chunkStart) * dim, (row - chunkStart + 1) * dim)
    }

    /** True when every row read so far still matches [fingerprint]. Call after the last read. */
    public suspend fun isCurrent(): Boolean = verify()

    public companion object {
        /** Rows per locked read: 256 × 1,344 floats is a 1.4 MB buffer. */
        public const val CHUNK_ROWS: Int = 256

        /** Rows served from a space already built, for engines without an on-demand path. */
        internal fun of(space: LibraryVectorSpace): LibraryVectorRows {
            val matrix = space.peekRows()
            val dim = space.dim
            return LibraryVectorRows(
                trackIds = space.trackIds,
                dim = dim,
                source = space.source,
                fingerprint = space.fingerprint,
                readChunk = { start, count, out -> matrix.copyInto(out, 0, start * dim, (start + count) * dim) },
                verify = { true },
            )
        }
    }
}

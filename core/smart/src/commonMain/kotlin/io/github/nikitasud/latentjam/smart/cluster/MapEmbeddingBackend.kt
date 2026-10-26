/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

internal sealed interface MapEmbeddingResult {
    /** [neighbours]: the 60-NN lists behind the coordinates, when the backend has them (Kotlin Barnes-Hut). */
    class Complete(val coordinates: FloatArray, val neighbours: BarnesHutTsne.Neighbours? = null) : MapEmbeddingResult
    data object Cancelled : MapEmbeddingResult
    class Failed(val reason: String) : MapEmbeddingResult
}

internal fun interface MapEmbeddingBackend {
    fun embed(rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, isActive: () -> Boolean): MapEmbeddingResult
}

/** Synchronous callback across JNI/Swift. Called on the computation thread, never retained. */
public fun interface MapCancellation {
    public fun isActive(): Boolean
}

internal object ExactMapEmbedding : MapEmbeddingBackend {
    override fun embed(rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, isActive: () -> Boolean): MapEmbeddingResult {
        if (!isActive()) return MapEmbeddingResult.Cancelled
        val result = Tsne.embed(rows, n, dim, 0x1A7E27, initial, isActive)
        return if (isActive()) MapEmbeddingResult.Complete(result) else MapEmbeddingResult.Cancelled
    }
}

/** Pure-Kotlin Barnes-Hut t-SNE for libraries above [LibraryLayout.EXACT_MAX_TRACKS]. */
internal object ApproximateMapEmbedding : MapEmbeddingBackend {
    override fun embed(rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, isActive: () -> Boolean): MapEmbeddingResult {
        if (!isActive()) return MapEmbeddingResult.Cancelled
        val embedding = BarnesHutTsne.embedWithNeighbours(rows, n, dim, 0x1A7E27, initial, isActive)
        val result = embedding.coordinates
        return when {
            !isActive() -> MapEmbeddingResult.Cancelled
            result.all(Float::isFinite) -> MapEmbeddingResult.Complete(result, embedding.neighbours)
            else -> MapEmbeddingResult.Failed("Non-finite coordinates")
        }
    }
}

internal object BarnesHutMapEmbedding : MapEmbeddingBackend {
    const val VERSION = "qdtsne-93f184ca-f32-vp60-p20-t0.5-i1000-e800-threads1-pca50-v1"
    override fun embed(rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, isActive: () -> Boolean): MapEmbeddingResult {
        if (n < 61) return ExactMapEmbedding.embed(rows, n, dim, initial, isActive)
        if (!isActive()) return MapEmbeddingResult.Cancelled
        val output = FloatArray(n * 2)
        return when (nativeMapEmbed(rows, n, dim, initial, output, MapCancellation(isActive))) {
            0 -> if (isActive() && output.all(Float::isFinite)) MapEmbeddingResult.Complete(output)
                 else if (!isActive()) MapEmbeddingResult.Cancelled else MapEmbeddingResult.Failed("Non-finite coordinates")
            1 -> MapEmbeddingResult.Cancelled
            else -> MapEmbeddingResult.Failed("Native map backend unavailable or failed")
        }
    }
}

internal expect fun nativeMapEmbed(
    rows: FloatArray, n: Int, dim: Int, initial: FloatArray?, output: FloatArray, cancellation: MapCancellation,
): Int

/** A derived result is unavailable; callers keep the previous cache and explain the failure. */
public class MapComputationUnavailable(
    public val reason: String,
    public val memoryLimited: Boolean = false,
) : IllegalStateException(reason)

/**
 * Conservative working-set estimates. [projectionBytes] and [regionBytes] include the fused input
 * matrix, for a check made before it is built; the `Work` variants exclude it, for a check made
 * when the caller's space already holds it (and the measured free heap already reflects it).
 */
public object MapMemoryBudget {
    private const val RESERVE = 8L * 1024 * 1024
    public fun projectionBytes(n: Int, dim: Int, experimental: Boolean): Long {
        require(n in 0..LibraryLayout.MAX_TRACKS && dim in 1..1344)
        val rows = 4L * n * dim
        val pca = 8L * n * 50 + 20L * dim * 50
        val embedding = when {
            // Neighbor lists, symmetric edges, VP-tree, optimizer and JNI/Swift copies.
            experimental && n >= 61 -> 4L * n * 50 * 3 + 128L * n * 60
            n > LibraryLayout.EXACT_MAX_TRACKS -> BarnesHutTsne.workingBytes(n)
            else -> 4L * n * (n - 1) + 64L * n
        }
        return RESERVE + rows + maxOf(pca, embedding)
    }
    public fun regionBytes(n: Int, dim: Int): Long {
        require(n >= 0 && dim > 0)
        if (n > AverageLinkageClustering.MAX_TRACKS) {
            // The owned rows are reordered in place. The projection, the micro-cluster direction
            // sums, their linkage matrix and the final region centroids (at most one per
            // MIN_CLUSTER_SIZE tracks) are budgeted together, without relying on GC between them.
            val sample = AverageLinkageClustering.MAX_TRACKS.toLong()
            val width = AverageLinkageClustering.PROJECTION_DIM.toLong()
            val regions = n.toLong() / TrackClustering.MIN_CLUSTER_SIZE
            return RESERVE + 4L * n * dim + 4L * (n + dim) * width + 4L * sample * dim +
                4L * sample * (sample - 1) + 4L * regions * dim + 160L * n
        }
        // Dropping degenerate directions can briefly retain both the owned input and a
        // compacted row matrix. Budget both, without relying on platform GC liveness.
        return RESERVE + 8L * n * dim + 4L * n * (n - 1) + 128L * n
    }

    /**
     * [projectionBytes] for [LibraryLayout.compute] over [LibraryVectorRows]: the `n x dim` input
     * is never held, only one [LibraryVectorRows.CHUNK_ROWS] buffer of it, plus the streamed
     * reduction's row and accumulator (inside the PCA term's `20 * dim * 50`).
     */
    public fun projectionStreamedBytes(n: Int, dim: Int, experimental: Boolean): Long =
        projectionBytes(n, dim, experimental) - 4L * n * dim +
            4L * minOf(n, LibraryVectorRows.CHUNK_ROWS) * dim

    /** [projectionBytes] without the fused input the caller's space already holds. */
    public fun projectionWorkBytes(n: Int, dim: Int, experimental: Boolean): Long =
        projectionBytes(n, dim, experimental) - 4L * n * dim

    /** [regionBytes] without the fused input the caller's space already holds. */
    public fun regionWorkBytes(n: Int, dim: Int): Long = regionBytes(n, dim) - 4L * n * dim
}

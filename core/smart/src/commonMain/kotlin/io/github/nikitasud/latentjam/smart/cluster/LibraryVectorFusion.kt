/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.VectorIndex
import kotlin.math.sqrt

/**
 * One owned, row-major vector matrix selected for discovering a library's My Mixes.
 *
 * The constructor and rows stay inside the smart module: callers can pass the space to
 * [LibraryWorlds], but cannot retain or mutate a second copy. Clustering consumes the matrix in
 * place, avoiding the old peak where two index snapshots, a fused map, and a second clustering
 * matrix were simultaneously resident.
 */
public class LibraryVectorSpace internal constructor(
    /**
     * The ids this space actually covers -- a filtered subset of whatever id list was requested,
     * since a track without a usable audio or metadata vector is dropped rather than padded with a
     * zero row. Public so callers that persist something keyed by this space (e.g.
     * [LibraryLayout]'s stored positions) can compare against the population they actually built
     * against, not the caller's original, unfiltered id list.
     */
    public val trackIds: List<TrackId>,
    rows: FloatArray,
    public val dim: Int,
    public val source: LibraryVectorSource,
) {
    private var ownedRows: FloatArray? = rows

    public val size: Int get() = trackIds.size

    init {
        require(dim > 0) { "Embedding dimension must be positive, got $dim" }
        require(rows.size == trackIds.size * dim) {
            "Row matrix has ${rows.size} values for ${trackIds.size} × $dim"
        }
    }

    /** One-shot handoff: spherical k-means centers this private matrix in place. */
    internal fun takeRows(): FloatArray =
        checkNotNull(ownedRows) { "A library vector space can only be clustered once" }
            .also { ownedRows = null }

    /** Test/diagnostic copy that cannot mutate either this matrix or the engine indexes. */
    internal fun vector(trackId: TrackId): FloatArray? {
        val row = trackIds.indexOf(trackId)
        if (row < 0) return null
        val sourceRows = ownedRows ?: return null
        return sourceRows.copyOfRange(row * dim, (row + 1) * dim)
    }
}

public enum class LibraryVectorSource {
    AUDIO,
    METADATA,
    AUDIO_AND_METADATA,
}

/**
 * Selects the best covered mix space and materializes it exactly once.
 *
 * Metadata supplies intent (genre, artist, era); audio supplies the sound the tags cannot express.
 * A conservative acoustic residual was the cross-library winner: it improved real-playlist
 * neighbours without sacrificing the clean metadata structure of an unrelated 2,400-track
 * library.
 */
public object LibraryVectorFusion {

    /**
     * Cross-library Pareto point. Fused cosine is 75% metadata and 25% audio when both exist,
     * preserving explicit user tags while correcting acoustically implausible neighbours.
     */
    public const val METADATA_WEIGHT: Float = 0.75f

    /**
     * Hybrid promotion requires both encoders for most tracks. Once promoted, a track with one
     * unreadable/missing modality is still retained using its normalized available modality and a
     * zero mask in the absent block; no otherwise usable track silently disappears.
     */
    public const val MIN_HYBRID_COVERAGE: Float = 0.8f

    /**
     * Map-backed entry point for deterministic tests and offline callers.
     *
     * Production uses [buildFromIndexes], which reads one track at a time under the engine lock and
     * therefore never materializes full defensive snapshots beside the output matrix.
     */
    public fun build(
        ids: List<TrackId>,
        audio: Map<TrackId, FloatArray>,
        metadata: Map<TrackId, FloatArray>,
        audioDim: Int,
        metadataDim: Int,
        metadataWeight: Float = METADATA_WEIGHT,
        minHybridCoverage: Float = MIN_HYBRID_COVERAGE,
    ): LibraryVectorSpace? {
        validateParameters(audioDim, metadataDim, metadataWeight, minHybridCoverage)
        val stableIds = ids.distinct()
        if (stableIds.isEmpty()) return null
        val audioIds = stableIds.filterTo(LinkedHashSet()) {
            audio[it].isUsable(audioDim)
        }
        val metadataIds = stableIds.filterTo(LinkedHashSet()) {
            metadata[it].isUsable(metadataDim)
        }
        return buildSelected(
            stableIds = stableIds,
            audioIds = audioIds,
            metadataIds = metadataIds,
            audio = audio::get,
            metadata = metadata::get,
            audioDim = audioDim,
            metadataDim = metadataDim,
            metadataWeight = metadataWeight,
            minHybridCoverage = minHybridCoverage,
        )
    }

    /**
     * Index-backed production path. Each candidate is copied and validated one at a time, then
     * discarded before the output pass; malformed custom-index rows cannot poison clustering, and
     * full snapshots are never simultaneously resident.
     */
    internal fun buildFromIndexes(
        ids: List<TrackId>,
        audio: VectorIndex,
        metadata: VectorIndex?,
        audioDim: Int,
        metadataDim: Int,
        metadataWeight: Float = METADATA_WEIGHT,
        minHybridCoverage: Float = MIN_HYBRID_COVERAGE,
    ): LibraryVectorSpace? {
        validateParameters(audioDim, metadataDim, metadataWeight, minHybridCoverage)
        val stableIds = ids.distinct()
        if (stableIds.isEmpty()) return null
        val audioIds = stableIds.filterTo(LinkedHashSet()) {
            audio.vector(it).isUsable(audioDim)
        }
        val metadataIds = stableIds.filterTo(LinkedHashSet()) {
            metadata?.vector(it).isUsable(metadataDim)
        }
        return buildSelected(
            stableIds = stableIds,
            audioIds = audioIds,
            metadataIds = metadataIds,
            audio = audio::vector,
            metadata = { id -> metadata?.vector(id) },
            audioDim = audioDim,
            metadataDim = metadataDim,
            metadataWeight = metadataWeight,
            minHybridCoverage = minHybridCoverage,
        )
    }

    private fun buildSelected(
        stableIds: List<TrackId>,
        audioIds: Set<TrackId>,
        metadataIds: Set<TrackId>,
        audio: (TrackId) -> FloatArray?,
        metadata: (TrackId) -> FloatArray?,
        audioDim: Int,
        metadataDim: Int,
        metadataWeight: Float,
        minHybridCoverage: Float,
    ): LibraryVectorSpace? {
        val sharedCount = audioIds.count(metadataIds::contains)
        val sharedCoverage = sharedCount.toFloat() / stableIds.size
        if (sharedCount > 0 && sharedCoverage >= minHybridCoverage) {
            val selectedIds = stableIds.filter { it in audioIds || it in metadataIds }
            val dim = audioDim + metadataDim
            val rows = FloatArray(selectedIds.size * dim)
            val audioScale = sqrt(1f - metadataWeight)
            val metadataScale = sqrt(metadataWeight)
            for ((row, id) in selectedIds.withIndex()) {
                val audioVector = audio(id)
                val metadataVector = metadata(id)
                val base = row * dim
                if (audioVector != null) {
                    // With no metadata, the available modality carries the full unit norm. Against
                    // a complete row it still contributes only sqrt(audioWeight), which is the
                    // natural masked-cosine fallback.
                    val scale = if (metadataVector == null) 1f else audioScale
                    copyNormalized(audioVector, rows, base, audioDim, scale)
                }
                if (metadataVector != null) {
                    val scale = if (audioVector == null) 1f else metadataScale
                    copyNormalized(metadataVector, rows, base + audioDim, metadataDim, scale)
                }
            }
            return LibraryVectorSpace(
                trackIds = selectedIds,
                rows = rows,
                dim = dim,
                source = LibraryVectorSource.AUDIO_AND_METADATA,
            )
        }

        return when {
            metadataIds.isNotEmpty() && metadataIds.size >= audioIds.size ->
                singleSpace(
                    ids = stableIds.filter(metadataIds::contains),
                    vectors = metadata,
                    dim = metadataDim,
                    source = LibraryVectorSource.METADATA,
                )
            audioIds.isNotEmpty() ->
                singleSpace(
                    ids = stableIds.filter(audioIds::contains),
                    vectors = audio,
                    dim = audioDim,
                    source = LibraryVectorSource.AUDIO,
                )
            else -> null
        }
    }

    private fun singleSpace(
        ids: List<TrackId>,
        vectors: (TrackId) -> FloatArray?,
        dim: Int,
        source: LibraryVectorSource,
    ): LibraryVectorSpace {
        val rows = FloatArray(ids.size * dim)
        for ((row, id) in ids.withIndex()) {
            copyNormalized(checkNotNull(vectors(id)), rows, row * dim, dim, scale = 1f)
        }
        return LibraryVectorSpace(ids, rows, dim, source)
    }

    private fun copyNormalized(
        vector: FloatArray,
        output: FloatArray,
        offset: Int,
        dim: Int,
        scale: Float,
    ) {
        check(vector.size == dim) {
            "Stored vector has dimension ${vector.size}, expected $dim"
        }
        val norm = sqrt(normSquared(vector)).toFloat().coerceAtLeast(1e-12f)
        for (d in 0 until dim) output[offset + d] = vector[d] / norm * scale
    }

    private fun FloatArray?.isUsable(dim: Int): Boolean =
        this != null && size == dim && all { it.isFinite() } && normSquared(this) > 1e-12

    private fun normSquared(vector: FloatArray): Double {
        var total = 0.0
        for (value in vector) total += value.toDouble() * value
        return total
    }

    private fun validateParameters(
        audioDim: Int,
        metadataDim: Int,
        metadataWeight: Float,
        minHybridCoverage: Float,
    ) {
        require(audioDim > 0) { "Audio dimension must be positive, got $audioDim" }
        require(metadataDim > 0) { "Metadata dimension must be positive, got $metadataDim" }
        require(metadataWeight in 0f..1f) {
            "Metadata weight must be within [0, 1], got $metadataWeight"
        }
        require(minHybridCoverage in 0f..1f) {
            "Hybrid coverage must be within [0, 1], got $minHybridCoverage"
        }
    }
}

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

/**
 * The population a fused space would cover, without the space.
 *
 * Callers that only need to know *which* tracks are laid out — [LibraryLayout.covers], the
 * Map's track ceiling and its mappable-set filter — would otherwise pay for the whole fused
 * matrix and read two fields off it.
 */
public class LibraryVectorCoverage internal constructor(
    /** Exactly the ids [LibraryVectorFusion.build] would put in [LibraryVectorSpace.trackIds]. */
    public val trackIds: List<TrackId>,
    public val source: LibraryVectorSource,
) {
    public val size: Int get() = trackIds.size
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
     * Which tracks a fused space would cover, and from which modality — without the rows.
     *
     * Same selection as [build], by construction: both delegate to [select]. Callers that only
     * need the population skip allocating a `trackIds.size × dim` matrix they would not read.
     */
    public fun coverage(
        ids: List<TrackId>,
        audio: Map<TrackId, FloatArray>,
        metadata: Map<TrackId, FloatArray>,
        audioDim: Int,
        metadataDim: Int,
        minHybridCoverage: Float = MIN_HYBRID_COVERAGE,
    ): LibraryVectorCoverage? {
        validateParameters(audioDim, metadataDim, METADATA_WEIGHT, minHybridCoverage)
        val stableIds = ids.distinct()
        if (stableIds.isEmpty()) return null
        return select(
            stableIds = stableIds,
            audioIds = stableIds.filterTo(LinkedHashSet()) { audio[it].isUsable(audioDim) },
            metadataIds = stableIds.filterTo(LinkedHashSet()) { metadata[it].isUsable(metadataDim) },
            minHybridCoverage = minHybridCoverage,
        )
    }

    /**
     * Index-backed [coverage]; the production entry point.
     *
     * Still reads every candidate vector — usability is a property of the row's contents, not of
     * its presence, so [VectorIndex.contains] cannot stand in without the selection drifting from
     * [buildFromIndexes]. What it avoids is the output matrix and the normalising pass over it.
     */
    public fun coverageFromIndexes(
        ids: List<TrackId>,
        audio: VectorIndex,
        metadata: VectorIndex?,
        audioDim: Int,
        metadataDim: Int,
        minHybridCoverage: Float = MIN_HYBRID_COVERAGE,
    ): LibraryVectorCoverage? {
        validateParameters(audioDim, metadataDim, METADATA_WEIGHT, minHybridCoverage)
        val stableIds = ids.distinct()
        if (stableIds.isEmpty()) return null
        return select(
            stableIds = stableIds,
            audioIds = stableIds.filterTo(LinkedHashSet()) { audio.vector(it).isUsable(audioDim) },
            metadataIds = stableIds.filterTo(LinkedHashSet()) {
                metadata?.vector(it).isUsable(metadataDim)
            },
            minHybridCoverage = minHybridCoverage,
        )
    }

    /**
     * The one place a fused space's population and modality are decided.
     *
     * Shared by [buildSelected] and [coverage] so the two can never disagree — `LibraryLayout`
     * compares its stored positions against this id list, and a divergence would either force a
     * t-SNE recompute on every visit or keep a layout that no longer covers the library.
     */
    private fun select(
        stableIds: List<TrackId>,
        audioIds: Set<TrackId>,
        metadataIds: Set<TrackId>,
        minHybridCoverage: Float,
    ): LibraryVectorCoverage? {
        val sharedCount = audioIds.count(metadataIds::contains)
        val sharedCoverage = sharedCount.toFloat() / stableIds.size
        if (sharedCount > 0 && sharedCoverage >= minHybridCoverage) {
            return LibraryVectorCoverage(
                trackIds = stableIds.filter { it in audioIds || it in metadataIds },
                source = LibraryVectorSource.AUDIO_AND_METADATA,
            )
        }
        return when {
            metadataIds.isNotEmpty() && metadataIds.size >= audioIds.size -> LibraryVectorCoverage(
                trackIds = stableIds.filter(metadataIds::contains),
                source = LibraryVectorSource.METADATA,
            )
            audioIds.isNotEmpty() -> LibraryVectorCoverage(
                trackIds = stableIds.filter(audioIds::contains),
                source = LibraryVectorSource.AUDIO,
            )
            else -> null
        }
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
        val selection = select(stableIds, audioIds, metadataIds, minHybridCoverage) ?: return null
        if (selection.source == LibraryVectorSource.AUDIO_AND_METADATA) {
            val selectedIds = selection.trackIds
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

        return when (selection.source) {
            LibraryVectorSource.METADATA -> singleSpace(
                ids = selection.trackIds,
                vectors = metadata,
                dim = metadataDim,
                source = LibraryVectorSource.METADATA,
            )
            LibraryVectorSource.AUDIO -> singleSpace(
                ids = selection.trackIds,
                vectors = audio,
                dim = audioDim,
                source = LibraryVectorSource.AUDIO,
            )
            // Handled above; listed so a new source cannot be added without deciding its rows.
            LibraryVectorSource.AUDIO_AND_METADATA -> null
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

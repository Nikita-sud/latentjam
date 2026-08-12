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
    /**
     * Stable identity of [source], [trackIds], and every raw usable modality vector selected for
     * this space. Unlike the normalized, one-shot row matrix, this remains available after
     * clustering so callers can validate content-addressed caches.
     */
    public val fingerprint: Long = 0L,
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
    /** The same content fingerprint [LibraryVectorFusion.build] exposes on its vector space. */
    public val fingerprint: Long,
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
        val inventory = inventory(
            stableIds = stableIds,
            audio = audio::get,
            metadata = metadata::get,
            audioDim = audioDim,
            metadataDim = metadataDim,
        )
        return buildSelected(
            stableIds = stableIds,
            inventory = inventory,
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
        val inventory = inventory(
            stableIds = stableIds,
            audio = audio::get,
            metadata = metadata::get,
            audioDim = audioDim,
            metadataDim = metadataDim,
        )
        return select(
            stableIds = stableIds,
            inventory = inventory,
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
        val inventory = inventory(
            stableIds = stableIds,
            audio = audio::vector,
            metadata = { id -> metadata?.vector(id) },
            audioDim = audioDim,
            metadataDim = metadataDim,
        )
        return select(
            stableIds = stableIds,
            inventory = inventory,
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
        inventory: VectorInventory,
        minHybridCoverage: Float,
    ): LibraryVectorCoverage? {
        val audioIds = inventory.audioIds
        val metadataIds = inventory.metadataIds
        val sharedCount = audioIds.count(metadataIds::contains)
        val sharedCoverage = sharedCount.toFloat() / stableIds.size
        if (sharedCount > 0 && sharedCoverage >= minHybridCoverage) {
            return coverage(
                trackIds = stableIds.filter { it in audioIds || it in metadataIds },
                source = LibraryVectorSource.AUDIO_AND_METADATA,
                inventory = inventory,
            )
        }
        return when {
            metadataIds.isNotEmpty() && metadataIds.size >= audioIds.size -> coverage(
                trackIds = stableIds.filter(metadataIds::contains),
                source = LibraryVectorSource.METADATA,
                inventory = inventory,
            )
            audioIds.isNotEmpty() -> coverage(
                trackIds = stableIds.filter(audioIds::contains),
                source = LibraryVectorSource.AUDIO,
                inventory = inventory,
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
        val inventory = inventory(
            stableIds = stableIds,
            audio = audio::vector,
            metadata = { id -> metadata?.vector(id) },
            audioDim = audioDim,
            metadataDim = metadataDim,
        )
        return buildSelected(
            stableIds = stableIds,
            inventory = inventory,
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
        inventory: VectorInventory,
        audio: (TrackId) -> FloatArray?,
        metadata: (TrackId) -> FloatArray?,
        audioDim: Int,
        metadataDim: Int,
        metadataWeight: Float,
        minHybridCoverage: Float,
    ): LibraryVectorSpace? {
        val selection = select(stableIds, inventory, minHybridCoverage) ?: return null
        val audioIds = inventory.audioIds
        val metadataIds = inventory.metadataIds
        if (selection.source == LibraryVectorSource.AUDIO_AND_METADATA) {
            val selectedIds = selection.trackIds
            val dim = audioDim + metadataDim
            val rows = FloatArray(selectedIds.size * dim)
            val audioScale = sqrt(1f - metadataWeight)
            val metadataScale = sqrt(metadataWeight)
            for ((row, id) in selectedIds.withIndex()) {
                // A track can enter a hybrid through just one usable modality. Do not re-read a
                // non-null but malformed row that the inventory deliberately excluded: treating
                // mere presence as usability used to feed a wrong-sized/NaN vector into
                // copyNormalized and either throw or poison the whole layout.
                val audioVector = if (id in audioIds) audio(id) else null
                val metadataVector = if (id in metadataIds) metadata(id) else null
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
                fingerprint = selection.fingerprint,
            )
        }

        return when (selection.source) {
            LibraryVectorSource.METADATA -> singleSpace(
                ids = selection.trackIds,
                vectors = metadata,
                dim = metadataDim,
                source = LibraryVectorSource.METADATA,
                fingerprint = selection.fingerprint,
            )
            LibraryVectorSource.AUDIO -> singleSpace(
                ids = selection.trackIds,
                vectors = audio,
                dim = audioDim,
                source = LibraryVectorSource.AUDIO,
                fingerprint = selection.fingerprint,
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
        fingerprint: Long,
    ): LibraryVectorSpace {
        val rows = FloatArray(ids.size * dim)
        for ((row, id) in ids.withIndex()) {
            copyNormalized(checkNotNull(vectors(id)), rows, row * dim, dim, scale = 1f)
        }
        return LibraryVectorSpace(ids, rows, dim, source, fingerprint)
    }

    private class VectorInventory(
        val audioIds: Set<TrackId>,
        val metadataIds: Set<TrackId>,
        val audioFingerprint: Long,
        val metadataFingerprint: Long,
    )

    /**
     * Scans each candidate once per modality. The digests let coverage and materialization derive
     * exactly the same identity without retaining the raw index rows beside the output matrix.
     */
    private fun inventory(
        stableIds: List<TrackId>,
        audio: (TrackId) -> FloatArray?,
        metadata: (TrackId) -> FloatArray?,
        audioDim: Int,
        metadataDim: Int,
    ): VectorInventory {
        // Library scans may return the same ids in a different order. The matrix keeps caller
        // order, but cache identity is content identity and therefore uses one canonical order.
        val canonicalIds = stableIds.sortedBy { it.value }
        val audioIds = LinkedHashSet<TrackId>()
        val audioFingerprint = Fingerprint64().apply {
            putString("audio")
            for (id in canonicalIds) {
                val vector = audio(id)
                if (vector != null && vector.isUsable(audioDim)) {
                    audioIds += id
                    putString(id.value)
                    putVector(vector)
                }
            }
        }.value

        val metadataIds = LinkedHashSet<TrackId>()
        val metadataFingerprint = Fingerprint64().apply {
            putString("metadata")
            for (id in canonicalIds) {
                val vector = metadata(id)
                if (vector != null && vector.isUsable(metadataDim)) {
                    metadataIds += id
                    putString(id.value)
                    putVector(vector)
                }
            }
        }.value

        return VectorInventory(
            audioIds = audioIds,
            metadataIds = metadataIds,
            audioFingerprint = audioFingerprint,
            metadataFingerprint = metadataFingerprint,
        )
    }

    private fun coverage(
        trackIds: List<TrackId>,
        source: LibraryVectorSource,
        inventory: VectorInventory,
    ): LibraryVectorCoverage {
        val fingerprint = Fingerprint64().apply {
            putString("latentjam-library-vector-content-v1")
            putString(source.name)
            putInt(trackIds.size)
            trackIds.sortedBy { it.value }.forEach { putString(it.value) }
            when (source) {
                LibraryVectorSource.AUDIO -> putLong(inventory.audioFingerprint)
                LibraryVectorSource.METADATA -> putLong(inventory.metadataFingerprint)
                LibraryVectorSource.AUDIO_AND_METADATA -> {
                    putLong(inventory.audioFingerprint)
                    putLong(inventory.metadataFingerprint)
                }
            }
        }.value and Long.MAX_VALUE
        return LibraryVectorCoverage(trackIds, source, fingerprint)
    }

    /** A small, platform-independent FNV-1a encoder with explicit length framing. */
    private class Fingerprint64 {
        var value: Long = FNV_OFFSET_BASIS
            private set

        fun putInt(input: Int) {
            for (shift in 24 downTo 0 step 8) putByte(input ushr shift)
        }

        fun putLong(input: Long) {
            for (shift in 56 downTo 0 step 8) putByte((input ushr shift).toInt())
        }

        fun putString(input: String) {
            val bytes = input.encodeToByteArray()
            putInt(bytes.size)
            bytes.forEach { putByte(it.toInt()) }
        }

        fun putVector(input: FloatArray) {
            putInt(input.size)
            input.forEach { putInt(it.toRawBits()) }
        }

        private fun putByte(input: Int) {
            value = (value xor (input and 0xff).toLong()) * FNV_PRIME
        }
    }

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

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

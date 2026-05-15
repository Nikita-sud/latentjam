/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * Retrieval.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.predictor

import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingDao
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingEntity
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.musikr.Music

/**
 * Cached embedding matrix + cosine top-K helper. Built lazily on first recommendation request and
 * invalidated when the library changes (handled by the engine).
 */
@Singleton
class Retrieval
@Inject
constructor(private val trackEmbeddingDao: TrackEmbeddingDao, private val mlSettings: MlSettings) {
    @Volatile private var snapshot: Snapshot? = null

    suspend fun ensureLoaded(modelVersion: String = mlSettings.embeddingVersion): Snapshot? {
        val current = snapshot
        if (current != null && current.modelVersion == modelVersion) return current
        val rows = trackEmbeddingDao.loadAll(modelVersion)
        if (rows.isEmpty()) return null
        val rawFlat = FloatArray(rows.size * EMBEDDING_DIM)
        val uids = ArrayList<Music.UID>(rows.size)
        // Parallel arrays of features. `tempos[i]` is NaN when the row has no committed
        // tempo (autocorrelation estimator declined / not yet backfilled); callers can
        // use Float.isNaN to detect unknown values without paying for autoboxing.
        val tempos = FloatArray(rows.size) { Float.NaN }
        val energies = FloatArray(rows.size) { Float.NaN }
        for ((i, row) in rows.withIndex()) {
            uids.add(row.songUid)
            decodeInto(row, rawFlat, i * EMBEDDING_DIM)
            row.tempo?.let { tempos[i] = it }
            row.energy?.let { energies[i] = it }
        }
        // We keep two views of the matrix in memory:
        //   - `rawFlat`  : as-stored, L2-normalized but uncentered. Used by the
        //     learned predictor's scorer (which was trained on raw embeddings).
        //   - `flat`     : the same vectors with the corpus mean subtracted and
        //     re-L2-normalized. Used by the cosine pre-rank / centroid fallback
        //     path, because raw CLAP embeddings carry a shared "this is audio"
        //     direction that saturates ~50 % of random pairs above cos > 0.95.
        // Memory cost: 2× a few MB for libraries up to ~10 k tracks. Cheap.
        val mean = computeMean(rawFlat, rows.size)
        val flat = rawFlat.copyOf()
        centerAndRenormalize(flat, rows.size, mean)
        val s =
            Snapshot(
                modelVersion = modelVersion,
                uids = uids,
                flat = flat,
                rawFlat = rawFlat,
                mean = mean,
                tempos = tempos,
                energies = energies,
            )
        snapshot = s
        return s
    }

    /**
     * Apply the corpus mean-centering transform to a query vector so it lives in the same space as
     * `Snapshot.flat`. Callers MUST run their query through this before passing it to [cosineTopK]
     * / [cosineSortAll] / [autoregressivePath] — the cached matrix is centered+renormalized at load
     * time, so an uncentered query produces meaningless dot products.
     */
    fun prepareQuery(snapshot: Snapshot, query: FloatArray): FloatArray {
        val out = FloatArray(EMBEDDING_DIM)
        var sumSq = 0.0
        for (d in 0 until EMBEDDING_DIM) {
            val v = query[d] - snapshot.mean[d]
            out[d] = v
            sumSq += v.toDouble() * v.toDouble()
        }
        val norm = kotlin.math.sqrt(sumSq).toFloat()
        if (norm > 1e-12f) {
            val inv = 1f / norm
            for (d in 0 until EMBEDDING_DIM) out[d] *= inv
        }
        return out
    }

    private fun computeMean(flat: FloatArray, n: Int): FloatArray {
        val mean = FloatArray(EMBEDDING_DIM)
        for (i in 0 until n) {
            val base = i * EMBEDDING_DIM
            for (d in 0 until EMBEDDING_DIM) mean[d] += flat[base + d]
        }
        val inv = 1f / n
        for (d in 0 until EMBEDDING_DIM) mean[d] *= inv
        return mean
    }

    private fun centerAndRenormalize(flat: FloatArray, n: Int, mean: FloatArray) {
        for (i in 0 until n) {
            val base = i * EMBEDDING_DIM
            var sumSq = 0.0
            for (d in 0 until EMBEDDING_DIM) {
                val v = flat[base + d] - mean[d]
                flat[base + d] = v
                sumSq += v.toDouble() * v.toDouble()
            }
            val norm = kotlin.math.sqrt(sumSq).toFloat()
            if (norm > 1e-12f) {
                val inv = 1f / norm
                for (d in 0 until EMBEDDING_DIM) flat[base + d] *= inv
            }
        }
    }

    fun invalidate() {
        snapshot = null
    }

    /**
     * Cosine top-K against [state] over the cached library, excluding any UIDs in [exclude].
     * Returned indices are positions in [Snapshot.uids]; scores are dot products against the
     * L2-normalized stored embeddings (= cosine similarity since both sides are unit-norm).
     */
    fun cosineTopK(
        snapshot: Snapshot,
        state: FloatArray,
        k: Int,
        exclude: Set<Music.UID>,
    ): List<Hit> {
        val n = snapshot.uids.size
        val flat = snapshot.flat
        // We retain the smallest score in the top-k via a parallel array; for k=100 a linear
        // scan is faster than a heap on Android Dalvik/ART for our sizes (<10k tracks).
        val outScores = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val outIndices = IntArray(k) { -1 }

        for (i in 0 until n) {
            if (snapshot.uids[i] in exclude) continue
            var dot = 0f
            val base = i * EMBEDDING_DIM
            for (d in 0 until EMBEDDING_DIM) {
                dot += flat[base + d] * state[d]
            }
            // Insertion into the top-k buffer.
            var minIdx = 0
            for (j in 1 until k) if (outScores[j] < outScores[minIdx]) minIdx = j
            if (dot > outScores[minIdx]) {
                outScores[minIdx] = dot
                outIndices[minIdx] = i
            }
        }
        // Pack and sort descending.
        val hits = ArrayList<Hit>(k)
        for (j in 0 until k) {
            if (outIndices[j] < 0) continue
            hits.add(Hit(uidIndex = outIndices[j], score = outScores[j]))
        }
        hits.sortByDescending { it.score }
        return hits
    }

    /**
     * Auto-regressive path through the library. Pick #1 is the nearest neighbor of [seed]; pick #2
     * is the nearest neighbor of pick #1; pick #i is the nearest neighbor of pick #(i-1). Continues
     * until every non-excluded track has been placed. Produces a queue where every consecutive pair
     * is locally similar, while the whole walk can drift across stylistic neighborhoods. Cheaper
     * than computing the full pairwise matrix — it's O(N² × D) but only one row of the matrix is
     * touched per step.
     */
    fun autoregressivePath(
        snapshot: Snapshot,
        seed: FloatArray,
        exclude: Set<Music.UID>,
    ): List<Hit> {
        val n = snapshot.uids.size
        val flat = snapshot.flat
        val available = BooleanArray(n) { snapshot.uids[it] !in exclude }
        val out = ArrayList<Hit>(n)
        // Cursor = current vector we're searching nearest neighbor to. Initially the seed.
        // After the first pick, we just track the pick's row in `flat` (no copy needed).
        var cursorExternal: FloatArray? = seed
        var cursorBase = 0
        while (true) {
            var bestI = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in 0 until n) {
                if (!available[i]) continue
                var dot = 0f
                val base = i * EMBEDDING_DIM
                val ext = cursorExternal
                if (ext != null) {
                    for (d in 0 until EMBEDDING_DIM) {
                        dot += flat[base + d] * ext[d]
                    }
                } else {
                    for (d in 0 until EMBEDDING_DIM) {
                        dot += flat[base + d] * flat[cursorBase + d]
                    }
                }
                if (dot > bestScore) {
                    bestScore = dot
                    bestI = i
                }
            }
            if (bestI < 0) break
            out.add(Hit(uidIndex = bestI, score = bestScore))
            available[bestI] = false
            cursorExternal = null
            cursorBase = bestI * EMBEDDING_DIM
        }
        return out
    }

    /**
     * Full library cosine sort, descending. Used by SMART shuffle to materialize a "smart-ordered"
     * version of the entire queue rather than a 20-pick beam.
     *
     * Scans [Snapshot.flat] (mean-centered). Pair with [prepareQuery] on the query vector to keep
     * both sides in the same space.
     */
    fun cosineSortAll(snapshot: Snapshot, state: FloatArray, exclude: Set<Music.UID>): List<Hit> =
        cosineSortAll(snapshot, state, exclude, useRaw = false)

    /**
     * Variant of [cosineSortAll] that scans the raw (uncentered) embedding matrix. Used by the
     * predictor pipeline, since `PredictorRuntime.encodeState` produces a state vector in the raw
     * embedding space.
     */
    fun cosineSortAllRaw(
        snapshot: Snapshot,
        state: FloatArray,
        exclude: Set<Music.UID>,
    ): List<Hit> = cosineSortAll(snapshot, state, exclude, useRaw = true)

    private fun cosineSortAll(
        snapshot: Snapshot,
        state: FloatArray,
        exclude: Set<Music.UID>,
        useRaw: Boolean,
    ): List<Hit> {
        val n = snapshot.uids.size
        val flat = if (useRaw) snapshot.rawFlat else snapshot.flat
        val out = ArrayList<Hit>(n)
        for (i in 0 until n) {
            if (snapshot.uids[i] in exclude) continue
            var dot = 0f
            val base = i * EMBEDDING_DIM
            for (d in 0 until EMBEDDING_DIM) {
                dot += flat[base + d] * state[d]
            }
            out.add(Hit(uidIndex = i, score = dot))
        }
        out.sortByDescending { it.score }
        return out
    }

    private fun decodeInto(row: TrackEmbeddingEntity, dst: FloatArray, offset: Int) {
        val buf = java.nio.ByteBuffer.wrap(row.embedding).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < EMBEDDING_DIM && buf.remaining() >= 4) {
            dst[offset + i] = buf.float
            i++
        }
    }

    /**
     * In-memory view of the embedding table. Beyond the embedding matrix itself we also surface the
     * parallel tempo/energy arrays so the recommender can apply BPM and energy filters without an
     * extra DAO round-trip per candidate. NaN entries mean the feature is missing (e.g. row hasn't
     * been backfilled, or the BPM estimator declined to commit on that track).
     */
    data class Snapshot(
        val modelVersion: String,
        val uids: List<Music.UID>,
        /** Flattened (N × EMBEDDING_DIM) embeddings, mean-centered + L2-normalized. */
        val flat: FloatArray,
        /**
         * Same vectors as [flat] but in their raw (uncentered, L2-normalized) form. The predictor's
         * scorer ONNX was trained on raw embeddings; pack pool candidates from this array when
         * calling `PredictorRuntime.score`.
         */
        val rawFlat: FloatArray,
        /** Corpus mean used to center [flat]; queries to [flat] must subtract this too. */
        val mean: FloatArray,
        val tempos: FloatArray,
        val energies: FloatArray,
    )

    data class Hit(val uidIndex: Int, val score: Float)

    companion object {
        const val EMBEDDING_DIM = 512
    }
}

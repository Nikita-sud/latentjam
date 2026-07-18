/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/** Every tuned constant of the chain, in one place. */
internal object ChainConfig {

    /** Bounds the scorer's vote so it can shape ordering but never veto the user's pick. */
    const val SCORER_SQUASH = 1.5f
    const val SCORER_TEMP = 2.0f

    /** Local coherence toward the previous pick. */
    const val COSINE_BLEND_WEIGHT = 3.0f

    /** Pull toward the seed for the whole walk, so one off-genre hop can't capture the chain. */
    const val CHAIN_SEED_GRAVITY = 2.5f

    const val SEM_CHAIN_SEED_GRAVITY = 2.0f
    const val SEM_CHAIN_PREV_BLEND = 1.0f

    const val HUB_CHAIN_DAMP = 0.6f
    const val HUB_PENALTY_BETA = 1.0f

    /** α weights AUDIO in the fused retrieval pool. */
    const val FUSED_TEXT_ALPHA = 0.4f

    const val CHAIN_ARTIST_SPACING = 3
    const val CHAIN_ARTIST_QUEUE_CAP = 3

    const val ENERGY_DEADBAND = 0.2f
    const val ENERGY_FLOOR = 0.7f

    const val MULTIPLIER_MIN = 0.05f
    const val MULTIPLIER_MAX = 2.0f
}

/**
 * A built queue plus the candidate pool it was drawn from.
 *
 * @param rows snapshot rows to play, in order
 * @param pool the rows considered, in retrieval order — kept for diagnostics and parity checks
 */
internal data class ChainResult(val rows: List<Int>, val pool: List<Int>) {
    companion object {
        val EMPTY = ChainResult(emptyList(), emptyList())
    }
}

/**
 * Builds a SMART queue: a coherent walk through the library starting from the user's pick.
 *
 * The shape of the score is the whole design. The learned scorer is squashed into a bounded band
 * because raw logits span ±5 while the cosine terms are bounded by ~±3 — unbounded, the model's
 * taste vote simply outshouted the user's explicit choice. Around it sit local coherence (toward
 * the previous pick), seed gravity (toward the original pick, for the whole walk), the pool-relative
 * semantic z-terms that keep niche clusters intact, and metadata multipliers applied in log space
 * so a neutral verdict contributes exactly zero.
 *
 * The runtime is optional: without it the chain still walks on its geometric terms alone, which is
 * how iOS and any pre-download state behave.
 */
internal class SmartChain(
    private val snapshot: SmartSnapshot,
    private val runtime: PredictorRuntime?,
) {

    /**
     * @param seedId the track the user picked
     * @param length how many tracks to queue after the seed
     * @param timeFeatures from [PredictorRuntime.timeFeatures] on the real clock
     */
    fun build(
        seedId: TrackId,
        length: Int,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray = PredictorRuntime.SESSION_FEATURES_COLD,
    ): ChainResult {
        val seedRow = snapshot.rowOf(seedId)
        if (seedRow < 0 || length <= 0) return ChainResult.EMPTY

        val dim = PredictorRuntime.STATE_DIM
        val tokenDim = PredictorRuntime.TOKEN_DIM

        // Cold start: training replicated the most recent track into every history slot when a
        // session held one event. Leaving the other slots at zero is far outside that distribution
        // and collapses the encoder onto popularity priors regardless of the seed.
        val history = FloatArray(PredictorRuntime.CONTEXT_K * tokenDim)
        for (k in 0 until PredictorRuntime.CONTEXT_K) {
            val offset = k * tokenDim
            snapshot.rawAudio.copyInto(history, offset, seedRow * dim, (seedRow + 1) * dim)
            history[offset + dim] = 1f
        }

        var live = runtime != null
        var state = if (live) {
            runCatching { encode(history, timeFeatures, sessionFeatures) }
                .onFailure { live = false }
                .getOrDefault(FloatArray(0))
        } else {
            FloatArray(0)
        }

        val pool = buildPool(seedRow, state)
        if (pool.isEmpty()) return ChainResult.EMPTY
        val poolRows = pool.toIntArray()

        // Short pools stay zero-padded: the scorer graph has the pool size baked in.
        val candidates = FloatArray(PredictorRuntime.POOL_SIZE * dim)
        for (i in pool.indices) {
            if (i >= PredictorRuntime.POOL_SIZE) break
            snapshot.rawAudio.copyInto(candidates, i * dim, pool[i] * dim, (pool[i] + 1) * dim)
        }

        // The seed term is fixed for the walk; the prev term is recomputed each hop.
        val zSeed = semanticZ(seedRow, poolRows)
        val noLogits = FloatArray(PredictorRuntime.POOL_SIZE)

        val chain = ArrayList<Int>(length)
        val used = HashSet<Int>()
        var anchorRow = seedRow
        val recentArtists = ArrayDeque<String>()
        recentArtists.addLast(snapshot.tracks[seedRow].meta.artistKey)
        val seenTitles = HashSet<String>()
        seenTitles.add(snapshot.tracks[seedRow].meta.normalizedTitle)
        val artistPlays = HashMap<String, Int>()

        while (chain.size < length) {
            val logits = if (live) {
                runCatching { runtime!!.score(state, candidates) }
                    .onFailure { live = false }
                    .getOrDefault(noLogits)
            } else {
                noLogits
            }
            val zPrev = semanticZ(anchorRow, poolRows)
            val anchorMeta = snapshot.tracks[anchorRow].meta

            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in pool.indices) {
                if (i in used) continue
                val row = pool[i]
                val meta = snapshot.tracks[row].meta
                if (meta.artistKey in recentArtists) continue
                if (meta.normalizedTitle in seenTitles) continue
                if ((artistPlays[meta.artistKey] ?: 0) >= ChainConfig.CHAIN_ARTIST_QUEUE_CAP) continue

                var score = ChainConfig.SCORER_SQUASH * tanh(logits[i] / ChainConfig.SCORER_TEMP)
                score += ChainConfig.COSINE_BLEND_WEIGHT * snapshot.centeredCosine(anchorRow, row)
                score += ChainConfig.CHAIN_SEED_GRAVITY * snapshot.centeredCosine(seedRow, row)
                score += ChainConfig.SEM_CHAIN_SEED_GRAVITY * zSeed[i] +
                    ChainConfig.SEM_CHAIN_PREV_BLEND * zPrev[i]

                var multiplier = MetadataRerank.adjustMultiplier(anchorMeta, meta)
                    .coerceIn(ChainConfig.MULTIPLIER_MIN, ChainConfig.MULTIPLIER_MAX)
                // The raw-cosine pool carries no CSLS correction, so the dense cinematic/game/anime
                // cluster leaks into chains from sparse seeds unless damped here.
                if (meta.isHub && !anchorMeta.isHub) {
                    multiplier = (multiplier * ChainConfig.HUB_CHAIN_DAMP)
                        .coerceAtLeast(ChainConfig.MULTIPLIER_MIN)
                }
                multiplier = (multiplier * energySmoothness(anchorRow, row))
                    .coerceAtLeast(ChainConfig.MULTIPLIER_MIN)
                score += ln(multiplier)

                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break

            val pickedRow = pool[bestIndex]
            chain.add(pickedRow)
            used.add(bestIndex)
            val pickedMeta = snapshot.tracks[pickedRow].meta
            seenTitles.add(pickedMeta.normalizedTitle)
            artistPlays[pickedMeta.artistKey] = (artistPlays[pickedMeta.artistKey] ?: 0) + 1
            recentArtists.addLast(pickedMeta.artistKey)
            while (recentArtists.size > ChainConfig.CHAIN_ARTIST_SPACING) recentArtists.removeFirst()
            anchorRow = pickedRow

            // The final pick needs no state advance — nothing consumes it, and the encoder is the
            // chain's dominant cost.
            if (chain.size >= length || !live) continue

            history.copyInto(history, 0, tokenDim, PredictorRuntime.CONTEXT_K * tokenDim)
            val offset = (PredictorRuntime.CONTEXT_K - 1) * tokenDim
            snapshot.rawAudio.copyInto(history, offset, pickedRow * dim, (pickedRow + 1) * dim)
            history[offset + dim] = 1f
            state = runCatching { encode(history, timeFeatures, sessionFeatures) }
                .onFailure { live = false }
                .getOrDefault(state)
        }
        return ChainResult(chain, pool)
    }

    /**
     * Medium- and long-term taste both read the NEWEST history slot.
     *
     * At cold start every slot holds the seed, so all three inputs agree; from the first hop on they
     * track the walk. Substituting the seed there instead would pin the encoder to the starting
     * track and flatten the state's contribution across the rest of the chain.
     */
    private fun encode(
        history: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray {
        val dim = PredictorRuntime.STATE_DIM
        val newest = (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM
        val recent = history.copyOfRange(newest, newest + dim)
        return runtime!!.encodeState(history, recent, recent, timeFeatures, sessionFeatures)
    }

    /**
     * Candidate generation: interleave two rankings, anchor first.
     *
     * The anchor ranking is centered cosine to the seed minus the hub penalty (what sounds like the
     * pick, with hubs suppressed). The state ranking fuses audio and metadata-text cosine against
     * the encoded state (what fits the session). Interleaving keeps both channels represented
     * instead of letting whichever is better calibrated dominate.
     */
    private fun buildPool(seedRow: Int, state: FloatArray): List<Int> {
        val n = snapshot.size
        val dim = PredictorRuntime.STATE_DIM
        val anchorScores = FloatArray(n) { row ->
            snapshot.centeredCosine(seedRow, row) -
                ChainConfig.HUB_PENALTY_BETA * snapshot.hubPenalty[row]
        }

        val stateScores = FloatArray(n)
        if (state.size >= dim) {
            val query = normalized(state, dim)
            // Retrieval fuses RAW cosines; the centered space is the chain's, not the pool's.
            val textDim = SmartSnapshot.TEXT_DIM
            val rawText = snapshot.rawText
            val seedText = if (rawText != null && snapshot.hasText?.get(seedRow) == true) {
                rawText.copyOfRange(seedRow * textDim, (seedRow + 1) * textDim)
            } else {
                null
            }
            for (row in 0 until n) {
                var audio = 0f
                val base = row * dim
                for (d in 0 until dim) audio += snapshot.rawAudio[base + d] * query[d]
                stateScores[row] = if (seedText != null && snapshot.hasText?.get(row) == true) {
                    var text = 0f
                    val textBase = row * textDim
                    for (d in 0 until textDim) text += rawText!![textBase + d] * seedText[d]
                    ChainConfig.FUSED_TEXT_ALPHA * audio + (1f - ChainConfig.FUSED_TEXT_ALPHA) * text
                } else {
                    audio
                }
            }
        } else {
            anchorScores.copyInto(stateScores)
        }

        val anchorOrder = order(anchorScores, seedRow)
        val stateOrder = order(stateScores, seedRow)
        val pool = ArrayList<Int>(PredictorRuntime.POOL_SIZE)
        val seen = HashSet<Int>()
        var i = 0
        while (pool.size < PredictorRuntime.POOL_SIZE && i < anchorOrder.size) {
            if (seen.add(anchorOrder[i])) pool.add(anchorOrder[i])
            if (pool.size < PredictorRuntime.POOL_SIZE && seen.add(stateOrder[i])) pool.add(stateOrder[i])
            i++
        }
        return pool
    }

    private fun semanticZ(refRow: Int, poolRows: IntArray): FloatArray {
        val zDescriptor = SemanticZ.poolZ(
            snapshot.centeredDescriptor, snapshot.hasDescriptor,
            SmartSnapshot.DESCRIPTOR_DIM, refRow, poolRows,
        )
        val zText = SemanticZ.poolZ(
            snapshot.centeredText, snapshot.hasText,
            SmartSnapshot.TEXT_DIM, refRow, poolRows,
        )
        return SemanticZ.combine(zDescriptor, zText, poolRows.size)
    }

    /**
     * Energy smoothness: penalise a large jump from the previous track, so a mellow→slammer
     * whiplash needs a real score advantage. The one audio feature worth sequencing on when cuts
     * are hard (BPM and key only matter with crossfades).
     */
    private fun energySmoothness(anchorRow: Int, candidateRow: Int): Float {
        val previous = snapshot.tracks[anchorRow].energy
        val candidate = snapshot.tracks[candidateRow].energy
        if (previous.isNaN() || candidate.isNaN()) return 1f
        val over = abs(previous - candidate) - ChainConfig.ENERGY_DEADBAND
        return if (over <= 0f) 1f else (1f - over).coerceAtLeast(ChainConfig.ENERGY_FLOOR)
    }

    private fun order(scores: FloatArray, exclude: Int): IntArray =
        scores.indices.filter { it != exclude }
            .sortedByDescending { scores[it] }
            .toIntArray()

    private fun normalized(v: FloatArray, dim: Int): FloatArray {
        var sumSq = 0.0
        for (i in 0 until dim) sumSq += v[i].toDouble() * v[i]
        val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(dim) { v[it] * scale }
    }
}

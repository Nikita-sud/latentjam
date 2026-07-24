/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt

/**
 * Pure tensor-packing helpers for the semantics-aware scorer (`predictor_scorer_n100.onnx`,
 * `scoring-semtext-v1`).
 *
 * The scorer's interface is 1344-wide on both inputs:
 * - `state[1, 1344]` = `[960-d encoder state ⊕ unit-normalized session-text-centroid 384]`
 * - `candidates[1, 100, 1344]` = `[960-d raw (L2-normalized) audio ⊕ unit-normalized MiniLM text
 *   384]`, with the 384 text block ZERO-FILLED wherever the track has no current text vector.
 *
 * The model was trained with text-dropout, so an all-zero text side (no text vectors present, or a
 * null snapshot text matrix) degrades gracefully to ~the old audio-only scorer rather than crashing
 * or producing garbage. These helpers keep that invariant in one testable place, matching the
 * offline harness's `_text_centroid` + `sem_logits` packing exactly.
 */
public object ScorerPacking {
    /** Audio half-width — the raw predictor embedding dim ([PredictorRuntime.EMBEDDING_DIM]). */
    public const val AUDIO_DIM: Int = 960

    /** Text half-width — the MiniLM metadata-text dim ([SmartSnapshot.TEXT_DIM]). */
    public const val TEXT_DIM: Int = 384

    /** Combined per-token width fed to the scorer graph. */
    public const val INPUT_DIM: Int = AUDIO_DIM + TEXT_DIM

    /**
     * Fill [out] (`scorerN × INPUT_DIM`, reused across chain hops) with the candidate block for the
     * pool rows in [poolRows]. Each pool slot `i` gets `[audioFlat[row] ⊕ text]`, where `text` is
     * the unit-norm text vector from [textFlat] when the row has one and [textFlat] is non-null,
     * else the 384 block stays zero (the trained text-dropout path). Pool slots beyond
     * `poolRows.size` (the padded tail of the fixed-100 tensor) are left fully zero.
     *
     * [audioFlat] is the snapshot's `N × AUDIO_DIM` raw matrix and [textFlat] its `N × TEXT_DIM`
     * text matrix (or null); [poolRows] holds snapshot row indices, one per pool position.
     */
    public fun packCandidates(
        audioFlat: FloatArray,
        textFlat: FloatArray?,
        hasText: BooleanArray,
        poolRows: IntArray,
        scorerN: Int,
        out: FloatArray,
    ) {
        require(out.size == scorerN * INPUT_DIM) {
            "candidate block must be scorerN×INPUT_DIM, got ${out.size}"
        }
        require(poolRows.size <= scorerN) {
            "poolRows (${poolRows.size}) may not exceed scorerN ($scorerN)"
        }
        out.fill(0f)
        for (i in poolRows.indices) {
            val row = poolRows[i]
            val dst = i * INPUT_DIM
            audioFlat.copyInto(out, dst, row * AUDIO_DIM, (row + 1) * AUDIO_DIM)
            if (textFlat != null && hasText[row]) {
                textFlat.copyInto(out, dst + AUDIO_DIM, row * TEXT_DIM, (row + 1) * TEXT_DIM)
            }
            // else: text half stays zero — text-dropout degradation.
        }
    }

    /**
     * Unit-normalized mean of the text vectors of the context tracks in [rows] — the session-text
     * centroid concatenated onto the state. Rows are de-duplicated (a repeated context track counts
     * once, matching the harness's `dict.fromkeys`), rows `< 0` (no track / padding) are skipped,
     * and rows without a current text vector are dropped. Returns a 384-d zero vector when
     * [textFlat] is null or no context row has text (the trained empty-context path).
     */
    public fun textCentroid(textFlat: FloatArray?, hasText: BooleanArray, rows: IntArray): FloatArray {
        val out = FloatArray(TEXT_DIM)
        if (textFlat == null) return out
        val seen = LinkedHashSet<Int>()
        var count = 0
        for (r in rows) {
            if (r < 0 || !hasText[r]) continue
            if (!seen.add(r)) continue
            val base = r * TEXT_DIM
            for (d in 0 until TEXT_DIM) out[d] += textFlat[base + d]
            count++
        }
        if (count == 0) return out
        val invCount = 1f / count
        var sumSq = 0.0
        for (d in 0 until TEXT_DIM) {
            out[d] *= invCount
            sumSq += out[d].toDouble() * out[d].toDouble()
        }
        val norm = sqrt(sumSq).toFloat()
        // Match the harness's 1e-9 guard: a degenerate (near-zero) mean is left un-normalized
        // rather than blown up.
        if (norm > 1e-9f) {
            val inv = 1f / norm
            for (d in 0 until TEXT_DIM) out[d] *= inv
        }
        return out
    }

    /**
     * Concatenate a 960-d encoder [state] with a 384-d [centroid] into the 1344-d scorer state
     * tensor. [centroid] is expected already unit-normalized (see [textCentroid]).
     */
    public fun packState(state: FloatArray, centroid: FloatArray): FloatArray {
        require(state.size == AUDIO_DIM) { "state must be AUDIO_DIM, got ${state.size}" }
        require(centroid.size == TEXT_DIM) { "centroid must be TEXT_DIM, got ${centroid.size}" }
        val out = FloatArray(INPUT_DIM)
        state.copyInto(out, 0, 0, AUDIO_DIM)
        centroid.copyInto(out, AUDIO_DIM, 0, TEXT_DIM)
        return out
    }
}

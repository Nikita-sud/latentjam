/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import org.koin.core.module.Module

/**
 * The two-stage learned model behind the SMART chain.
 *
 * Stage one encodes recent listening into a state vector; stage two scores a fixed block of
 * candidates against it. Splitting them is what makes the chain affordable: the state is re-encoded
 * once per hop, but scoring a hundred candidates is a single batched call rather than a hundred.
 *
 * ### Tensor contract (fixed by the research-side export; do not drift)
 * State encoder:
 * - `history_small`  `[1, 4, 961]` — the last four plays, each 960-d audio embedding plus a trailing
 *   `played_pct` in `[0, 1]`
 * - `history_medium` `[1, 960]` — 30-day recency-weighted centroid
 * - `history_large`  `[1, 960]` — 365-day frequency-weighted centroid
 * - `time_features`  `[1, 5]` — see [timeFeatures]
 * - `session_features` `[1, 5]` — see [SESSION_FEATURES_COLD]
 * - output `[1, 960]`
 *
 * Scorer:
 * - `state` `[1, 960]`, `candidates` `[1, 100, 960]` → output `[1, 100]` raw logits
 *
 * Both take audio embeddings in their RAW (uncentered, unit-norm) form — the centered space is the
 * chain's own construction and the models never saw it.
 *
 * Threading: not thread-safe. The engine serializes all access on its single-parallelism dispatcher.
 */
public interface PredictorRuntime {

    /** Trailing `played_pct` slot makes a history token 961 wide. */
    public val tokenDim: Int get() = TOKEN_DIM

    /** Loads both graphs. Idempotent; heavy (tens of MB). */
    public suspend fun load(): Result<Unit>

    /**
     * @param historySmall [CONTEXT_K] × [tokenDim], flattened, oldest first
     * @return the `[960]` state vector
     */
    public fun encodeState(
        historySmall: FloatArray,
        historyMedium: FloatArray,
        historyLarge: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray

    /**
     * @param candidates [POOL_SIZE] × [STATE_DIM], flattened; short pools are zero-padded
     * @return [POOL_SIZE] raw logits
     */
    public fun score(state: FloatArray, candidates: FloatArray): FloatArray

    /** Releases native resources. Idempotent. */
    public fun close()

    public companion object {
        public const val CONTEXT_K: Int = 4
        public const val STATE_DIM: Int = 960
        public const val TOKEN_DIM: Int = STATE_DIM + 1
        public const val POOL_SIZE: Int = 100

        /**
         * Cold-start session features: `[ln(position + 1), skipRate, ln(elapsedMinutes + 1),
         * completedCount, meanPlayedPct]` for a session holding exactly the seed — second track,
         * nothing skipped, ~30 s elapsed, one clean completion.
         *
         * Zeroing these instead would put the encoder far outside its training distribution, where
         * it collapses onto popularity priors and ignores the seed entirely.
         */
        public val SESSION_FEATURES_COLD: FloatArray =
            floatArrayOf(ln(2f), 0f, ln(1.5f), 1f, 1f)

        /**
         * `[sin/cos of hour-of-day, sin/cos of day-of-week, isWeekend]`.
         *
         * @param hourOfDay 0..23
         * @param dayOfWeek Monday = 0 … Sunday = 6, matching the pandas convention used in training
         */
        public fun timeFeatures(hourOfDay: Int, dayOfWeek: Int): FloatArray {
            val twoPi = 2.0 * PI
            return floatArrayOf(
                sin(twoPi * hourOfDay / 24.0).toFloat(),
                cos(twoPi * hourOfDay / 24.0).toFloat(),
                sin(twoPi * dayOfWeek / 7.0).toFloat(),
                cos(twoPi * dayOfWeek / 7.0).toFloat(),
                if (dayOfWeek >= 5) 1f else 0f,
            )
        }
    }
}

/**
 * Koin bindings for this platform's [PredictorRuntime]. Android resolves an
 * `android.content.Context` from the graph for asset access; iOS has no extra requirements.
 */
public expect fun smartPredictorModule(): Module

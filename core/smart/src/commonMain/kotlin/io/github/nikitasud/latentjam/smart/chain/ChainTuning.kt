/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

/**
 * The chain's tunable terms, defaulting to the shipped constants.
 *
 * One place instead of scattered literals so the offline simulation can sweep candidate values
 * over a real exported library BEFORE a constant changes; production call sites never pass this
 * and always get the shipped behavior. Values here change only together with harness evidence.
 */
internal data class ChainTuning(
    /**
     * Experimental continuation policy for unmarked queues. Refill from the whole library and
     * leave the current neighborhood only when no eligible close tracks remain. Marked groups
     * retain the existing quota policy. Default stays off until the continuity benchmark passes.
     */
    val continueAfterExhaustion: Boolean = false,
    /**
     * How the continuation mode holds a queue to its neighbourhood. Infinity (the default) admits
     * only neighbourhood tracks while any remain. A finite value builds each hop's pool from every
     * channel around the reference, as the default chain does, and adds this many score units to
     * the neighbourhood's tracks: what the scorer and the descriptors know about what goes together
     * can then outbid sound alone. EXPERIMENTAL.
     */
    val neighbourhoodBonus: Float = Float.POSITIVE_INFINITY,
    /**
     * Scales the chain's semantic terms (descriptor and text gravity toward the reference and the
     * previous pick) against its sound terms; 1 is the shipped balance. EXPERIMENTAL.
     */
    val semanticWeight: Float = 1f,
    /**
     * The descriptor's share in the continuation mode's neighbourhood test, fused = (1 - w) audio +
     * w descriptor; 0.5 is the shipped equal weighting ([Reanchor.fusedCos]). EXPERIMENTAL.
     */
    val neighbourhoodDescriptorWeight: Float = 0.5f,
    /**
     * Rings: once the neighbourhood is spent, the continuation mode lowers the closeness threshold
     * around the same reference in steps of this size, down to [ringFloor], before it moves the
     * reference to the latest pick. The walk then reaches the nearest remaining style first, instead
     * of wherever its last pick leads. 0 keeps the move at once. EXPERIMENTAL.
     */
    val ringStep: Float = 0f,
    /** The widest ring, as a fused cosine to the reference; see [ringStep]. */
    val ringFloor: Float = 0.20f,
    /**
     * Seed gravity inside a widened ring, as a share of the usual: the ring already keeps the walk
     * near its reference, so a lower value lets the previous pick choose among the ring's tracks
     * and keeps the step smooth. 1 is the usual pull. EXPERIMENTAL.
     */
    val ringSeedPull: Float = 1f,
    /**
     * Style gate: a candidate whose artist descriptor is less similar than this (centred cosine) to
     * the previous pick's is passed over while the pool still holds one that is not, so a queue does
     * not jump genre, era or scene from one track to the next. Candidates without a descriptor pass.
     * Negative infinity is off. EXPERIMENTAL.
     */
    val styleGate: Float = Float.NEGATIVE_INFINITY,
    /**
     * Sound floor: a candidate whose audio (centred cosine) is less similar than this to the previous
     * pick's is passed over while the pool still holds one that is not, so a queue does not change
     * sound abruptly from one track to the next. Negative infinity is off. EXPERIMENTAL.
     */
    val soundFloor: Float = Float.NEGATIVE_INFINITY,
    /**
     * Runs of one artist: when the queue ends with k tracks in a row by one artist (the seed counts),
     * that artist's next candidate loses k times this many standard deviations of the hop's candidate
     * scores. Soft by design: a track that fits clearly better than every other artist's still
     * plays, so an artist with the only close tracks keeps them; each further one in a row must fit
     * better by more. JourneySequencer takes the same value for the order. 0 is off. EXPERIMENTAL.
     */
    val artistRunPenalty: Float = 0f,
    /**
     * A learned correction added to every candidate's score: one weight per [Rerank] feature, in
     * score units. Null is off. EXPERIMENTAL.
     */
    val rerankWeights: FloatArray? = null,
    /** See [ChainConfig.COMPANION_BONUS]. */
    val companionBonus: Float = ChainConfig.COMPANION_BONUS,
    /**
     * How far (in score units, where 1.0 cosine ~ 3.0) a marked group's champion may trail the
     * hop's best candidate and still take a guaranteed quota turn. Infinity reproduces the
     * unconditional quota.
     */
    val quotaMargin: Float = ChainConfig.COMPANION_QUOTA_MARGIN,
    /** Whether extreme track durations are damped; the fixture's rows have no durations. */
    val durationSanity: Boolean = true,
    /**
     * EXPERIMENTAL personal-preference term: `score += personalWeight * personalAffinity(row)`.
     * Null and 0 by default, so shipped queues and the recorded parity replays are
     * byte-identical without it. It exists for the offline personalization simulation —
     * enabling it in production is a product decision against SMART's objectivity principle,
     * not a tuning tweak, and must carry its own harness evidence.
     */
    val personalAffinity: ((Int) -> Float)? = null,
    val personalWeight: Float = 0f,
)

/**
 * Damping for tracks that are poor queue citizens regardless of sound: second-long jingles and
 * multi-movement suites. Log-space multiplier like the metadata verdicts — unknown stays neutral.
 */
internal fun durationSanityMultiplier(durationMs: Long?): Float {
    if (durationMs == null || durationMs <= 0) return 1f
    return when {
        durationMs < 60_000 -> 0.75f
        durationMs < 90_000 -> 0.9f
        durationMs > 12 * 60_000 -> 0.7f
        durationMs > 8 * 60_000 -> 0.85f
        else -> 1f
    }
}

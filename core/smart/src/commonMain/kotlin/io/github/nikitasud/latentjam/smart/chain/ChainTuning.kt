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

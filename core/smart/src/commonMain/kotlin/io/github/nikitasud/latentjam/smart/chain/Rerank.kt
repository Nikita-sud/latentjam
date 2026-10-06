/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

/**
 * A learned correction to the chain's score: a weighted sum of what the chain already knows about a
 * candidate at a hop, fitted offline to a listening judge's choices between candidates
 * ([ChainTuning.rerankWeights]). The same features go into the pick trace, so the weights are fitted
 * on exactly the numbers the chain computes.
 */
internal object Rerank {
    /** Audio cosine to the previous pick. */
    const val AUDIO_PREVIOUS = 0
    /** Audio cosine to the gravity reference (the seed, the walk's intent, or the re-anchored seed). */
    const val AUDIO_REFERENCE = 1
    /** Audio cosine to the original pick. */
    const val AUDIO_SEED = 2
    /** Artist-descriptor cosines to the previous pick, the reference row and the original pick (0 when absent). */
    const val STYLE_PREVIOUS = 3
    const val STYLE_REFERENCE = 4
    const val STYLE_SEED = 5
    /** Text cosines to the previous pick and the original pick (0 when absent). */
    const val TEXT_PREVIOUS = 6
    const val TEXT_SEED = 7
    /** The scorer's bounded vote, as it enters the score. */
    const val SCORER = 8
    /** 1 when the candidate's artist is the previous pick's. */
    const val SAME_ARTIST = 9
    /** 1 when the candidate and the original pick both carry a descriptor. */
    const val STYLE_KNOWN = 10
    /** 1 when the candidate is in the continuation mode's neighbourhood of the reference. */
    const val IN_NEIGHBOURHOOD = 11
    const val FEATURES = 12

    fun features(
        snapshot: SmartSnapshot,
        out: FloatArray,
        row: Int,
        previousRow: Int,
        referenceRow: Int,
        seedRow: Int,
        audioPrevious: Float,
        audioReference: Float,
        scorerTerm: Float,
        sameArtist: Boolean,
        inNeighbourhood: Boolean,
    ) {
        out[AUDIO_PREVIOUS] = audioPrevious
        out[AUDIO_REFERENCE] = audioReference
        out[AUDIO_SEED] = snapshot.centeredCosine(seedRow, row)
        out[STYLE_PREVIOUS] = snapshot.descriptorCosine(previousRow, row) ?: 0f
        out[STYLE_REFERENCE] = snapshot.descriptorCosine(referenceRow, row) ?: 0f
        val styleSeed = snapshot.descriptorCosine(seedRow, row)
        out[STYLE_SEED] = styleSeed ?: 0f
        out[TEXT_PREVIOUS] = snapshot.textCosine(previousRow, row) ?: 0f
        out[TEXT_SEED] = snapshot.textCosine(seedRow, row) ?: 0f
        out[SCORER] = scorerTerm
        out[SAME_ARTIST] = if (sameArtist) 1f else 0f
        out[STYLE_KNOWN] = if (styleSeed != null) 1f else 0f
        out[IN_NEIGHBOURHOOD] = if (inNeighbourhood) 1f else 0f
    }

    /** The correction in score units; [weights] has one entry per feature. */
    fun term(weights: FloatArray, features: FloatArray): Float {
        var sum = 0f
        for (k in 0 until FEATURES) sum += weights[k] * features[k]
        return sum
    }
}

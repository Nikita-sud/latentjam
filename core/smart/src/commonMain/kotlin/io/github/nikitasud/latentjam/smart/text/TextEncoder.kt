/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import org.koin.core.module.Module

/**
 * The metadata half of fused retrieval: `all-MiniLM-L6-v2` over a track's tags, giving a 384-d
 * sentence vector alongside the audio embedding.
 *
 * This runs entirely on the phone — no LLM, no network — so an imported track is self-contained the
 * moment it is scanned. Fusing it into candidate generation roughly doubled pool recall over
 * audio-only in offline evaluation, because tags carry what audio cannot: language, era, and the
 * difference between two songs that merely sound alike.
 *
 * Pipeline, matching sentence-transformers: WordPiece tokenize → transformer forward → mean-pool
 * over tokens → L2-normalise.
 */
public interface TextEncoder {

    /** Loads the model and vocabulary. Idempotent; ~23 MB of weights. */
    public suspend fun load(): Result<Unit>

    /**
     * @return a 384-d unit vector, or null for blank input or a failed run — callers drop the text
     *   vector for that track and fall back to audio-only rather than substituting zeros.
     */
    public fun encode(metadata: String): FloatArray?

    /** Releases native resources. Idempotent. */
    public fun close()

    public companion object {
        public const val TEXT_DIM: Int = 384

        /**
         * The string the encoder embeds: `"genre; artist; title; year"`, blanks dropped.
         *
         * Field order and separator are part of the model contract, not a formatting choice — the
         * measured retrieval win is specific to this arrangement, and vectors built any other way
         * are not comparable with the ones already stored.
         */
        public fun metadataString(
            genre: String?,
            artist: String?,
            title: String?,
            year: Int?,
        ): String = listOfNotNull(
            genre?.takeIf { it.isNotBlank() },
            artist?.takeIf { it.isNotBlank() },
            title?.takeIf { it.isNotBlank() },
            year?.takeIf { it > 0 }?.toString(),
        ).joinToString("; ")
    }
}

/** Koin bindings for this platform's [TextEncoder]. */
public expect fun smartTextEncoderModule(): Module

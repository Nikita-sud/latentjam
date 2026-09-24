/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import org.koin.core.module.Module

/**
 * Optional scorer conditioning: a 384-d sentence vector over trusted track tags, alongside the
 * 960-d audio embedding.
 *
 * The encoder is a three-layer, 256-wide BERT (5.2 MB as INT8) that LatentJam distilled from
 * `all-MiniLM-L6-v2` on music strings — artist names, trusted tag strings and search phrases,
 * including phrases translated into twenty languages — so its vectors live in MiniLM's space: the
 * scorer, the artist adapter and search, all fitted there, read them unchanged. It has its own
 * WordPiece vocabulary, and it saw at most [MAX_TOKENS] tokens in training.
 *
 * This runs entirely on the phone — no LLM, no network — so an imported track is self-contained the
 * moment it is scanned. Candidate retrieval interleaves audio and text rankings without a numeric
 * cross-modal weight; the scorer's learned, bounded branch decides how much metadata should affect
 * ordering. A missing vector is an exact audio-only fallback.
 *
 * Pipeline, matching sentence-transformers: WordPiece tokenize → transformer forward → mean-pool
 * over tokens → L2-normalise.
 */
public interface TextEncoder {

    /** Loads the model and vocabulary. Idempotent; ~5 MB of weights. */
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

        /** Input length, `[CLS]` and `[SEP]` included: the longest the encoder was trained on. */
        public const val MAX_TOKENS: Int = 48

        /**
         * The trusted string the encoder embeds: `"genre; artist; year; language"`, blanks dropped.
         * The year is the recording's original year when the tags know it, otherwise the edition
         * year the scanner reported; the language word is [TextLanguage]'s verdict from the tag, the
         * knowledge pack's [artistLanguage] or the script. The title enters only through that script
         * check, never as text: a filename such as `Hard Techno Mix` remains incapable of putting a
         * genre into this channel.
         *
         * A track without any year ends in the pack's [artistDecade] instead (`"; 1980s"`), which
         * raised P@10 on the year-less MPD libraries by 0.3–2.7 pp.
         *
         * Field order and separator are part of the model contract, not a formatting choice — the
         * measured retrieval win is specific to this arrangement, and vectors built any other way
         * are not comparable with the ones already stored. Extending the string re-encodes every
         * stored vector through the engine's text identity version.
         */
        public fun metadataString(
            genre: String?,
            artist: String?,
            title: String?,
            year: Int?,
            originalYear: Int? = null,
            language: String? = null,
            artistLanguage: String? = null,
            artistDecade: Int? = null,
        ): String {
            val knownYear = originalYear?.takeIf { it > 0 } ?: year?.takeIf { it > 0 }
            return listOfNotNull(
                genre?.takeIf { it.isNotBlank() },
                artist?.takeIf { it.isNotBlank() },
                knownYear?.toString(),
                TextLanguage.word(language, title, artist, artistLanguage),
                artistDecade?.takeIf { knownYear == null }?.let { "${it}s" },
            ).joinToString("; ")
        }
    }
}

/** Koin bindings for this platform's [TextEncoder]. */
public expect fun smartTextEncoderModule(): Module

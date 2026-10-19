/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * Artist popularity by entity of the music entity index: ln(1 + distinct listeners) in public listening histories
 * (ListenBrainz FIT, CC0), counted offline (tools/research/listenbrainz_eval/serving/artist_pop.py); artists with too
 * few listeners are absent (unknown). The ranker's logpop column reads it when the model was trained on it
 * (`inputs.logpop = artist_pop`).
 *
 * Layout: [ArtistVectors]'s, magic "LJPOPS1\0", dimension 1, float32.
 */
public class ArtistPopularity private constructor(private val table: ArtistVectors) {
    /** Whether [entity] has a value. */
    public fun has(entity: Int): Boolean = table.has(entity)

    /** ln(1 + listeners) of [entity], or null when unknown. */
    public fun value(entity: Int): Double? = table.vector(entity)?.get(0)?.toDouble()

    public companion object {
        private val MAGIC = "LJPOPS1\u0000".encodeToByteArray()

        public fun parse(bytes: ByteArray): ArtistPopularity? = parse(AssetBytes.of(bytes))

        /** Null for a missing, damaged or newer-format asset: the column then reads 0 (unknown). */
        public fun parse(bytes: AssetBytes): ArtistPopularity? =
            ArtistVectors.parse(bytes, MAGIC)?.takeIf { it.dimension == 1 }?.let(::ArtistPopularity)
    }
}

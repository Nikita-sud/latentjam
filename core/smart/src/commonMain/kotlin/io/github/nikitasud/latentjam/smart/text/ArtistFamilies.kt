/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * The genre family of each knowledge-pack entity (tools/research/listenbrainz_eval/serving/artist_family.py: the
 * family of the first genre of the entity's pack attributes, by Genres' aliases). PLAN_FINAL §9: a track's family is
 * its pack entity's when the entity resolves, else its own genre tag's — the rule the final ranker was trained with.
 *
 * Layout: [ArtistVectors]'s, magic "LJFAMS1\0", dimension 1, float32 codes into [CODES].
 */
public class ArtistFamilies private constructor(private val table: ArtistVectors) {
    /** [entity]'s family, or null when it has none. */
    public fun family(entity: Int): String? {
        val code = table.vector(entity)?.get(0) ?: return null
        val i = code.toInt()
        return if (i.toFloat() == code && i in CODES.indices) CODES[i] else null
    }

    public companion object {
        /** The families Genres.aliasFamily names, in code order. */
        public val CODES: List<String> = listOf("classical", "dance", "pop", "rap", "rock", "soundtrack")
        private val MAGIC = "LJFAMS1\u0000".encodeToByteArray()

        public fun parse(bytes: ByteArray): ArtistFamilies? = parse(AssetBytes.of(bytes))

        /** Null for a missing, damaged or newer-format asset: families then come from the genre tags alone. */
        public fun parse(bytes: AssetBytes): ArtistFamilies? =
            ArtistVectors.parse(bytes, MAGIC)?.takeIf { it.dimension == 1 }?.let(::ArtistFamilies)
    }
}

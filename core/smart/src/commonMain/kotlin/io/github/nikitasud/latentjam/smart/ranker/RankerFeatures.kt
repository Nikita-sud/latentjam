/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the ranker knows of one track, relative to the hop's anchor where it says so.
 *
 * @property cosine artist-descriptor cosine to the anchor; NaN when either has no pack descriptor
 * @property family genre family, null when unknown
 * @property language pack language, null when unknown
 * @property decade pack decade (1990 for the 1990s), null when unknown
 * @property artistKey engine artist key; empty is no artist
 * @property colisten the anchor artist's co-listening similarity to this track's artist, 0 when unlisted
 * @property logPopularity ln(1 + listeners); 0 when the app has no popularity table
 */
public class RankerRow(
    public val cosine: Double = Double.NaN,
    public val family: String? = null,
    public val language: String? = null,
    public val decade: Int? = null,
    public val artistKey: String = "",
    public val colisten: Double = 0.0,
    public val logPopularity: Double = 0.0,
)

/**
 * The CORE features of tools/research/listenbrainz_eval/proto_ranker.py (`lib_features`), per
 * candidate, relative to the anchor; no listening history. RankerGoldenTest pins them
 * against that script's own rows.
 */
public object RankerFeatures {
    public val NAMES: List<String> = listOf(
        "cos", "cos_z", "desc_known", "same_family", "family_known", "same_lang", "lang_known", "same_artist",
        "decade_dist", "decade_known", "logpop", "logpop_z", "colisten",
    )

    /** One row per candidate, in [NAMES] order. */
    public fun of(anchor: RankerRow, candidates: List<RankerRow>): Array<DoubleArray> {
        val n = candidates.size
        val known = BooleanArray(n) { !candidates[it].cosine.isNaN() }
        // cos_z: unknown cosines take the known mean, the whole pool is z-scored, unknown rows read 0.
        var knownSum = 0.0
        var knownCount = 0
        for (i in 0 until n) if (known[i]) { knownSum += candidates[i].cosine; knownCount++ }
        val fill = if (knownCount > 0) knownSum / knownCount else 0.0
        val cosZ = zScores(DoubleArray(n) { if (known[it]) candidates[it].cosine else fill })
        val popZ = zScores(DoubleArray(n) { candidates[it].logPopularity })
        return Array(n) { i ->
            val c = candidates[i]
            val familyKnown = anchor.family != null && c.family != null
            val languageKnown = anchor.language != null && c.language != null
            val decadeKnown = anchor.decade != null && c.decade != null
            doubleArrayOf(
                if (known[i]) c.cosine else 0.0,
                if (known[i]) cosZ[i] else 0.0,
                b(known[i]),
                b(familyKnown && c.family == anchor.family),
                b(familyKnown),
                b(languageKnown && c.language == anchor.language),
                b(languageKnown),
                b(anchor.artistKey.isNotEmpty() && c.artistKey == anchor.artistKey),
                if (decadeKnown) abs(c.decade!! - anchor.decade!!) / 10.0 else 0.0,
                b(decadeKnown),
                c.logPopularity,
                popZ[i],
                c.colisten,
            )
        }
    }

    private fun b(value: Boolean) = if (value) 1.0 else 0.0

    /** proto_ranker.zq: population z-scores, all zero when the spread is zero. */
    internal fun zScores(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return x
        val mean = x.average()
        var squares = 0.0
        for (v in x) squares += (v - mean) * (v - mean)
        val sd = sqrt(squares / x.size)
        return if (sd > 0.0) DoubleArray(x.size) { (x[it] - mean) / sd } else DoubleArray(x.size)
    }
}

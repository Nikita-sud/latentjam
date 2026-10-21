/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the ranker knows of one track, relative to the hop's anchor where it says so.
 *
 * @property cosine artist-descriptor cosine to the anchor; NaN when either has no descriptor
 * @property family genre family, null when unknown
 * @property language pack language, null when unknown
 * @property decade pack decade (1990 for the 1990s), null when unknown
 * @property artistKey the artist key the model's same-artist feature uses; empty is no artist
 * @property colisten the anchor artist's co-listening similarity to this track's artist, 0 when unlisted
 * @property logPopularity ln(1 + listeners) of the track's artist (the model's `inputs.logpop`); 0 when unknown or unused
 * @property popularityKnown the artist popularity table has the track's artist (not a model column; the research
 *   reference computes the same flag)
 * @property lfm LightFM artist-vector cosine to the anchor; NaN when either has no vector
 * @property durationMs the track's sanitised duration; null when unknown or absurd
 * @property descriptorImputed the descriptor (of this track or the anchor) came from the audio tagger
 * @property familyImputed the family (of this track or the anchor) came from the audio tagger
 * @property audio A0 audio-embedding cosine to the anchor; NaN when either is not indexed yet
 * @property artistOverlap primary-weighted overlap of the artist sets with the anchor's (§1.2), 0..1
 * @property familyProbabilities the audio tagger's P(family) when the family is imputed: same_family becomes the
 *   expected match (the training data had no imputed rows, so no weight is learned for imputation itself)
 */
public class RankerRow(
    public val cosine: Double = Double.NaN,
    public val family: String? = null,
    public val language: String? = null,
    public val decade: Int? = null,
    public val artistKey: String = "",
    public val colisten: Double = 0.0,
    public val logPopularity: Double = 0.0,
    public val popularityKnown: Boolean = false,
    /** The listener has hearted this track (FULL plans only, "Learn from my habits"). */
    public val favourite: Boolean = false,
    /** Days since it was hearted; NaN when it is not a favourite or the time is unknown. */
    public val favouritedDaysAgo: Double = Double.NaN,
    public val lfm: Double = Double.NaN,
    public val durationMs: Long? = null,
    public val descriptorImputed: Boolean = false,
    public val familyImputed: Boolean = false,
    public val audio: Double = Double.NaN,
    public val artistOverlap: Double = 0.0,
    public val familyProbabilities: Map<String, Double>? = null,
    /** PLAN_L2: the track's original year (else its year), null when unknown. */
    public val year: Int? = null,
    /** PLAN_L2: normalised album title + album artist; empty for no or an unknown album. */
    public val albumKey: String = "",
    /** PLAN_L2: the album is a compilation (Various Artists, or three or more artists on it in the library). */
    public val compilation: Boolean = false,
    /** PLAN_L2: the family came from the track's own genre tag, not its artist. */
    public val familyFromTrack: Boolean = false,
)

/**
 * The FULL model's personal features of one candidate (proto_ranker.py `lib_features`, PERSONAL): from the listener's
 * own visible history at the seed. [transTrack] / [transArtist] are relative to the hop's anchor.
 */
public class PersonalRow(
    public val heard: Boolean = false,
    public val count: Int = 0,
    public val ageSeconds: Double = Double.POSITIVE_INFINITY,
    public val activation: Double = Double.NaN,
    public val transTrack: Int = 0,
    public val transArtist: Int = 0,
)

/**
 * The ranker's feature rows: proto_ranker.py's CORE (`lib_features`), proto_ranker2.py's LightFM pair, proto_ranker's
 * PERSONAL block (FULL), then PLAN_FINAL §1.2's duration / availability / imputed / audio / artist-set features. A model
 * uses the ones its asset names; the rest are computed and ignored. RankerGoldenTest and RankerQueueGoldenTest pin the
 * research's features.
 */
public object RankerFeatures {
    public val CORE: List<String> = listOf(
        "cos", "cos_z", "desc_known", "same_family", "family_known", "same_lang", "lang_known", "same_artist",
        "decade_dist", "decade_known", "logpop", "logpop_z", "colisten",
    )
    public val LFM: List<String> = listOf("lfm_cos", "lfm_known")
    public val PERSONAL: List<String> = listOf(
        "heard", "log_freq", "freq_rank", "recency", "recency_rank", "actr", "actr_rank", "trans_track", "trans_artist",
    )
    public val EXTRA: List<String> = listOf(
        "dur_log", "dur_short", "dur_known", "desc_imputed", "family_imputed", "audio_cos", "audio_known", "artist_overlap",
    )
    /**
     * Appended after the first 32 so their indexes never move (reference.py's TAIL, PLAN_FINAL §9): pop_known — the
     * artist popularity table has the track's artist (with `inputs.logpop = artist_pop`); is_favourite — the listener
     * hearted it; favourited_days_ago — days since that heart, 0 when not a favourite or the time is unknown (hearts from
     * before the time was kept). The two heart columns are set only for FULL plans ("Learn from my habits"); their
     * weights come from the model asset (0 until the research sets them).
     */
    public val TAIL: List<String> = listOf("pop_known", "is_favourite", "favourited_days_ago")

    /**
     * PLAN_L2's track columns, appended (reference.py L2COLS): year distance / known, same album / known, same
     * compilation, the family came from the track's tag, and |ln(duration ratio)| to the anchor.
     */
    public val L2: List<String> = listOf("year_dist", "year_known", "same_album", "album_known", "same_compilation", "family_is_track", "dur_rel")

    /**
     * PLAN_DECODER2 §19 AS (owner-adopted 2026-10-21, reference.py ALBCOLS): same album as the anchor while the session's
     * last two plays before the press were on the seed's album.
     */
    public val ALBUM: List<String> = listOf("album_session")
    public val NAMES: List<String> = CORE + LFM + PERSONAL + EXTRA + TAIL + L2 + ALBUM

    /** Tracks shorter than this count as short (skits, interludes); a parameter of the duration features (research: 90 s). */
    public const val SHORT_TRACK_MS: Long = 90_000

    /**
     * One row per candidate, in [NAMES] order. [personal], when given, is parallel to [candidates]. [l2]: an unknown
     * popularity takes the pool mean of the known ones (audit 01 #4), as cos_z fills.
     */
    public fun of(
        anchor: RankerRow,
        candidates: List<RankerRow>,
        personal: List<PersonalRow>? = null,
        l2: Boolean = false,
        /** The album-session prefix (the session's last two plays before the press on the seed's album key). */
        albumPrefix: Boolean = false,
    ): Array<DoubleArray> {
        val n = candidates.size
        val known = BooleanArray(n) { !candidates[it].cosine.isNaN() }
        // cos_z: unknown cosines take the known mean, the whole pool is z-scored, unknown rows read 0.
        var knownSum = 0.0
        var knownCount = 0
        for (i in 0 until n) if (known[i]) { knownSum += candidates[i].cosine; knownCount++ }
        val fill = if (knownCount > 0) knownSum / knownCount else 0.0
        val cosZ = zScores(DoubleArray(n) { if (known[it]) candidates[it].cosine else fill })
        val pop = DoubleArray(n) { candidates[it].logPopularity }
        if (l2) {
            var popSum = 0.0
            var popCount = 0
            for (i in 0 until n) if (candidates[i].popularityKnown) { popSum += pop[i]; popCount++ }
            val popFill = if (popCount > 0) popSum / popCount else 0.0
            for (i in 0 until n) if (!candidates[i].popularityKnown) pop[i] = popFill
        }
        val popZ = zScores(pop.copyOf())
        // audio_cos of a candidate without a vector (not indexed yet) = the mean of the known ones in this pool, 0 when
        // none is known: the uncentred audio term then neither buries nor lifts an unindexed track (reference.py).
        var audioSum = 0.0
        var audioCount = 0
        for (i in 0 until n) if (!candidates[i].audio.isNaN()) { audioSum += candidates[i].audio; audioCount++ }
        val audioFill = if (audioCount > 0) audioSum / audioCount else 0.0
        val freqRank = personal?.let { p -> rank01(DoubleArray(n) { p[it].count.toDouble() }) }
        val recencyRank = personal?.let { p ->
            rank01(DoubleArray(n) { if (p[it].heard) -minOf(p[it].ageSeconds, 1e12) else -1e13 })
        }
        val actrRank = personal?.let { p -> rank01(DoubleArray(n) { if (p[it].activation.isNaN()) -1e9 else p[it].activation }) }
        return Array(n) { i ->
            val c = candidates[i]
            val familyKnown = (anchor.family != null || anchor.familyProbabilities != null) &&
                (c.family != null || c.familyProbabilities != null)
            val languageKnown = anchor.language != null && c.language != null
            val decadeKnown = anchor.decade != null && c.decade != null
            val p = personal?.get(i)
            val duration = c.durationMs
            val yearKnown = anchor.year != null && c.year != null
            val albumKnown = anchor.albumKey.isNotEmpty() && c.albumKey.isNotEmpty()
            val sameAlbum = c.albumKey == anchor.albumKey
            doubleArrayOf(
                if (known[i]) c.cosine else 0.0,
                if (known[i]) cosZ[i] else 0.0,
                b(known[i]),
                if (familyKnown) familyMatch(anchor, c) else 0.0,
                b(familyKnown),
                b(languageKnown && c.language == anchor.language),
                b(languageKnown),
                b(anchor.artistKey.isNotEmpty() && c.artistKey == anchor.artistKey),
                if (decadeKnown) abs(c.decade!! - anchor.decade!!) / 10.0 else 0.0,
                b(decadeKnown),
                pop[i],
                popZ[i],
                c.colisten,
                if (c.lfm.isNaN()) 0.0 else c.lfm,
                b(!c.lfm.isNaN()),
                b(p?.heard == true),
                if (p != null) ln(1.0 + p.count) else 0.0,
                freqRank?.get(i) ?: 0.0,
                if (p != null && p.heard) -ln(1.0 + minOf(p.ageSeconds, 1e12)) / 10.0 else 0.0,
                recencyRank?.get(i) ?: 0.0,
                if (p != null && p.heard && !p.activation.isNaN()) p.activation else 0.0,
                actrRank?.get(i) ?: 0.0,
                if (p != null) ln(1.0 + p.transTrack) else 0.0,
                if (p != null) ln(1.0 + p.transArtist) else 0.0,
                if (duration != null) ln(duration / 1000.0) else 0.0,
                b(duration != null && duration < SHORT_TRACK_MS),
                b(duration != null),
                b(c.descriptorImputed || anchor.descriptorImputed),
                b(c.familyImputed || anchor.familyImputed),
                if (c.audio.isNaN()) audioFill else c.audio,
                b(!c.audio.isNaN()),
                c.artistOverlap,
                b(c.popularityKnown),
                b(c.favourite),
                if (c.favourite && !c.favouritedDaysAgo.isNaN()) c.favouritedDaysAgo else 0.0,
                if (yearKnown) abs(c.year!! - anchor.year!!) / 10.0 else 0.0,
                b(yearKnown),
                b(albumKnown && sameAlbum),
                b(albumKnown),
                b(albumKnown && sameAlbum && (c.compilation || anchor.compilation)),
                b(c.familyFromTrack),
                if (duration != null && anchor.durationMs != null) abs(ln(duration.toDouble() / anchor.durationMs.toDouble())) else 0.0,
                b(albumPrefix && albumKnown && sameAlbum),
            )
        }
    }

    private fun b(value: Boolean) = if (value) 1.0 else 0.0

    /** 1 / 0 for two known families; with an imputed side, P(the two families are the same) from the tagger. */
    internal fun familyMatch(anchor: RankerRow, c: RankerRow): Double {
        val a = anchor.familyProbabilities
        val p = c.familyProbabilities
        return when {
            a == null && p == null -> b(c.family == anchor.family)
            a != null && p != null -> a.entries.sumOf { (f, pa) -> pa * (p[f] ?: 0.0) }
            p != null -> p[anchor.family] ?: 0.0
            else -> a!![c.family] ?: 0.0
        }
    }

    /** proto_ranker.zq: population z-scores, all zero when the spread is zero. */
    internal fun zScores(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return x
        val mean = x.average()
        var squares = 0.0
        for (v in x) squares += (v - mean) * (v - mean)
        val sd = sqrt(squares / x.size)
        return if (sd > 0.0) DoubleArray(x.size) { (x[it] - mean) / sd } else DoubleArray(x.size)
    }

    /** proto_ranker.rank01: ranks scaled to [0, 1], ties sharing their mean rank; zeros below two rows. */
    internal fun rank01(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return DoubleArray(n)
        // Primitive comparison: compareTo would split -0.0 from 0.0, which numpy's sort ties.
        val order = x.indices.sortedWith { a, c -> if (x[a] < x[c]) -1 else if (x[a] > x[c]) 1 else 0 }
        val rank = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && x[order[j + 1]] == x[order[i]]) j++
            val mean = (i + j) / 2.0
            for (k in i..j) rank[order[k]] = mean
            i = j + 1
        }
        return DoubleArray(n) { rank[it] / (n - 1) }
    }
}

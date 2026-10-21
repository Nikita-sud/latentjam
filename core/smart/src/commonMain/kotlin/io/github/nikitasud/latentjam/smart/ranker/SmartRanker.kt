/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitFlow
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.chain.MetadataRerank
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.ArtistFamilies
import io.github.nikitasud.latentjam.smart.text.ArtistNeighbours
import io.github.nikitasud.latentjam.smart.text.ArtistPopularity
import io.github.nikitasud.latentjam.smart.text.ArtistSets
import io.github.nikitasud.latentjam.smart.text.ArtistVectors
import io.github.nikitasud.latentjam.smart.text.KnownArtists
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * The hop loop of tools/research/listenbrainz_eval/proto_ranker2.py (`Query.queue`) with PLAN_FINAL §8's variety rule,
 * over pool indices.
 *
 * Hop 1 scores the pool against the seed; from hop 2 the anchor is [RankerModel.anchor]: the seed, the previous pick,
 * or both mixed. As proto_ranker4's D0: rows [blocked] (song-version dedup) leave first; the penalised score is the
 * score − p · runLength · sd(raw scores of the rows left) for rows of the artist the queue ends with (the seed counts),
 * minus [RankerModel.thirdInRowCap] for a third track in a row by one artist; the rows within ε =
 * [RankerModel.epsilon] · sd(penalised scores) of the best form the hop's near-best set, ordered by [tieOrder];
 * [choose] picks one of them (§1.6 step 5's uniform draw on device; the first, the research's lowest-key rule, when
 * ε = 0). Features are always computed over the whole pool, as the research does. The
 * loop stops early when no row may be taken or [stop] says the time budget is spent.
 */
public object RankerQueue {
    public fun build(
        model: RankerModel,
        poolSize: Int,
        artistKeys: List<String>,
        seedArtist: String,
        tieOrder: IntArray,
        length: Int,
        /** Picks one of the hop's near-best rows (ordered by [tieOrder]); hop 0 is the first pick. */
        choose: (hop: Int, nearBest: List<Int>) -> Int = { _, nearBest -> nearBest.first() },
        /** Test hook: given the hop, its chosen row and the penalised scores, the row to take (golden teacher forcing). */
        take: ((hop: Int, best: Int, scores: (Int) -> Double) -> Int)? = null,
        /** Rows the hop may not take (song-version dedup), given the picks so far. */
        blocked: (row: Int, picks: List<Int>) -> Boolean = { _, _ -> false },
        /** True when the time budget is spent: the loop returns what it has. */
        stop: () -> Boolean = { false },
        /** §7.2b's rule-only path with no features: every seed score ties, so no hop is decoded (the ladder runs). */
        skipWhenTied: Boolean = false,
        /** Golden-test hook: each hop's scores and choice (reference.py's trace). */
        trace: ((HopTrace) -> Unit)? = null,
        /** Each row's artist-set credits (the re-entry term's shared-credit match); null without [RankerModel.artistReentry]. */
        credits: List<LongArray>? = null,
        seedCredits: LongArray = LongArray(0),
        /** PLAN_L1 §2c: each row's impression weight; null = no freshness term. */
        fresh: DoubleArray? = null,
        /** PLAN_AUDIO_ON §8.2: each row's e (NaN = unknown) and the seed's; null = no energy term. */
        energies: DoubleArray? = null,
        seedEnergy: Double? = null,
        /** Arm P: the audio column of an anchor's rows (the previous pick, the seed at hop 1); null = the usual audio term. */
        audioAgainst: ((anchor: Int) -> DoubleArray)? = null,
        /** Soft novelty: each row's 1 − age / 24 h (0 = not in the soft novelty set); null = no term. */
        novelty: DoubleArray? = null,
        /** Fast 3 mismatch terms: each row's device family and the seed's. */
        families: List<String?>? = null,
        seedFamily: String? = null,
        /** Fast 3.1 holidaySeason: the rows that lose λ (holiday tracks, out of season, the seed not one); null = none. */
        holiday: BooleanArray? = null,
        /** Fast 4 continuity: per model head, each row's value (NaN = unknown) and the seed's (null = term off). */
        heads: List<Pair<DoubleArray, Double?>>? = null,
        /** Fast 3.2 rule penalties per row (duration, title flags), subtracted after the spread; null = none. */
        rulePenalties: DoubleArray? = null,
        /** Fast 3.2 unknownSeedAnchor: the seed has no family and no descriptor. */
        seedUnknown: Boolean = false,
        /** Audit 03 #1: the queue a top-up continues, oldest first, as (artist key, credits); null = a fresh plan. */
        prefix: List<Pair<String, LongArray>>? = null,
        /** The prefix's last track's e (the 'prev' energy reference of the first hop). */
        prefixEnergy: Double? = null,
        /** Fast 3.3: each row's max 1 / log2(2 + size) over the user playlists it shares with the anchor; null = none. */
        playlistBonus: ((anchor: Int) -> DoubleArray?)? = null,
        /** PLAN_DECODER2 NOREPEAT: the run term takes [RankerModel.NoRepeat.lambdaStart] instead of λ_run. */
        noRepeat: Boolean = false,
        /** The artist-variety setting: × λ_run (or λ_start), λ_re and the third-in-row cap; 0 = no artist-variety terms. */
        varietyScale: Double = 1.0,
        /** Cold fix: semChain's per-row term for a hop's anchor (SEED, PREFIX or a pool row); null = none. */
        semTerms: ((anchor: Int) -> DoubleArray)? = null,
        /** Cold fix: familyMismatch's λ for this plan (coldLambda on a seed without a pack entity); null = the model's. */
        familyMismatchLambda: Double? = null,
        /** vc22 queueAlbumCap: each row's album core and the seed's ("" = none). */
        albumKeys: List<String>? = null,
        seedAlbum: String = "",
        features: (anchor: Int) -> Array<DoubleArray>,
    ): List<Int> {
        if (poolSize == 0 || length <= 0) return emptyList()
        var seedRows: Array<DoubleArray>? = null
        fun scores(anchor: Int): DoubleArray {
            var rows = features(anchor)
            if (anchor == SEED) seedRows = rows
            val share = model.anchorShare
            if (anchor != SEED && share != null) {
                // Fast 3: the listed columns of a previous-pick row lean on the seed's row for the same candidate.
                val base = seedRows ?: features(SEED).also { seedRows = it }
                rows = Array(rows.size) { i ->
                    rows[i].copyOf().also { r -> for (c in share.columnIndexes) r[c] = share.share * r[c] + (1.0 - share.share) * base[i][c] }
                }
            }
            val sc = if (model.personalNudge == null) DoubleArray(poolSize) { model.score(rows[it]) } else {
                // Night r5: the personal columns sit at the model's means (no contribution); they act only as the nudge.
                DoubleArray(poolSize) { model.scoreWithoutPersonal(rows[it], PERSONAL_COLUMNS) }
            }
            if (model.audioZ) addAudioZ(model, sc, rows)
            model.transBonus?.let { (lt, la) ->
                // Night r4: "after A this person plays B" as seed-specific intent, outside the learned weights.
                val jt = RankerFeatures.NAMES.indexOf("trans_track")
                val ja = RankerFeatures.NAMES.indexOf("trans_artist")
                for (i in 0 until poolSize) sc[i] = sc[i] + lt * rows[i][jt] + la * rows[i][ja]
            }
            if (holiday != null) model.holidaySeason?.let { lam -> for (i in 0 until poolSize) if (holiday[i]) sc[i] = sc[i] - lam }
            if (families != null) {
                model.classMismatch?.let { cm ->
                    if (seedFamily !in cm.families) for (i in 0 until poolSize) if (families[i] in cm.families) sc[i] = sc[i] - cm.lambda
                }
            }
            // reference.py's order: class mismatch, the playlist bonus, then family mismatch.
            model.playlistBonus?.takeIf { model.personalNudge == null }?.let { pb ->
                playlistBonus?.invoke(anchor)?.let { b -> for (i in 0 until poolSize) sc[i] = sc[i] + pb.lambda * b[i] }
            }
            if (families != null) {
                (familyMismatchLambda ?: model.familyMismatch)?.takeIf { model.familyMismatch != null }?.let { lam ->
                    if (seedFamily != null) for (i in 0 until poolSize) {
                        val f = families[i] ?: continue
                        if (f == seedFamily) continue
                        // Fast 3.1: a near pair takes nearScale · λ.
                        sc[i] = sc[i] - lam * (if (setOf(f, seedFamily) in model.familyNear) model.familyNearScale else 1.0)
                    }
                }
            }
            return sc
        }
        val seedScores = scores(SEED)
        if (skipWhenTied && seedScores.all { it == seedScores[0] }) return emptyList()
        // Night r5: each row's personal nudge n in [0, 1] against an anchor (the ranked signals, saturating).
        val nudgeCache = HashMap<Int, DoubleArray>()
        fun nudgeOf(anchor: Int): DoubleArray = nudgeCache.getOrPut(anchor) {
            val pn = model.personalNudge!!
            val rows = features(anchor)
            val pl = playlistBonus?.invoke(anchor)
            val jt = RankerFeatures.NAMES.indexOf("trans_track")
            val ja = RankerFeatures.NAMES.indexOf("trans_artist")
            val jf = RankerFeatures.NAMES.indexOf("is_favourite")
            val jq = RankerFeatures.NAMES.indexOf("log_freq")
            DoubleArray(poolSize) { i ->
                val r = rows[i]
                val x = pn.track * minOf(1.0, r[jt] / LN4) + pn.artist * minOf(1.0, r[ja] / LN4) +
                    pn.playlist * minOf(1.0, 2.0 * (pl?.get(i) ?: 0.0)) + pn.favourite * minOf(1.0, maxOf(0.0, r[jf])) +
                    pn.frequency * minOf(1.0, r[jq] / LN21)
                minOf(1.0, maxOf(0.0, x))
            }
        }
        val nudge: ((Int) -> DoubleArray)? = if (model.personalNudge == null) null else { anchor ->
            val a = nudgeOf(SEED)
            if (anchor == SEED) a else { val b = nudgeOf(anchor); DoubleArray(poolSize) { maxOf(a[it], b[it]) } }
        }
        model.artistReentry?.takeIf { it.creditRun }?.let { re ->
            return buildCreditRun(
                model, re, poolSize, artistKeys, seedArtist, tieOrder, length, choose, take, blocked, stop, trace,
                credits, seedCredits, seedScores, ::scores, fresh, energies, seedEnergy, audioAgainst, novelty, heads,
                seedFamily, rulePenalties, seedUnknown, prefix, prefixEnergy, noRepeat, varietyScale, semTerms, families, nudge,
                albumKeys, seedAlbum,
            )
        }
        val lambdaRun = model.runPenalty * varietyScale
        val thirdCap = model.thirdInRowCap * varietyScale
        val lambdaRe = (model.artistReentry?.lambda ?: 0.0) * varietyScale
        val open = BooleanArray(poolSize) { true }
        val picks = ArrayList<Int>(length)
        var runArtist = seedArtist
        var runLength = if (runArtist.isEmpty()) 0 else 1
        repeat(minOf(length, poolSize)) {
            if (picks.isNotEmpty() && stop()) return picks
            val s = when {
                picks.isEmpty() || model.anchor == RankerModel.Anchor.SEED -> seedScores
                model.anchor == RankerModel.Anchor.PREVIOUS -> scores(picks.last())
                else -> {
                    val previous = scores(picks.last())
                    val total = model.seedWeight + model.previousWeight
                    DoubleArray(poolSize) { (model.seedWeight * seedScores[it] + model.previousWeight * previous[it]) / total }
                }
            }
            // proto_ranker4 D0 (PLAN_FINAL §8): dedup first; the penalty uses the sd of the raw scores over what is
            // left, the third-in-a-row cap follows, and ε uses the sd of those penalised, capped scores.
            val allowed = BooleanArray(poolSize) { open[it] && s[it].isFinite() && !blocked(it, picks) }
            if (allowed.none { it }) return picks
            val sd = spread(s, allowed)
            val penalty = if (lambdaRun > 0.0 && runLength > 0) lambdaRun * runLength * sd else 0.0
            val runAt = runArtist
            val capped = thirdCap > 0.0 && runAt.isNotEmpty() && runLength >= 2
            fun penalised(i: Int): Double {
                var t = s[i]
                if (penalty != 0.0 && artistKeys[i] == runAt) t -= penalty
                if (capped && artistKeys[i] == runAt) t -= thirdCap
                if (fresh != null && fresh[i] != 0.0) t -= model.freshness!!.lambda * sd * fresh[i]
                return t
            }
            // PLAN_L1 §2a re-entry: the queue so far, newest first, the seed as its oldest pick.
            val reentry = model.artistReentry?.takeIf { lambdaRe != 0.0 }
            var prevKey = ""
            var backKeys: List<String> = emptyList()
            var backCredits: List<LongArray> = emptyList()
            if (reentry != null) {
                val keys = picks.asReversed().map { artistKeys[it] } + seedArtist
                val cr = picks.asReversed().map { credits?.get(it) ?: NO_CREDITS } + seedCredits
                prevKey = keys[0]
                val end = minOf(reentry.k, keys.size)
                backKeys = if (end > 1) keys.subList(1, end) else emptyList()
                backCredits = if (end > 1) cr.subList(1, end) else emptyList()
            }
            fun reentryOf(i: Int): Double {
                if (reentry == null || (prevKey.isNotEmpty() && artistKeys[i] == prevKey)) return 0.0
                var r = 0.0
                val mine = credits?.get(i) ?: NO_CREDITS
                for (j in backKeys.indices) {
                    val m = when {
                        artistKeys[i].isNotEmpty() && artistKeys[i] == backKeys[j] -> 1.0
                        mine.isNotEmpty() && backCredits[j].isNotEmpty() && mine.any { it in backCredits[j] } -> reentry.credit
                        else -> 0.0
                    }
                    r += reentry.gamma.pow(j) * m
                }
                return r
            }
            val t = DoubleArray(poolSize) {
                if (!allowed[it]) Double.NaN else {
                    var v = penalised(it)
                    if (reentry != null) {
                        val r = reentryOf(it)
                        if (r != 0.0) v -= lambdaRe * sd * r
                    }
                    v
                }
            }
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 0 until poolSize) if (allowed[i]) bestScore = maxOf(bestScore, t[i])
            val spreadPenalised = spread(t, allowed)
            val floor = bestScore - model.epsilon * spreadPenalised
            val nearBest = (0 until poolSize)
                .filter { allowed[it] && t[it] >= floor }
                .sortedBy { tieOrder[it] }
            var best = if (model.epsilon == 0.0 && nearBest.size == 1) nearBest[0] else choose(picks.size, nearBest)
            if (take != null) best = take(picks.size, best, ::penalised)
            trace?.invoke(HopTrace(s, t, sd, penalty, runAt, runLength, capped, bestScore, spreadPenalised, floor, nearBest, best))
            open[best] = false
            picks += best
            val artist = artistKeys[best]
            if (runArtist.isNotEmpty() && artist == runArtist) runLength++ else {
                runArtist = artist
                runLength = if (artist.isEmpty()) 0 else 1
            }
        }
        return picks
    }

    /** m(i, j) of PLAN_L1: 1 for the same non-empty artist key, [credit] for intersecting artist-set credits, else 0. */
    private fun redundancy(aKey: String, aCredits: LongArray, bKey: String, bCredits: LongArray, credit: Double): Double = when {
        aKey.isNotEmpty() && aKey == bKey -> 1.0
        aCredits.isNotEmpty() && bCredits.isNotEmpty() && aCredits.any { it in bCredits } -> credit
        else -> 0.0
    }

    /** [build] with PLAN_L1 §10.3's credit-aware run and re-entry (reference.py `decode_v5`). */
    private fun buildCreditRun(
        model: RankerModel,
        re: RankerModel.ArtistReentry,
        poolSize: Int,
        artistKeys: List<String>,
        seedArtist: String,
        tieOrder: IntArray,
        length: Int,
        choose: (hop: Int, nearBest: List<Int>) -> Int,
        take: ((hop: Int, best: Int, scores: (Int) -> Double) -> Int)?,
        blocked: (row: Int, picks: List<Int>) -> Boolean,
        stop: () -> Boolean,
        trace: ((HopTrace) -> Unit)?,
        credits: List<LongArray>?,
        seedCredits: LongArray,
        seedScores: DoubleArray,
        scores: (Int) -> DoubleArray,
        fresh: DoubleArray?,
        energies: DoubleArray?,
        seedEnergy: Double?,
        audioAgainst: ((anchor: Int) -> DoubleArray)?,
        novelty: DoubleArray?,
        heads: List<Pair<DoubleArray, Double?>>?,
        seedFamily: String?,
        rulePenalties: DoubleArray?,
        seedUnknown: Boolean,
        prefix: List<Pair<String, LongArray>>?,
        prefixEnergy: Double?,
        noRepeat: Boolean,
        varietyScale: Double,
        semTerms: ((anchor: Int) -> DoubleArray)?,
        families: List<String?>?,
        nudge: ((Int) -> DoubleArray)?,
        albumKeys: List<String>?,
        seedAlbum: String,
    ): List<Int> {
        val lambdaRun = (if (noRepeat) model.noRepeat!!.lambdaStart else model.runPenalty) * varietyScale
        val lambdaRe = re.lambda * varietyScale
        fun creditsOf(i: Int) = credits?.get(i) ?: NO_CREDITS
        val open = BooleanArray(poolSize) { true }
        val picks = ArrayList<Int>(length)
        // Oldest first: the seed, then the picks.
        val histKeys = arrayListOf(seedArtist)
        val histCredits = arrayListOf(seedCredits)
        var anchorKey: String? = if (seedArtist.isNotEmpty() || seedCredits.isNotEmpty()) seedArtist else null
        var anchorCredits = seedCredits
        var runLength = if (anchorKey != null) 1 else 0
        // Audit 03 #1: a top-up continues the session: the prefix replays the run / re-entry state as picks would.
        for ((key, cr) in prefix.orEmpty()) {
            histKeys += key
            histCredits += cr
            val a = anchorKey
            if (a != null && redundancy(key, cr, a, anchorCredits, re.credit) > 0.0) {
                runLength++
            } else if (key.isNotEmpty() || cr.isNotEmpty()) {
                anchorKey = key
                anchorCredits = cr
                runLength = 1
            } else {
                anchorKey = null
                anchorCredits = NO_CREDITS
                runLength = 0
            }
        }
        val npre = prefix?.size ?: 0
        repeat(minOf(length, poolSize)) {
            if (picks.isNotEmpty() && stop()) return picks
            val prevAnchor = picks.lastOrNull() ?: if (npre > 0) PREFIX else null
            // Cold fix: semChain's terms against this hop's anchor (the seed at hop 1).
            val semT = semTerms?.invoke(prevAnchor ?: SEED)
            val combined = when {
                prevAnchor == null || model.anchor == RankerModel.Anchor.SEED || (model.unknownSeedAnchor && seedUnknown) -> seedScores
                model.anchor == RankerModel.Anchor.PREVIOUS -> scores(prevAnchor)
                else -> {
                    val previous = scores(prevAnchor)
                    val total = model.seedWeight + model.previousWeight
                    DoubleArray(poolSize) { (model.seedWeight * seedScores[it] + model.previousWeight * previous[it]) / total }
                }
            }
            // 0.9.1 chain skeleton: the ranker as a squashed scorer term (non-finite scores pass through).
            val s = model.scorerSquash?.let { (scale, temp) ->
                DoubleArray(poolSize) { val x = combined[it]; if (x.isFinite()) scale * kotlin.math.tanh(x / temp) else x }
            } ?: combined
            val allowed = BooleanArray(poolSize) { open[it] && s[it].isFinite() && !blocked(it, picks) }
            if (allowed.none { it }) return picks
            val sd = spread(s, allowed)
            // The picks before the current run, newest first, at distance runLength + 1, + 2, … ≤ k.
            val beforeSize = if (runLength > 0) histKeys.size - runLength else histKeys.size
            val dist0 = if (runLength > 0) runLength else 0
            val backDistance = ArrayList<Int>()
            val backIndex = ArrayList<Int>()
            for (jj in 0 until beforeSize) {
                val d = dist0 + 1 + jj
                if (d > re.k) continue
                backDistance += d
                backIndex += beforeSize - 1 - jj
            }
            val aKey = anchorKey
            val aPrev = audioAgainst?.invoke(picks.lastOrNull() ?: if (npre > 0) PREFIX else SEED)
            // PLAN_AUDIO_ON §8.2: a row without e takes the pool mean of |Δe| as its |Δe|.
            val en = model.energy?.takeIf { energies != null }
            var eFill = 0.0
            var eFillRef = 0.0
            var eSd = 1.0
            if (en != null) {
                val ref0 = if (!en.seedHinge) {
                    if (picks.isNotEmpty()) energies!![picks.last()].takeIf { !it.isNaN() } else if (npre > 0) prefixEnergy else seedEnergy
                } else {
                    seedEnergy
                }
                var sum = 0.0
                var n = 0
                for (i in 0 until poolSize) if (allowed[i] && !energies!![i].isNaN()) { sum += energies[i]; n++ }
                eFillRef = ref0 ?: if (n > 0) sum / n else 0.0
                var dsum = 0.0
                for (i in 0 until poolSize) if (allowed[i] && !energies!![i].isNaN()) dsum += kotlin.math.abs(energies[i] - eFillRef)
                eFill = eFillRef + if (n > 0) dsum / n else 0.0
                if (en.poolScale) {
                    // Fast track 2: |Δe| in units of the allowed rows' e spread (population sd, floored).
                    val mu = if (n > 0) sum / n else 0.0
                    var sq = 0.0
                    for (i in 0 until poolSize) if (allowed[i] && !energies!![i].isNaN()) sq += (energies[i] - mu) * (energies[i] - mu)
                    eSd = maxOf(E_SD_FLOOR, if (n > 0) sqrt(sq / n) else E_SD_FLOOR)
                }
            }
            // Fast 4 continuity, per head in name order: the allowed rows' known values give the pool sd (floored) and
            // the unknown fill (seed + mean |Δ|); a head without the seed's value is off.
            val cont = ArrayList<HeadTerm>()
            if (heads != null) for ((hi, spec) in model.continuity.withIndex()) {
                val (values, seedValue) = heads.getOrNull(hi) ?: continue
                if (seedValue == null) continue
                // Fast 3.2 gates: the seed's family, and the seed's own value.
                if (spec.seedFamilies != null && seedFamily !in spec.seedFamilies) continue
                if (spec.seedMin != null && seedValue < spec.seedMin) continue
                var sum = 0.0
                var n = 0
                for (i in 0 until poolSize) if (allowed[i] && !values[i].isNaN()) { sum += values[i]; n++ }
                val mu = if (n > 0) sum / n else 0.0
                var sq = 0.0
                var dsum = 0.0
                for (i in 0 until poolSize) if (allowed[i] && !values[i].isNaN()) {
                    sq += (values[i] - mu) * (values[i] - mu)
                    dsum += kotlin.math.abs(values[i] - seedValue)
                }
                val hsd = if (n > 0) maxOf(HEAD_SD_FLOOR, sqrt(sq / n)) else HEAD_SD_FLOOR
                cont += HeadTerm(spec.lambda, spec.tau, values, seedValue, hsd, seedValue + if (n > 0) dsum / n else 0.0)
            }
            // Diagnostics only: each row's terms (run, freshness + novelty, arm P audio, energy, re-entry).
            val terms = if (trace != null) Array(5) { DoubleArray(poolSize) } else null
            fun penalised(i: Int): Double {
                var v = s[i]
                val ma = if (aKey != null) redundancy(artistKeys[i], creditsOf(i), aKey, anchorCredits, re.credit) else 0.0
                if (ma > 0.0 && lambdaRun > 0.0) v -= lambdaRun * runLength * sd * ma
                terms?.let { it[0][i] = s[i] - v }
                var mark = v
                if (fresh != null && fresh[i] != 0.0) {
                    val f = model.freshness!!
                    var pen = f.lambda * fresh[i]
                    if (f.cap != null) pen = minOf(pen, f.cap)
                    v -= pen * sd
                }
                if (novelty != null && novelty[i] != 0.0) v -= model.softNovelty!!.lambda * sd * novelty[i]
                if (rulePenalties != null && rulePenalties[i] != 0.0) v -= rulePenalties[i]
                // vc22: the same cap on the album core (one soundtrack under several artist spellings / volumes).
                model.queueAlbumCap?.let { qc ->
                    val key = albumKeys?.get(i).orEmpty()
                    if (key.isNotEmpty()) {
                        val recent = (listOf(seedAlbum) + picks.map { albumKeys!![it] }).takeLast(maxOf(1, qc.window - 1))
                        if (recent.count { it == key } >= qc.cap) v -= qc.lambda * (if (qc.sdUnits) maxOf(1.0, sd) else 1.0)
                    }
                }
                // vc23: the whole-queue artist budget (the same rule over a long window).
                model.queueArtistBudget?.let { qc ->
                    val key = artistKeys[i]
                    if (key.isNotEmpty()) {
                        val recent = histKeys.takeLast(maxOf(1, qc.window - 1))
                        if (recent.count { it == key } >= qc.cap) v -= qc.lambda * (if (qc.sdUnits) maxOf(1.0, sd) else 1.0)
                    }
                }
                // Night r4: a soft per-queue artist cap over the last window − 1 items (the seed included).
                model.queueArtistCap?.let { qc ->
                    val key = artistKeys[i]
                    if (key.isNotEmpty()) {
                        val recent = histKeys.takeLast(maxOf(1, qc.window - 1))
                        if (recent.count { it == key } >= qc.cap) v -= qc.lambda * (if (qc.sdUnits) maxOf(1.0, sd) else 1.0)
                    }
                }
                // vc23: cumulative tail drift — off the seed's family after k consecutive off-family picks.
                model.familyDrift?.let { lam ->
                    if (families != null && seedFamily != null) {
                        val f = families[i]
                        if (f != null && f != seedFamily) {
                            var k = 0
                            for (p in picks.indices.reversed()) { val fp = families[picks[p]]; if (fp != null && fp != seedFamily) k++ else break }
                            if (k > 0) v -= lam * k
                        }
                    }
                }
                // Night 2026-10-21: a one-track genre jump (the family differs from the previous pick's and the seed's).
                model.prevFamilyJump?.let { lam ->
                    val last = picks.lastOrNull()
                    if (families != null && last != null && seedFamily != null) {
                        val f = families[i]
                        val fp = families[last]
                        if (f != null && fp != null && f != fp && f != seedFamily) v -= lam
                    }
                }
                terms?.let { it[1][i] = mark - v }
                if (semT != null) v += semT[i]
                mark = v
                if (aPrev != null) v += model.audioWeight * aPrev[i]
                terms?.let { it[2][i] = v - mark }
                mark = v
                if (en != null) {
                    val ei = energies!![i].takeIf { !it.isNaN() } ?: eFill
                    val ref = if (!en.seedHinge) {
                        if (picks.isNotEmpty()) energies[picks.last()].takeIf { !it.isNaN() } else if (npre > 0) prefixEnergy else seedEnergy
                    } else {
                        seedEnergy
                    }
                    // Audit 05 #4: an unknown anchor energy switches the term off at this hop.
                    if (ref != null) {
                        val d = kotlin.math.abs(ei - ref) / eSd
                        v -= en.lambda * (if (!en.seedHinge) d else maxOf(0.0, d - en.tau))
                    }
                }
                for (h in cont) {
                    val xi = h.values[i].takeIf { !it.isNaN() } ?: h.fill
                    v -= h.lambda * maxOf(0.0, kotlin.math.abs(xi - h.seed) / h.sd - h.tau)
                }
                terms?.let { it[3][i] = mark - v }
                mark = v
                if (ma == 0.0 && lambdaRe != 0.0) {
                    var r = 0.0
                    for (b in backDistance.indices) {
                        val d = backDistance[b]
                        if (d >= 2) {
                            val h = backIndex[b]
                            r += re.gamma.pow(d - 2) * redundancy(artistKeys[i], creditsOf(i), histKeys[h], histCredits[h], re.credit)
                        }
                    }
                    if (r != 0.0) v -= lambdaRe * sd * r
                }
                terms?.let { it[4][i] = mark - v }
                return v
            }
            val t = DoubleArray(poolSize) { if (allowed[it]) penalised(it) else Double.NaN }
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 0 until poolSize) if (allowed[i]) bestScore = maxOf(bestScore, t[i])
            if (nudge != null) {
                // Night r5: the bounded personal tie-breaker among the near-best (never lifts a clearly worse row).
                val nd = nudge(prevAnchor ?: SEED)
                val delta = model.personalNudge!!.delta * spread(t, allowed)
                val top = bestScore
                for (i in 0 until poolSize) if (allowed[i] && t[i] >= top - delta && nd[i] > 0.0) t[i] = t[i] + delta * nd[i]
                for (i in 0 until poolSize) if (allowed[i]) bestScore = maxOf(bestScore, t[i])
            }
            val spreadPenalised = spread(t, allowed)
            val floor = bestScore - model.epsilon * spreadPenalised
            val nearBest = (0 until poolSize).filter { allowed[it] && t[it] >= floor }.sortedBy { tieOrder[it] }
            // Audit 03 #1: the hop draws continue after the prefix, so a top-up never repeats a plan's salts.
            var best = if (model.epsilon == 0.0 && nearBest.size == 1) nearBest[0] else choose(npre + picks.size, nearBest)
            if (take != null) best = take(picks.size, best, ::penalised)
            trace?.invoke(HopTrace(s, t, sd, 0.0, aKey.orEmpty(), runLength, false, bestScore, spreadPenalised, floor, nearBest, best, terms))
            open[best] = false
            picks += best
            val key = artistKeys[best]
            val cr = creditsOf(best)
            histKeys += key
            histCredits += cr
            if (aKey != null && redundancy(key, cr, aKey, anchorCredits, re.credit) > 0.0) {
                runLength++
            } else if (key.isNotEmpty() || cr.isNotEmpty()) {
                anchorKey = key
                anchorCredits = cr
                runLength = 1
            } else {
                anchorKey = null
                anchorCredits = NO_CREDITS
                runLength = 0
            }
        }
        return picks
    }

    private val LN4 = kotlin.math.ln(4.0)
    private val LN21 = kotlin.math.ln(21.0)
    private val PERSONAL_COLUMNS: Set<Int> = (RankerFeatures.PERSONAL + listOf("is_favourite", "favourited_days_ago")).map { RankerFeatures.NAMES.indexOf(it) }.toSet()

    /** The anchor index [build] passes for the seed. */
    public const val SEED: Int = -1

    /** Audit 03 #1: a top-up's last queued track (the request's prefix) as the previous-pick anchor of its first hop. */
    public const val PREFIX: Int = -2

    private val NO_CREDITS = LongArray(0)

    /** One hop of [build], as reference.py traces it: scores s, penalised t (NaN = not allowed), the near-best rows. */
    public class HopTrace(
        public val s: DoubleArray,
        public val t: DoubleArray,
        public val sd: Double,
        public val penalty: Double,
        public val runArtist: String,
        public val runLength: Int,
        public val capped: Boolean,
        public val best: Double,
        public val sdPenalised: Double,
        public val floor: Double,
        public val nearBest: List<Int>,
        public val pick: Int,
        /** v5 decoder, diagnostics: per row the run, freshness + novelty, arm-P audio (gain), energy and re-entry terms. */
        public val terms: Array<DoubleArray>? = null,
    )

    /** Population sd of [s] over the rows [open] marks. */
    /** Fast track 2: the floor of the pool's e spread (raw e units). */
    internal const val E_SD_FLOOR: Double = 0.005

    /** Fast 4: the floor of the pool's spread of a continuity head. */
    internal const val HEAD_SD_FLOOR: Double = 0.01

    private class HeadTerm(val lambda: Double, val tau: Double, val values: DoubleArray, val seed: Double, val sd: Double, val fill: Double)

    private val AUDIO_COS = RankerFeatures.NAMES.indexOf("audio_cos")
    private val AUDIO_KNOWN = RankerFeatures.NAMES.indexOf("audio_known")

    /**
     * Fast track 2 `audioZ`: + audioWeight · (audio_cos − μ) / σ on each row with a known vector, μ and σ the known rows'
     * mean and population sd in pool order; no term when σ ≤ 1e-9 or none is known (reference.py scores_of).
     */
    internal fun addAudioZ(model: RankerModel, scores: DoubleArray, rows: Array<DoubleArray>) {
        if (model.audioWeight == 0.0 || model.audioAgainstPrevious) return
        var sum = 0.0
        var n = 0
        for (r in rows) if (r[AUDIO_KNOWN] == 1.0) { sum += r[AUDIO_COS]; n++ }
        if (n == 0) return
        val mu = sum / n
        var sq = 0.0
        for (r in rows) if (r[AUDIO_KNOWN] == 1.0) sq += (r[AUDIO_COS] - mu) * (r[AUDIO_COS] - mu)
        val sd = sqrt(sq / n)
        if (sd <= 1e-9) return
        for (i in rows.indices) if (rows[i][AUDIO_KNOWN] == 1.0) scores[i] = scores[i] + model.audioWeight * (rows[i][AUDIO_COS] - mu) / sd
    }

    private fun spread(s: DoubleArray, open: BooleanArray): Double {
        var n = 0
        var sum = 0.0
        for (i in s.indices) if (open[i] && s[i].isFinite()) { n++; sum += s[i] }
        if (n == 0) return 0.0
        val mean = sum / n
        var squares = 0.0
        for (i in s.indices) if (open[i] && s[i].isFinite()) squares += (s[i] - mean) * (s[i] - mean)
        return sqrt(squares / n)
    }
}

/**
 * One plan request (§7.2b). [candidates] are the tracks the queue may take (playable, not queued, not removed);
 * [recentIds] the queue's rows before [seed], newest last (the dedup window); [exclude] what was heard in the last 24
 * hours or earlier in this session; [history] the listener's plays (FULL and the ladder's recency rung).
 */
public class PlanRequest(
    public val seed: TrackDescriptor,
    public val library: List<TrackDescriptor>,
    public val candidates: List<TrackDescriptor>,
    public val length: Int,
    public val exclude: Set<TrackId> = emptySet(),
    public val recentIds: List<TrackId> = emptyList(),
    public val press: Long = 0,
    public val history: PersonalHistory = PersonalHistory.EMPTY,
    /** Use the FULL model when it is loaded ("Learn from my habits"). */
    public val personal: Boolean = false,
    /** Plan with the rule-only path (§7.2: asset failure, heap bound, circuit breaker, kill switch). */
    public val ruleOnly: Boolean = false,
    /** PLAN_L1 §2c: the tracks this seed's earlier presses showed, newest press first (the freshness term). */
    public val impressions: List<Set<TrackId>> = emptyList(),
    /** Fast track 2 soft novelty: ms since each [exclude] id's last play (absent = 0). */
    public val excludeAgeMs: Map<TrackId, Long> = emptyMap(),
    /**
     * Audit 03 #1: the queue a top-up continues — its tracks after [seed], oldest first. The seed stays the anchor; the
     * prefix leaves the candidates and the decoder starts from it as from its own earlier picks. Empty = a fresh plan.
     */
    public val prefix: List<TrackId> = emptyList(),
    /** The press time (epoch ms): fast 3.1's holiday season reads it. */
    public val seedMs: Long = 0,
    /** Fast 3: tracks the listener played after a press showed them (freshness.engaged = exempt). */
    public val engaged: Set<TrackId> = emptySet(),
    /** Fast 3.3 (FULL only): the listener's own playlists (M3U files, app playlists), each in its order. */
    public val playlists: List<List<TrackId>> = emptyList(),
    /** The artist-variety setting as a multiplier on the decoder's artist terms (1 = the model's tuned values, 0 = none). */
    public val varietyScale: Double = 1.0,
    /** PLAN_DECODER2 §19 AS: the current session's plays before the press as (track, start ms), any order (CORE and FULL). */
    public val recentPlays: List<Pair<TrackId, Long>> = emptyList(),
)

/** How a plan was made, for the aggregate log line, the circuit breaker and the on-device impression log. */
public class PlanReport(
    public val ids: List<TrackId>,
    public val model: String,
    public val poolSize: Int,
    public val fromModel: Int,
    public val fromLadder: Int,
    public val timedOut: Boolean,
    public val imputed: Int,
    /** One per id, in plan order. */
    public val picks: List<PlanPick> = emptyList(),
    /** What audio the plan could read (audit 2026-10-20 audio #2: indexing must not starve it). */
    public val audio: AudioCoverage = AudioCoverage.NONE,
    /** Aggregate counts for the per-press diagnostics log (pool family / language / album coverage, non-zero columns). */
    public val stats: Map<String, Int> = emptyMap(),
    /** Pool rows per retriever. */
    public val sourceCounts: Map<String, Int> = emptyMap(),
)

/**
 * The audio a plan read: whether the seed had a vector and e, how many pool rows had a vector ([poolVectors], -1 when
 * the model reads none) and an e ([poolEnergies], -1 likewise), how many audio neighbours R1 got, and which reads ran
 * out of their budget ([timedOut]: "impute", "vectors", "energy", "R1").
 */
public class AudioCoverage(
    public val seedVector: Boolean,
    public val seedEnergy: Boolean,
    public val poolVectors: Int,
    public val poolEnergies: Int,
    public val r1Neighbours: Int,
    public val timedOut: Set<String>,
    /** Rows the imputation cache did not hold yet (computed under the budget instead); -1 without a cache. */
    public val imputeFallbacks: Int = -1,
) {
    /** The log form: `seed=v,e pool=12/40v 30/40e r1=50 timeout=R1`. */
    public fun describe(poolSize: Int): String = buildString {
        append("seed=").append(if (seedVector) "v" else "-").append(',').append(if (seedEnergy) "e" else "-")
        if (poolVectors >= 0) append(" pool=").append(poolVectors).append('/').append(poolSize).append('v')
        if (poolEnergies >= 0) append(' ').append(poolEnergies).append('/').append(poolSize).append('e')
        append(" r1=").append(r1Neighbours)
        if (imputeFallbacks >= 0) append(" imputeUncached=").append(imputeFallbacks)
        if (timedOut.isNotEmpty()) append(" timeout=").append(timedOut.sorted().joinToString("+"))
    }

    public companion object {
        public val NONE: AudioCoverage = AudioCoverage(false, false, -1, -1, 0, emptySet())
    }
}

/**
 * How one planned track was chosen. [score] is the decoder's penalised score at its hop (NaN for a ladder pick);
 * [probability] the chance the salted draw had of choosing it — 1 / the near-best set's size (§8: a uniform draw), 1
 * for a ladder pick or a sole best; [tie] whether other rows shared the near-best set; [source] the retriever that
 * proposed it (R1 audio, R2 co-listening, R4 artist, R5 family, R6 favourites, R7 transitions, topup, whole,
 * rule-R4, rule-sample) or the ladder rung ("ladder:audio", …).
 */
public class PlanPick(
    public val score: Double,
    public val probability: Double,
    public val tie: Boolean,
    public val source: String,
)

/**
 * A track's knowledge as reference.py's fixtures give it (the golden seam): what [SmartRanker]'s `know()` derives
 * from the tags and the pack — the engine artist key, the artist-set cap key and keys, family, language, decade,
 * entity, the song-version key (hashed here as in the app) and the raw pack descriptor and LightFM vector.
 */
internal class ResolvedTrack(
    val engineArtist: String,
    val setArtist: Long,
    val artists: LongArray,
    val family: String?,
    val language: String?,
    val decade: Int?,
    val entity: Int,
    val version: String,
    val descriptor: FloatArray?,
    val lfm: FloatArray?,
    val year: Int? = null,
    val albumKey: String = "",
    val compilation: Boolean = false,
    val familyFromTrack: Boolean = false,
)

/**
 * A plan's decoder, hop by hop, for the debug diagnostics ("Diagnose recent SMART starts"; never on in normal use):
 * the [topK] best allowed rows by penalised score (and the pick), each with its raw and penalised scores, choice
 * probability, retriever, the run penalty / cap it took and its per-feature contributions w · z ([RankerModel.contributions],
 * mixed over the hop's anchors as the score is), plus the ladder's rungs. Rows carry the raw feature values too.
 */
public class PlanDiagnostics(public val topK: Int = 5) {
    public class Entry(
        public val id: TrackId,
        public val s: Double,
        public val t: Double,
        public val nearBest: Boolean,
        public val probability: Double,
        public val source: String,
        public val artistKey: String,
        public val penalty: Double,
        public val cap: Double,
        /** The re-entry term this row lost (PLAN_L1 §2a), 0 without one. */
        public val reentry: Double,
        public val contributions: DoubleArray,
        /** The row's feature values at the seed anchor, in [RankerFeatures.NAMES] order. */
        public val seedRow: DoubleArray,
        /** v5: run, freshness + novelty, arm-P audio gain, energy, re-entry (what the row lost / gained); null otherwise. */
        public val terms: DoubleArray? = null,
    )

    public class Hop(
        public val hop: Int,
        public val pick: TrackId,
        public val sd: Double,
        public val penalty: Double,
        public val runArtist: String,
        public val runLength: Int,
        public val capped: Boolean,
        public val nearBestSize: Int,
        public val floor: Double,
        public val entries: List<Entry>,
        /** Every pool row's rank at this hop by penalised score (1 = best), 0 when the row was not allowed; pool order. */
        public val ranks: IntArray = IntArray(0),
    )

    public var model: String = ""
    public var features: List<String> = emptyList()
    public var poolSize: Int = 0
    public var poolHow: String = ""
    /** How many pool tracks each retriever proposed. */
    public val sourceCounts: MutableMap<String, Int> = LinkedHashMap()
    public val hops: MutableList<Hop> = ArrayList()
    public val ladder: MutableList<Pair<TrackId, String>> = ArrayList()
    /** The pool, and every allowed row at hop 1 best first (the seed anchor alone), for "was it there, and why did it lose". */
    public var poolIds: List<TrackId> = emptyList()
    public val firstHop: MutableList<Entry> = ArrayList()
}

/** One plan as reference.py traces it (golden tests only). */
internal class PlanTrace {
    var query: String = ""
    var poolHow: String = ""
    var window: Int = 0
    var pool: List<TrackId> = emptyList()
    var seedFeatures: Array<DoubleArray> = emptyArray()
    val hops: MutableList<RankerQueue.HopTrace> = ArrayList()
    val draws: MutableList<Long?> = ArrayList()
    val ladder: MutableList<Pair<TrackId, String>> = ArrayList()
    var imputedTracks: List<TrackId> = emptyList()
    var albumPrefix: Boolean = false
    var semChain: Boolean = false
}

/**
 * SMART's learned ranker (docs/smart-ranker.md, PLAN_FINAL §1, §7, §8): exclusions, candidates (the whole eligible
 * pool when small, else the retrievers' quotas), features with audio-tagger imputation, the [RankerQueue] decoder with
 * song-version dedup and a time budget, then the ladder until the queue is full. Never throws for bad metadata and
 * never returns an empty plan while another eligible track exists.
 *
 * Knowledge per track is read once per library list; imputations are kept per track. Not thread-safe; the chooser
 * calls it under its lock.
 */
public class SmartRanker(
    private val model: RankerModel,
    private val knowledge: ArtistKnowledge?,
    private val neighbours: ArtistNeighbours?,
    private val vectors: ArtistVectors? = null,
    private val known: KnownArtists = KnownArtists.NONE,
    private val tagger: AudioTagger? = null,
    private val audio: AudioSource = AudioSource.NONE,
    private val fullModel: RankerModel? = null,
    /** Artist popularity (LJPOPS1), read only when the active model's `inputs.logpop` is artist_pop. */
    private val popularity: ArtistPopularity? = null,
    /** The pack entities' genre families (LJFAMS1): a resolved entity's family wins over the genre tag's (§9). */
    private val families: ArtistFamilies? = null,
    /**
     * PLAN_L2's genre taxonomy. With it and an L2 model, a track's family is its genre tag's (familyFromTrack), else
     * its artist's on the same taxonomy ([families] must then be the L2 table); year / album key / compilation are read.
     */
    private val taxonomy: GenreFamilies? = null,
    /** The tagger's output computed at indexing (audits 04 #2 / 05 #5); plans read it and fall back to the budget. */
    private val imputations: ImputationCache? = null,
    /** Fast 3.2 continuity heads computed from the press's vectors; null = the AudioSource's cached values. */
    private val linearHeads: LinearHeads? = null,
    /** artist_sets.py's as2 rules (audit 07 #4 / #7 / #10): on with the models trained on them (the FIT rebuild). */
    artistSetsAs2: Boolean = false,
) {
    /** as2 when the caller asks for it or the shipped model was trained with it (`"artistSets": "as2"`). */
    private val artistSetsAs2: Boolean = artistSetsAs2 || model.artistSetsAs2 || fullModel?.artistSetsAs2 == true
    private val versionKeyV2: Boolean = model.versionKeyV2 || fullModel?.versionKeyV2 == true
    private val l2Tracks: Boolean = taxonomy != null && (model.serving.l2 || fullModel?.serving?.l2 == true)

    /**
     * One track's knowledge. The descriptor and the LightFM vector are read on first use (a 10k library would hold
     * ~15 MB of descriptors otherwise); imputed blocks replace missing ones and carry their flag.
     */
    private inner class Known(
        val track: TrackDescriptor,
        packFamily: String?,
        val language: String?,
        val decade: Int?,
        val engineArtist: String,
        /** The artist-set cap key (64-bit; 0 = none): compact for 10k-track libraries. */
        val setArtist: Long,
        val artists: LongArray,
        val entity: Int,
        /** 64-bit hash of the song-version key. */
        val version: Long,
        val durationMs: Long?,
        /** PLAN_L2 track fields: the original year (else the year), the album key, compilation, family from the tag. */
        val year: Int? = null,
        val albumKey: String = "",
        val familyFromTrack: Boolean = false,
    ) {
        /** Set once per library (it needs every track's album key), see [knownOf]. */
        var compilation: Boolean = false
        // Read on first use; plain fields, not delegates: a 10k library keeps one Known per track.
        private var loaded = 0
        private var packDescriptorValue: FloatArray? = null
        private var lfmValue: FloatArray? = null
        val packDescriptor: FloatArray?
            get() {
                if (loaded and 1 == 0) {
                    loaded = loaded or 1
                    packDescriptorValue = runCatching {
                        knowledge?.descriptor(track.artist, track.artists.firstOrNull(), text = null)?.let(::normalized)
                    }.getOrNull()
                }
                return packDescriptorValue
            }
        val lfm: FloatArray?
            get() {
                if (loaded and 2 == 0) {
                    loaded = loaded or 2
                    lfmValue = if (entity >= 0) runCatching { vectors?.vector(entity)?.let(::normalized) }.getOrNull() else null
                }
                return lfmValue
            }
        var imputedDescriptor: FloatArray? = null
        var family: String? = packFamily
        var familyImputed: Boolean = false
        var familyProbabilities: Map<String, Double>? = null
        var imputationTried: Boolean = false
        val descriptor: FloatArray? get() = packDescriptor ?: imputedDescriptor
        val descriptorImputed: Boolean get() = packDescriptor == null && imputedDescriptor != null

        /** The golden seam: the pack descriptor and LightFM vector as given, not read. */
        fun preset(descriptor: FloatArray?, lfm: FloatArray?) {
            packDescriptorValue = descriptor?.let(::normalized)
            lfmValue = if (entity >= 0) lfm?.let(::normalized) else null
            loaded = 3
        }
    }

    /** Golden tests: resolved records in place of the pack and the parsers (reference.py's fixtures). */
    internal var resolver: ((TrackDescriptor) -> ResolvedTrack?)? = null

    /** Golden tests: the next plan's trace. */
    internal var tracer: PlanTrace? = null

    /** One string per distinct artist key across the cache. */
    private val artistStrings = HashMap<String, String>()
    private var knownFor: List<TrackDescriptor>? = null
    private var knownById: Map<TrackId, Known> = emptyMap()

    /** Library tracks whose artist has a pack entity / a co-listening list / a LightFM vector (build notes). */
    public var entityCoverage: Triple<Int, Int, Int> = Triple(0, 0, 0)
        private set

    public val modelName: String get() = model.name
    public val hasFullModel: Boolean get() = fullModel != null
    public val fullModelName: String? get() = fullModel?.name

    /** Diagnostics: the family the ranker resolved for [id] in the last planned library (track tag / artist / tagger). */
    public fun familyOf(id: TrackId): String? = knownById[id]?.family

    /** The pack language a planned track carries (null = unknown to the ranker); diagnostics only. */
    public fun languageOf(id: TrackId): String? = knownById[id]?.language

    /** The pack entity a planned track's artist resolved to (-1 = none, null = not planned yet); diagnostics only. */
    public fun entityOf(id: TrackId): Int? = knownById[id]?.entity

    /** The freshness block of the model a plan with these flags runs (PLAN_L1 §2c), or null when it has none. */
    public fun freshnessFor(personal: Boolean, ruleOnly: Boolean): RankerModel.Freshness? = when {
        ruleOnly -> null
        personal && fullModel != null -> fullModel.freshness
        else -> model.freshness
    }?.takeIf { it.lambda != 0.0 }

    private fun know(track: TrackDescriptor): Known {
        resolver?.invoke(track)?.let { r ->
            return Known(
                track = track,
                packFamily = r.family,
                language = r.language,
                decade = r.decade,
                engineArtist = artistStrings.getOrPut(r.engineArtist) { r.engineArtist },
                setArtist = r.setArtist,
                artists = r.artists,
                entity = r.entity,
                version = ArtistSets.keyHash(r.version),
                durationMs = track.durationMs?.takeIf { it in 1..MAX_DURATION_MS },
                year = r.year,
                albumKey = r.albumKey,
                familyFromTrack = r.familyFromTrack,
            ).also {
                it.preset(r.descriptor, r.lfm)
                it.compilation = r.compilation
            }
        }
        val primary = track.artists.firstOrNull()
        val facts = runCatching { knowledge?.facts(track.artist, primary) }.getOrNull()
        val entity = runCatching { knowledge?.entity(track.artist, primary) }.getOrNull() ?: -1
        val tagFamily = HabitFlow.family(track.genre)
        val artistFamily = if (entity >= 0) runCatching { families?.family(entity) }.getOrNull() else null
        // PLAN_L2: the track's own tag family on the twelve-family taxonomy first, else the artist's. Before L2
        // (PLAN_FINAL §9): the pack entity's family when the entity resolves, else the genre tag's. The artist-set
        // parser keeps the old tag family either way (its classical-composer rule reads the file's own genre).
        val trackFamily = if (l2Tracks) taxonomy?.familyOfTag(track.genre) else null
        val family = if (l2Tracks) trackFamily ?: artistFamily else artistFamily ?: tagFamily
        val set = runCatching { ArtistSets.parse(
            track.artist, track.title, known,
            // With as2 (the rebuild's taxonomy), the classical-composer rule reads the one family chain: GenreFamilies.
            genreFamily = if (artistSetsAs2 && taxonomy != null) taxonomy.familyOfTag(track.genre) else tagFamily,
            as2 = artistSetsAs2,
        ) }.getOrNull()
        // as2 (audit 07 #7): a placeholder credit ("Unknown Artist", "Various Artists", localised forms) is no artist.
        val engineArtist = (if (artistSetsAs2 && ArtistSets.isPlaceholder(track.artists.firstOrNull() ?: track.artist.orEmpty(), as2 = true)) ""
            else HabitSlots.artistKey(track)).let { k -> artistStrings.getOrPut(k) { k } }
        return Known(
            track = track,
            packFamily = family,
            language = facts?.language,
            decade = facts?.decade,
            engineArtist = engineArtist,
            setArtist = set?.capKey ?: 0L,
            artists = set?.artists?.toLongArray() ?: EMPTY_ARTISTS,
            entity = entity,
            version = ArtistSets.keyHash(versionKey(track, engineArtist, versionKeyV2)),
            durationMs = track.durationMs?.takeIf { it in 1..MAX_DURATION_MS },
            year = if (l2Tracks) (track.originalYear ?: track.year)?.takeIf { it > 0 } else null,
            albumKey = if (l2Tracks) AlbumKeys.albumKey(track.album, track.albumArtist, track.artist) else "",
            familyFromTrack = trackFamily != null,
        ).also { if (l2Tracks) it.compilation = AlbumKeys.isVarious(track.albumArtist) }
    }

    private fun knownOf(library: List<TrackDescriptor>): Map<TrackId, Known> {
        if (knownFor !== library) {
            artistStrings.clear()
            knownById = library.associate { it.id to know(it) }
            knownFor = library
            if (l2Tracks && resolver == null) {
                // PLAN_L2: an album key three or more artists share in the library is a compilation.
                val artistsOn = HashMap<String, MutableSet<String>>()
                for (k in knownById.values) if (k.albumKey.isNotEmpty()) artistsOn.getOrPut(k.albumKey) { HashSet() } += k.engineArtist
                for (k in knownById.values) if (k.albumKey.isNotEmpty() && (artistsOn[k.albumKey]?.size ?: 0) >= 3) k.compilation = true
            }
            val values = knownById.values
            entityCoverage = Triple(
                values.count { it.entity >= 0 },
                neighbours?.let { n -> values.count { it.entity >= 0 && n.has(it.entity) } } ?: 0,
                vectors?.let { v -> values.count { it.entity >= 0 && v.has(it.entity) } } ?: 0,
            )
        }
        return knownById
    }

    /** Fills a missing descriptor or family from the audio tagger (flagged imputed); counts the tracks it filled. */
    private suspend fun impute(
        rows: List<Known>,
        timeouts: MutableSet<String>? = null,
        fallbacks: IntArray? = null,
        /** Vectors already read for this plan (the batched read); others are asked of [audio]. */
        read: Map<TrackId, FloatArray> = emptyMap(),
    ): Int {
        suspend fun vectorOf(id: TrackId): FloatArray? = read[id] ?: runCatching { audio.vector(id) }.getOrNull()
        val head = tagger ?: return 0
        var filled = 0
        // The cached outputs first: a plain read, no budget, the same result on every phone and at every moment.
        val missing = ArrayList<Pair<Known, FloatArray>>()
        for (k in rows) {
            if (k.imputationTried || (k.descriptor != null && k.family != null)) continue
            val cache = imputations ?: break
            val embedding = vectorOf(k.track.id) ?: continue
            val hit = cache.cached(k.track.id, embedding)
            if (!hit.hit) { missing += k to embedding; continue }
            k.imputationTried = true
            if (apply(k, hit.imputed ?: continue)) filled++
        }
        if (imputations != null) {
            fallbacks?.let { it[0] += missing.size }
            if (missing.isEmpty()) return filled
        }
        // Not backfilled yet (or no cache): computed here within its own budget; what it misses reads as unknown.
        val done = withTimeoutOrNull(AUDIO_BUDGET_MS) { for (k in rows) {
            if (k.imputationTried || (k.descriptor != null && k.family != null)) continue
            val embedding = vectorOf(k.track.id) ?: continue
            k.imputationTried = true
            val out = (if (imputations != null) imputations.compute(k.track.id, embedding) else head.impute(embedding)) ?: continue
            if (apply(k, out)) filled++
        } }
        if (done == null) timeouts?.add("impute")
        return filled
    }

    /** Fills [k]'s missing descriptor / family from the tagger's [out] (flagged imputed). */
    private fun apply(k: Known, out: AudioTagger.Imputed): Boolean {
        if (k.descriptor == null) k.imputedDescriptor = out.descriptor
        if (k.family == null) {
            k.family = out.family
            k.familyImputed = true
            k.familyProbabilities = out.familyProbabilities
        }
        return true
    }

    /**
     * Audits 04 #2 / 05 #5: the tagger's output for every [library] track with a vector, computed once and kept (no
     * budget). Returns how many were computed; 0 without a tagger or a cache.
     */
    public suspend fun backfillImputations(library: List<TrackDescriptor>): Int =
        imputations?.backfill(library.map { it.id }) { id -> runCatching { audio.vector(id) }.getOrNull() } ?: 0

    public suspend fun plan(request: PlanRequest): PlanReport = plan(request, null)

    /** [plan] with [diagnostics] filled (the debug action; the time budget is not applied). */
    public suspend fun plan(request: PlanRequest, diagnostics: PlanDiagnostics?): PlanReport {
        val active = when {
            request.ruleOnly -> RankerModel.RULE_SET
            request.personal && fullModel != null -> fullModel
            else -> model
        }
        val serving = active.serving
        val cache = knownOf(request.library)
        val seedKnown = cache[request.seed.id] ?: know(request.seed)
        val prefixIds = request.prefix.filter { it in cache }
        val preSet = prefixIds.toHashSet()
        val prefixKnown = prefixIds.lastOrNull()?.let { cache[it] }
        val eligible = request.candidates
            .filter { it.id != request.seed.id && it.id !in preSet && !isNonMusic(it, artistSetsAs2) }
            .distinctBy { it.id }
        val n = eligible.size
        if (n == 0 || request.length <= 0) return PlanReport(emptyList(), active.name, 0, 0, 0, false, 0)
        val sources = HashMap<TrackId, String>()
        val eligibleKnown = eligible.map { cache[it.id] ?: know(it) }
        val byId = eligibleKnown.associateBy { it.track.id }
        val draws = HopDraws(SaltedKey.seed(SaltedKey.SALT, SaltedKey.contentKey(request.seed), request.press))
        val salted = saltedOrder(eligibleKnown, request)
        // Fast 3.3 (FULL only): the user's own playlists, each track -> (playlist index, distinct size).
        val playlistsOf = HashMap<TrackId, MutableList<Pair<Int, Int>>>()
        if (active.playlistBonus != null && request.personal) {
            for ((p, list) in request.playlists.withIndex()) {
                val distinct = list.distinct()
                for (id in distinct) playlistsOf.getOrPut(id) { ArrayList(1) } += p to distinct.size
            }
        }
        // Fast track 2 soft novelty: only the hardLast newest of the novelty set stay excluded (ties by id bytes); the
        // rest join the source and lose λ_n · sd · (1 − age / 24 h) in the decoder.
        var exclude = request.exclude
        // vc20 / r5: the seed's habitual followers (≥ followMin picks right after it) skip the 24 h exclusion (FULL).
        (active.followerExemptMin ?: active.personalNudge?.followMin)?.takeIf { request.personal && exclude.isNotEmpty() }?.let { fm ->
            val keep = request.history.followers(request.seed.id).filter { request.history.followCount(request.seed.id, it) >= fm }.toSet()
            if (keep.isNotEmpty()) exclude = exclude - keep
        }
        var noveltyAge: Map<TrackId, Long> = emptyMap()
        active.softNovelty?.takeIf { exclude.isNotEmpty() }?.let { sn ->
            val ages = request.excludeAgeMs
            val order = exclude.sortedWith(compareBy<TrackId> { ages[it] ?: 0L }.thenComparator { a, b -> SaltedKey.idOrder.compare(a.value, b.value) })
            val hard = order.take(sn.hardLast).toHashSet()
            noveltyAge = exclude.filter { it !in hard }.associateWith { ages[it] ?: 0L }
            exclude = hard
        }
        val notExcluded = eligibleKnown.filter { it.track.id !in exclude }
        // The seed's imputation comes first, so R5 retrieves by an imputed family on the first plan (§1.3).
        val timeouts = HashSet<String>()
        var r1Count = 0
        val imputeFallbacks = IntArray(1)
        var imputed = impute(listOf(seedKnown), timeouts, imputeFallbacks)
        // Novelty can empty a small library; then the ladder may still take recently heard tracks.
        val poolSource = notExcluded.ifEmpty { eligibleKnown }
        val trace = tracer
        trace?.query = SaltedKey.contentKey(request.seed)
        trace?.poolHow = when {
            request.ruleOnly -> "rule-only"
            poolSource.size <= serving.wholeLibraryUpTo -> "whole"
            else -> "retrievers"
        }
        val pool = when {
            request.ruleOnly -> ruleOnlyPool(poolSource, seedKnown, salted, sources)
            poolSource.size <= serving.wholeLibraryUpTo -> poolSource
            else -> retrieve(poolSource, seedKnown, request, serving, salted, sources, active, playlistsOf) { got, timedOut ->
                r1Count = got
                if (timedOut) timeouts += "R1"
            }
        }.sortedWith { a, b -> SaltedKey.idOrder.compare(a.track.id.value, b.track.id.value) }
        // Audits 05 #2 / #5: the seed's and the pool's vectors and e in one batched read before anything uses them: no
        // per-row budget, so a plan never depends on how busy the phone is; all-or-nothing under a safety cap.
        // The prefix's last track (the first top-up hop's previous anchor) has its vector and e too.
        val readIds = (pool.map { it.track.id } + seedKnown.track.id + listOfNotNull(prefixKnown?.track?.id)).distinct()
        val audioIds = readIds
        val rawVectors: Map<TrackId, FloatArray> = withTimeoutOrNull(AUDIO_BATCH_CAP_MS) {
            runCatching { audio.vectors(readIds) }.getOrNull().orEmpty()
        } ?: emptyMap<TrackId, FloatArray>().also { timeouts += "vectors" }
        val rawEnergies: Map<TrackId, Double> = if (active.energy != null || active.r1EnergyTau != null) {
            withTimeoutOrNull(AUDIO_BATCH_CAP_MS) { runCatching { audio.energies(readIds) }.getOrNull().orEmpty() }
                ?: emptyMap<TrackId, Double>().also { timeouts += "energy" }
        } else {
            emptyMap()
        }
        imputed += impute(pool, timeouts, imputeFallbacks, rawVectors)
        // The prefix anchor is imputed like the seed (reference.py: after the pool).
        if (prefixKnown != null) imputed += impute(listOf(prefixKnown), timeouts, imputeFallbacks, rawVectors)
        val window = minOf(serving.recentWindow, n / 2).coerceAtLeast(1)
        val previousIds = request.recentIds + prefixIds.filter { it !in request.recentIds } + request.seed.id
        // PLAN_DECODER2 §19 AS: the session's last two plays within 30 min before the press, both on the seed's album key.
        val albumPrefix = seedKnown.albumKey.isNotEmpty() && request.recentPlays
            .filter { (_, ms) -> request.seedMs - HabitSlots.SESSION_GAP_MS <= ms && ms < request.seedMs }
            .sortedBy { it.second }.takeLast(2)
            .let { last -> last.size == 2 && last.all { (id, _) -> cache[id]?.albumKey == seedKnown.albumKey } }
        trace?.albumPrefix = albumPrefix
        fun versionOf(id: TrackId) = (byId[id] ?: cache[id])?.version ?: ArtistSets.keyHash("id:${id.value}")
        val artistKeyOf = { k: Known -> if (serving.artistKey == "artistSet") (if (k.setArtist == 0L) "" else k.setArtist.toString()) else k.engineArtist }
        val audioVectors = HashMap<TrackId, FloatArray?>()
        val usesAudio = active.features.contains("audio_cos") || active.audioWeight != 0.0
        if (usesAudio) for (id in audioIds) audioVectors[id] = rawVectors[id]?.let(::normalized)
        // PLAN_AUDIO_ON §8.2: e per pool row (NaN = unknown), from the same batched read.
        val energies = if (active.energy != null) DoubleArray(pool.size) { i -> rawEnergies[pool[i].track.id]?.takeIf { it.isFinite() } ?: Double.NaN } else null
        val seedEnergy = if (active.energy != null) rawEnergies[seedKnown.track.id]?.takeIf { it.isFinite() } else null
        // PLAN_L1 §2c: Σ_p delta^p over the presses (newest first) whose plan showed the row.
        val fresh = active.freshness?.takeIf { it.lambda != 0.0 && request.impressions.isNotEmpty() }?.let { f ->
            val sets = request.impressions.take(f.presses)
            // Fast 3: tracks played after being shown take no impression weight (engaged = exempt).
            val engaged = if (f.engagedExempt) request.engaged else emptySet()
            DoubleArray(pool.size) { i ->
                var w = 0.0
                val id = pool[i].track.id
                for ((p, shown) in sets.withIndex()) if (id in shown && id !in engaged) w += f.delta.pow(p)
                w
            }
        }
        // PLAN_L1 §2b: FULL's seed gate, from the seed-anchor rows before gating, applied to every anchor's rows.
        var gateValues: DoubleArray? = null
        val popularityOn = active.logpop == RankerModel.LOGPOP_ARTIST && popularity != null
        val personalRows = if (active.features.any { it in RankerFeatures.PERSONAL }) request.history else null
        // Hearts count only in a FULL plan ("Learn from my habits"), whatever columns the model names (reference.py).
        val hearts = if (request.personal) request.history else null
        val budgetMs = serving.hopBudgetMs * request.length
        // The decoder's own budget: preparation (knowledge, retrieval, imputation) has its own limits above.
        val decoding = TimeSource.Monotonic.markNow()
        val hopScores = ArrayList<Double>()
        val hopChoices = ArrayList<Int>()
        val diagPicks = ArrayList<Int>()
        val diagRows = HashMap<Int, Array<DoubleArray>>()
        val artistKeysOfPool = if (diagnostics != null) pool.map(artistKeyOf) else emptyList()
        diagnostics?.let { d ->
            d.model = active.name
            d.features = active.features + "audio"
            d.poolSize = pool.size
            for (k in pool) d.sourceCounts[sources[k.track.id] ?: "whole"] = (d.sourceCounts[sources[k.track.id] ?: "whole"] ?: 0) + 1
            d.poolHow = when {
                request.ruleOnly -> "rule-only"
                poolSource.size <= serving.wholeLibraryUpTo -> "whole"
                else -> "retrievers"
            }
        }
        // Cold fix: semChain for a seed the pack does not know (scope cold), centred on the library's means.
        val sem = active.semChain?.takeIf { !it.coldOnly || seedKnown.entity < 0 }?.let { spec ->
            withTimeoutOrNull(AUDIO_BATCH_CAP_MS) { semChainFor(spec, seedKnown, prefixKnown, pool, cache.values, rawVectors) }
                ?: null.also { timeouts += "semChain" }
        }
        trace?.semChain = sem != null
        var seedRowsSeen: Array<DoubleArray>? = null
        // Night r4: same-song dedup on the normalised title (seed, prefix and picks).
        val songKeys = active.songDedupMinChars?.let { mc -> pool.map { SongKey.of(it.track.title, mc) } }
        val songBase = if (songKeys == null) emptySet() else
            (listOf(seedKnown.track.title) + prefixIds.map { cache.getValue(it).track.title }).mapNotNull { SongKey.of(it, active.songDedupMinChars!!) }.toSet()
        val modelPicks = RankerQueue.build(
            model = active,
            poolSize = pool.size,
            artistKeys = pool.map(artistKeyOf),
            seedArtist = artistKeyOf(seedKnown),
            albumKeys = if (active.queueAlbumCap == null) null else pool.map { AlbumCore.of(it.track.album) },
            seedAlbum = if (active.queueAlbumCap == null) "" else AlbumCore.of(seedKnown.track.album),
            tieOrder = IntArray(pool.size) { it },
            length = request.length,
            choose = { hop, nearBest ->
                trace?.draws?.add(draws.output(hop + 1))
                nearBest[draws.pick(hop + 1, nearBest.size)]
            },
            blocked = { row, picks ->
                blockedVersion(pool[row].version, picks.map { pool[it].track.id }, previousIds, window, ::versionOf) ||
                    (songKeys != null && songKeys[row] != null && (songKeys[row] in songBase || picks.any { songKeys[it] == songKeys[row] } ||
                        active.songDedupPrefixChars?.let { pc ->
                            val k = songKeys[row]!!
                            songBase.any { SongKey.prefixMatch(it, k, pc) } || picks.any { p -> songKeys[p]?.let { SongKey.prefixMatch(it, k, pc) } == true }
                        } == true))
            },
            stop = { decoding.elapsedNow().inWholeMilliseconds > budgetMs && trace == null && diagnostics == null },
            skipWhenTied = request.ruleOnly,
            credits = if (active.artistReentry != null) pool.map { it.artists } else null,
            seedCredits = seedKnown.artists,
            fresh = fresh,
            families = if (active.familyMismatch != null || active.classMismatch != null) pool.map { it.family } else null,
            seedFamily = seedKnown.family,
            holiday = active.holidaySeason?.takeIf { !Holidays.inSeason(request.seedMs) && !Holidays.isHoliday(seedKnown.track) }
                ?.let { BooleanArray(pool.size) { i -> Holidays.isHoliday(pool[i].track) } },
            heads = if (active.continuity.isEmpty()) null else active.continuity.map { h ->
                DoubleArray(pool.size) { i -> headValue(h.name, pool[i].track.id, rawVectors) ?: Double.NaN } to
                    headValue(h.name, seedKnown.track.id, rawVectors)
            },
            rulePenalties = (if (active.durationRule == null && active.titleFlags == null) null else RulePenalties.of(
                active, seedKnown.durationMs, seedKnown.track.title, pool.map { it.durationMs }, pool.map { it.track.title },
            )).let { rp ->
                // Cold fix round 2: the hub terms join the rule penalties (reference.py: rule_pen + hub_pen).
                val hub = hubPenalties(active, seedKnown, pool, cache.values, rawVectors)
                when {
                    hub == null -> rp
                    rp == null -> hub
                    else -> DoubleArray(pool.size) { rp[it] + hub[it] }
                }
            }.let { rp ->
                // Night 2026-10-21: the lexicon classes join the rule penalties.
                val classes = active.tagClasses
                if (classes.isEmpty()) rp else {
                    val seedIn = classes.filter { it.matches(seedKnown.track) }.map { it.name }.toSet()
                    val out = rp?.copyOf() ?: DoubleArray(pool.size)
                    for (c in classes) {
                        if (c.name in seedIn) continue
                        if (c.seedIn.isNotEmpty() && c.seedIn.none { it in seedIn }) continue
                        for (i in pool.indices) if (c.matches(pool[i].track)) out[i] += c.lambda
                    }
                    out.takeIf { o -> o.any { it != 0.0 } }
                }
            },
            seedUnknown = seedKnown.family == null && seedKnown.packDescriptor == null && seedKnown.imputedDescriptor == null,
            prefix = prefixIds.map { id -> cache.getValue(id).let { k -> artistKeyOf(k) to k.artists } }.ifEmpty { null },
            prefixEnergy = prefixKnown?.let { rawEnergies[it.track.id]?.takeIf { e -> e.isFinite() } },
            playlistBonus = if (playlistsOf.isEmpty()) null else { anchor: Int ->
                val anchorId = when (anchor) {
                    RankerQueue.SEED -> request.seed.id
                    RankerQueue.PREFIX -> prefixIds.last()
                    else -> pool[anchor].track.id
                }
                val anchorLists = playlistsOf[anchorId]?.associate { it }
                if (anchorLists.isNullOrEmpty()) null else DoubleArray(pool.size) { i ->
                    var best = 0.0
                    for ((p, size) in playlistsOf[pool[i].track.id].orEmpty()) {
                        if (p in anchorLists) best = maxOf(best, 1.0 / kotlin.math.log2(2.0 + size))
                    }
                    best
                }
            },
            noRepeat = active.noRepeat?.let { nr ->
                // PLAN_DECODER2 v4.6 (FULL only): by the pre-session seed-artist share alone; how the seed started is never read.
                request.personal && request.history.preSessionShare(
                    request.seedMs, { id -> cache[id]?.let(artistKeyOf) ?: "" }, artistKeyOf(seedKnown), nr.minPlays,
                )?.let { it < nr.rho } ?: false
            } ?: false,
            varietyScale = request.varietyScale,
            semTerms = sem?.let { chain ->
                { anchor: Int ->
                    chain.terms(
                        when (anchor) {
                            RankerQueue.SEED -> null
                            RankerQueue.PREFIX -> prefixKnown!!.track.id
                            else -> pool[anchor].track.id
                        },
                    )
                }
            },
            familyMismatchLambda = if (seedKnown.entity < 0) active.familyColdLambda else null,
            novelty = if (active.softNovelty != null) DoubleArray(pool.size) { i ->
                noveltyAge[pool[i].track.id]?.let { maxOf(0.0, 1.0 - it.toDouble() / NOVELTY_MS) } ?: 0.0
            } else null,
            energies = energies,
            seedEnergy = seedEnergy,
            audioAgainst = if (active.audioAgainstPrevious && active.audioWeight != 0.0) { anchor: Int ->
                val col = RankerFeatures.NAMES.indexOf("audio_cos")
                val rows = gatedRows(anchor, seedKnown, pool, artistKeyOf, audioVectors, personalRows, request.seed.id, popularityOn, hearts, active, prefixKnown, albumPrefix) { gateValues = it ?: gateValues; gateValues }
                DoubleArray(pool.size) { rows[it][col] }
            } else null,
            trace = { h: RankerQueue.HopTrace ->
                hopScores += h.t[h.pick]
                hopChoices += h.nearBest.size
                if (diagnostics != null) {
                    diagnose(diagnostics, h, active, pool, sources, artistKeysOfPool, diagPicks, diagRows)
                    diagPicks += h.pick
                }
                if (trace != null) {
                    if (trace.draws.size < trace.hops.size + 1) trace.draws.add(null)
                    trace.hops += h
                }
            },
        ) { anchor ->
            gatedRows(anchor, seedKnown, pool, artistKeyOf, audioVectors, personalRows, request.seed.id, popularityOn, hearts, active, prefixKnown, albumPrefix) { gateValues = it ?: gateValues; gateValues }
                .also { if (diagnostics != null) diagRows[anchor] = it }
                .also { if (anchor == RankerQueue.SEED && seedRowsSeen == null) seedRowsSeen = it }
        }.map { pool[it].track.id }
        val timedOut = modelPicks.size < minOf(request.length, pool.size) && decoding.elapsedNow().inWholeMilliseconds > budgetMs
        val picks = ArrayList(modelPicks)
        val planPicks = ArrayList<PlanPick>(request.length)
        for ((h, id) in modelPicks.withIndex()) {
            val size = hopChoices[h]
            planPicks += PlanPick(hopScores[h], 1.0 / size, size > 1, sources[id] ?: "whole")
        }
        val ladder = Ladder(
            eligibleKnown, byId, previousIds, window, salted, request.history, ::versionOf,
            artistKeyOf, artistKeyOf(seedKnown), active.thirdInRowCap > 0.0 || active.artistReentry != null,
        )
        while (picks.size < request.length) {
            val (next, rung) = ladder.next(picks, audio) ?: break
            picks += next
            planPicks += PlanPick(Double.NaN, 1.0, false, "ladder:$rung")
            diagnostics?.ladder?.add(next to rung)
            trace?.ladder?.add(next to rung)
        }
        if (trace != null) {
            trace.window = window
            trace.pool = pool.map { it.track.id }
            trace.seedFeatures = if (pool.isEmpty()) emptyArray() else
                gatedRows(RankerQueue.SEED, seedKnown, pool, artistKeyOf, audioVectors, personalRows, request.seed.id, popularityOn, hearts, active, prefixKnown, albumPrefix) { gateValues = it ?: gateValues; gateValues }
            trace.imputedTracks = (pool + seedKnown).filter { it.descriptorImputed || it.familyImputed }.map { it.track.id }
                .distinct().sortedWith { a, b -> SaltedKey.idOrder.compare(a.value, b.value) }
        }
        val coverage = AudioCoverage(
            seedVector = rawVectors[seedKnown.track.id] != null,
            seedEnergy = rawEnergies[seedKnown.track.id]?.isFinite() == true,
            poolVectors = pool.count { rawVectors[it.track.id] != null },
            poolEnergies = energies?.count { !it.isNaN() } ?: -1,
            r1Neighbours = r1Count,
            timedOut = timeouts,
            imputeFallbacks = if (imputations != null) imputeFallbacks[0] else -1,
        )
        // Per-press diagnostics (the phone's smart_press_diag log): how the family, album and language columns filled.
        val stats = LinkedHashMap<String, Int>()
        stats["poolWithFamily"] = pool.count { it.family != null }
        stats["poolFamilyFromTrack"] = pool.count { it.familyFromTrack }
        stats["poolWithLanguage"] = pool.count { it.language != null }
        stats["poolWithAlbumKey"] = pool.count { it.albumKey.isNotEmpty() }
        stats["taxonomyFamilies"] = taxonomy?.families?.size ?: 0
        stats["albumPrefix"] = if (albumPrefix) 1 else 0
        stats["seedFamilyKnown"] = if (seedKnown.family != null) 1 else 0
        stats["seedLanguageKnown"] = if (seedKnown.language != null) 1 else 0
        seedRowsSeen?.let { rows ->
            for (name in listOf("same_family", "family_known", "family_is_track", "same_lang", "lang_known", "colisten", "lfm_cos", "album_session")) {
                val c = RankerFeatures.NAMES.indexOf(name)
                if (c >= 0) stats["nz_$name"] = rows.count { it.size > c && it[c] != 0.0 }
            }
        }
        val sourceCounts = LinkedHashMap<String, Int>()
        for (k in pool) sourceCounts[sources[k.track.id] ?: "whole"] = (sourceCounts[sources[k.track.id] ?: "whole"] ?: 0) + 1
        return PlanReport(
            picks, active.name, pool.size, modelPicks.size, picks.size - modelPicks.size, timedOut, imputed, planPicks, coverage,
            stats = stats, sourceCounts = sourceCounts,
        )
    }

    /** Per-library CSLS hub scores (0.9.1 SmartSnapshot.computeHubPenalty), kept until the library or its audio changes. */
    private var hubCache: Pair<Long, Map<TrackId, Double>>? = null

    /** β · hub(i) plus the hub-genre damp per pool row; null when the model has neither. */
    private suspend fun hubPenalties(
        model: RankerModel,
        seed: Known,
        pool: List<Known>,
        library: Collection<Known>,
        poolVectors: Map<TrackId, FloatArray>,
    ): DoubleArray? {
        val spec = model.hubPenalty
        val damp = model.hubGenreDamp
        if (spec == null && damp == null) return null
        var out: DoubleArray? = null
        if (spec != null) {
            val hubs = hubScores(spec, library, poolVectors)
            out = DoubleArray(pool.size) { spec.beta * (hubs[pool[it].track.id] ?: 0.0) }
        }
        if (damp != null) {
            val seedHub = genreIsHub(seed.track.genre)
            val gp = DoubleArray(pool.size) { if (genreIsHub(pool[it].track.genre) && !seedHub) -kotlin.math.ln(damp) else 0.0 }
            out = out?.let { o -> DoubleArray(pool.size) { o[it] + gp[it] } } ?: gp
        }
        return out
    }

    private suspend fun hubScores(spec: RankerModel.HubPenalty, library: Collection<Known>, poolVectors: Map<TrackId, FloatArray>): Map<TrackId, Double> {
        val ids = ArrayList<TrackId>(library.size)
        val units = ArrayList<FloatArray>(library.size)
        for (chunk in library.map { it.track.id }.chunked(SEM_BATCH)) {
            val rows = runCatching { audio.vectors(chunk) }.getOrNull().orEmpty()
            for (id in chunk) (poolVectors[id] ?: rows[id])?.let(::normalized)?.let { ids += id; units += it }
        }
        var key = ids.size.toLong()
        for (id in ids) key = key * 31 + id.value.hashCode()
        hubCache?.takeIf { it.first == key }?.let { return it.second }
        if (ids.size < 2 || ids.size > HUB_MAX_TRACKS) return emptyMap<TrackId, Double>().also { hubCache = key to it }
        val dim = units[0].size
        val mu = DoubleArray(dim)
        for (u in units) for (d in 0 until dim) mu[d] += u[d].toDouble()
        val m = Array(units.size) { i ->
            val r = DoubleArray(dim) { d -> units[i][d].toDouble() - mu[d] / units.size }
            var nn = 0.0
            for (x in r) nn += x * x
            nn = sqrt(nn)
            val div = if (nn > 0.0) nn else 1.0
            for (d in 0 until dim) r[d] = r[d] / div
            r
        }
        val ref = ids.indices.sortedWith { a, b -> SaltedKey.idOrder.compare(ids[a].value, ids[b].value) }.take(spec.sample)
        val k = minOf(spec.topk, ref.size - 1)
        val h = DoubleArray(m.size)
        val row = DoubleArray(ref.size)
        for (i in m.indices) {
            for ((col, j) in ref.withIndex()) {
                if (j == i) { row[col] = Double.NEGATIVE_INFINITY; continue }
                var dot = 0.0
                val a = m[i]
                val b = m[j]
                for (d in 0 until dim) dot += a[d] * b[d]
                row[col] = dot
            }
            val sorted = row.sortedArray()
            var sum = 0.0
            for (t in sorted.size - k until sorted.size) sum += sorted[t]
            h[i] = sum / k
        }
        var mean = 0.0
        for (x in h) mean += x
        mean /= h.size
        val out = HashMap<TrackId, Double>(ids.size * 2)
        for (i in ids.indices) out[ids[i]] = h[i] - mean
        hubCache = key to out
        return out
    }

    /** The cold fix's SemChain over the library's centred audio, pack-descriptor and metadata-text spaces. */
    private suspend fun semChainFor(
        spec: RankerModel.SemChainSpec,
        seed: Known,
        prefix: Known?,
        pool: List<Known>,
        library: Collection<Known>,
        poolVectors: Map<TrackId, FloatArray>,
    ): SemChain {
        val ids = library.map { it.track.id }
        val needed = (pool.map { it.track.id } + seed.track.id + listOfNotNull(prefix?.track?.id)).toHashSet()
        // Audio: the library mean streamed in batches (no whole-library copy); the needed rows kept as unit vectors.
        var audioMean: SemChain.Companion.Mean? = null
        val audioUnit = HashMap<TrackId, FloatArray>()
        for (chunk in ids.chunked(SEM_BATCH)) {
            val rows = runCatching { audio.vectors(chunk) }.getOrNull().orEmpty()
            for (id in chunk) {
                val u = (poolVectors[id] ?: rows[id])?.let(::normalized) ?: continue
                (audioMean ?: SemChain.Companion.Mean(u.size).also { audioMean = it }).add(u)
                if (id in needed) audioUnit[id] = u
            }
        }
        val texts = runCatching { audio.textVectors(ids) }.getOrNull().orEmpty()
        var textMean: SemChain.Companion.Mean? = null
        val textUnit = HashMap<TrackId, FloatArray>()
        for (id in ids) {
            val u = texts[id]?.let(::normalized) ?: continue
            (textMean ?: SemChain.Companion.Mean(u.size).also { textMean = it }).add(u)
            if (id in needed) textUnit[id] = u
        }
        var descMean: SemChain.Companion.Mean? = null
        val descUnit = HashMap<TrackId, FloatArray>()
        for (k in library) {
            val u = k.packDescriptor ?: continue
            (descMean ?: SemChain.Companion.Mean(u.size).also { descMean = it }).add(u)
            if (k.track.id in needed) descUnit[k.track.id] = u
        }
        fun centredMap(unit: Map<TrackId, FloatArray>, mean: SemChain.Companion.Mean?): Map<TrackId, DoubleArray> {
            val mu = mean?.mean() ?: return emptyMap()
            val out = HashMap<TrackId, DoubleArray>()
            for ((id, u) in unit) SemChain.centred(u, mu)?.let { out[id] = it }
            return out
        }
        return SemChain(
            spec, seed.track.id, pool.map { it.track.id },
            centredMap(audioUnit, audioMean), centredMap(descUnit, descMean), centredMap(textUnit, textMean),
        )
    }

    private fun diagnose(
        d: PlanDiagnostics,
        h: RankerQueue.HopTrace,
        model: RankerModel,
        pool: List<Known>,
        sources: Map<TrackId, String>,
        artistKeys: List<String>,
        picks: List<Int>,
        rows: Map<Int, Array<DoubleArray>>,
    ) {
        val seedRows = rows[RankerQueue.SEED] ?: return
        val prevRows = picks.lastOrNull()?.let { rows[it] }
        fun contributions(i: Int): DoubleArray {
            val c0 = model.contributions(seedRows[i])
            if (picks.isEmpty() || prevRows == null || model.anchor == RankerModel.Anchor.SEED) return c0
            val c1 = model.contributions(prevRows[i])
            if (model.anchor == RankerModel.Anchor.PREVIOUS) return c1
            val total = model.seedWeight + model.previousWeight
            return DoubleArray(c0.size) { (model.seedWeight * c0[it] + model.previousWeight * c1[it]) / total }
        }
        val near = h.nearBest.toHashSet()
        val ranked = h.t.indices.filter { !h.t[it].isNaN() }.sortedByDescending { h.t[it] }
        if (d.hops.isEmpty()) d.poolIds = pool.map { it.track.id }
        val shown = (if (d.hops.isEmpty()) ranked else ranked.take(d.topK) + h.pick).distinct()
        val entries = shown.map { i ->
                val same = h.runArtist.isNotEmpty() && artistKeys[i] == h.runArtist
                PlanDiagnostics.Entry(
                    id = pool[i].track.id, s = h.s[i], t = h.t[i], nearBest = i in near,
                    probability = if (i in near) 1.0 / h.nearBest.size else 0.0,
                    source = sources[pool[i].track.id] ?: "whole", artistKey = artistKeys[i],
                    penalty = if (same) h.penalty else 0.0, cap = if (same && h.capped) model.thirdInRowCap else 0.0,
                    reentry = (h.s[i] - (if (same) h.penalty else 0.0) - (if (same && h.capped) model.thirdInRowCap else 0.0) - h.t[i])
                        .let { if (kotlin.math.abs(it) < 1e-12) 0.0 else it },
                    contributions = contributions(i), seedRow = seedRows[i],
                    terms = h.terms?.let { tm -> DoubleArray(tm.size) { tm[it][i] } },
                )
            }
        if (d.hops.isEmpty()) d.firstHop += entries
        val keep = ranked.take(d.topK).toHashSet().also { it += h.pick }
        d.hops += PlanDiagnostics.Hop(
            hop = d.hops.size + 1, pick = pool[h.pick].track.id, sd = h.sd, penalty = h.penalty, runArtist = h.runArtist,
            runLength = h.runLength, capped = h.capped, nearBestSize = h.nearBest.size, floor = h.floor,
            entries = shown.indices.filter { shown[it] in keep }.map { entries[it] },
            ranks = IntArray(pool.size).also { rk -> ranked.forEachIndexed { pos, i -> rk[i] = pos + 1 } },
        )
    }

    /**
     * One anchor's feature rows, with [RankerModel.seedGate] applied: the gate values come from the seed-anchor rows
     * before gating (computed on first use, handed back through [gate]) and scale the gate's columns of every row.
     */
    private fun gatedRows(
        anchor: Int,
        seedKnown: Known,
        pool: List<Known>,
        artistKeyOf: (Known) -> String,
        audioVectors: Map<TrackId, FloatArray?>,
        personalRows: PersonalHistory?,
        seedId: TrackId,
        popularityOn: Boolean,
        hearts: PersonalHistory?,
        model: RankerModel,
        prefixKnown: Known? = null,
        albumPrefix: Boolean = false,
        gate: (DoubleArray?) -> DoubleArray?,
    ): Array<DoubleArray> {
        fun raw(a: Known) = features(a, pool, artistKeyOf, audioVectors, personalRows, seedId, popularityOn, hearts, model.imputedUnknown, model.serving.l2, albumPrefix, model.langScript, model.langScriptLatin)
        val rows = raw(
            when (anchor) {
                RankerQueue.SEED -> seedKnown
                RankerQueue.PREFIX -> checkNotNull(prefixKnown) { "the prefix anchor without a prefix" }
                else -> pool[anchor]
            },
        )
        model.seedGate?.let { g ->
            val values = gate(null) ?: gate(gateValuesOf(g, if (anchor == RankerQueue.SEED) rows else raw(seedKnown)))!!
            for (i in rows.indices) for (c in g.columns) rows[i][c] = rows[i][c] * values[i]
        }
        // Cold fix: FULL's personal block is scaled down when the seed has no pack entity (after the gate); round 2
        // optionally on warm seeds too.
        val scale = if (seedKnown.entity < 0) model.semChain?.personalScale else model.semChain?.personalScaleWarm
        if (scale != null) {
            val j0 = RankerFeatures.NAMES.indexOf("heard")
            val keep = if (model.semChain?.transExempt == true) setOf(RankerFeatures.NAMES.indexOf("trans_track"), RankerFeatures.NAMES.indexOf("trans_artist")) else emptySet()
            for (r in rows) for (j in j0 until j0 + RankerFeatures.PERSONAL.size) if (j !in keep) r[j] = r[j] * scale
        }
        return rows
    }

    private fun artistKeyOfServing(serving: RankerModel.Serving): (Known) -> String =
        { k -> if (serving.artistKey == "artistSet") (if (k.setArtist == 0L) "" else k.setArtist.toString()) else k.engineArtist }

    private fun popularityOnFor(model: RankerModel) = model.logpop == RankerModel.LOGPOP_ARTIST && popularity != null

    private fun gateValuesOf(g: RankerModel.SeedGate, seedRows: Array<DoubleArray>): DoubleArray = DoubleArray(seedRows.size) { i ->
        val z = ((g.core.score(seedRows[i]) - g.mu) / g.sigma).coerceIn(-g.clip, g.clip)
        1.0 / (1.0 + kotlin.math.exp(-g.a * z))
    }

    private suspend fun energyOf(id: TrackId): Double? = runCatching { audio.energy(id) }.getOrNull()?.takeIf { it.isFinite() }

    /** A continuity head's value: computed from the press's vectors when the ranker has the heads, else the source's. */
    private suspend fun headValue(name: String, id: TrackId, vectors: Map<TrackId, FloatArray>): Double? {
        val h = linearHeads
        if (h != null && name in h.names) return vectors[id]?.let { v -> normalized(v)?.let { h.value(name, it) } }?.takeIf { it.isFinite() }
        return headOf(name, id)
    }

    private suspend fun headOf(name: String, id: TrackId): Double? = runCatching { audio.head(name, id) }.getOrNull()?.takeIf { it.isFinite() }

    /** Back-compatible entry point: the plan's ids. */
    public suspend fun plan(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        candidates: List<TrackDescriptor>,
        exclude: Set<TrackId>,
        length: Int,
        press: Long = 0,
    ): List<TrackId> = plan(PlanRequest(seed, library, candidates, length, exclude, press = press)).ids

    private fun features(
        anchor: Known,
        pool: List<Known>,
        artistKeyOf: (Known) -> String,
        audioVectors: Map<TrackId, FloatArray?>,
        personal: PersonalHistory?,
        seedId: TrackId,
        popularityOn: Boolean,
        hearts: PersonalHistory?,
        imputedUnknown: Boolean = false,
        l2: Boolean = false,
        albumPrefix: Boolean = false,
        langScript: Boolean = false,
        langScriptLatin: Boolean = false,
    ): Array<DoubleArray> {
        val related = HashMap<Int, Double>()
        fun languageOf(k: Known) = if (langScript) ScriptLanguage.effective(k.language, k.track.title, k.track.artist, langScriptLatin) else k.language
        // PLAN_L1 §10.5 RULE: an imputed descriptor is unknown for the descriptor columns, an imputed family for the
        // family columns (it still serves R5); the imputed flags themselves stay.
        fun familyOf(k: Known) = if (imputedUnknown && k.familyImputed) null else k.family
        fun probabilitiesOf(k: Known) = if (imputedUnknown && k.familyImputed) null else k.familyProbabilities
        if (anchor.entity >= 0) neighbours?.forEachNeighbour(anchor.entity) { n, s -> related[n] = s.toDouble() }
        val anchorAudio = audioVectors[anchor.track.id]
        val anchorArtists = anchor.artists
        val rows = pool.map { c ->
            val pop = if (popularityOn && c.entity >= 0) runCatching { popularity?.value(c.entity) }.getOrNull() else null
            RankerRow(
                cosine = if (imputedUnknown) cosine(anchor.packDescriptor, c.packDescriptor) else cosine(anchor.descriptor, c.descriptor),
                family = familyOf(c),
                language = languageOf(c),
                decade = c.decade,
                artistKey = artistKeyOf(c),
                colisten = if (c.entity >= 0) related[c.entity] ?: 0.0 else 0.0,
                logPopularity = pop ?: 0.0,
                popularityKnown = pop != null,
                favourite = hearts?.isFavourite(c.track.id) == true,
                favouritedDaysAgo = hearts?.favouritedDaysAgo(c.track.id) ?: Double.NaN,
                lfm = cosine(anchor.lfm, c.lfm),
                durationMs = c.durationMs,
                descriptorImputed = c.descriptorImputed,
                familyImputed = c.familyImputed,
                audio = cosine(anchorAudio, audioVectors[c.track.id]),
                artistOverlap = overlap(anchorArtists, c.artists),
                familyProbabilities = probabilitiesOf(c),
                year = c.year,
                albumKey = c.albumKey,
                compilation = c.compilation,
                familyFromTrack = c.familyFromTrack,
            )
        }
        val personalRows = personal?.let { h ->
            val anchorId = anchor.track.id.takeIf { anchor.track.id != seedId } ?: seedId
            pool.map { c -> h.row(c.track.id, anchorId, anchor.engineArtist) }
        }
        return RankerFeatures.of(
            RankerRow(
                family = familyOf(anchor), language = languageOf(anchor), decade = anchor.decade, artistKey = artistKeyOf(anchor),
                descriptorImputed = anchor.descriptorImputed, familyImputed = anchor.familyImputed,
                familyProbabilities = probabilitiesOf(anchor),
                durationMs = anchor.durationMs, year = anchor.year, albumKey = anchor.albumKey, compilation = anchor.compilation,
            ),
            rows,
            personalRows,
            l2,
            albumPrefix,
            langScript,
        )
    }

    /** §1.1's retrievers, each up to its quota, unioned in retriever order (R1, R2, R4, R5, then FULL's R6 / R7). */
    private suspend fun retrieve(
        pool: List<Known>,
        seed: Known,
        request: PlanRequest,
        serving: RankerModel.Serving,
        salted: Map<TrackId, Long>,
        sources: MutableMap<TrackId, String>,
        active: RankerModel,
        playlistsOf: Map<TrackId, List<Pair<Int, Int>>>,
        onR1: (neighbours: Int, timedOut: Boolean) -> Unit = { _, _ -> },
    ): List<Known> {
        val byId = pool.associateBy { it.track.id }
        val out = LinkedHashMap<TrackId, Known>()
        val capKey = artistKeyOfServing(serving)
        // PLAN_L2 (audit 04 #9): at most artistCap new tracks per artist from one retriever; R4 and the fills are exempt.
        fun take(ids: List<TrackId>, quota: Int, source: String, capped: Boolean = true) {
            var added = 0
            val perArtist = if (serving.l2 && capped) HashMap<String, Int>() else null
            for (id in ids) {
                if (added >= quota) break
                val k = byId[id] ?: continue
                if (id in out) continue
                if (perArtist != null) {
                    val a = capKey(k)
                    val c = perArtist[a] ?: 0
                    if (a.isNotEmpty() && c >= serving.artistCap) continue
                    perArtist[a] = c + 1
                }
                out[id] = k
                added++
                sources[id] = source
            }
        }
        val quota = { name: String -> serving.quotas[name] ?: 0 }
        val nbOrNull = withTimeoutOrNull(R1_BUDGET_MS) { runCatching { audio.neighbours(seed.track.id, quota("audio") * 2) }.getOrNull().orEmpty() }
        val nb = nbOrNull.orEmpty()
        onR1(nb.size, nbOrNull == null)
        val es = if (active.r1EnergyTau != null) energyOf(seed.track.id) else null
        if (es != null) {
            // PLAN_AUDIO_ON §8.8.3 R1e: half the quota nearest, half nearest within the seed's energy band; a band
            // shorter than half is filled from the cosine list.
            var tau = active.r1EnergyTau!!
            if (active.r1EnergyPoolScale) {
                // Fast track 2: τ in units of the source rows' e spread (population sd, floored), in source order.
                val known = ArrayList<Double>()
                for (k in pool) energyOf(k.track.id)?.let { known += it }
                val mu = if (known.isNotEmpty()) known.fold(0.0) { a, x -> a + x } / known.size else 0.0
                val sd = if (known.isNotEmpty()) maxOf(RankerQueue.E_SD_FLOOR, sqrt(known.fold(0.0) { a, x -> a + (x - mu) * (x - mu) } / known.size)) else RankerQueue.E_SD_FLOOR
                tau *= sd
            }
            val half = quota("audio") / 2
            val first = nb.filter { it in byId }.take(half)
            val firstSet = first.toHashSet()
            val band = nb.filter { it in byId && it !in firstSet && energyOf(it)?.let { e -> kotlin.math.abs(e - es) <= tau } == true }
            val bandSet = band.toHashSet()
            take(first + band + nb.filter { it !in firstSet && it !in bandSet }, quota("audio"), "R1")
        } else {
            take(nb, quota("audio"), "R1")
        }
        if (seed.entity >= 0 && neighbours != null) {
            val rank = HashMap<Int, Double>()
            neighbours.forEachNeighbour(seed.entity) { e, s -> rank[e] = s.toDouble() }
            take(pool.filter { it.entity in rank }.sortedWith(compareByDescending<Known> { rank[it.entity] ?: 0.0 }.thenBy { salted[it.track.id] })
                .map { it.track.id }, quota("colisten"), "R2")
        }
        val album = seed.track.album?.takeIf(String::isNotBlank)
        // PLAN_L2 §3.1b: the album key (title + album artist) when the seed has one, else the raw album string.
        val sameAlbum: (Known) -> Boolean = if (seed.albumKey.isNotEmpty()) { k -> k.albumKey == seed.albumKey } else { k -> album != null && k.track.album == album }
        take(pool.filter { (seed.setArtist != 0L && it.setArtist == seed.setArtist) || sameAlbum(it) }
            .sortedBy { salted[it.track.id] }.map { it.track.id }, quota("artist"), "R4", capped = false)
        if (seed.family != null) take(pool.filter { it.family == seed.family }.sortedBy { salted[it.track.id] }.map { it.track.id }, quota("family"), "R5")
        if (request.personal && (!request.history.isEmpty || request.history.hasFavourites)) {
            take(request.history.followers(seed.track.id), quota("own") / 2, "R7")
            // R6: the tracks the listener hearted first (id-byte order; a heart never played has no activation), then
            // the ACT-R order (reference.py, PLAN_E_GATES §10).
            var hearted = pool.filter { request.history.isFavourite(it.track.id) }.map { it.track.id }
                .sortedWith { a, b -> SaltedKey.idOrder.compare(a.value, b.value) }
            var favs = request.history.favourites()
            val gate = active.seedGate?.takeIf { it.orderR6 }
            // The seed gate over a set of tracks (their rows are z-scored among themselves, as reference.py's r6g).
            fun gateOver(ids: List<TrackId>): Map<TrackId, Double> {
                val ks = ids.mapNotNull { byId[it] }.sortedWith { a, b -> SaltedKey.idOrder.compare(a.track.id.value, b.track.id.value) }
                if (ks.isEmpty() || gate == null) return emptyMap()
                val rows = features(seed, ks, artistKeyOfServing(serving), emptyMap(), null, seed.track.id, popularityOnFor(active), null, active.imputedUnknown, active.serving.l2)
                val gv = gateValuesOf(gate, rows)
                return HashMap<TrackId, Double>().also { m -> ks.forEachIndexed { i, k -> m[k.track.id] = gv[i] } }
            }
            if (serving.l2 && gate != null && hearted.isNotEmpty()) {
                // Audit 06 #1: hearts in the gated order (−ln g), ties by salted key.
                val gm = gateOver(hearted)
                hearted = hearted.sortedWith(compareBy<TrackId> { -kotlin.math.ln(gm[it] ?: 0.5) }.thenBy { salted[it] })
            }
            if (gate != null) {
                // PLAN_L1 §10.4: ACT-R + ln g, g the seed gate over the heard rows in the pool (no audio, no personal rows).
                val g = gateOver(favs.filter { it in byId })
                if (g.isNotEmpty()) {
                    favs = favs.filter { it in g }.sortedWith(
                        compareByDescending<TrackId> { (request.history.activationOf(it) ?: 0.0) + kotlin.math.ln(g.getValue(it)) }
                            .thenComparator { a, b -> SaltedKey.idOrder.compare(a.value, b.value) },
                    ) + favs.filter { it !in g }
                }
            }
            take(hearted + favs, quota("own") - quota("own") / 2, "R6")
        }
        val r8 = active.playlistBonus
        val seedPlaylists = playlistsOf[seed.track.id]
        if (r8 != null && !seedPlaylists.isNullOrEmpty()) {
            // R8 (fast 3.3, FULL): tracks sharing a user playlist with the seed, salted order, its own per-artist cap.
            val shared = seedPlaylists.mapTo(HashSet()) { it.first }
            val ids = playlistsOf.entries.filter { (id, lists) -> id != seed.track.id && lists.any { it.first in shared } }
                .map { it.key }.sortedBy { salted[it] ?: 0L }
            val perArtist = HashMap<String, Int>()
            val got = ArrayList<TrackId>()
            for (id in ids) {
                val k = byId[id] ?: continue
                if (id in out) continue
                val a = capKey(k)
                val c = perArtist[a] ?: 0
                if (a.isNotEmpty() && c >= r8.artistCap) continue
                perArtist[a] = c + 1
                got += id
                if (got.size >= r8.quota) break
            }
            take(got, r8.quota, "R8", capped = false)
        }
        if (serving.l2) {
            // Audit 04 #1 / #11: fill to a fixed target, the seed's family first (capped), then a salted sample.
            val target = minOf(serving.poolTarget, pool.size)
            if (serving.unknownSeedAudioFill && out.size < target && seed.family == null && seed.packDescriptor == null &&
                seed.imputedDescriptor == null
            ) {
                // Fast 3: a seed with no family and no descriptor fills from its own audio neighbours (nearest first).
                val near = withTimeoutOrNull(R1_BUDGET_MS) { runCatching { audio.neighbours(seed.track.id, target) }.getOrNull() }.orEmpty()
                take(near, target - out.size, "fill-audio")
            }
            // Cold fix round 3: like 0.9.1 (no fill), a seed without a pack entity fills from its own audio neighbours.
            val coldNoFill = serving.coldAudioFill && (serving.audioFillAll || seed.entity < 0)
            if (coldNoFill && out.size < target) {
                val near = withTimeoutOrNull(R1_BUDGET_MS) { runCatching { audio.neighbours(seed.track.id, target) }.getOrNull() }.orEmpty()
                take(near, target - out.size, "fill-audio")
            }
            if (out.size < target && seed.family != null) {
                take(pool.filter { it.family == seed.family }.sortedBy { salted[it.track.id] }.map { it.track.id }, target - out.size, "fill-family")
            }
            if (out.size < target && !coldNoFill) take(pool.sortedBy { salted[it.track.id] }.map { it.track.id }, target - out.size, "fill", capped = false)
        }
        // Too few retrieved for a full queue: a salted sample tops the pool up (never a smaller pool than needed).
        if (out.size < request.length * 2) take(pool.sortedBy { salted[it.track.id] }.map { it.track.id }, request.length * 2, "topup", capped = false)
        return out.values.toList()
    }

    /** §7.2b's rule-only candidates: R4 from tags (quota 100) plus a 500-track sample in salted order. */
    private fun ruleOnlyPool(pool: List<Known>, seed: Known, salted: Map<TrackId, Long>, sources: MutableMap<TrackId, String>): List<Known> {
        val ordered = pool.sortedBy { salted[it.track.id] }
        val sameArtist = ordered.filter {
            (seed.setArtist != 0L && it.setArtist == seed.setArtist) ||
                (seed.track.album?.isNotBlank() == true && it.track.album == seed.track.album)
        }.take(100)
        for (k in sameArtist) sources[k.track.id] = "rule-R4"
        val sample = ordered.take(500)
        for (k in sample) sources.getOrPut(k.track.id) { "rule-sample" }
        return (sameArtist + sample).distinctBy { it.track.id }
    }

    /** Salted order key per track: the first 8 bytes of SHA-256(salt|query|press|id) (§7.2b). */
    private fun saltedOrder(rows: List<Known>, request: PlanRequest): Map<TrackId, Long> {
        val query = SaltedKey.contentKey(request.seed)
        return rows.associate { k ->
            val d = Sha256.digest("${SaltedKey.SALT}|$query|${request.press}|${k.track.id.value}".encodeToByteArray())
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
            k.track.id to (v xor Long.MIN_VALUE) // unsigned order as a signed Long
        }
    }

    /**
     * §7.2's ladder: each call adds one track, from the first rung that has one — audio neighbours of the last
     * pick, descriptor neighbours, the same genre family (salted), then the least-recently-played track outside the
     * recency window. These keep the decoder's third-in-a-row cap (the seed in the run) as a hard rule; on the last
     * rung it is relaxed first, then the dedup window shrinks to 1, then the recency window to the previous pick
     * (§7.2b). When every eligible track is queued, the queue repeats (§7.2b: n = 1 repeats its track, n = 2
     * alternates, n = 3 cycles): the least recent of the plan, the queue before it and the log, outside the recency
     * window and never a version of the previous pick while another track exists.
     */
    private inner class Ladder(
        private val eligible: List<Known>,
        private val byId: Map<TrackId, Known>,
        private val before: List<TrackId>,
        private val window: Int,
        private val salted: Map<TrackId, Long>,
        private val history: PersonalHistory,
        private val versionOf: (TrackId) -> Long,
        private val artistKeyOf: (Known) -> String,
        private val seedArtist: String,
        private val capOn: Boolean,
    ) {
        suspend fun next(picks: List<TrackId>, audio: AudioSource): Pair<TrackId, String>? {
            val taken = picks.toHashSet()
            val previous = picks.lastOrNull() ?: before.last()
            val previousKnown = byId[previous] ?: knownById[previous]
            // The run the queue ends with, the seed included (RankerQueue.build's rule).
            var runArtist = seedArtist
            var runLength = if (runArtist.isEmpty()) 0 else 1
            for (id in picks) {
                val a = byId[id]?.let(artistKeyOf).orEmpty()
                if (runArtist.isNotEmpty() && a == runArtist) runLength++ else {
                    runArtist = a
                    runLength = if (a.isEmpty()) 0 else 1
                }
            }
            val capAt = if (capOn && runLength >= 2) runArtist else ""
            fun ok(k: Known, dedup: Int, cap: Boolean = true) =
                k.track.id !in taken && !blockedVersion(k.version, picks, before, dedup, versionOf) &&
                    !(cap && capAt.isNotEmpty() && artistKeyOf(k) == capAt)
            boundedNeighbours(previous, 50, LADDER_AUDIO_BUDGET_MS)
                .firstNotNullOfOrNull { id -> byId[id]?.takeIf { ok(it, window) } }?.let { return it.track.id to "audio" }
            previousKnown?.descriptor?.let { d ->
                // A salted sample bounds the rung's work and memory on a large library.
                eligible.sortedBy { salted[it.track.id] }.take(LADDER_SAMPLE).filter { it.descriptor != null && ok(it, window) }
                    .maxWithOrNull(compareBy<Known> { cosine(d, it.descriptor) }.thenByDescending { salted[it.track.id] })
                    ?.let { return it.track.id to "descriptor" }
            }
            previousKnown?.family?.let { f ->
                eligible.filter { it.family == f && ok(it, window) }.minByOrNull { salted[it.track.id] ?: 0L }?.let { return it.track.id to "family" }
            }
            val recent = (before + picks).takeLast(window).toHashSet()
            fun lastRung(dedup: Int, recency: Set<TrackId>, cap: Boolean) = eligible
                .filter { ok(it, dedup, cap) && it.track.id !in recency }
                .minWithOrNull(compareBy<Known> { history.lastPlayIndex(it.track.id) ?: -1 }.thenBy { salted[it.track.id] })
            lastRung(window, recent, cap = true)?.let { return it.track.id to "least-recent" }
            lastRung(window, recent, cap = false)?.let { return it.track.id to "least-recent-nocap" }
            lastRung(1, recent, cap = false)?.let { return it.track.id to "least-recent-dedup1" }
            lastRung(1, setOf(previous), cap = false)?.let { return it.track.id to "least-recent-recency1" }
            eligible.filter { it.track.id !in taken && !blockedVersion(it.version, picks, before, 1, versionOf) }
                .minByOrNull { salted[it.track.id] ?: 0L }?.let { return it.track.id to "any" }
            // Everything is queued: repeat the least recent (the log, then the queue before the plan, then the plan).
            if (eligible.isEmpty()) return null
            val order = HashMap<TrackId, Int>()
            val logged = history.size
            for ((i, id) in (before + picks).withIndex()) order[id] = logged + i
            fun last(k: Known) = order[k.track.id] ?: history.lastPlayIndex(k.track.id) ?: -1
            val notPrevious = eligible.filter { !blockedVersion(it.version, picks, before, 1, versionOf) }
            val repeat = notPrevious.filter { it.track.id !in recent }.ifEmpty { notPrevious }.ifEmpty { eligible }
            return repeat.minWithOrNull(compareBy<Known> { last(it) }.thenBy { salted[it.track.id] })?.let { it.track.id to "repeat" }
        }
    }

    /**
     * Whether a track of [version] may not follow: it is a version of the previous pick, or of one of the last
     * [window] picks (the queue's rows before the plan included).
     */
    private fun blockedVersion(version: Long, picks: List<TrackId>, before: List<TrackId>, window: Int, versionOf: (TrackId) -> Long): Boolean {
        val recent = (before + picks).takeLast(maxOf(1, window))
        return recent.any { versionOf(it) == version }
    }

    /** Audio neighbours within [budgetMs]; a busy or failing index gives none (the next rung or retriever runs). */
    private suspend fun boundedNeighbours(id: TrackId, k: Int, budgetMs: Long): List<TrackId> =
        withTimeoutOrNull(budgetMs) { runCatching { audio.neighbours(id, k) }.getOrNull() }.orEmpty()

    private fun overlap(anchor: LongArray, candidate: LongArray): Double {
        if (anchor.isEmpty() || candidate.isEmpty()) return 0.0
        if (anchor[0] == candidate[0]) return 1.0
        return if (candidate.any { it in anchor }) 0.5 else 0.0
    }

    public companion object {
        /** Unit length in float64 accumulation, stored as float32; null for a zero or non-finite vector. */
        /** Library rows per audio read when semChain takes the library mean. */
        private const val SEM_BATCH = 256

        /** Above this many tracks with audio the hub scores are skipped (all-pairs memory and time on a phone). */
        private const val HUB_MAX_TRACKS = 6_000

        private val HUB_TOKENS = setOf("ost", "soundtrack", "score", "anime", "cinematic", "orchestral", "game", "ambient", "library", "western")
        private val HUB_SPLIT = Regex("[^a-zа-яё]+")

        /** 0.9.1 Genres.isHub: any [^a-zа-яё]+ token of the lower-cased genre tag is a hub token. */
        internal fun genreIsHub(genre: String?): Boolean =
            !genre.isNullOrEmpty() && genre.lowercase().split(HUB_SPLIT).any { it in HUB_TOKENS }

        internal fun normalized(v: FloatArray): FloatArray? {
            var norm = 0.0
            for (x in v) norm += x.toDouble() * x
            if (!(norm > 0.0) || !norm.isFinite()) return null
            val scale = 1.0 / sqrt(norm)
            return FloatArray(v.size) { (v[it] * scale).toFloat() }
        }

        /**
         * Float32 dot product accumulated left to right (reference.py's rule, parity at 1e-9). Kotlin/Native compiles
         * `dot += a * b` to separate multiply and add (no fused multiply-add): RankerArithmeticTest pins it on JVM and iOS.
         */
        internal fun cosine(a: FloatArray?, b: FloatArray?): Double {
            if (a == null || b == null || a.size != b.size) return Double.NaN
            var dot = 0.0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot.toDouble()
        }

        /** §7.2b's time limits: R1 (100 ms), the ladder's audio rung (20 ms), audio reads for features and imputation. */
        private const val R1_BUDGET_MS = 100L
        private const val LADDER_AUDIO_BUDGET_MS = 20L
        private const val AUDIO_BUDGET_MS = 100L
        /** The batched vector / e read's safety cap: past it the plan reads no audio at all (all-or-nothing). */
        private const val AUDIO_BATCH_CAP_MS = 2_000L
        /** Soft novelty: the penalty fades over 24 h since the last play. */
        private const val NOVELTY_MS = 86_400_000L

        private val EMPTY_ARTISTS = LongArray(0)

        /** Tracks the ladder's descriptor rung compares at most. */
        private const val LADDER_SAMPLE = 2_000

        /** Longer than a day is not a duration (corrupt tags): unknown. */
        public const val MAX_DURATION_MS: Long = 24L * 3_600_000L

        private val NON_MUSIC_FOLDERS = setOf("ringtones", "notifications", "alarms", "recordings", "call recordings")

        /** as2 (audit 07 #5, with the rebuild): voice and spoken-word folders too. */
        private val NON_MUSIC_FOLDERS_AS2 = NON_MUSIC_FOLDERS +
            setOf("podcasts", "audiobooks", "voice recorder", "whatsapp voice notes", "call_rec", "sound_recorder")

        /** §7.2's non-music filter: ringtone, notification, alarm and recording folders (MediaStore IS_MUSIC is upstream). */
        public fun isNonMusic(track: TrackDescriptor, as2: Boolean = false): Boolean {
            val folders = if (as2) NON_MUSIC_FOLDERS_AS2 else NON_MUSIC_FOLDERS
            return track.folderPath.orEmpty().split('/', '\\').any { it.trim().lowercase() in folders }
        }

        /** The app's song-version key: the artist key and the title without version qualifiers; a track is a version of itself. */
        public fun versionKey(track: TrackDescriptor, artistKey: String = HabitSlots.artistKey(track), v2: Boolean = false): String {
            val title = MetadataRerank.normalizeTitle(track.title, keepParts = v2)
            return if (title.isEmpty() || artistKey.isEmpty()) "id:${track.id.value}" else "$artistKey|$title"
        }
    }
}

/** Hop h's draw is the h-th SplitMix64 output from the press's seed (h = 1 for the first pick). */
internal class HopDraws(seed: Long) {
    private val generator = SplitMix64(seed)
    private val outputs = ArrayList<Long>()

    fun output(hop: Int): Long {
        while (outputs.size < hop) outputs += generator.next()
        return outputs[hop - 1]
    }

    /** An index below [size] from hop [hop]'s output, as an unsigned remainder. */
    fun pick(hop: Int, size: Int): Int = (output(hop).toULong() % size.toULong()).toInt()
}

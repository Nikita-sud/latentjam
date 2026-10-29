/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalInputs
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import io.github.nikitasud.latentjam.smart.personal.RecencyMode
import kotlin.math.min

/**
 * The personal addition of one hop for a candidate (PERSONALISATION_RULES v5…v6, PLAN_PERSONAL v2 §4 and v6). δ is
 * δ_weak; δ_habit = [HABIT] δ. In the mask M: t = clamp(min(cap, habit⁺ + artist + playlists + heart + affinity
 * [+ D1credit]) + habit⁻ − E7, −δ, cap), where habit = δ_tier · b / 6 from the jar b and cap is δ_habit for a tier-2
 * habit that passed the cluster guard, δ otherwise. Outside M (within δ_habit of the best) only a guarded tier-2 habit
 * adds, alone. As novelty, a D1-strong habit also relieves its own recency penalty (the listener's own evidence, times ρ).
 * Only with "Learn from my habits" on; [SmartChain] applies it.
 */
internal class PersonalTerm(snapshot: SmartSnapshot, private val inputs: PersonalInputs) {
    private val model = inputs.model
    private val ids: List<TrackId> = snapshot.tracks.map { it.id }
    private val songs = IntArray(snapshot.size) { model.songOf(ids[it]) }
    private val artists: Array<IntArray> = Array(snapshot.size) { model.artistsOf(ids[it]) }
    private val hearted = BooleanArray(snapshot.size) { ids[it] in inputs.hearts }
    private val playlists: Array<IntArray> = run {
        val of = HashMap<TrackId, MutableList<Int>>()
        inputs.playlists.forEachIndexed { p, members -> for (id in members) of.getOrPut(id) { ArrayList() } += p }
        Array(snapshot.size) { row -> of[ids[row]]?.toIntArray() ?: NONE }
    }

    /** No history and no priors: no addition at all (C), so the chain stays the shipped one. */
    val active: Boolean = !model.isEmpty || inputs.hearts.isNotEmpty() || inputs.playlists.isNotEmpty()

    val recencyMode: RecencyMode get() = inputs.recency

    /**
     * The addition for [row] after [anchor] in a hop of [delta] (δ_weak): t plus, in [RecencyMode.NOVELTY], the relief
     * s_own · R · ρ of a D1-strong habit. [inMask] says whether the row is in M; outside it only a tier-2 habit that
     * passes [guard] (asked only then) adds. [recencyLoss] is R(B), the recency penalty's counterfactual log size;
     * [readmitted] marks a row the starvation guard let back in, which no habit relieves.
     */
    fun addition(anchor: Int, row: Int, delta: Float, recencyLoss: Float, readmitted: Boolean, inMask: Boolean = true, guard: () -> Boolean = { false }): Float {
        if (delta <= 0f) return 0f
        val a = songs[anchor]
        val b = songs[row]
        val tier = model.tier(a, b)
        val jar = model.jar(a, b)
        val reach = tier == 2 && jar > 0f && guard()
        if (!inMask && !reach) return 0f
        val s = model.strength(a, b)
        val rho = model.jarWeight(a, b)
        val strong = !readmitted && recencyLoss > 0f && s > 0f && model.strong(a, b)
        // The recency brake is relieved by the listener's own evidence only, never by SMART-sourced evidence.
        val relief = if (strong && inputs.recency == RecencyMode.NOVELTY) model.ownStrength(a, b) * recencyLoss * rho else 0f
        val cap = if (reach) HABIT * delta else delta
        val habit = if (tier >= 1 && jar > 0f) cap * jar / PersonalModel.JAR_MAX else 0f
        if (!inMask) return habit + relief
        var positives = habit
        if (inputs.artistTerm) positives += ARTIST * delta * model.artistStrength(artists[anchor], artists[row])
        if (sharePlaylist(anchor, row)) positives += PLAYLIST * delta
        if (hearted[row]) positives += HEART * delta
        positives += AFFINITY * delta * model.affinity(b)
        if (strong && inputs.recency == RecencyMode.CREDIT) positives += min(delta, s * recencyLoss) * rho
        // A rejected pair's jar below zero is a δ_weak negative, whatever its tier.
        val negative = if (jar < 0f) delta * jar / PersonalModel.JAR_MAX else 0f
        val t = (min(cap, positives) + negative - delta * model.skipPenalty(b)).coerceIn(-delta, cap)
        return t + relief
    }

    /** Whether [row]'s habit after [anchor] could reach beyond M (tier 2 with a positive jar): the guard is asked only then. */
    fun reaches(anchor: Int, row: Int): Boolean {
        val a = songs[anchor]
        val b = songs[row]
        return model.tier(a, b) == 2 && model.jar(a, b) > 0f
    }

    /** Reports the rows whose pick the personal term changed (logged as personalChanged). */
    fun changed(rows: Set<Int>) {
        if (rows.isNotEmpty()) inputs.onPersonalChanged(rows.map { ids[it] }.toSet())
    }

    private fun sharePlaylist(a: Int, b: Int): Boolean {
        val pa = playlists[a]
        if (pa.isEmpty()) return false
        val pb = playlists[b]
        for (x in pa) if (x in pb) return true
        return false
    }

    companion object {
        const val ARTIST = 0.3f
        const val PLAYLIST = 0.15f
        const val HEART = 0.15f
        const val AFFINITY = 0.15f
        const val MARGIN_K = 0.5f
        const val MARGIN_TOP = 50
        /** δ_habit = HABIT × δ_weak = 1.5 IQR (PLAN_PERSONAL v6). */
        const val HABIT = 3f
        /** The cluster guard's thresholds: family Jaccard, theme z with families, theme z without. */
        const val GUARD_JACCARD = 0.5f
        const val GUARD_Z = 0f
        const val GUARD_Z_NO_FAMILIES = 1f
        private val NONE = IntArray(0)

        /** δ = [MARGIN_K] × the IQR of the best [MARGIN_TOP] scores (all of them when fewer); 0 when degenerate. */
        fun delta(scores: FloatArray, scored: BooleanArray): Float {
            val values = ArrayList<Float>()
            for (i in scores.indices) if (scored[i]) values += scores[i]
            if (values.size < 2) return 0f
            values.sortDescending()
            val top = values.take(MARGIN_TOP).sorted()
            val iqr = quantile(top, 0.75f) - quantile(top, 0.25f)
            return if (iqr > 0f) MARGIN_K * iqr else 0f
        }

        /**
         * The cluster guard (PLAN_PERSONAL v6) of B after the anchor A: (A's primary family is one of B's, or the family
         * Jaccard is ≥ [GUARD_JACCARD]) and B's theme z for reference A ≥ [GUARD_Z]; with no families on either side,
         * the theme z ≥ [GUARD_Z_NO_FAMILIES]. Without a theme ([themeZ] null) the families decide alone, and no
         * families fail.
         */
        fun clusterGuard(anchorFamilies: Set<String>, rowFamilies: Set<String>, themeZ: Float?): Boolean {
            if (anchorFamilies.isEmpty() || rowFamilies.isEmpty()) return themeZ != null && themeZ >= GUARD_Z_NO_FAMILIES
            val shared = anchorFamilies.count { it in rowFamilies }
            val jaccard = shared.toFloat() / (anchorFamilies.size + rowFamilies.size - shared)
            val families = anchorFamilies.first() in rowFamilies || jaccard >= GUARD_JACCARD
            return families && (themeZ == null || themeZ >= GUARD_Z)
        }

        /** Linear-interpolation quantile of an ascending list. */
        private fun quantile(sorted: List<Float>, q: Float): Float {
            val position = q * (sorted.size - 1)
            val low = position.toInt()
            val high = min(low + 1, sorted.size - 1)
            return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
        }
    }
}

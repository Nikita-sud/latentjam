/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalInputs
import io.github.nikitasud.latentjam.smart.personal.RecencyMode
import kotlin.math.min

/**
 * The personal addition of one hop for a candidate in the mask M (PERSONALISATION_RULES v5…v5.5, PLAN_PERSONAL v2 §4): the
 * term t = clamp(min(δ, habit + artist + playlists + heart + affinity [+ D1credit]) − rejection − E7, ±δ), plus, as novelty,
 * a D1-strong habit's relief of its own recency penalty (from the listener's own evidence only). Only with "Learn from my habits" on; [SmartChain] applies it to M.
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
     * The addition for [row] after [anchor] in a hop of [delta]: t plus, in [RecencyMode.NOVELTY], the relief s · R of a
     * D1-strong habit. [recencyLoss] is R(B), the recency penalty's counterfactual log size; [readmitted] marks a row
     * the starvation guard let back in, which no habit relieves.
     */
    fun addition(anchor: Int, row: Int, delta: Float, recencyLoss: Float, readmitted: Boolean): Float {
        if (delta <= 0f) return 0f
        val a = songs[anchor]
        val b = songs[row]
        val s = model.strength(a, b)
        val strong = !readmitted && recencyLoss > 0f && s > 0f && model.strong(a, b)
        var positives = delta * s
        if (inputs.artistTerm) positives += ARTIST * delta * model.artistStrength(artists[anchor], artists[row])
        if (sharePlaylist(anchor, row)) positives += PLAYLIST * delta
        if (hearted[row]) positives += HEART * delta
        positives += AFFINITY * delta * model.affinity(b)
        if (strong && inputs.recency == RecencyMode.CREDIT) positives += min(delta, s * recencyLoss)
        val t = (min(delta, positives) - delta * model.rejection(a, b) - delta * model.skipPenalty(b)).coerceIn(-delta, delta)
        // The recency brake is relieved by the listener's own evidence only, never by SMART-sourced evidence.
        val relief = if (strong && inputs.recency == RecencyMode.NOVELTY) model.ownStrength(a, b) * recencyLoss else 0f
        return t + relief
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

        /** Linear-interpolation quantile of an ascending list. */
        private fun quantile(sorted: List<Float>, q: Float): Float {
            val position = q * (sorted.size - 1)
            val low = position.toInt()
            val high = min(low + 1, sorted.size - 1)
            return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
        }
    }
}

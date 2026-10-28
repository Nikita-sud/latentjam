/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalInputs
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import kotlin.math.min

/**
 * The personal term of one hop (PERSONALISATION_RULES.md v3, PLAN_PERSONAL.md §1, §4), over a snapshot's rows: recency
 * with D1's relief, and — with "Learn from my habits" on — the habits, rejections, playlists and the track prior. In
 * units of the hop's δ and clamped to ±δ, so it only decides among candidates the core already scores within δ of its
 * best ([SmartChain] masks the rest).
 */
internal class PersonalTerm(
    snapshot: SmartSnapshot,
    private val inputs: PersonalInputs,
    /** D1's recency relief; tests turn it off to show what it does. */
    private val relief: Boolean = true,
) {
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

    /** No history: no term at all (C1), so the chain stays the core. */
    val active: Boolean = !model.isEmpty

    val habits: Boolean get() = inputs.habits

    /**
     * t for [row] after [anchor]. [full] = false gives recency alone (what "Learn from my habits" off plans with): the
     * loop guard's reference, so an acceptance the habits decided is never counted again as evidence.
     */
    fun term(anchor: Int, row: Int, delta: Float, full: Boolean = inputs.habits): Float {
        if (!active || delta <= 0f) return 0f
        val a = songs[anchor]
        val b = songs[row]
        var t = 0f
        var relief = 0f
        if (full && a >= 0 && b >= 0) {
            if (this.relief && model.strong(a, b)) relief = min(1f, model.confidence(a, b))
            t += delta * (model.habit(a, b) + ARTIST * model.artistHabit(artists[anchor], artists[row]) - model.rejection(a, b))
            if (sharePlaylist(anchor, row)) t += PLAYLIST * delta
        }
        if (full) t += delta * model.trackPrior(b, hearted[row])
        t -= delta * model.recency(b, inputs.nowMs) * (1f - relief)
        return t.coerceIn(-delta, delta)
    }

    /** D2: the anchor's far habits as snapshot rows, best first; none with "Learn from my habits" off. */
    fun farHabits(anchor: Int): IntArray {
        if (!active || !inputs.habits) return NONE
        val a = songs[anchor]
        if (a < 0) return NONE
        val far = model.farHabits(a, inputs.nowMs)
        if (far.isEmpty()) return NONE
        // Each far song as its first row in the snapshot.
        return far.toList().mapNotNull { song -> songs.indices.firstOrNull { songs[it] == song } }.toIntArray()
    }

    /** Reports the rows whose pick the personal term changed (the loop guard's personalChanged). */
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
        const val MARGIN_K = 0.5f
        const val MARGIN_TOP = 50
        const val FAR_MARGIN = 3f
        const val FAR_EARLIEST_SLOT = 3
        private val NONE = IntArray(0)

        /** δ = [MARGIN_K] × the IQR of the best [MARGIN_TOP] core scores (all of them when fewer); 0 when degenerate. */
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

        /** The personal model's decayed evidence is per song; the session window it uses is also the chain's. */
        const val SESSION_GAP_MS: Long = PersonalModel.SESSION_GAP_MS
    }
}

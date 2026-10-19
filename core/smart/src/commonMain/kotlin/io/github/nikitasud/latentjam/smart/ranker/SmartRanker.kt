/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitFlow
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.ArtistNeighbours
import kotlin.math.sqrt

/**
 * SMART's learned ranker (test build; docs/smart-ranker.md): plans a queue hop by hop, scoring every
 * candidate with [model] over [RankerFeatures] relative to the hop's anchor (the seed, or the previous
 * pick) and choosing the best after the artist-run penalty: score − p · runLength · sd(hop scores) for
 * candidates of the artist the queue currently ends with (the seed included), as SmartChain's
 * artistRunPenalty. Popularity is 0 (no table ships) and there is no audio term yet.
 *
 * Knowledge per track — pack descriptor, language, decade, entity; genre family from the tag — is read
 * once per library list. Not thread-safe; the chooser calls it under its lock.
 */
public class SmartRanker(
    private val model: RankerModel,
    private val knowledge: ArtistKnowledge?,
    private val neighbours: ArtistNeighbours?,
) {
    private class Known(
        val descriptor: FloatArray?,
        val family: String?,
        val language: String?,
        val decade: Int?,
        val artistKey: String,
        val entity: Int,
    )

    private var knownFor: List<TrackDescriptor>? = null
    private var known: Map<TrackId, Known> = emptyMap()

    /** Library tracks whose artist has a pack entity / a co-listening list (for the build notes). */
    public var entityCoverage: Pair<Int, Int> = 0 to 0
        private set

    public val modelName: String get() = model.name

    private fun know(track: TrackDescriptor): Known {
        val primary = track.artists.firstOrNull()
        val raw = knowledge?.descriptor(track.artist, primary, text = null)
        val facts = knowledge?.facts(track.artist, primary)
        return Known(
            descriptor = raw?.let(::normalized),
            family = HabitFlow.family(track.genre),
            language = facts?.language,
            decade = facts?.decade,
            artistKey = HabitSlots.artistKey(track),
            entity = knowledge?.entity(track.artist, primary) ?: -1,
        )
    }

    /** Reads the library's knowledge when the list changed; returns the cache. */
    private fun knownOf(library: List<TrackDescriptor>): Map<TrackId, Known> {
        if (knownFor !== library) {
            known = library.associate { it.id to know(it) }
            knownFor = library
            val withEntity = known.values.count { it.entity >= 0 }
            val listed = neighbours?.let { n -> known.values.count { it.entity >= 0 && n.has(it.entity) } } ?: 0
            entityCoverage = withEntity to listed
        }
        return known
    }

    /**
     * Up to [length] track ids after [seed], best first, from [candidates] minus [exclude] (and the
     * seed); [library] is the list the per-track knowledge is cached for.
     */
    public fun plan(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        candidates: List<TrackDescriptor>,
        exclude: Set<TrackId>,
        length: Int,
    ): List<TrackId> {
        val cache = knownOf(library)
        val seedKnown = cache[seed.id] ?: know(seed)
        val pool = candidates.filter { it.id != seed.id && it.id !in exclude }
        val poolKnown = pool.map { cache[it.id] ?: know(it) }
        val taken = BooleanArray(pool.size)
        val picks = ArrayList<TrackId>(length)
        var runArtist = seedKnown.artistKey
        var runLength = if (runArtist.isEmpty()) 0 else 1
        var anchor = seedKnown
        var anchorScores = scores(anchor, poolKnown, taken)
        repeat(minOf(length, pool.size)) {
            if (model.anchor == RankerModel.Anchor.PREVIOUS && picks.isNotEmpty()) anchorScores = scores(anchor, poolKnown, taken)
            val hop = DoubleArray(pool.size) { if (taken[it]) Double.NaN else anchorScores[it] }
            if (model.runPenalty > 0.0 && runLength > 0) {
                val penalty = model.runPenalty * runLength * spread(hop)
                if (penalty > 0.0) for (i in hop.indices) {
                    if (!taken[i] && poolKnown[i].artistKey == runArtist) hop[i] -= penalty
                }
            }
            var best = -1
            for (i in hop.indices) if (!taken[i] && (best < 0 || hop[i] > hop[best])) best = i
            if (best < 0) return picks
            taken[best] = true
            picks += pool[best].id
            val artist = poolKnown[best].artistKey
            if (artist.isNotEmpty() && artist == runArtist) runLength++ else {
                runArtist = artist
                runLength = if (artist.isEmpty()) 0 else 1
            }
            anchor = poolKnown[best]
        }
        return picks
    }

    /** Model scores of the pool rows not yet [taken], relative to [anchor]; taken rows are NaN. */
    private fun scores(anchor: Known, pool: List<Known>, taken: BooleanArray): DoubleArray {
        val open = pool.indices.filter { !taken[it] }
        val related = HashMap<Int, Double>()
        if (anchor.entity >= 0) neighbours?.forEachNeighbour(anchor.entity) { n, s -> related[n] = s.toDouble() }
        val rows = open.map { i ->
            val c = pool[i]
            RankerRow(
                cosine = cosine(anchor.descriptor, c.descriptor),
                family = c.family,
                language = c.language,
                decade = c.decade,
                artistKey = c.artistKey,
                colisten = if (c.entity >= 0) related[c.entity] ?: 0.0 else 0.0,
            )
        }
        val features = RankerFeatures.of(
            RankerRow(family = anchor.family, language = anchor.language, decade = anchor.decade, artistKey = anchor.artistKey),
            rows,
        )
        val out = DoubleArray(pool.size) { Double.NaN }
        open.forEachIndexed { k, i -> out[i] = model.score(features[k]) }
        return out
    }

    private fun spread(hop: DoubleArray): Double {
        var n = 0
        var sum = 0.0
        for (v in hop) if (!v.isNaN()) { n++; sum += v }
        if (n < 2) return 0.0
        val mean = sum / n
        var squares = 0.0
        for (v in hop) if (!v.isNaN()) squares += (v - mean) * (v - mean)
        return sqrt(squares / n)
    }

    private fun normalized(v: FloatArray): FloatArray? {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        if (norm <= 0.0 || !norm.isFinite()) return null
        val scale = 1.0 / sqrt(norm)
        return FloatArray(v.size) { (v[it] * scale).toFloat() }
    }

    private fun cosine(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.size != b.size) return Double.NaN
        var dot = 0.0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.toDouble()
    }
}

/** Loads the ranker's two assets lazily and fails closed: no model, no ranker (SMART keeps its chain). */
public class SmartRankerAssets(
    private val knowledge: ArtistKnowledge?,
    private val loadModel: () -> String?,
    private val loadNeighbours: () -> io.github.nikitasud.latentjam.smart.text.AssetBytes?,
) {
    public val ranker: SmartRanker? by lazy {
        val model = runCatching { loadModel()?.let(RankerModel::parse) }.getOrNull() ?: return@lazy null
        val neighbours = runCatching { loadNeighbours()?.let(ArtistNeighbours::parse) }.getOrNull()
        SmartRanker(model, knowledge, neighbours)
    }
}

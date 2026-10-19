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
import io.github.nikitasud.latentjam.smart.text.ArtistVectors
import kotlin.math.sqrt

/**
 * The hop loop of tools/research/listenbrainz_eval/proto_ranker2.py (`Query.queue`), over pool indices.
 *
 * Hop 1 scores the pool against the seed; from hop 2 the anchor is [RankerModel.anchor]: the seed, the previous
 * pick, or both mixed. Each hop picks the best open row of score − p · runLength · sd(open rows' scores) for rows
 * of the artist the queue ends with (the seed counts); ties go to the lower [tieOrder]. Features are always
 * computed over the whole pool, as the research does.
 */
public object RankerQueue {
    public fun build(
        model: RankerModel,
        poolSize: Int,
        artistKeys: List<String>,
        seedArtist: String,
        tieOrder: IntArray,
        length: Int,
        /** Test hook: given the hop, its best row and the penalised scores, the row to take (golden teacher forcing). */
        take: ((hop: Int, best: Int, scores: (Int) -> Double) -> Int)? = null,
        features: (anchor: Int) -> Array<DoubleArray>,
    ): List<Int> {
        fun scores(anchor: Int) = features(anchor).let { rows -> DoubleArray(poolSize) { model.score(rows[it]) } }
        val seedScores = scores(SEED)
        val open = BooleanArray(poolSize) { true }
        val picks = ArrayList<Int>(length)
        var runArtist = seedArtist
        var runLength = if (runArtist.isEmpty()) 0 else 1
        repeat(minOf(length, poolSize)) {
            val s = when {
                picks.isEmpty() || model.anchor == RankerModel.Anchor.SEED -> seedScores
                model.anchor == RankerModel.Anchor.PREVIOUS -> scores(picks.last())
                else -> {
                    val previous = scores(picks.last())
                    val total = model.seedWeight + model.previousWeight
                    DoubleArray(poolSize) { (model.seedWeight * seedScores[it] + model.previousWeight * previous[it]) / total }
                }
            }
            val penalty = if (model.runPenalty > 0.0 && runLength > 0) model.runPenalty * runLength * spread(s, open) else 0.0
            var best = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 0 until poolSize) {
                if (!open[i]) continue
                val t = if (penalty != 0.0 && artistKeys[i] == runArtist) s[i] - penalty else s[i]
                if (best < 0 || t > bestScore || (t == bestScore && tieOrder[i] < tieOrder[best])) {
                    best = i
                    bestScore = t
                }
            }
            if (best < 0) return picks
            if (take != null) {
                val hopScores = s
                val runAt = runArtist
                best = take(picks.size, best) { i -> if (penalty != 0.0 && artistKeys[i] == runAt) hopScores[i] - penalty else hopScores[i] }
            }
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

    /** The anchor index [build] passes for the seed. */
    public const val SEED: Int = -1

    private fun spread(s: DoubleArray, open: BooleanArray): Double {
        var n = 0
        var sum = 0.0
        for (i in s.indices) if (open[i]) { n++; sum += s[i] }
        if (n == 0) return 0.0
        val mean = sum / n
        var squares = 0.0
        for (i in s.indices) if (open[i]) squares += (s[i] - mean) * (s[i] - mean)
        return sqrt(squares / n)
    }
}

/**
 * SMART's learned ranker (test build; docs/smart-ranker.md): plans a queue with [RankerQueue] over the
 * candidates, scoring with [model] over [RankerFeatures]. Popularity is 0 (no table ships) and there is no
 * audio term yet.
 *
 * Knowledge per track — pack descriptor, language, decade, entity, co-listening list, LightFM vector; genre
 * family from the tag — is read once per library list. Not thread-safe; the chooser calls it under its lock.
 */
public class SmartRanker(
    private val model: RankerModel,
    private val knowledge: ArtistKnowledge?,
    private val neighbours: ArtistNeighbours?,
    private val vectors: ArtistVectors? = null,
) {
    private class Known(
        val descriptor: FloatArray?,
        val family: String?,
        val language: String?,
        val decade: Int?,
        val artistKey: String,
        val entity: Int,
        val lfm: FloatArray?,
    )

    private var knownFor: List<TrackDescriptor>? = null
    private var known: Map<TrackId, Known> = emptyMap()

    /** Library tracks whose artist has a pack entity / a co-listening list / a LightFM vector (build notes). */
    public var entityCoverage: Triple<Int, Int, Int> = Triple(0, 0, 0)
        private set

    public val modelName: String get() = model.name

    private fun know(track: TrackDescriptor): Known {
        val primary = track.artists.firstOrNull()
        val raw = knowledge?.descriptor(track.artist, primary, text = null)
        val facts = knowledge?.facts(track.artist, primary)
        val entity = knowledge?.entity(track.artist, primary) ?: -1
        return Known(
            descriptor = raw?.let(::normalized),
            family = HabitFlow.family(track.genre),
            language = facts?.language,
            decade = facts?.decade,
            artistKey = HabitSlots.artistKey(track),
            entity = entity,
            lfm = if (entity >= 0) vectors?.vector(entity)?.let(::normalized) else null,
        )
    }

    private fun knownOf(library: List<TrackDescriptor>): Map<TrackId, Known> {
        if (knownFor !== library) {
            known = library.associate { it.id to know(it) }
            knownFor = library
            val values = known.values
            entityCoverage = Triple(
                values.count { it.entity >= 0 },
                neighbours?.let { n -> values.count { it.entity >= 0 && n.has(it.entity) } } ?: 0,
                values.count { it.lfm != null },
            )
        }
        return known
    }

    /**
     * Up to [length] track ids after [seed] from [candidates] minus [exclude] (and the seed); [library] is the
     * list the per-track knowledge is cached for. Ties go to the earlier candidate.
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
        val picks = RankerQueue.build(
            model = model,
            poolSize = pool.size,
            artistKeys = poolKnown.map { it.artistKey },
            seedArtist = seedKnown.artistKey,
            tieOrder = IntArray(pool.size) { it },
            length = length,
        ) { anchor -> features(if (anchor == RankerQueue.SEED) seedKnown else poolKnown[anchor], poolKnown) }
        return picks.map { pool[it].id }
    }

    private fun features(anchor: Known, pool: List<Known>): Array<DoubleArray> {
        val related = HashMap<Int, Double>()
        if (anchor.entity >= 0) neighbours?.forEachNeighbour(anchor.entity) { n, s -> related[n] = s.toDouble() }
        val rows = pool.map { c ->
            RankerRow(
                cosine = cosine(anchor.descriptor, c.descriptor),
                family = c.family,
                language = c.language,
                decade = c.decade,
                artistKey = c.artistKey,
                colisten = if (c.entity >= 0) related[c.entity] ?: 0.0 else 0.0,
                lfm = cosine(anchor.lfm, c.lfm),
            )
        }
        return RankerFeatures.of(
            RankerRow(family = anchor.family, language = anchor.language, decade = anchor.decade, artistKey = anchor.artistKey),
            rows,
        )
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
    private val loadVectors: () -> io.github.nikitasud.latentjam.smart.text.AssetBytes? = { null },
) {
    public val ranker: SmartRanker? by lazy {
        val model = runCatching { loadModel()?.let(RankerModel::parse) }.getOrNull() ?: return@lazy null
        val neighbours = runCatching { loadNeighbours()?.let(ArtistNeighbours::parse) }.getOrNull()
        val vectors = runCatching { loadVectors()?.let(ArtistVectors::parse) }.getOrNull()
        SmartRanker(model, knowledge, neighbours, vectors)
    }
}

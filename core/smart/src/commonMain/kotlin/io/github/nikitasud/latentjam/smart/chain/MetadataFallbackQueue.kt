/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.VectorIndex
import kotlin.math.ln

/**
 * Honest first-run fallback for the short interval before audio embeddings exist.
 *
 * It uses only the on-device trusted metadata vectors (`genre; artist; year`) that are already
 * available for the whole library. Titles never enter the vector, so a genre word in a filename or
 * mix title cannot steer retrieval. This is deliberately a separate path from [SmartChain]: it
 * never feeds fake zero-audio rows into a scorer that was trained on real audio.
 */
internal object MetadataFallbackQueue {

    fun build(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
        length: Int,
        textIndex: VectorIndex?,
        historyEvents: List<SmartHistoryEvent> = emptyList(),
    ): List<TrackId> {
        if (length <= 0 || textIndex == null) return emptyList()
        val seedVector = textIndex.vector(seed.id) ?: return emptyList()
        val candidates = library.mapNotNull { track ->
            if (track.id == seed.id) return@mapNotNull null
            textIndex.vector(track.id)?.let { vector -> Candidate(track, vector) }
        }
        if (candidates.isEmpty()) return emptyList()

        val result = ArrayList<TrackId>(minOf(length, candidates.size))
        val used = HashSet<TrackId>()
        val seedMeta = seed.toMeta()
        val recency = RecencyRerank(historyEvents)
        var anchorMeta = seedMeta
        var anchorVector = seedVector
        val recentArtists = ArrayDeque<String>()
        recentArtists.addLast(seedMeta.artistKey)
        val seenTitles = HashSet<String>()
        seedMeta.normalizedTitle.takeIf(String::isNotEmpty)?.let(seenTitles::add)

        while (result.size < length) {
            var best: Candidate? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (candidate in candidates) {
                if (candidate.track.id in used) continue
                val meta = candidate.track.toMeta()
                if (meta.artistKey in recentArtists) continue
                if (meta.normalizedTitle.isNotEmpty() && meta.normalizedTitle in seenTitles) continue

                val multiplier = (MetadataRerank.adjustMultiplier(anchorMeta, meta) *
                    recency.multiplier(candidate.track.id))
                    .coerceIn(ChainConfig.MULTIPLIER_MIN, ChainConfig.MULTIPLIER_MAX)
                val score = ChainConfig.COSINE_BLEND_WEIGHT * cosine(anchorVector, candidate.vector) +
                    ChainConfig.CHAIN_SEED_GRAVITY * cosine(seedVector, candidate.vector) +
                    ln(multiplier)
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            val picked = best ?: break
            val meta = picked.track.toMeta()
            result += picked.track.id
            used += picked.track.id
            meta.normalizedTitle.takeIf(String::isNotEmpty)?.let(seenTitles::add)
            recentArtists.addLast(meta.artistKey)
            while (recentArtists.size > ChainConfig.CHAIN_ARTIST_SPACING) {
                recentArtists.removeFirst()
            }
            anchorMeta = meta
            anchorVector = picked.vector
        }
        return result
    }

    private fun TrackDescriptor.toMeta(): TrackMeta = TrackMeta(
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        year = year,
    )

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return 0f
        var dot = 0f
        for (index in left.indices) dot += left[index] * right[index]
        return dot
    }

    private data class Candidate(
        val track: TrackDescriptor,
        val vector: FloatArray,
    )
}

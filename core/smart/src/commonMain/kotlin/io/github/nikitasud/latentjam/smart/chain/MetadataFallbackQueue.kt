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
        companionGroups: List<Set<TrackId>> = emptyList(),
    ): List<TrackId> {
        if (length <= 0 || textIndex == null) return emptyList()
        val seedVector = textIndex.vector(seed.id) ?: return emptyList()
        val candidateTracks = library.distinctBy { it.id }.filterNot { it.id == seed.id }
        val companions = CompanionMembership.build(
            rowIds = listOf(seed.id) + candidateTracks.map { it.id },
            groups = companionGroups,
        )
        val seedRow = 0
        val candidates = candidateTracks.mapIndexedNotNull { candidateIndex, track ->
            textIndex.vector(track.id)?.let { vector ->
                Candidate(track, vector, companionRow = candidateIndex + 1)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val result = ArrayList<TrackId>(minOf(length, candidates.size))
        val used = HashSet<TrackId>()
        val seedMeta = seed.toMeta()
        val recency = RecencyRerank(historyEvents)
        var anchorMeta = seedMeta
        var anchorVector = seedVector
        var anchorCompanionRow = seedRow
        val seedCompanionGroups = companions.groupsOf(seedRow)
        val quotaPositionByGroup = IntArray(companions.groupCount) { -1 }
        seedCompanionGroups.forEachIndexed { position, group ->
            quotaPositionByGroup[group] = position
        }
        var nextQuotaGroupPosition = 0
        val recentArtists = ArrayDeque<String>()
        recentArtists.addLast(seedMeta.artistKey)
        val seenTitles = HashSet<String>()
        seedMeta.normalizedTitle.takeIf(String::isNotEmpty)?.let(seenTitles::add)

        while (result.size < length) {
            var best: Candidate? = null
            var bestScore = Float.NEGATIVE_INFINITY
            val bestCompanions = arrayOfNulls<Candidate>(seedCompanionGroups.size)
            val bestCompanionScores = FloatArray(seedCompanionGroups.size) {
                Float.NEGATIVE_INFINITY
            }
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
                    ln(multiplier) +
                    if (companions.sharesGroup(anchorCompanionRow, candidate.companionRow)) {
                        ChainConfig.COMPANION_BONUS *
                            companions.weight(anchorCompanionRow, candidate.companionRow)
                    } else {
                        0f
                    }
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
                for (group in companions.groupsOf(candidate.companionRow)) {
                    val position = quotaPositionByGroup[group]
                    if (position >= 0 && score > bestCompanionScores[position]) {
                        bestCompanionScores[position] = score
                        bestCompanions[position] = candidate
                    }
                }
            }
            var picked = best ?: break
            val quotaHop = seedCompanionGroups.isNotEmpty() &&
                (result.size + 1) % ChainConfig.COMPANION_QUOTA_STRIDE == 0
            if (quotaHop) {
                for (offset in seedCompanionGroups.indices) {
                    val position = (nextQuotaGroupPosition + offset) % seedCompanionGroups.size
                    val companion = bestCompanions[position]
                    if (companion != null) {
                        picked = companion
                        nextQuotaGroupPosition = (position + 1) % seedCompanionGroups.size
                        break
                    }
                }
            }
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
            anchorCompanionRow = picked.companionRow
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
        val companionRow: Int,
    )
}

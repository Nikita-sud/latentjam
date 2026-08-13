/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Two files closer than this in audio space are treated as the same recording. The audio
 * embeddings are L2-normalized, so different encodes of one recording land at ~0.995+, while
 * covers, remixes and live versions stay clearly below.
 */
internal const val DUPLICATE_SIMILARITY = 0.99f

/**
 * Groups tracks whose stored audio embeddings are near-identical — the tag-blind duplicate
 * finder. Every pair in a group meets [threshold], so whichever row the listener keeps is a
 * near-duplicate of every row that will be hidden. Biggest group first; singletons are dropped.
 *
 * Pair checks short-circuit as soon as their squared distance exceeds the normalized-vector
 * threshold. This preserves exact cosine semantics while avoiding a full high-dimensional dot
 * product for the overwhelmingly common non-duplicate pair.
 */
internal fun audioDuplicateGroups(
    vectors: Map<TrackId, FloatArray>,
    threshold: Float = DUPLICATE_SIMILARITY,
    durationsMs: Map<TrackId, Long?> = emptyMap(),
    cancellationCheck: () -> Unit = {},
): List<List<TrackId>> {
    val ids = vectors.keys.sortedBy(TrackId::value)
    val rows = ids.map { vectors.getValue(it) }
    val durations = ids.map { id -> durationsMs[id]?.takeIf { it > 0 } }

    fun areDuplicates(aIndex: Int, bIndex: Int): Boolean {
        val a = rows[aIndex]
        val b = rows[bIndex]
        if (a.size != b.size) return false
        // For L2-normalized rows, ||a-b||² = 2 - 2*cos(a,b). Accumulated squared distance is
        // monotonic, so most unrelated tracks can be rejected after only a few dimensions.
        val maximumSquaredDistance = 2f * (1f - threshold.coerceIn(-1f, 1f))
        var squaredDistance = 0f
        for (dimension in a.indices) {
            val difference = a[dimension] - b[dimension]
            squaredDistance += difference * difference
            if (!squaredDistance.isFinite() || squaredDistance > maximumSquaredDistance) {
                return false
            }
        }
        return true
    }

    // A connected component is unsafe here: A≈B and B≈C does not imply A≈C. Build deterministic
    // complete-link groups, but only decode vector pairs whose known durations can represent the
    // same recording. Duration buckets turn a normal large library from all-pairs work into local
    // windows; unknown durations remain conservative and compare against every earlier row.
    val durationToleranceMs = 2_000L
    val durationBucketWidthMs = durationToleranceMs + 1L
    val knownDurationBuckets = mutableMapOf<Long, MutableList<Int>>()
    val unknownDurationIndices = mutableListOf<Int>()
    val completeLinkGroups = mutableListOf<MutableList<Int>>()
    val groupOf = IntArray(ids.size) { -1 }
    var pairChecks = 0
    for (candidate in ids.indices) {
        if (candidate and 127 == 0) cancellationCheck()
        val duration = durations[candidate]
        val possibleMatches = if (duration == null) {
            (0 until candidate).toMutableList()
        } else {
            buildList {
                addAll(unknownDurationIndices)
                val bucket = duration / durationBucketWidthMs
                for (candidateBucket in (bucket - 1)..(bucket + 1)) {
                    knownDurationBuckets[candidateBucket].orEmpty().forEach { prior ->
                        val priorDuration = durations[prior] ?: return@forEach
                        val difference = if (duration >= priorDuration) {
                            duration - priorDuration
                        } else {
                            priorDuration - duration
                        }
                        if (difference <= durationToleranceMs) add(prior)
                    }
                }
            }.sorted()
        }
        val duplicatePrior = HashSet<Int>()
        for (prior in possibleMatches) {
            if (pairChecks++ and 1023 == 0) cancellationCheck()
            if (areDuplicates(candidate, prior)) duplicatePrior += prior
        }
        val compatibleGroupIndex = duplicatePrior.asSequence()
            .map { groupOf[it] }
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .firstOrNull { groupIndex ->
                completeLinkGroups[groupIndex].all { member -> member in duplicatePrior }
            }
        val groupIndex = if (compatibleGroupIndex == null) {
            completeLinkGroups += mutableListOf(candidate)
            completeLinkGroups.lastIndex
        } else {
            completeLinkGroups[compatibleGroupIndex] += candidate
            compatibleGroupIndex
        }
        groupOf[candidate] = groupIndex
        if (duration == null) {
            unknownDurationIndices += candidate
        } else {
            knownDurationBuckets.getOrPut(duration / durationBucketWidthMs, ::mutableListOf) +=
                candidate
        }
    }

    return completeLinkGroups
        .filter { it.size > 1 }
        .map { group -> group.map { ids[it] } }
        .sortedWith(compareByDescending<List<TrackId>> { it.size }.thenBy { it.first().value })
}

/**
 * One membership list (playlist rows, favorites) after merging a duplicate group into
 * [survivor]: every duplicate occurrence becomes the survivor, later repeats collapse, order
 * is otherwise preserved. Null means the list never referenced the group and needs no write.
 */
internal fun mergedMembership(
    current: List<TrackId>,
    duplicates: Set<TrackId>,
    survivor: TrackId,
): List<TrackId>? {
    if (current.none { it in duplicates }) return null
    val rewritten = current.map { if (it in duplicates) survivor else it }.distinct()
    return rewritten.takeIf { it != current }
}

/**
 * Rewrites all durable references before hiding any duplicate. A concurrent playlist or
 * favorites edit aborts the merge; previously rewritten playlists remain valid because all
 * copies are still visible and no references have been discarded.
 */
internal suspend fun mergeDuplicateGroup(
    group: List<TrackDescriptor>,
    survivor: TrackDescriptor,
    playlists: Playlists,
    favorites: Favorites,
    onHideTrack: suspend (TrackDescriptor) -> Unit,
) {
    val losers = group.filter { it.id != survivor.id }
    val duplicateIds = losers.mapTo(mutableSetOf(), TrackDescriptor::id)
    for (playlist in playlists.all()) {
        val current = playlist.trackIds.map(::TrackId)
        val replacement = mergedMembership(current, duplicateIds, survivor.id) ?: continue
        check(playlists.replaceTracksIfUnchanged(playlist.id, current, replacement)) {
            "Playlist changed during duplicate merge"
        }
    }

    val currentFavorites = favorites.all()
    mergedMembership(currentFavorites, duplicateIds, survivor.id)?.let { replacement ->
        check(favorites.replaceIfUnchanged(currentFavorites, replacement)) {
            "Favorites changed during duplicate merge"
        }
    }
    losers.forEach { onHideTrack(it) }
}
